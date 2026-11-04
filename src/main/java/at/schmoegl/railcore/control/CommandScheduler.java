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
 *       (zusammenfassen). Ein Wert, der dem zuletzt gesendeten <i>und bestätigten</i> gleicht, wird nie gesendet.
 *       „Bestätigt“ heißt: gesendet bei intakter Verbindung und seither weder Verbindungsverlust noch Nothalt.</li>
 *   <li><b>Vorrang</b> ({@link CommandPriority}): streng nach Klasse, innerhalb der Klasse älteste zuerst; in der
 *       Sicherheitsklasse Halt vor Bremsstufen. Ein niedriger eingestufter Befehl überschreibt nie einen höher
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
 *   <li><b>Neuverbinden</b>: alle Zustände unbestätigt, für jede bekannte Lok wird einmalig Halt neu übertragen
 *       (nie Fahrstufe &gt; 0 ohne die Schranke).</li>
 *   <li><b>Überlast</b> wird erkannt und gemeldet (Journal gedrosselt, Momentaufnahme); Sicherheit hat Vorrang,
 *       Komfort wird zurückgestellt. Züge werden <i>nicht</i> ausgebremst (folgt in Meilenstein 4).</li>
 * </ul>
 * Gleisspannung ist kein DCC-Paket und läuft ohne Warteschlange.
 */
public final class CommandScheduler implements CommandSink {

    private static final double EPS = 1e-9;
    private static final long SECOND = 1_000_000_000L;

    private enum Kind { DRIVE, FUNCTION, TURNOUT }

    private record Key(Kind kind, int address, int index) {}

    private record DriveValue(int step, Direction direction, SpeedSteps steps) {}

    private static final class Slot {
        final Key key;
        Object sent;                    // zuletzt gesendeter Wert, null = nie
        boolean confirmed;
        long lastCommandNanos = Long.MIN_VALUE;
        Pending pending;

        Slot(Key key) { this.key = key; }
    }

    private static final class Pending {
        final Slot slot;
        final long firstNanos;
        final long seq;
        final int cost;
        CommandPriority priority;
        Object value;
        DriveListener listener;

        Pending(Slot slot, long firstNanos, long seq, int cost, CommandPriority priority, Object value, DriveListener listener) {
            this.slot = slot;
            this.firstNanos = firstNanos;
            this.seq = seq;
            this.cost = cost;
            this.priority = priority;
            this.value = value;
            this.listener = listener;
        }

        boolean halt() { return value instanceof DriveValue d && d.step() == 0; }
    }

    private static final Comparator<Pending> ORDER = Comparator
            .comparingInt((Pending p) -> p.priority.ordinal())
            .thenComparingInt(p -> p.halt() ? 0 : 1)
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

    // Token-Bucket
    private double budget;
    private int activeLocos;
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
    private final long[] exceededWait = new long[CommandPriority.values().length];

    private final Counters total = new Counters();
    private final Counters window = new Counters();
    private long windowStartNanos;
    private volatile SchedulerMetrics lastReport = SchedulerMetrics.EMPTY;

    /** Zähler und Wartezeiten eines Zeitraums. */
    private static final class Counters {
        final EnumMap<CommandPriority, TimingStatistics> wait = new EnumMap<>(CommandPriority.class);
        long submitted, sent, suppressed, coalesced, rejected, droppedAtDispatch, droppedOnEmergency, retransmissions,
                overloadEvents;
        int maxBacklog;
        double offered, spent;

        Counters() { for (CommandPriority p : CommandPriority.QUEUED) wait.put(p, new TimingStatistics()); }

        void reset() {
            wait.values().forEach(TimingStatistics::reset);
            submitted = sent = suppressed = coalesced = rejected = droppedAtDispatch = droppedOnEmergency = retransmissions
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
        this.budget = correction.correct(config.budgetPerSecond(0));
        this.tokens = config.burstCommands();
        this.lastRefillNanos = now;
        this.demandWindowStart = now;
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
        int active = countActive(now);
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
            if (s.key.kind() != Kind.DRIVE || !(s.sent instanceof DriveValue d) || s.pending != null) continue;
            Pending p = new Pending(s, now, seq++, 1, CommandPriority.SAFETY, new DriveValue(0, d.direction(), d.steps()),
                    DriveListener.NONE);
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
            String why = null;
            if (p.priority.outranks(priority))
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
        if (priority == CommandPriority.SAFETY) demandCount++;
        if (slot.confirmed && value.equals(slot.sent)) {
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
            p.priority = priority;                 // gleich oder höher (niedriger wurde oben abgewiesen)
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
        try {
            while (true) {
                long now = clock.nanoTime();
                refill(now);
                Pending head = queue.isEmpty() ? null : queue.first();
                if (head == null || (head.priority == CommandPriority.COMFORT && overloaded)) {
                    cancelWake();                  // Komfort bei Überlast zurückgestellt; der Takt prüft erneut
                    return;
                }
                double floor = head.halt() ? -config.haltBorrowCommands() : 0;
                double missing = head.cost + floor - tokens;
                if (missing > EPS) {
                    scheduleWake(now, missing);
                    return;
                }
                queue.pollFirst();
                head.slot.pending = null;
                tokens -= head.cost;
                send(head, now);
            }
        } finally {
            dispatching = false;
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
        long waited = Math.max(0, now - p.firstNanos);
        total.wait.get(p.priority).record(waited);
        window.wait.get(p.priority).record(waited);
        int k = p.priority.ordinal();
        exceededWait[k] = Math.max(exceededWait[k], overloadRelevantWait(p.priority, p.firstNanos, now));
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
            oldest[k] = Math.min(oldest[k], p.firstNanos);
            if (p.priority == CommandPriority.COMFORT) comfort++;
        }
        // Rückstau ohne Komfort: Komfort ist je Lok und Funktion zusammengefasst, also begrenzt, und darf warten
        int backlog = queue.size() - comfort;
        if (backlog > config.overloadBacklog())
            reasons.add("Rückstau " + backlog + " Befehle (Grenze " + config.overloadBacklog() + ")");
        for (CommandPriority c : CommandPriority.QUEUED) {
            int k = c.ordinal();
            long pendingWait = oldest[k] == Long.MAX_VALUE ? 0 : overloadRelevantWait(c, oldest[k], now);
            if (overloaded && c == CommandPriority.COMFORT) pendingWait = 0;   // gerade zurückgestellt
            long w = Math.max(exceededWait[k], pendingWait);
            if (w > config.waitLimitNanos(c))
                reasons.add(String.format(Locale.ROOT, "Wartezeit %s %.0f ms (Grenze %d ms)", c.german(), w / 1e6,
                        config.waitLimitNanos(c) / 1_000_000));
            exceededWait[k] = 0;
        }
        if (now - demandWindowStart >= SECOND) {
            demandRate = demandCount * (double) SECOND / (now - demandWindowStart);
            demandCount = 0;
            demandWindowStart = now;
        }
        if (demandRate > budget + EPS)
            reasons.add(String.format(Locale.ROOT, "Bedarf Sicherheit/Bremskurven %.1f/s über Budget %.1f/s", demandRate, budget));

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
                c.overloadEvents, overloaded, overloadReasons);
    }
}
