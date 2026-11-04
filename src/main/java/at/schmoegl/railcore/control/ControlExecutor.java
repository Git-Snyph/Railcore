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

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zentraler Steuerungs-Executor (Entscheidung 4, 23). Genau ein Thread ändert den Anlagenzustand ({@link PlantState});
 * andere Threads (Zentrale, Oberfläche, Watchdog) legen nur Ereignisse und Befehle in die Warteschlange.
 * Der Executor arbeitet sie nacheinander ab und rechnet einen festen Takt (Verbindungsüberwachung,
 * Rückmelder-Entprellung). Nach jedem Schritt veröffentlicht er eine unveränderliche {@link PlantSnapshot}.
 * <p>
 * <b>Nothalt</b> ({@link #emergencyStop}) ist aus jedem Thread aufrufbar, sendet sofort an die Zentrale – ohne
 * Warteschlange und ohne auf den Executor zu warten (Grundsatz 7) – und lässt den Executor den Zustand danach
 * vorrangig nachziehen. Er wird nie automatisch aufgehoben, nur durch {@link ControlCommand.AcknowledgeEmergency}.
 * <p>
 * <b>Testbetrieb:</b> {@link #step()}, {@link #processPending()} und {@link #tick()} laufen ohne eigenen Thread; der
 * aufrufende Thread wird beim ersten Aufruf zum Steuerungs-Thread. {@link #start()} legt den echten Thread darüber.
 */
public final class ControlExecutor {

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

    private ControlExecutor(ControlConfig config, TimeSource clock, Journal journal, CommandStation station,
                            CommandSink sink, Collection<Integer> knownContacts) {
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.journal = Objects.requireNonNull(journal);
        this.station = Objects.requireNonNull(station);
        this.guard = new ThreadGuard(journal);
        this.plant = new PlantState(guard, journal, knownContacts);
        this.feedback = new FeedbackFilter(clock, journal, config.feedbackReleaseMillis(), config.flickerWarnThreshold(),
                e -> plant.setSensor(e.contact(), e.occupied() ? SensorState.OCCUPIED : SensorState.FREE));
        this.communication = new CommunicationWatchdog(clock, journal, config.commWarnMillis(), config.commLostMillis());
        this.commands = new GatedCommandSink(guard, plant, journal, Objects.requireNonNull(sink), GatedCommandSink.MovementAuthority.NONE);
        this.queue = new LinkedBlockingQueue<>(config.queueCapacity());
        this.watchdog = new ExecutorWatchdog(this, clock, journal, config);
        this.lastTickNanos = clock.nanoTime();
        this.snapshot = plant.initialSnapshot(lastTickNanos);
    }

    /**
     * Legt den Executor an und meldet ihn als Listener an der Zentrale an.
     *
     * @param sink           Ausgabe der Befehle (später CommandScheduler); der Nothalt geht direkt an {@code station}
     * @param knownContacts  Melder der Anlage; sie starten „unbekannt“
     */
    public static ControlExecutor create(ControlConfig config, TimeSource clock, Journal journal, CommandStation station,
                                         CommandSink sink, Collection<Integer> knownContacts) {
        ControlExecutor ex = new ControlExecutor(config, clock, journal, station, sink, knownContacts);
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

    /** Ein vollständiger Schritt: alle anstehenden Ereignisse abarbeiten, dann einen Takt rechnen. */
    public void step() {
        processPending();
        tick();
    }

    /** Arbeitet zuerst vorrangige Aufgaben, dann alle zu Beginn anstehenden Ereignisse ab. */
    public void processPending() {
        bindOrCheck();
        runUrgent();
        int n = queue.size();
        for (int i = 0; i < n; i++) {
            Task t = queue.poll();
            if (t == null) break;
            runTask(t);
            runUrgent();
        }
        publish();
    }

    /** Ein Rechentakt: Lebenszeichen übernehmen, Verbindung prüfen, Rückmelder entprellen, Momentaufnahme veröffentlichen. */
    public void tick() {
        bindOrCheck();
        try {
            OperatingState state = plant.operatingState();
            long hb = latestHeartbeat.getAndSet(NO_HEARTBEAT);
            if (hb != NO_HEARTBEAT) communication.alive(hb);
            if (state != OperatingState.OFFLINE) checkCommunication();
            feedback.poll();
        } catch (RuntimeException e) {
            internalError("Takt", e);
        }
        lastTickNanos = clock.nanoTime();
        publish();
    }

    // ================================================================== Thread-Betrieb

    /** Startet den Steuerungs-Thread und den Executor-Watchdog. */
    public synchronized void start() {
        if (thread != null) throw new IllegalStateException("Steuerungs-Executor läuft bereits");
        Thread t = new Thread(this::loop, "railcore-steuerung");
        t.setPriority(Thread.MAX_PRIORITY);
        guard.bind(t);
        thread = t;
        running = true;
        lastTickNanos = clock.nanoTime();
        t.start();
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

    private void loop() {
        long tickNanos = config.tickNanos();
        long next = clock.nanoTime() + tickNanos;
        while (running) {
            try {
                runUrgent();
                long wait = next - clock.nanoTime();
                if (wait <= 0) {
                    tick();
                    long now = clock.nanoTime();
                    next += tickNanos;
                    if (next <= now) {
                        journal.warn("steuerung", "TICK_OVERRUN", "Takt um " + (now - next + tickNanos) / 1_000_000 + " ms überschritten");
                        next = now + tickNanos;
                    }
                    continue;
                }
                Task t = queue.poll(wait, TimeUnit.NANOSECONDS);
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
        }
        lastComm = s;
    }

    private void connectionLost(String why) {
        emergencyStop("verbindung", why);
        enterFault("verbindung", why);
        feedback.reset();
        plant.allSensorsUnknown(why);
        plant.setTrackPower(TrackPower.UNKNOWN);
        plant.allTrainsUnconfirmed(why);
    }

    private void applyEmergency(String source, String reason, boolean sent) {
        plant.latchEmergency(source + ": " + reason);
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
            } else {
                journal.info("zentrale", "CONNECTED", "Verbindung gemeldet im Zustand " + s.german() + ": " + info);
            }
        } else if (s == OperatingState.CONNECTING) {
            plant.transition(OperatingState.OFFLINE, "Verbindungsaufbau abgebrochen: " + info);
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
