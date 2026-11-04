package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.CommandStation;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.util.TimeSource;

import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

/**
 * Verdrahtung von Steuerungs-Executor, Befehlsplaner und Zentrale (Lastenheft 2 „Nebenläufigkeit“, Entscheidungen 4,
 * 23, 27, 28). Nimmt eine beliebige {@link CommandStation} entgegen – Z21-Adapter oder Simulator – und ist danach der
 * einzige Weg zu ihr:
 * <ul>
 *   <li><b>Befehle</b> (Fahren, Funktionen, Weichen, Abfragen) nur auf dem Steuerungs-Thread über
 *       {@link ControlExecutor#commands()}: Sicherheitsschranke → {@link CommandScheduler} (Vorrang, DCC-Budget,
 *       unveränderte Fahrstufen nicht senden) → Zentrale. Die Zentrale selbst gibt die Laufzeit nicht heraus.</li>
 *   <li><b>Nothalt</b> ({@link #emergencyStop}) aus jedem Thread, sofort an die Zentrale, an jeder Warteschlange
 *       vorbei (Grundsatz 7); nie automatisch aufgehoben.</li>
 *   <li><b>Ereignisse</b> der Zentrale (Lebenszeichen, Verbindung, Gleisspannung, Kurzschluss, Melder, RailCom,
 *       Lokmeldungen, Weichenmeldungen, Fähigkeiten) legt deren Empfangs-Thread nur in die Warteschlange des Executors;
 *       allein der Executor ändert den {@link PlantState}.</li>
 *   <li><b>Verbinden</b>: Melder „unbekannt“, Züge „unbestätigt“ (Grundsatz 5); jede bekannte Lok erhält einmal
 *       Halt, nie eine Fahrstufe (Entscheidung 28); beim ersten Verbinden nach dem Programmstart zusätzlich Nothalt für
 *       die ganze Anlage, der quittiert werden muss (Entscheidung 33). Nach Verbindungsverlust (Kommunikations-Watchdog) Nothalt,
 *       Störung, Melder unbekannt, Meldungen der Zentrale verworfen; nach Wiederverbindung erneut Nothalt und Halt
 *       an alle bekannten Loks (Lastenheft 7). Fähigkeiten werden vom Adapter erkannt, nie vorausgesetzt.</li>
 *   <li><b>Executor-Watchdog</b> (eigener Thread) löst Nothalt aus, wenn der Steuerungs-Thread hängt.</li>
 *   <li><b>Handregler</b> auf Stellvertreter-Adressen werden als zeitabhängige Aufgabe abgefragt
 *       ({@link HandControllerPoller}).</li>
 *   <li><b>Trennen und Beenden</b> senden vorher Nothalt, solange die Zentrale verbunden ist: die Z21 fährt sonst
 *       mit der letzten Fahrstufe weiter (Entscheidungen 10, 33). Die folgende Trennmeldung löst keinen zweiten,
 *       zwangsläufig scheiternden Nothalt aus.</li>
 * </ul>
 * Betrieb: {@link #start()} (Steuerungs-Thread und Watchdog), dann {@link #connect()}. Tests mit
 * {@code TimeSource.Manual} rufen {@link #start()} nicht auf, sondern bedienen {@link ControlExecutor#pump()}
 * selbst; der aufrufende Thread wird dabei zum Steuerungs-Thread.
 */
public final class ControlRuntime implements AutoCloseable {

    private final ControlExecutor executor;
    private final CommandStation station;
    private final Journal journal;
    private volatile boolean closed;

    private ControlRuntime(ControlExecutor executor, CommandStation station, Journal journal) {
        this.executor = executor;
        this.station = station;
        this.journal = journal;
    }

    /**
     * Legt Executor und Befehlsplaner für {@code station} an. Die Zentrale darf danach nur noch über diese Laufzeit
     * angesprochen werden.
     *
     * @param knownContacts Melder der Anlage; sie starten „unbekannt“
     * @param authority     Bewegungsfreigabe (Reservierung); {@link MovementAuthority#NONE}, solange es keine gibt
     */
    public static ControlRuntime create(ControlConfig control, SchedulerConfig scheduler, HandControllerConfig hand,
                                        TimeSource clock, Journal journal, CommandStation station,
                                        Collection<Integer> knownContacts, MovementAuthority authority) {
        Objects.requireNonNull(hand);
        ControlExecutor ex = ControlExecutor.create(control, scheduler, clock, journal, station, knownContacts, authority);
        HandControllerPoller poller = new HandControllerPoller(ex, hand);
        ex.enableEmergencyStopOnFirstConnect();   // Entscheidung 33 (2)
        ex.enqueue("Handregler-Abfrage einrichten", poller::install);
        journal.info("steuerung", "RUNTIME_CREATED", "Steuerung verdrahtet mit Zentrale „" + station.name() + "“");
        return new ControlRuntime(ex, station, journal);
    }

    /** Mit Standardwerten und ohne Bewegungsfreigabe (jede Bewegung wird abgewiesen). */
    public static ControlRuntime create(TimeSource clock, Journal journal, CommandStation station,
                                        Collection<Integer> knownContacts) {
        return create(ControlConfig.defaults(), SchedulerConfig.defaults(), HandControllerConfig.defaults(), clock,
                journal, station, knownContacts, MovementAuthority.NONE);
    }

    /** Startet Steuerungs-Thread und Executor-Watchdog (Betrieb). */
    public void start() { executor.start(); }

    /**
     * Verbindung zur Zentrale öffnen. „Verbunden“ meldet der Adapter erst, wenn die Zentrale antwortet; bis dahin
     * bleibt der Zustand „Offline“.
     */
    public void connect() throws IOException {
        if (closed) throw new IllegalStateException("Steuerung ist beendet");
        journal.info("steuerung", "CONNECT", "Verbinde mit " + station.name());
        station.connect();
    }

    /**
     * Verbindung trennen. Ist die Zentrale verbunden, wird vorher Nothalt gesendet (Entscheidung 33) – ohne Verbindung kann RailCore die
     * Loks nicht mehr anhalten. Der Nothalt bleibt gesetzt und muss nach dem nächsten Verbinden quittiert werden.
     */
    public void disconnect() {
        // Entscheidung 33 (1): Nothalt, keine Gleisspannung aus; danach Trennmeldung ohne zweiten Nothalt
        if (station.isConnected() && executor.sendEmergencyStop("steuerung", "Verbindung wird getrennt"))
            executor.markOrderlyDisconnect();
        station.disconnect();
    }

    /** Nothalt aus jedem Thread: sofort an die Zentrale, unabhängig von Warteschlange und Executor. */
    public void emergencyStop(String source, String reason) { executor.emergencyStop(source, reason); }

    /** Bedienbefehl einreihen (aus jedem Thread). */
    public boolean submit(ControlCommand command) { return executor.submit(command); }

    /** Jüngste unveränderliche Momentaufnahme (aus jedem Thread). */
    public PlantSnapshot snapshot() { return executor.snapshot(); }

    /** Anzeigename der Zentrale. */
    public String stationName() { return station.name(); }

    /**
     * Der Steuerungs-Executor für Kernmodule auf dem Steuerungs-Thread (Zugsteuerung, später Reservierung). Befehle
     * an die Zentrale gehen ausschließlich über {@link ControlExecutor#commands()}.
     */
    public ControlExecutor executor() { return executor; }

    /** Beenden: Nothalt (falls verbunden), Verbindung trennen, Watchdog und Steuerungs-Thread anhalten. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            disconnect();
        } finally {
            executor.stop();
        }
    }
}
