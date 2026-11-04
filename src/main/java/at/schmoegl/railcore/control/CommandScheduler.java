package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.CommandStation;
import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * Befehlsplaner zwischen Sicherheitsschranke und Zentrale (Entscheidung 9, 27; Lastenheft 6 „Befehlsplaner und
 * DCC-Befehlsbudget“, Lastenheft 7 „Befehlsverwaltung“). Läuft ausschließlich auf dem Steuerungs-Thread
 * ({@link ThreadGuard}); Sendezeitpunkte werden über den Zeitplan des Executors eingeplant, es gibt keinen eigenen
 * Sende-Thread.
 * <ul>
 *   <li><b>Ein Platz je Ziel</b> (Lok-Fahrbefehl, Lokfunktion, Weiche): nur der neueste wartende Wert gilt
 *       (zusammenfassen). Ein Wert, der dem zuletzt gesendeten <i>und bestätigten</i> gleicht, wird nie gesendet –
 *       außer bei Weichen: sie melden keine Endlage und werden nie unterdrückt (Entscheidung 28).
 *       „Bestätigt“ heißt: gesendet bei intakter Verbindung und seither weder Verbindungsverlust noch Nothalt.</li>
 *   <li><b>Vorrang</b> ({@link CommandPriority}): streng nach Klasse; innerhalb der Klasse Halt vor Absenkungen vor
 *       Erhöhungen (Bremsen vor Anfahren, Entscheidung 28), dann älteste zuerst. Ein niedriger eingestufter Befehl überschreibt nie einen höher
 *       eingestuften wartenden; ein wartender Halt wird nur durch einen Halt ersetzt. Fahrstufe 0 gilt immer
 *       mindestens als Sicherheit.</li>
 *   <li><b>Budget</b> aus dem Rechenmodell ({@link SchedulerConfig}), laufend mit der Zahl aktiver Loks
 *       nachgeführt; Ausgabe über einen Token-Bucket gleichmäßig verteilt. Haltebefehle dürfen das Budget kurz
 *       überziehen. Nachführung aus gemessener Antwortzeit: {@link BudgetCorrection}, noch inaktiv.</li>
 *   <li><b>Beim Senden erneut geprüft</b> ({@link CommandRules}): wird eine wartende Fahrt inzwischen gesperrt
 *       (Nothalt, Störung, Verbindung, Reservierung), wird statt ihrer Halt gesendet. Ein in diesem Moment aus
 *       einem anderen Thread ausgelöster Nothalt wird nach einer gesendeten Fahrt sofort wiederholt.</li>
 *   <li><b>Nothalt</b> läuft nie hierüber; {@link #onEmergency} verwirft wartende Fahrten und erklärt alle
 *       gesendeten Zustände für unbestätigt.</li>
 *   <li><b>Neuverbinden</b>: alle Zustände unbestätigt, für jede bekannte Lok (gesendet oder als angemeldeter Zug
 *       eingetragen, {@link #registerKnownLoco}) wird einmalig Halt neu übertragen (nie Fahrstufe &gt; 0 ohne die
 *       Schranke).</li>
 *   <li><b>Abfragen</b> ({@link #requestLocoState}) kosten kein DCC-Budget, warten aber in der Vorrangreihenfolge.</li>
 *   <li><b>Überlast</b> wird erkannt und gemeldet (Journal gedrosselt, Momentaufnahme); Sicherheit hat Vorrang,
 *       Komfort wird zurückgestellt. Züge werden <i>nicht</i> ausgebremst (folgt in Meilenstein 4).</li>
 * </ul>
 * Gleisspannung ist kein DCC-Paket und läuft ohne Warteschlange.
 */
public final class CommandScheduler implements CommandSink {

    private static final double EPS = 1e-9;
    private static final long SECOND = 1_000_000_000L;

    private enum Kind { DRIVE, FUNCTION, TURNOUT, QUERY }

    private record Key(Kind kind, int address, int index) {}

    private record DriveValue(int step, Direction direction, SpeedSteps steps) {}

    /** Wert einer Lokzustands-Abfrage (kein DCC-Paket, Kosten 0). */
    private record Query() {}

    private static final Query QUERY_VALUE = new Query();

    private static final class Slot {
        final Key key;
        Object sent;                    // zuletzt gesendeter Wert, null = nie
        boolean confirmed;
        /** Bekannte Lok (angemeldeter Zug) ohne gesendeten Wert: erhält nach dem Verbinden trotzdem Halt. */
        SpeedSteps knownSteps;
        long lastCommandNanos = Long.MIN_VALUE;
        Pending pending;

        Slot(Key key) { this.key = key; }
    }

    private static final class Pending {
        final Slot slot;
        final long firstNanos;                 // erstes Einreihen: Reihenfolge innerhalb der Klasse
        long classNanos;                       // Eintritt in die aktuelle Klasse: Wartezeit je Klasse
        boolean escalated;                     // Bremsstufe von Automatik zur Sicherheit aufgestuft (Entscheidung 30)
        final long seq;
        final int cost;
        CommandPriority priority;
        Object value;
        DriveListener listener;

        Pending(Slot slot, long firstNanos, long seq, int cost, CommandPriority priority, Object value, DriveListener listener) {
            this.slot = slot;
            this.firstNanos = firstNanos;
            this.classNanos = firstNanos;
            this.seq = seq;
            this.cost = cost;
            this.priority = priority;
            this.value = value;
            this.listener = listener;
        }

        boolean halt() { return value instanceof DriveValue d && d.step() == 0; }

        /** Absenkung gegenüber dem zuletzt gesendeten Wert (gleiche Richtung): Bremsen vor Anfahren. */
        boolean reduction() { return isReduction(value, slot.sent); }
    }

    /** Absenkung gegenüber dem zuletzt gesendeten Wert bei gleicher Richtung. */
    private static boolean isReduction(Object value, Object sent) {
        return value instanceof DriveValue d && sent instanceof DriveValue s
                && d.direction() == s.direction() && d.step() < s.step();
    }

    private static final Comparator<Pending> ORDER = Comparator
            .comparingInt((Pending p) -> p.priority.ordinal())
            .thenComparingInt(p -> p.halt() ? 0 : 1)
            .thenComparingInt(p -> p.escalated ? 1 : 0)          // echte Sicherheitseingriffe vor Aufstufungen
            .thenComparingInt(p -> p.reduction() ? 0 : 1)        // Entscheidung 28: Bremsen vor Anfahren
            .thenComparingLong(p -> p.firstNanos)
            .thenComparingLong(p -> p.seq);

    private final SchedulerConfig config;
    private final ThreadGuard guard;
    private final TimeSource clock;
    private final Journal journal;
    private final CommandStation station;
    private final CommandRules rules;
    private final PlantState plant;
    private final ControlExecutor executor;
    private final LongSupplier emergencyEpoch;
    private final BudgetCorrection correction;

    private final Map<Key, Slot> slots = new LinkedHashMap<>();   // feste Reihenfolge: Läufe reproduzierbar
    private final TreeSet<Pending> queue = new TreeSet<>(ORDER);
    private long seq;
    private boolean dispatching;
    private ScheduledAction wake;
    private ScheduledAction escalationWake;

    // Token-Bucket
    private double budget;
    private int activeLocos;
    private int externalActive;
    private double tokens;
    private long lastRefillNanos;

    // Zustand
    /** Verbindung nicht (mehr) bestätigt: Start, Verlust; Gesendetes gilt dann nicht als bestätigt. */
    private boolean linkDown = true;
    private long seenEpoch;
    private boolean overloaded;
    private List<String> overloadReasons = List.of();
    private long lastOverloadSeenNanos;
    private long lastOverloadReportNanos;
    private long comfortReleasedNanos = Long.MIN_VALUE;
    private long demandWindowStart;
    private long demandCount;
    private double demandRate;
    /** Bedarf der Sicherheitsklasse je abgeschlossener Sekunde (gleitendes Mittel über demandWindowSeconds). */
    private final java.util.ArrayDeque<Double> demandPerSecond = new java.util.ArrayDeque<>();
    /** Wartezeiten je Klasse im laufenden Sekundenfenster (für den Überlast-Befund). */
    private final TimingStatistics[] evalWait = new TimingStatistics[CommandPriority.values().length];
    private final double[] lastWaitP95Ms = new double[CommandPriority.values().length];

    private final Counters total = new Counters();
    private final Counters window = new Counters();
    private long windowStartNanos;
    private volatile SchedulerMetrics lastReport = SchedulerMetrics.EMPTY;

    /** Zähler und Wartezeiten eines Zeitraums. */
    private static final class Counters {
        final EnumMap<CommandPriority, TimingStatistics> wait = new EnumMap<>(CommandPriority.class);
        /** Sicherheit aufgeteilt: echte Sicherheitsbefehle und aufgestufte Bremsstufen (Teil der Sicherheit). */
        final TimingStatistics safetyOwn = new TimingStatistics(), escalated = new TimingStatistics();
        long submitted, sent, suppressed, coalesced, rejected, droppedAtDispatch, droppedOnEmergency, retransmissions,
                overloadEvents, escalations;
        int maxBacklog;
        double offered, spent;

        Counters() { for (CommandPriority p : CommandPriority.QUEUED) wait.put(p, new TimingStatistics()); }

        void reset() {
            wait.values().forEach(TimingStatistics::reset);
            safetyOwn.reset();
            escalated.reset();
            submitted = sent = suppressed = coalesced = rejected = droppedAtDispatch = droppedOnEmergency = retransmissions = escalations
                    = overloadEvents = 0;
            maxBacklog = 0;
            offered = spent = 0;
        }
    }

    CommandScheduler(SchedulerConfig config, ThreadGuard guard, TimeSource clock, Journal journal, CommandStation station,
                     CommandRules rules, PlantState plant, ControlExecutor executor, LongSupplier emergencyEpoch,
                     BudgetCorrection correction) {
        this.config = Objects.requireNonNull(config);
        this.guard = Objects.requireNonNull(guard);
        this.clock = Objects.requireNonNull(clock);
        this.journal = Objects.requireNonNull(journal);
        this.station = Objects.requireNonNull(station);
        this.rules = Objects.requireNonNull(rules);
        this.plant = Objects.requireNonNull(plant);
        this.executor = Objects.requireNonNull(executor);
        this.emergencyEpoch = Objects.requireNonNull(emergencyEpoch);
        this.correction = Objects.requireNonNull(correction);
        long now = clock.nanoTime();
        this.budget = config.budgetPerSecond(0);           // Korrektur erst im Takt, dort nie über dem Modell
        this.tokens = config.burstCommands();
        this.lastRefillNanos = now;
        this.demandWindowStart = now;
        for (int k = 0; k < evalWait.length; k++) evalWait[k] = new TimingStatistics();
        this.windowStartNanos = now;
        this.seenEpoch = emergencyEpoch.getAsLong();
        journal.info("befehle", "SCHEDULER_CREATED", "Befehlsplaner: " + config.describe() + String.format(Locale.ROOT,
                "; Budget bei 0/10/25/50/100 aktiven Loks: %.1f/%.1f/%.1f/%.1f/%.1f Befehle/s (Rechenmodell, Echo-Nachführung inaktiv)",
                config.budgetPerSecond(0), config.budgetPerSecond(10), config.budgetPerSecond(25),
                config.budgetPerSecond(50), config.budgetPerSecond(100)));
    }

    // ================================================================== CommandSink

    @Override
    public boolean drive(int address, int speedStep, SpeedSteps steps, Direction direction, CommandPriority priority,
                         DriveListener listener) {
        guard.check("Fahrbefehl planen");
        Objects.requireNonNull(steps);
        Objects.requireNonNull(direction);
        requireQueued(priority);
        if (speedStep < 0 || speedStep > steps.maxStep)
            throw new IllegalArgumentException("Fahrstufe " + speedStep + " außerhalb 0–" + steps.maxStep);
        CommandPriority p = speedStep == 0 && CommandPriority.SAFETY.outranks(priority) ? CommandPriority.SAFETY : priority;
        return submit(new Key(Kind.DRIVE, address, 0), new DriveValue(speedStep, direction, steps), p, 1,
                Objects.requireNonNull(listener), "Fahrbefehl Lok " + address + " Stufe " + speedStep);
    }

    @Override
    public boolean function(int address, int function, boolean on, CommandPriority priority) {
        guard.check("Lokfunktion planen");
        requireQueued(priority);
        return submit(new Key(Kind.FUNCTION, address, function), on, priority, 1, DriveListener.NONE,
                "Funktion F" + function + " Lok " + address);
    }

    @Override
    public boolean turnout(int address, TurnoutPosition position, CommandPriority priority) {
        guard.check("Weiche planen");
        requireQueued(priority);
        return submit(new Key(Kind.TURNOUT, address, 0), Objects.requireNonNull(position), priority, config.turnoutCost(),
                DriveListener.NONE, "Weiche " + address + " → " + position);
    }

    /**
     * Lokzustands-Abfrage: kein DCC-Paket, Kosten 0 Budget-Einheiten; sie wartet trotzdem in der Vorrangreihenfolge
     * hinter höher eingestuften Befehlen, denen Tokens fehlen. Nie als „unverändert“ unterdrückt.
     */
    @Override
    public boolean requestLocoState(int address, CommandPriority priority) {
        guard.check("Abfrage planen");
        requireQueued(priority);
        return submit(new Key(Kind.QUERY, address, 0), QUERY_VALUE, priority, 0, DriveListener.NONE,
                "Abfrage Lok " + address);
    }

    /** Gleisspannung ist kein DCC-Paket: ohne Warteschlange, sofort. */
    @Override
    public boolean trackPower(boolean on) {
        guard.check("Gleisspannung");
        station.setTrackPower(on);
        return true;
    }

    // ================================================================== Abfragen (Steuerungs-Thread)

    /** Aktuelles Budget (Befehle/s). */
    public double budgetPerSecond() { guard.check("Budget lesen"); return budget; }

    public int activeLocos() { guard.check("aktive Loks lesen"); return activeLocos; }

    public int backlog() { guard.check("Rückstau lesen"); return queue.size(); }

    public boolean overloaded() { guard.check("Überlast lesen"); return overloaded; }

    public SchedulerConfig config() { return config; }

    /**
     * Voraussichtliche Wartezeit eines jetzt eingereihten Befehls der Klasse {@code p} (kein Halt): Kosten aller
     * gleich oder höher eingestuften wartenden Befehle plus eins, abzüglich vorhandener Tokens, geteilt durch das
     * Budget. Für die Halteplanung (Befehlsverzögerung = eingemessene Verzögerung + diese Wartezeit).
     */
    public long expectedWaitNanos(CommandPriority p) {
        guard.check("Wartezeit schätzen");
        refill(clock.nanoTime());
        double ahead = 1;
        for (Pending q : queue) {
            if (p.outranks(q.priority)) break;     // Warteschlange ist nach Klasse sortiert
            if (p == CommandPriority.SAFETY && q.escalated) continue;   // echte Sicherheitsbefehle stehen davor
            ahead += q.cost;
        }
        double need = ahead - tokens;
        return need <= 0 ? 0 : (long) Math.ceil(need / budget * SECOND);
    }

    /** Messwerte seit dem Start. */
    public SchedulerMetrics metrics() {
        guard.check("Messwerte lesen");
        return build(total);
    }

    /** Messwerte des laufenden Messfensters. */
    public SchedulerMetrics windowMetrics() {
        guard.check("Messwerte lesen");
        return build(window);
    }

    /** Zuletzt im Journal berichtete Messwerte (aus jedem Thread). */
    public SchedulerMetrics lastReport() { return lastReport; }

    // ================================================================== Ereignisse des Executors

    /** Takt: Budget nachführen, Überlast prüfen, Messwerte berichten, Ausgabe anstoßen. */
    void onTick(long now) {
        guard.check("Befehlsplaner-Takt");
        refill(now);
        int active = countActive(now) + externalActive;
        activeLocos = active;
        double model = config.budgetPerSecond(active);
        double corrected = correction.correct(model);
        budget = Double.isFinite(corrected) && corrected > 0 ? Math.min(model, corrected) : model;   // nie über dem Modell
        evaluateOverload(now);
        reportIfDue(now);
        tryDispatch();
    }

    /**
     * Nothalt wurde übernommen (Steuerungs-Thread): wartende Fahrbefehle mit Fahrstufe &gt; 0 verwerfen, alle
     * gesendeten Fahrzustände für unbestätigt erklären (die Zentrale hat die Loks angehalten).
     */
    void onEmergency() {
        guard.check("Nothalt im Befehlsplaner");
        seenEpoch = emergencyEpoch.getAsLong();
        List<Pending> dropped = new ArrayList<>();
        for (Pending p : List.copyOf(queue)) {
            if (p.value instanceof DriveValue d && d.step() > 0) {
                queue.remove(p);
                p.slot.pending = null;
                dropped.add(p);
            }
        }
        for (Slot s : slots.values()) if (s.key.kind() == Kind.DRIVE) s.confirmed = false;
        if (!dropped.isEmpty()) {
            total.droppedOnEmergency += dropped.size();
            window.droppedOnEmergency += dropped.size();
            journal.warn("befehle", "COMMANDS_DROPPED_ON_EMERGENCY", "Nothalt: " + dropped.size()
                    + " wartende Fahrbefehle verworfen");
        }
        for (Pending p : dropped) p.listener.dropped("Nothalt");
        tryDispatch();
    }

    /** Verbindung verloren: alles Gesendete gilt als unbestätigt; Gesendetes bis zum Neuverbinden ebenso. */
    void connectionLost() {
        guard.check("Verbindungsverlust im Befehlsplaner");
        if (linkDown) return;
        linkDown = true;
        for (Slot s : slots.values()) s.confirmed = false;
        journal.info("befehle", "SCHEDULER_LINK_DOWN", "Befehlsplaner: Verbindung verloren, alle gesendeten Zustände unbestätigt");
    }

    /**
     * Verbindung (wieder) da: alle Zustände unbestätigt; für jede bekannte Lok ohne wartenden Befehl wird einmalig
     * Halt neu übertragen. Fahrstufen &gt; 0 werden nie selbständig wiederholt – sie müssen neu durch die Schranke.
     */
    void connectionRestored() {
        guard.check("Neuverbinden im Befehlsplaner");
        if (!linkDown) return;                     // nur nach Start oder Verlust, nie bei doppelter Meldung
        linkDown = false;
        long now = clock.nanoTime();
        int n = 0;
        for (Slot s : slots.values()) {
            s.confirmed = false;
            if (s.key.kind() != Kind.DRIVE || s.pending != null) continue;
            DriveValue halt = haltFor(s);
            if (halt == null) continue;
            Pending p = new Pending(s, now, seq++, 1, CommandPriority.SAFETY, halt, DriveListener.NONE);
            s.pending = p;
            queue.add(p);
            n++;
        }
        if (n > 0) {
            total.retransmissions += n;
            window.retransmissions += n;
            noteBacklog();
            journal.info("befehle", "SCHEDULER_RETRANSMIT", "Neuverbinden: für " + n
                    + " Loks wird einmalig Halt neu übertragen (Zustand der Zentrale unbestätigt)");
        }
        tryDispatch();
    }

    /**
     * Bekannte Lok (angemeldeter Zug) eintragen: Sie erhält nach jedem (Neu-)Verbinden einmal Halt, auch wenn ihr in
     * dieser Sitzung noch nie ein Befehl gesendet wurde (Entscheidung 28, Grundsatz 5: nach einem Absturz kann die
     * Zentrale sie noch mit der letzten Fahrstufe fahren). Sendet selbst nichts.
     */
    void registerKnownLoco(int address, SpeedSteps steps) {
        guard.check("Bekannte Lok eintragen");
        Slot s = slots.computeIfAbsent(new Key(Kind.DRIVE, address, 0), Slot::new);
        s.knownSteps = Objects.requireNonNull(steps);
    }

    /**
     * Einmal Halt für eine bekannte Lok einreihen (Sicherheit), z. B. wenn ein Zug bei bestehender Verbindung
     * angemeldet wird. Richtung: zuletzt gesendet, sonst von der Zentrale gemeldet, sonst vorwärts.
     */
    void haltKnownLoco(int address) {
        guard.check("Halt bekannte Lok");
        Key key = new Key(Kind.DRIVE, address, 0);
        Slot s = slots.get(key);
        if (s == null) return;
        DriveValue halt = haltFor(s);
        if (halt == null) return;
        submit(key, halt, CommandPriority.SAFETY, 1, DriveListener.NONE, "Halt bekannte Lok " + address);
    }

    /** Halt mit der bekannten Richtung; null, wenn weder gesendet noch als bekannte Lok eingetragen. */
    private DriveValue haltFor(Slot s) {
        if (s.sent instanceof DriveValue d) return new DriveValue(0, d.direction(), d.steps());
        if (s.knownSteps == null) return null;
        // Entscheidung 33 (3): nie angesprochen → zuletzt gemeldete Richtung, sonst vorwärts, ohne vorherige Abfrage
        // (kein Verzug; bei einer stehenden Lok wechselt höchstens Licht oder Richtung)
        Direction dir = plant.reportedLoco(s.key.address()).map(l -> l.direction()).orElse(Direction.FORWARD);
        return new DriveValue(0, dir, s.knownSteps);
    }

    /** Ist die Verbindung laut Befehlsplaner da (seit Start bzw. Verlust neu verbunden)? */
    boolean linkUp() {
        guard.check("Verbindung lesen");
        return !linkDown;
    }

    /**
     * Zahl der Adressen, die die Zentrale ohne uns fährt (Handregler auf Stellvertreter-Adressen mit Fahrstufe &gt; 0):
     * Die Zentrale frischt sie auf dem Gleis auf, das kostet DCC-Kapazität. Zählt im nächsten Takt zu den aktiven Loks.
     */
    void setExternalActiveLocos(int count) {
        guard.check("Fremd aktive Loks setzen");
        if (count < 0) throw new IllegalArgumentException("Anzahl negativ: " + count);
        externalActive = count;
    }

    /** Eingang für die spätere Echo-Nachführung; derzeit ohne Wirkung ({@link BudgetCorrection#NONE}). */
    void echoMeasured(long roundTripNanos) {
        guard.check("Echo melden");
        correction.echoMeasured(roundTripNanos);
    }

    // ================================================================== Einreihen

    private static void requireQueued(CommandPriority p) {
        Objects.requireNonNull(p);
        if (p == CommandPriority.EMERGENCY)
            throw new IllegalArgumentException("Nothalt läuft nie über den Befehlsplaner (ControlExecutor.emergencyStop)");
    }

    private boolean submit(Key key, Object value, CommandPriority priority, int cost, DriveListener listener, String what) {
        long now = clock.nanoTime();
        Slot slot = slots.computeIfAbsent(key, Slot::new);
        if (key.kind() == Kind.DRIVE) slot.lastCommandNanos = now;
        Pending p = slot.pending;
        boolean halt = value instanceof DriveValue d && d.step() == 0;
        if (p != null) {
            // Entscheidung 30: eine aufgestufte Bremsstufe nimmt spätere Werte ihrer Ursprungsklasse weiter auf
            boolean continuesEscalated = p.escalated && priority == CommandPriority.AUTOMATIC;
            String why = null;
            if (p.priority.outranks(priority) && !continuesEscalated)
                why = "höher eingestufter Befehl (" + p.priority.german() + ") wartet noch";
            else if (p.halt() && !halt)
                why = "wartender Halt wird nur durch Halt ersetzt";
            if (why != null) {
                total.rejected++;
                window.rejected++;
                journal.warn("befehle", "COMMAND_REJECTED", what + " (" + priority.german() + ") abgewiesen: " + why);
                return false;
            }
        }
        total.submitted++;
        window.submitted++;
        // Bedarf der Bremskurven: jede Absenkung (planmäßig als Automatik) und jeder Sicherheitsbefehl
        if (priority == CommandPriority.SAFETY || isReduction(value, slot.sent)) demandCount++;
        // Weichen nie unterdrücken (keine Endlagenmeldung, Entscheidung 12/28), Abfragen ebenso nicht (sie sollen eine
        // frische Antwort holen); wartend wird trotzdem zusammengefasst
        boolean suppressible = key.kind() == Kind.DRIVE || key.kind() == Kind.FUNCTION;
        if (suppressible && slot.confirmed && value.equals(slot.sent)) {
            if (p != null) {                       // neuester Wert = Zustand der Zentrale: Wartendes entfällt
                queue.remove(p);
                slot.pending = null;
                total.coalesced++;
                window.coalesced++;
            }
            total.suppressed++;
            window.suppressed++;
            tryDispatch();
            return true;
        }
        if (p != null) {
            queue.remove(p);
            p.value = value;
            if (p.escalated && priority == CommandPriority.AUTOMATIC && isReduction(value, slot.sent)) {
                // weiterhin Bremsstufe: bleibt Sicherheit, Wartezeit läuft dort weiter
            } else if (priority != p.priority) {
                recordClassWait(p, now);           // Wartezeit je Klasse ab Eintritt
                p.classNanos = now;
                p.priority = priority;             // höher, gleich oder Rückkehr einer Aufstufung in ihre Klasse
                p.escalated = false;
            } else {
                p.escalated = false;
            }
            p.listener = listener;
            queue.add(p);
            total.coalesced++;
            window.coalesced++;
        } else {
            p = new Pending(slot, now, seq++, cost, priority, value, listener);
            slot.pending = p;
            queue.add(p);
            noteBacklog();
        }
        tryDispatch();
        return true;
    }

    private void noteBacklog() {
        int n = queue.size();
        total.maxBacklog = Math.max(total.maxBacklog, n);
        window.maxBacklog = Math.max(window.maxBacklog, n);
    }

    // ================================================================== Ausgabe

    private void refill(long now) {
        long dt = now - lastRefillNanos;
        if (dt <= 0) return;
        double add = budget * dt / SECOND;
        total.offered += add;
        window.offered += add;
        tokens = Math.min(config.burstCommands(), tokens + add);
        lastRefillNanos = now;
    }

    /** Sendet, solange Tokens reichen; sonst Wecker auf den Zeitpunkt, zu dem das nächste Token reicht. */
    private void tryDispatch() {
        if (dispatching) return;                   // Aufruf aus einer Rückmeldung: die äußere Schleife sendet weiter
        dispatching = true;
        List<Pending> escalated;
        try {
            escalated = escalateDue(clock.nanoTime());
            dispatchLoop();
        } finally {
            dispatching = false;
        }
        for (Pending p : escalated) p.listener.escalated();
        planEscalation(clock.nanoTime());
    }

    /**
     * Entscheidung 30: Bremsstufen (Absenkungen) der Automatik, die länger als {@code escalateAfterMillis} warten,
     * werden zur Sicherheit aufgestuft. Erhöhungen und Anfahren nie. Die bisherige Wartezeit wird der Automatik
     * zugerechnet, ab jetzt zählt sie in der Sicherheit.
     */
    private List<Pending> escalateDue(long now) {
        long limit = config.escalateAfterNanos();
        List<Pending> due = List.of();
        for (Pending p : queue) {
            if (p.priority == CommandPriority.AUTOMATIC && p.reduction() && now - p.classNanos >= limit) {
                if (due.isEmpty()) due = new ArrayList<>();
                due.add(p);
            }
        }
        for (Pending p : due) {
            queue.remove(p);
            recordClassWait(p, now);
            p.priority = CommandPriority.SAFETY;
            p.classNanos = now;
            p.escalated = true;
            queue.add(p);
            total.escalations++;
            window.escalations++;
        }
        return due;
    }

    /** Wecker auf die nächste fällige Aufstufung (genau zur Wartegrenze, nicht erst im nächsten Takt). */
    private void planEscalation(long now) {
        long limit = config.escalateAfterNanos();
        long next = Long.MAX_VALUE;
        for (Pending p : queue)
            if (p.priority == CommandPriority.AUTOMATIC && p.reduction()) next = Math.min(next, p.classNanos + limit);
        if (next == Long.MAX_VALUE) {
            if (escalationWake != null) escalationWake.cancel();
            escalationWake = null;
            return;
        }
        if (escalationWake != null && escalationWake.isPending()) {
            if (escalationWake.dueNanos() == next) return;
            escalationWake.cancel();
        }
        escalationWake = executor.schedule(Math.max(next, now), "Aufstufung Bremsstufe", this::tryDispatch);
    }

    /** Wartezeit eines Befehls in seiner bisherigen Klasse erfassen (beim Senden oder beim Klassenwechsel). */
    private void recordClassWait(Pending p, long now) {
        long waited = Math.max(0, now - p.classNanos);
        total.wait.get(p.priority).record(waited);
        window.wait.get(p.priority).record(waited);
        if (p.priority == CommandPriority.SAFETY) {
            (p.escalated ? total.escalated : total.safetyOwn).record(waited);
            (p.escalated ? window.escalated : window.safetyOwn).record(waited);
        }
        evalWait[p.priority.ordinal()].record(overloadRelevantWait(p.priority, p.classNanos, now));
    }

    private void dispatchLoop() {
        while (true) {
            long now = clock.nanoTime();
            refill(now);
            Pending head = queue.isEmpty() ? null : queue.first();
            if (head == null || (head.priority == CommandPriority.COMFORT && overloaded)) {
                cancelWake();                      // Komfort bei Überlast zurückgestellt; der Takt prüft erneut
                return;
            }
            double floor = head.halt() ? -config.haltBorrowCommands() : 0;
            // Kosten über dem Bucket-Inhalt (Weiche bei kleinem Bucket): starten bei vollem Bucket, Rest als Schuld
            double missing = Math.min(head.cost, config.burstCommands()) + floor - tokens;
            if (missing > EPS) {
                scheduleWake(now, missing);
                return;
            }
            queue.pollFirst();
            head.slot.pending = null;
            tokens -= head.cost;
            send(head, now);
        }
    }

    private void scheduleWake(long now, double missingTokens) {
        long due = now + Math.max(1, (long) Math.ceil(missingTokens / budget * SECOND));
        if (wake != null && wake.isPending()) {
            if (wake.dueNanos() <= due) return;
            wake.cancel();
        }
        wake = executor.schedule(due, "Befehlsausgabe", this::tryDispatch);
    }

    private void cancelWake() {
        if (wake != null && wake.isPending()) wake.cancel();
        wake = null;
    }

    private void send(Pending p, long now) {
        recordClassWait(p, now);
        Slot s = p.slot;
        switch (p.value) {
            case DriveValue d -> sendDrive(p, d, now);
            case Boolean on -> {
                station.setLocoFunction(s.key.address(), s.key.index(), on);
                markSent(p, on);
            }
            case TurnoutPosition pos -> {
                String why = rules.accessoryBlocked();
                if (why != null) {
                    tokens += p.cost;              // nicht gesendet: Budget zurück
                    countDropped();
                    journal.warn("befehle", "COMMAND_DROPPED", "Weiche " + s.key.address() + " → " + pos
                            + " beim Senden verworfen: " + why);
                    return;
                }
                station.setTurnout(s.key.address(), pos);
                markSent(p, pos);
            }
            case Query q -> {
                String why = linkDown ? "Verbindung nicht hergestellt" : rules.queryBlocked();
                if (why != null) {                 // Verbindung inzwischen weg: Abfrage entfällt (Kosten 0)
                    countDropped();
                    journal.debug("befehle", "COMMAND_DROPPED", "Abfrage Lok " + s.key.address() + " entfällt: " + why);
                    return;
                }
                station.requestLocoState(s.key.address());
                markSent(p, q);
            }
            default -> throw new IllegalStateException("Unbekannter Befehlswert " + p.value);
        }
    }

    private void sendDrive(Pending p, DriveValue d, long now) {
        int address = p.slot.key.address();
        String dropReason = null;
        if (d.step() > 0) {
            String why = rules.movementBlocked(address);
            if (why == null && emergencyEpoch.getAsLong() != seenEpoch) why = "Nothalt angefordert";
            if (why != null) {
                dropReason = why;
                countDropped();
                journal.warn("befehle", "COMMAND_DROPPED", "Fahrbefehl Lok " + address + " Stufe " + d.step()
                        + " beim Senden verworfen: " + why + " – Halt statt dessen");
                d = new DriveValue(0, d.direction(), d.steps());
            }
        }
        long epochBefore = emergencyEpoch.getAsLong();
        station.setLocoDrive(address, d.step(), d.steps(), d.direction());
        markSent(p, d);
        if (d.step() > 0 && emergencyEpoch.getAsLong() != epochBefore) {
            // Nothalt aus einem anderen Thread während dieses Sendens: Nothalt muss der letzte Befehl sein
            station.emergencyStop();
            journal.critical("nothalt", "EMERGENCY_STOP_RESENT", "Nothalt während Fahrbefehl Lok " + address
                    + " ausgelöst – Nothalt erneut gesendet");
        }
        p.listener.sent(d.step(), d.direction(), now);
        if (dropReason != null) p.listener.dropped(dropReason);
    }

    private void markSent(Pending p, Object value) {
        Slot s = p.slot;
        s.sent = value;
        s.confirmed = !linkDown;
        total.sent++;
        window.sent++;
        total.spent += p.cost;
        window.spent += p.cost;
    }

    private void countDropped() {
        total.droppedAtDispatch++;
        window.droppedAtDispatch++;
    }

    // ================================================================== Budget, Überlast, Bericht

    private int countActive(long now) {
        long hold = config.activeHoldNanos();
        int n = 0;
        for (Slot s : slots.values()) {
            if (s.key.kind() != Kind.DRIVE) continue;
            boolean moving = s.sent instanceof DriveValue d && d.step() > 0;
            boolean recent = s.lastCommandNanos != Long.MIN_VALUE && now - s.lastCommandNanos < hold;
            if (moving || recent) n++;
        }
        return n;
    }

    /**
     * Für den Überlast-Befund maßgebliche Wartezeit. Komfort wird bei Überlast absichtlich zurückgestellt; diese
     * Zeit zählt nicht, sonst hielte der zurückgestellte Komfort die Überlast nach dem Aufheben selbst wieder fest.
     */
    private long overloadRelevantWait(CommandPriority p, long firstNanos, long now) {
        long from = p == CommandPriority.COMFORT ? Math.max(firstNanos, comfortReleasedNanos) : firstNanos;
        return Math.max(0, now - from);
    }

    private void evaluateOverload(long now) {
        List<String> reasons = new ArrayList<>();
        long[] oldest = new long[CommandPriority.values().length];
        java.util.Arrays.fill(oldest, Long.MAX_VALUE);
        int comfort = 0;
        for (Pending p : queue) {
            int k = p.priority.ordinal();
            oldest[k] = Math.min(oldest[k], p.classNanos);
            if (p.priority == CommandPriority.COMFORT) comfort++;
        }
        // Rückstau ohne Komfort: Komfort ist je Lok und Funktion zusammengefasst, also begrenzt, und darf warten
        int backlog = queue.size() - comfort;
        if (backlog > config.overloadBacklog())
            reasons.add("Rückstau " + backlog + " Befehle (Grenze " + config.overloadBacklog() + ")");
        if (now - demandWindowStart >= SECOND) {      // Sekundenfenster: Bedarf und p95 der Wartezeit je Klasse
            demandPerSecond.addLast(demandCount * (double) SECOND / (now - demandWindowStart));
            while (demandPerSecond.size() > config.demandWindowSeconds()) demandPerSecond.removeFirst();
            demandRate = demandPerSecond.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            demandCount = 0;
            demandWindowStart = now;
            for (CommandPriority c : CommandPriority.QUEUED) {
                int k = c.ordinal();
                lastWaitP95Ms[k] = evalWait[k].count() > 0 ? evalWait[k].summary().p95Ms() : 0;
                evalWait[k].reset();
            }
        }
        // Einzelne Spitzen (alle Züge rechnen im selben Takt) sind keine Überlast: maßgeblich ist das p95 der
        // letzten Sekunde; ein noch Wartender erst ab der doppelten Grenze.
        for (CommandPriority c : CommandPriority.QUEUED) {
            int k = c.ordinal();
            long limit = config.waitLimitNanos(c);
            long pendingWait = oldest[k] == Long.MAX_VALUE ? 0 : overloadRelevantWait(c, oldest[k], now);
            if (overloaded && c == CommandPriority.COMFORT) pendingWait = 0;   // gerade zurückgestellt
            if (lastWaitP95Ms[k] * 1e6 > limit)
                reasons.add(String.format(Locale.ROOT, "Wartezeit %s p95 %.0f ms (Grenze %d ms)", c.german(),
                        lastWaitP95Ms[k], limit / 1_000_000));
            else if (pendingWait > 2 * limit)
                reasons.add(String.format(Locale.ROOT, "Wartezeit %s %.0f ms wartend (Grenze %d ms)", c.german(),
                        pendingWait / 1e6, limit / 1_000_000));
        }
        if (demandRate > budget + EPS)
            reasons.add(String.format(Locale.ROOT, "Bedarf Bremskurven/Sicherheit %.1f/s (Mittel %d s) über Budget %.1f/s", demandRate,
                    config.demandWindowSeconds(), budget));

        if (!reasons.isEmpty()) {
            lastOverloadSeenNanos = now;
            boolean changed = !reasons.equals(overloadReasons);
            overloadReasons = List.copyOf(reasons);
            if (!overloaded) {
                overloaded = true;
                total.overloadEvents++;
                window.overloadEvents++;
                lastOverloadReportNanos = now;
                journal.warn("befehle", "SCHEDULER_OVERLOAD", "Überlast der Befehlsausgabe: " + String.join("; ", reasons)
                        + String.format(Locale.ROOT, " (Budget %.1f/s, aktive Loks %d). Sicherheit hat Vorrang, Komfort wird zurückgestellt",
                        budget, activeLocos));
                publish();
            } else if (changed && now - lastOverloadReportNanos >= config.overloadReportSeconds() * SECOND) {
                lastOverloadReportNanos = now;
                journal.warn("befehle", "SCHEDULER_OVERLOAD", "Überlast hält an: " + String.join("; ", reasons));
                publish();
            }
        } else if (overloaded && now - lastOverloadSeenNanos >= config.overloadClearSeconds() * SECOND) {
            overloaded = false;
            overloadReasons = List.of();
            comfortReleasedNanos = now;
            journal.info("befehle", "SCHEDULER_OVERLOAD_CLEARED", "Überlast der Befehlsausgabe aufgehoben; Komfort wird wieder gesendet");
            publish();
        }
    }

    private void reportIfDue(long now) {
        if (now - windowStartNanos < config.metricsReportSeconds() * SECOND) return;
        SchedulerMetrics m = build(window);
        if (m.submitted() > 0 || m.backlog() > 0 || m.overloadEvents() > 0) {
            if (m.overloadEvents() > 0 || overloaded) journal.warn("befehle", "SCHEDULER_METRICS", "Befehlsplaner: " + m.format());
            else journal.info("befehle", "SCHEDULER_METRICS", "Befehlsplaner: " + m.format());
        }
        lastReport = m;
        window.reset();
        windowStartNanos = now;
        plant.setScheduler(m);                     // Momentaufnahme: Werte des abgeschlossenen Messfensters
    }

    /** Status in die Momentaufnahme (nur bei Überlastwechsel und periodischem Bericht, nicht je Befehl). */
    private void publish() {
        plant.setScheduler(build(window));
    }

    private SchedulerMetrics build(Counters c) {
        EnumMap<CommandPriority, TimingStatistics.Summary> w = new EnumMap<>(CommandPriority.class);
        c.wait.forEach((p, s) -> w.put(p, s.summary()));
        double util = c.offered > 0 ? Math.min(1.0, c.spent / c.offered) : 0;
        return new SchedulerMetrics(budget, activeLocos, queue.size(), c.maxBacklog, util, w, c.submitted, c.sent,
                c.suppressed, c.coalesced, c.rejected, c.droppedAtDispatch, c.droppedOnEmergency, c.retransmissions,
                c.overloadEvents, overloaded, overloadReasons, c.escalations, c.safetyOwn.summary(), c.escalated.summary());
    }
}
