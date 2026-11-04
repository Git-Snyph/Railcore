package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.CommandStation;
import at.schmoegl.railcore.core.CommandStationListener;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.FeedbackFilter;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.safety.SensorState;
import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zentraler Steuerungs-Executor (Entscheidung 4, 23). Genau ein Thread ändert den Anlagenzustand ({@link PlantState});
 * andere Threads (Zentrale, Oberfläche, Watchdog) legen nur Ereignisse und Befehle in die Warteschlange.
 * Der Executor arbeitet sie <b>sofort</b> nach Eingang ab, nicht erst im nächsten Takt (Entscheidung 24). Der Takt
 * (konfigurierbar, Standard 50 ms) dient nur zeitabhängigen Aufgaben (Verbindungsüberwachung, Rückmelder-Entprellung,
 * Bewegungsrechnung über {@link TickTask}); diese erhalten die tatsächlich vergangene Zeit aus der {@link TimeSource},
 * nie „Takt × n“. Brems- und Haltebefehle werden mit {@link #schedule} auf ihren vorausberechneten Zeitpunkt
 * eingeplant und dann ausgeführt, unabhängig vom Taktraster. Die Abweichung der Takte und der eingeplanten Befehle
 * von ihrem Sollzeitpunkt wird gemessen und gedrosselt im Journal berichtet ({@code TICK_JITTER}).
 * Nach jedem Schritt veröffentlicht der Executor eine unveränderliche {@link PlantSnapshot}.
 * <p>
 * <b>Nothalt</b> ({@link #emergencyStop}) ist aus jedem Thread aufrufbar, sendet sofort an die Zentrale – ohne
 * Warteschlange und ohne auf den Executor zu warten (Grundsatz 7) – und lässt den Executor den Zustand danach
 * vorrangig nachziehen. Er wird nie automatisch aufgehoben, nur durch {@link ControlCommand.AcknowledgeEmergency}.
 * <p>
 * <b>Testbetrieb:</b> {@link #step()}, {@link #processPending()}, {@link #tick()} und {@link #pump()} laufen ohne
 * eigenen Thread; der aufrufende Thread wird beim ersten Aufruf zum Steuerungs-Thread. {@link #pump()} zusammen mit
 * {@link #nextDueNanos()} bildet die Steuerschleife für Simulation mit {@code TimeSource.Manual} exakt nach.
 * {@link #start()} legt den echten Thread darüber.
 */
public final class ControlExecutor {

    /** Zeitabhängige Aufgabe, die in jedem Takt auf dem Steuerungs-Thread gerechnet wird. */
    @FunctionalInterface
    public interface TickTask {
        /**
         * @param nowNanos      Zeitpunkt dieses Takts (TimeSource)
         * @param elapsedNanos  tatsächlich vergangene Zeit seit dem vorigen Takt (nicht die Taktlänge)
         */
        void onTick(long nowNanos, long elapsedNanos);
    }

    /** Beobachter für Rückmelderereignisse (nach der Entprellung), läuft auf dem Steuerungs-Thread. */
    @FunctionalInterface
    public interface FeedbackObserver {
        /** @param timestampNanos Empfangszeitpunkt des Ereignisses (nicht der Verarbeitungszeitpunkt) */
        void onFeedback(int contact, boolean occupied, long timestampNanos);
    }

    /** Schutz gegen eine Aufgabe, die sich endlos sofort neu einplant. */
    private static final int MAX_TIMERS_PER_PASS = 10_000;

    private record Task(String label, Runnable action) {}

    private static final Task WAKE = new Task("wecken", () -> { });
    private static final long NO_HEARTBEAT = Long.MIN_VALUE;

    private final ControlConfig config;
    private final TimeSource clock;
    private final Journal journal;
    private final CommandStation station;
    private final ThreadGuard guard;
    private final PlantState plant;
    private final FeedbackFilter feedback;
    private final CommunicationWatchdog communication;
    private final GatedCommandSink commands;
    private final CommandScheduler scheduler;
    /** Zählt jede Nothalt-Anforderung (aus jedem Thread), bevor gesendet wird; der Befehlsplaner prüft sie. */
    private final AtomicLong emergencyEpoch = new AtomicLong();
    private final ExecutorWatchdog watchdog;
    private final BlockingQueue<Task> queue;
    /** Vorrangige Aufgaben (Nothalt nachziehen, Störung) – unbegrenzt, damit sie nie verloren gehen. */
    private final ConcurrentLinkedQueue<Task> urgent = new ConcurrentLinkedQueue<>();
    /** Zusammengefasstes Lebenszeichen der Zentrale (jüngster Empfangszeitpunkt), Übergabe ohne Warteschlange. */
    private final AtomicLong latestHeartbeat = new AtomicLong(NO_HEARTBEAT);
    private final CommandStationListener stationListener = new StationListener();

    private volatile long lastTickNanos;
    private volatile boolean stalled;
    private volatile PlantSnapshot snapshot;
    private volatile boolean running;
    private CommunicationWatchdog.Status lastComm = CommunicationWatchdog.Status.OK;
    private Thread thread;

    // ---- Zeitplan und Takt (nur Steuerungs-Thread)
    private final PriorityQueue<ScheduledAction> timers = new PriorityQueue<>(
            Comparator.comparingLong(ScheduledAction::dueNanos).thenComparingLong(ScheduledAction::sequence));
    private long timerSequence;
    private final List<TickTask> tickTasks = new ArrayList<>();
    private final List<FeedbackObserver> feedbackObservers = new ArrayList<>();
    private long nextTickNanos;
    private long previousTickStartNanos;
    private final TimingStatistics tickJitter = new TimingStatistics();
    private final TimingStatistics timerLateness = new TimingStatistics();
    private long jitterWindowStartNanos;
    private boolean overrunReportedInWindow;
    private volatile TimingStatistics.Summary lastTickJitterReport = TimingStatistics.Summary.EMPTY;
    private volatile TimingStatistics.Summary lastTimerLatenessReport = TimingStatistics.Summary.EMPTY;

    private ControlExecutor(ControlConfig config, SchedulerConfig schedulerConfig, TimeSource clock, Journal journal,
                            CommandStation station, Collection<Integer> knownContacts, MovementAuthority authority,
                            BudgetCorrection correction) {
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.journal = Objects.requireNonNull(journal);
        this.station = Objects.requireNonNull(station);
        this.guard = new ThreadGuard(journal);
        this.plant = new PlantState(guard, journal, knownContacts);
        this.feedback = new FeedbackFilter(clock, journal, config.feedbackReleaseMillis(), config.flickerWarnThreshold(),
                this::onFilteredFeedback);
        this.communication = new CommunicationWatchdog(clock, journal, config.commWarnMillis(), config.commLostMillis());
        CommandRules rules = new CommandRules(plant, Objects.requireNonNull(authority));
        this.scheduler = new CommandScheduler(Objects.requireNonNull(schedulerConfig), guard, clock, journal, station, rules,
                plant, this, emergencyEpoch::get, correction);
        this.commands = new GatedCommandSink(guard, rules, journal, scheduler);
        this.queue = new LinkedBlockingQueue<>(config.queueCapacity());
        this.watchdog = new ExecutorWatchdog(this, clock, journal, config);
        this.lastTickNanos = clock.nanoTime();
        this.previousTickStartNanos = lastTickNanos;
        this.nextTickNanos = lastTickNanos + config.tickNanos();
        this.jitterWindowStartNanos = lastTickNanos;
        this.snapshot = plant.initialSnapshot(lastTickNanos);
    }

    /**
     * Legt den Executor ohne Reservierungsverwaltung an: jede Bewegung wird abgewiesen ({@link MovementAuthority#NONE}).
     * Befehle gehen über Schranke → {@link CommandScheduler} (Standardwerte) → {@code station}; der Nothalt direkt.
     *
     * @param knownContacts  Melder der Anlage; sie starten „unbekannt“
     */
    public static ControlExecutor create(ControlConfig config, TimeSource clock, Journal journal, CommandStation station,
                                         Collection<Integer> knownContacts) {
        return create(config, clock, journal, station, knownContacts, MovementAuthority.NONE);
    }

    /**
     * Wie {@link #create(ControlConfig, TimeSource, Journal, CommandStation, Collection)}, mit einer
     * Bewegungsfreigabe (später die Reservierungsverwaltung). Die übrigen Sperren der Befehlsschranke (Nothalt,
     * Störung, Verbindung, Betriebszustand) bleiben davon unberührt.
     */
    public static ControlExecutor create(ControlConfig config, TimeSource clock, Journal journal, CommandStation station,
                                         Collection<Integer> knownContacts, MovementAuthority authority) {
        return create(config, SchedulerConfig.defaults(), clock, journal, station, knownContacts, authority);
    }

    /** Wie oben, mit eigenen Einstellungen des Befehlsplaners. */
    public static ControlExecutor create(ControlConfig config, SchedulerConfig schedulerConfig, TimeSource clock,
                                         Journal journal, CommandStation station, Collection<Integer> knownContacts,
                                         MovementAuthority authority) {
        return create(config, schedulerConfig, clock, journal, station, knownContacts, authority, BudgetCorrection.NONE);
    }

    /** Paketintern: mit Budget-Nachführung (Erweiterungsstelle, bis zur Bestätigung des Echos nicht genutzt). */
    static ControlExecutor create(ControlConfig config, SchedulerConfig schedulerConfig, TimeSource clock, Journal journal,
                                  CommandStation station, Collection<Integer> knownContacts, MovementAuthority authority,
                                  BudgetCorrection correction) {
        ControlExecutor ex = new ControlExecutor(config, schedulerConfig, clock, journal, station, knownContacts, authority,
                Objects.requireNonNull(correction));
        station.addListener(ex.stationListener);
        journal.info("steuerung", "EXECUTOR_CREATED", "Steuerungs-Executor angelegt (Takt " + config.tickMillis()
                + " ms, Watchdog-Toleranz " + config.watchdogToleranceMillis() + " ms, " + knownContacts.size() + " Melder unbekannt)");
        return ex;
    }

    // ================================================================== aus jedem Thread

    /** Jüngste Momentaufnahme (unveränderlich). */
    public PlantSnapshot snapshot() { return snapshot; }

    /** Befehl einreihen. @return false, wenn die Warteschlange überläuft (dann Nothalt und Störung) */
    public boolean submit(ControlCommand command) {
        Objects.requireNonNull(command);
        return enqueue(command.getClass().getSimpleName(), () -> handle(command));
    }

    /**
     * Nothalt: sendet sofort an die Zentrale, unabhängig von Warteschlange und Executor, und reiht danach das
     * Nachziehen des Zustands vorrangig ein. Schlägt das Senden fehl, wird das protokolliert und der Zustand trotzdem
     * auf Nothalt bzw. Störung gesetzt.
     */
    public void emergencyStop(String source, String reason) {
        emergencyEpoch.incrementAndGet();   // vor dem Senden: der Befehlsplaner sendet danach keine Fahrt mehr
        boolean sent;
        try {
            station.emergencyStop();
            sent = true;
            journal.critical("nothalt", "EMERGENCY_STOP_SENT", "Nothalt gesendet (" + source + "): " + reason);
        } catch (RuntimeException e) {
            sent = false;
            journal.critical("nothalt", "EMERGENCY_STOP_SEND_FAILED", "Nothalt konnte nicht gesendet werden (" + source + "): " + reason + " – " + e);
        }
        boolean wasSent = sent;
        urgent.add(new Task("Nothalt nachziehen", () -> applyEmergency(source, reason, wasSent)));
        queue.offer(WAKE);
    }

    /** Hat der Executor-Watchdog einen Hänger erkannt? */
    public boolean isStalled() { return stalled; }

    public ExecutorWatchdog watchdog() { return watchdog; }

    // ================================================================== nur Steuerungs-Thread

    /** Veränderlicher Anlagenzustand; jeder Zugriff wird auf den Steuerungs-Thread geprüft. */
    public PlantState plant() { return plant; }

    /** Befehlsausgabe mit Sicherheitsschranke; nur vom Steuerungs-Thread nutzbar. */
    public CommandSink commands() { return commands; }

    /**
     * Befehlsplaner hinter der Schranke (Messwerte, Budget, Wartezeit-Schätzung); nur Steuerungs-Thread. Befehle
     * immer über {@link #commands()} senden, nie direkt hierüber.
     */
    public CommandScheduler scheduler() { return scheduler; }

    /**
     * Ein vollständiger Schritt ohne Zeitplan (Testbetrieb): alle anstehenden Ereignisse und fälligen eingeplanten
     * Aufgaben abarbeiten, dann einen Takt rechnen – unabhängig davon, ob er laut Taktraster fällig ist.
     */
    public void step() {
        processPending();
        runDueTimers();
        tick();
        nextTickNanos = previousTickStartNanos + config.tickNanos();
    }

    /** Arbeitet zuerst vorrangige Aufgaben, dann alle zu Beginn anstehenden Ereignisse ab. */
    public void processPending() {
        bindOrCheck();
        drainQueue();
        publish();
    }

    /**
     * Ein Durchlauf der Steuerschleife (auch Simulation mit {@code TimeSource.Manual}): vorrangige Aufgaben und alle
     * anstehenden Ereignisse sofort, danach alle fälligen eingeplanten Aufgaben, danach der Takt, falls er laut
     * Taktraster fällig ist (mit Messung der Taktschwankung).
     */
    public void pump() {
        bindOrCheck();
        drainQueue();
        runDueWork();
        publish();
    }

    /** Nächster Zeitpunkt, zu dem der Executor ohne neues Ereignis etwas zu tun hat (Takt oder eingeplante Aufgabe). */
    public long nextDueNanos() {
        guard.check("Zeitplan lesen");
        ScheduledAction head = peekTimer();
        return head == null ? nextTickNanos : Math.min(nextTickNanos, head.dueNanos());
    }

    /** Ein Rechentakt außerhalb des Taktrasters (Testbetrieb); ohne Messung der Taktschwankung. */
    public void tick() {
        bindOrCheck();
        runTick(clock.nanoTime());
        publish();
    }

    /**
     * Plant eine Aufgabe auf einen festen Zeitpunkt ein. Sie läuft auf dem Steuerungs-Thread, sobald der Zeitpunkt
     * erreicht ist (nicht taktgerastert); liegt er in der Vergangenheit, beim nächsten Durchlauf. Eine Ausnahme in der
     * Aufgabe führt wie jeder interne Fehler zu Nothalt und Störung.
     */
    public ScheduledAction schedule(long dueNanos, String label, Runnable action) {
        guard.check("Aufgabe einplanen");
        ScheduledAction a = new ScheduledAction(dueNanos, timerSequence++, Objects.requireNonNull(label), Objects.requireNonNull(action));
        timers.add(a);
        return a;
    }

    /** Zeitabhängige Aufgabe für jeden Takt anmelden. */
    public void addTickTask(TickTask task) {
        guard.check("Taktaufgabe anmelden");
        tickTasks.add(Objects.requireNonNull(task));
    }

    public void removeTickTask(TickTask task) {
        guard.check("Taktaufgabe abmelden");
        tickTasks.remove(task);
    }

    /** Beobachter für entprellte Rückmelderereignisse anmelden. */
    public void addFeedbackObserver(FeedbackObserver observer) {
        guard.check("Rückmelder-Beobachter anmelden");
        feedbackObservers.add(Objects.requireNonNull(observer));
    }

    public void removeFeedbackObserver(FeedbackObserver observer) {
        guard.check("Rückmelder-Beobachter abmelden");
        feedbackObservers.remove(observer);
    }

    /** Uhr des Executors (für Planungen auf dem Steuerungs-Thread). */
    public TimeSource clock() { return clock; }

    public Journal journal() { return journal; }

    public ControlConfig config() { return config; }

    /** Taktschwankung im laufenden Messfenster (nur Steuerungs-Thread). */
    public TimingStatistics.Summary tickJitter() {
        guard.check("Taktschwankung lesen");
        return tickJitter.summary();
    }

    /** Verspätung eingeplanter Aufgaben im laufenden Messfenster (nur Steuerungs-Thread). */
    public TimingStatistics.Summary timerLateness() {
        guard.check("Zeitplan-Verspätung lesen");
        return timerLateness.summary();
    }

    /** Zuletzt im Journal berichtete Taktschwankung (aus jedem Thread). */
    public TimingStatistics.Summary lastTickJitterReport() { return lastTickJitterReport; }

    /** Zuletzt im Journal berichtete Verspätung eingeplanter Aufgaben (aus jedem Thread). */
    public TimingStatistics.Summary lastTimerLatenessReport() { return lastTimerLatenessReport; }

    // ================================================================== Thread-Betrieb

    /** Startet den Steuerungs-Thread und den Executor-Watchdog. */
    public synchronized void start() {
        if (thread != null) throw new IllegalStateException("Steuerungs-Executor läuft bereits");
        Thread t = new Thread(this::loop, "railcore-steuerung");
        t.setPriority(Thread.MAX_PRIORITY);
        guard.bind(t);
        thread = t;
        running = true;
        long now = clock.nanoTime();
        lastTickNanos = now;
        previousTickStartNanos = now;
        nextTickNanos = now + config.tickNanos();
        jitterWindowStartNanos = now;
        t.start();   // Thread.start: alle Felder oben sind für den Steuerungs-Thread sichtbar
        watchdog.start();
        journal.info("steuerung", "EXECUTOR_STARTED", "Steuerungs-Executor gestartet");
    }

    /** Hält Watchdog und Steuerungs-Thread an (Programmende). Löst keinen Nothalt aus. */
    public void stop() {
        Thread t;
        synchronized (this) { t = thread; }
        watchdog.stop();
        running = false;
        if (t != null) {
            t.interrupt();
            try { t.join(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        journal.info("steuerung", "EXECUTOR_STOPPED", "Steuerungs-Executor angehalten");
    }

    /**
     * Steuerschleife: Ereignisse werden sofort bei Eingang verarbeitet; bis zum nächsten fälligen Zeitpunkt (Takt
     * oder eingeplante Aufgabe) wartet der Thread an der Warteschlange und die letzte kurze Strecke
     * ({@link ControlConfig#timerSpinMicros()}) aktiv, weil die Zeitgeber des Betriebssystems grob auflösen.
     */
    private void loop() {
        long spin = config.timerSpinNanos();
        while (running) {
            try {
                runUrgent();
                runDueWork();
                publish();
                long wait = nextDueNanos() - clock.nanoTime();
                if (wait <= 0) continue;
                Task t;
                if (wait > spin) {
                    t = queue.poll(wait - spin, TimeUnit.NANOSECONDS);
                } else {
                    t = queue.poll();
                    if (t == null) { Thread.onSpinWait(); continue; }
                }
                if (t != null) {
                    runTask(t);
                    runUrgent();
                    publish();
                }
            } catch (InterruptedException e) {
                if (!running) break;
            } catch (RuntimeException e) {
                internalError("Steuerschleife", e);
            } catch (Error e) {
                // Thread stirbt; der Watchdog bemerkt den fehlenden Takt zusätzlich.
                journal.critical("steuerung", "EXECUTOR_DIED", "Steuerungs-Executor beendet durch " + e);
                emergencyStop("steuerung", "Steuerungs-Executor ausgefallen: " + e);
                throw e;
            }
        }
    }

    // ================================================================== intern

    long lastTickNanos() { return lastTickNanos; }

    void markStalled(boolean value) { stalled = value; }

    /** Störung melden (aus jedem Thread); wird vorrangig vom Executor übernommen. */
    void reportFault(String source, String why) {
        urgent.add(new Task("Störung", () -> enterFault(source, why)));
        queue.offer(WAKE);
    }

    /** Für Tests im selben Paket: beliebige Aufgabe einreihen. */
    boolean enqueue(String label, Runnable action) {
        if (queue.offer(new Task(label, action))) return true;
        String why = "Ereigniswarteschlange voll (" + config.queueCapacity() + "), Ereignis „" + label + "“ verworfen";
        journal.critical("steuerung", "QUEUE_OVERFLOW", why);
        emergencyStop("steuerung", why);
        reportFault("steuerung", why);
        return false;
    }

    private void bindOrCheck() {
        if (!guard.isBound()) guard.bind(Thread.currentThread());
        guard.check("Executor-Schritt");
    }

    private void runUrgent() {
        Task t;
        while ((t = urgent.poll()) != null) runTask(t);
    }

    /** Vorrangige Aufgaben und alle zu Beginn anstehenden Ereignisse, jedes sofort. */
    private void drainQueue() {
        runUrgent();
        int n = queue.size();
        for (int i = 0; i < n; i++) {
            Task t = queue.poll();
            if (t == null) break;
            runTask(t);
            runUrgent();
        }
    }

    /** Fällige eingeplante Aufgaben, danach der Takt, falls er laut Raster fällig ist. */
    private void runDueWork() {
        runDueTimers();
        long now = clock.nanoTime();
        if (now >= nextTickNanos) scheduledTick(now);
    }

    private ScheduledAction peekTimer() {
        ScheduledAction head;
        while ((head = timers.peek()) != null && head.isCancelled()) timers.poll();
        return head;
    }

    private void runDueTimers() {
        for (int i = 0; ; i++) {
            ScheduledAction a = peekTimer();
            long now = clock.nanoTime();
            if (a == null || a.dueNanos() > now) return;
            if (i >= MAX_TIMERS_PER_PASS) {
                internalError("Zeitplan", new IllegalStateException("mehr als " + MAX_TIMERS_PER_PASS + " sofort fällige Aufgaben"));
                timers.clear();
                return;
            }
            timers.poll();
            timerLateness.record(now - a.dueNanos());
            a.markDone(now);
            runUrgent();
            runTask(new Task(a.label(), a.action()));
            runUrgent();
        }
    }

    /** Takt laut Raster: Abweichung vom Sollzeitpunkt messen, Takt rechnen, nächsten Sollzeitpunkt setzen. */
    private void scheduledTick(long now) {
        long tickNanos = config.tickNanos();
        long deviation = now - nextTickNanos;
        tickJitter.record(deviation);
        if (deviation >= tickNanos) {
            tickJitter.recordOverrun();
            if (!overrunReportedInWindow) {
                overrunReportedInWindow = true;
                journal.warn("steuerung", "TICK_OVERRUN", "Takt um " + deviation / 1_000_000
                        + " ms verspätet, mindestens ein Takt ausgefallen (weitere im nächsten Bericht zur Taktschwankung)");
            }
        }
        runTick(now);
        nextTickNanos += tickNanos;
        if (nextTickNanos <= now) nextTickNanos = now + tickNanos;   // nach Überlauf neu aufsetzen, nicht nachholen
    }

    /** Ein Rechentakt mit der tatsächlich seit dem vorigen Takt vergangenen Zeit. */
    private void runTick(long now) {
        long elapsed = Math.max(0, now - previousTickStartNanos);
        previousTickStartNanos = now;
        try {
            OperatingState state = plant.operatingState();
            long hb = latestHeartbeat.getAndSet(NO_HEARTBEAT);
            if (hb != NO_HEARTBEAT) communication.alive(hb);
            if (state != OperatingState.OFFLINE) checkCommunication();
            feedback.poll();
        } catch (RuntimeException e) {
            internalError("Takt", e);
        }
        try {
            scheduler.onTick(now);
        } catch (RuntimeException e) {
            internalError("Befehlsplaner", e);
        }
        runUrgent();
        for (TickTask task : List.copyOf(tickTasks)) {
            try {
                task.onTick(now, elapsed);
            } catch (RuntimeException e) {
                internalError("Taktaufgabe", e);
            }
            runUrgent();
        }
        reportTimingIfDue(now);
        lastTickNanos = clock.nanoTime();
    }

    /** Gedrosselter Bericht: höchstens einmal je {@link ControlConfig#jitterReportSeconds()}. */
    private void reportTimingIfDue(long now) {
        if (now - jitterWindowStartNanos < config.jitterReportNanos()) return;
        TimingStatistics.Summary ticks = tickJitter.summary();
        TimingStatistics.Summary timed = timerLateness.summary();
        if (ticks.count() > 0 || ticks.overruns() > 0 || timed.count() > 0) {
            String msg = "Taktschwankung (Takt " + config.tickMillis() + " ms): " + ticks.format()
                    + "; eingeplante Befehle: " + timed.format();
            boolean bad = ticks.overruns() > 0 || ticks.p99Ms() > config.tickMillis() / 2.0 || timed.p99Ms() > 5.0;
            if (bad) journal.warn("steuerung", "TICK_JITTER", msg);
            else journal.info("steuerung", "TICK_JITTER", msg);
        }
        lastTickJitterReport = ticks;
        lastTimerLatenessReport = timed;
        tickJitter.reset();
        timerLateness.reset();
        overrunReportedInWindow = false;
        jitterWindowStartNanos = now;
    }

    /** Ausgabe der Rückmelder-Entprellung: Melderzustand setzen, dann Beobachter (Positionsabgleich) informieren. */
    private void onFilteredFeedback(FeedbackFilter.Event e) {
        plant.setSensor(e.contact(), e.occupied() ? SensorState.OCCUPIED : SensorState.FREE);
        for (FeedbackObserver o : List.copyOf(feedbackObservers))
            o.onFeedback(e.contact(), e.occupied(), e.timestampNanos());
    }

    private void runTask(Task t) {
        try {
            t.action().run();
        } catch (RuntimeException e) {
            internalError(t.label(), e);
        }
    }

    private void internalError(String where, RuntimeException e) {
        journal.critical("steuerung", "INTERNAL_ERROR", "Interner Fehler in „" + where + "“: " + e);
        emergencyStop("steuerung", "Interner Fehler in „" + where + "“");
        try {
            enterFault("steuerung", "Interner Fehler in „" + where + "“");
        } catch (RuntimeException again) {
            journal.critical("steuerung", "INTERNAL_ERROR", "Störung konnte nicht gesetzt werden: " + again);
        }
    }

    private void publish() {
        PlantSnapshot s = snapshot;
        if (s.version() != plant.version() || s.executorStalled() != stalled)
            snapshot = plant.snapshot(clock.nanoTime(), stalled);
    }

    private void checkCommunication() {
        CommunicationWatchdog.Status s = communication.check();
        plant.setCommunication(s);
        if (s == CommunicationWatchdog.Status.LOST && lastComm != CommunicationWatchdog.Status.LOST) {
            connectionLost("Verbindung zur Zentrale verloren");
        } else if (lastComm == CommunicationWatchdog.Status.LOST && s != CommunicationWatchdog.Status.LOST) {
            // Lastenheft 7: nach Wiederverbindung sofort Nothalt, Zustände neu einlesen, Freigabe durch Bediener
            emergencyStop("verbindung", "Verbindung wiederhergestellt – Nothalt vor dem Neueinlesen");
            scheduler.connectionRestored();
        }
        lastComm = s;
    }

    private void connectionLost(String why) {
        emergencyStop("verbindung", why);
        scheduler.connectionLost();
        enterFault("verbindung", why);
        feedback.reset();
        plant.allSensorsUnknown(why);
        plant.setTrackPower(TrackPower.UNKNOWN);
        plant.allTrainsUnconfirmed(why);
    }

    private void applyEmergency(String source, String reason, boolean sent) {
        plant.latchEmergency(source + ": " + reason);
        scheduler.onEmergency();
        if (plant.operatingState() != OperatingState.FAULT
                && !plant.transition(OperatingState.EMERGENCY_STOP, "Nothalt (" + source + "): " + reason)) {
            enterFault(source, "Nothalt im Zustand " + plant.operatingState().german() + ": " + reason);
        }
        if (!sent) enterFault(source, "Nothalt konnte nicht an die Zentrale gesendet werden");
    }

    private void enterFault(String source, String why) {
        plant.latchEmergency(source + ": " + why);
        if (plant.operatingState() == OperatingState.FAULT) {
            journal.error(source, "FAULT", "Weitere Störung: " + why);
        } else if (!plant.transition(OperatingState.FAULT, why)) {
            journal.critical(source, "FAULT_NOT_ENTERED", "Störung im Zustand " + plant.operatingState().german()
                    + " nicht setzbar, Nothalt bleibt gesetzt: " + why);
        }
    }

    // ------------------------------------------------------------------ Befehle

    private void handle(ControlCommand command) {
        journal.info("bedienung", "COMMAND", command.getClass().getSimpleName() + " von " + command.operator());
        switch (command) {
            case ControlCommand.AcknowledgeEmergency a -> acknowledge(a.operator());
            case ControlCommand.RequestReady r -> requestReady(r.operator());
            case ControlCommand.RegisterTrain r -> registerTrain(r);
        }
    }

    private void acknowledge(String operator) {
        OperatingState s = plant.operatingState();
        if (stalled) { reject("Quittieren", "Executor-Watchdog meldet einen Hänger"); return; }
        if (s != OperatingState.OFFLINE && plant.communication() == CommunicationWatchdog.Status.LOST) {
            reject("Quittieren", "Verbindung zur Zentrale verloren");
            return;
        }
        if (s == OperatingState.EMERGENCY_STOP || s == OperatingState.FAULT) {
            if (plant.transition(OperatingState.RECOVERY, "Quittiert durch " + operator + " – Zustände prüfen"))
                plant.clearEmergencyLatch(operator);
            else reject("Quittieren", "Übergang nach Wiederanlauf nicht erlaubt");
        } else if (plant.emergencyLatched()) {
            plant.clearEmergencyLatch(operator);
        } else {
            journal.info("bedienung", "NOTHING_TO_ACKNOWLEDGE", "Kein Nothalt und keine Störung zu quittieren");
        }
    }

    private void requestReady(String operator) {
        OperatingState s = plant.operatingState();
        String why = null;
        if (s != OperatingState.CONNECTING && s != OperatingState.RECOVERY) why = "nur aus „Verbinde“ oder „Wiederanlauf“ möglich (ist " + s.german() + ")";
        else if (plant.emergencyLatched()) why = "Nothalt nicht quittiert";
        else if (stalled) why = "Executor-Watchdog meldet einen Hänger";
        else if (plant.communication() != CommunicationWatchdog.Status.OK) why = "Verbindung zur Zentrale nicht in Ordnung";
        if (why != null) { reject("Freigabe „Bereit“", why); return; }
        plant.transition(OperatingState.READY, "Freigabe durch " + operator);
    }

    private void registerTrain(ControlCommand.RegisterTrain r) {
        int a = r.train().address();
        if (a >= 9000 && a <= 9999) {   // Entscheidung 7: Stellvertreter-Bereich
            reject("Zug anmelden", "Adresse " + a + " liegt im Stellvertreter-Bereich 9000–9999");
            return;
        }
        plant.registerTrain(r.train());
    }

    private void reject(String what, String why) {
        journal.warn("bedienung", "COMMAND_REJECTED", what + " abgewiesen: " + why);
    }

    // ------------------------------------------------------------------ Ereignisse der Zentrale

    private void onConnection(boolean connected, String info) {
        OperatingState s = plant.operatingState();
        if (connected) {
            if (s == OperatingState.OFFLINE) {
                communication.reset();
                lastComm = CommunicationWatchdog.Status.OK;
                plant.setCommunication(CommunicationWatchdog.Status.OK);
                plant.transition(OperatingState.CONNECTING, "Verbunden mit " + info);
                scheduler.connectionRestored();
            } else {
                journal.info("zentrale", "CONNECTED", "Verbindung gemeldet im Zustand " + s.german() + ": " + info);
                scheduler.connectionRestored();   // nach Verlust: Halt einmalig neu übertragen (Zustand bleibt Störung)
            }
        } else if (s == OperatingState.CONNECTING) {
            plant.transition(OperatingState.OFFLINE, "Verbindungsaufbau abgebrochen: " + info);
            scheduler.connectionLost();
            feedback.reset();
            plant.allSensorsUnknown("Verbindung getrennt");
        } else if (s != OperatingState.OFFLINE) {
            connectionLost("Zentrale getrennt: " + info);
        }
    }

    private void onTrackPower(TrackPower p) {
        plant.setTrackPower(p);
        OperatingState s = plant.operatingState();
        boolean operating = s.manualMayMove() || s == OperatingState.RECOVERY;
        if (p == TrackPower.SHORT_CIRCUIT) {
            enterFault("zentrale", "Zentrale meldet Kurzschluss");
        } else if (p == TrackPower.EMERGENCY_STOP && operating) {
            applyEmergency("zentrale", "Zentrale meldet Nothalt", true);
        }
    }

    private void onFeedback(int contact, boolean occupied, long ts) {
        feedback.raw(contact, occupied, Math.min(ts, clock.nanoTime()));
    }

    /** Übersetzt Ereignisse der Zentrale (Hintergrund-Thread) in Executor-Aufgaben. */
    private final class StationListener implements CommandStationListener {
        @Override public void onConnectionChanged(boolean connected, String info) {
            enqueue("Verbindung", () -> onConnection(connected, info));
        }
        @Override public void onHeartbeat(long timestampNanos) {
            latestHeartbeat.accumulateAndGet(timestampNanos, Math::max);
        }
        @Override public void onTrackPower(TrackPower state) {
            enqueue("Gleisspannung", () -> ControlExecutor.this.onTrackPower(state));
        }
        @Override public void onFeedback(int contact, boolean occupied, long timestampNanos) {
            enqueue("Rückmelder", () -> ControlExecutor.this.onFeedback(contact, occupied, timestampNanos));
        }
        @Override public void onError(String message, Throwable cause) {
            journal.error("zentrale", "STATION_ERROR", message + (cause == null ? "" : " (" + cause + ")"));
        }
    }
}
