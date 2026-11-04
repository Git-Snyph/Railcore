package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Contacts;
import at.schmoegl.railcore.core.LocoState;
import at.schmoegl.railcore.core.StationCapabilities;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.safety.SensorState;
import at.schmoegl.railcore.safety.StateMachine;
import at.schmoegl.railcore.train.Train;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Veränderlicher Anlagenzustand. Gehört ausschließlich dem Steuerungs-Executor: jeder Zugriff wird über den
 * {@link ThreadGuard} geprüft und von fremden Threads abgewiesen. Andere Threads lesen {@link PlantSnapshot}s.
 * <p>
 * Startzustand nach Grundsatz 5: Betriebszustand OFFLINE, Gleisspannung unbekannt, alle Melder unbekannt,
 * alle Züge unbestätigt. Jede Änderung wird im Journal protokolliert (Grundsatz 8).
 */
public final class PlantState {

    private final ThreadGuard guard;
    private final Journal journal;
    private final StateMachine stateMachine;
    private final Map<Integer, SensorState> sensors = new HashMap<>();
    private final Map<String, Train> trains = new LinkedHashMap<>();
    private final Map<String, TrainStatus> trainStatus = new HashMap<>();
    private TrackPower trackPower = TrackPower.UNKNOWN;
    private CommunicationWatchdog.Status communication = CommunicationWatchdog.Status.OK;
    private boolean emergencyLatched;
    private SchedulerMetrics scheduler = SchedulerMetrics.EMPTY;
    private StationCapabilities capabilities = StationCapabilities.UNKNOWN;
    private final Map<Integer, LocoState> reportedLocos = new HashMap<>();
    private final Map<Integer, List<Integer>> railCom = new HashMap<>();
    private final Map<Integer, TurnoutPosition> reportedTurnouts = new HashMap<>();
    private final Map<Integer, PlantSnapshot.HandControllerView> handControllers = new TreeMap<>();
    private long version;

    PlantState(ThreadGuard guard, Journal journal, Collection<Integer> knownContacts) {
        this.guard = guard;
        this.journal = journal;
        this.stateMachine = new StateMachine(journal);
        for (int c : knownContacts) sensors.put(c, SensorState.UNKNOWN);
    }

    // ------------------------------------------------------------------ lesen (nur Executor-Thread)

    public OperatingState operatingState() { guard.check("Betriebszustand lesen"); return stateMachine.state(); }

    public boolean emergencyLatched() { guard.check("Nothalt lesen"); return emergencyLatched; }

    public TrackPower trackPower() { guard.check("Gleisspannung lesen"); return trackPower; }

    public CommunicationWatchdog.Status communication() { guard.check("Verbindung lesen"); return communication; }

    /** Nicht erfasste Melder sind „unbekannt“. */
    public SensorState sensor(int contact) {
        guard.check("Melder lesen");
        return sensors.getOrDefault(contact, SensorState.UNKNOWN);
    }

    public Optional<TrainStatus> trainStatus(String trainId) {
        guard.check("Zug lesen");
        return Optional.ofNullable(trainStatus.get(trainId));
    }

    /** Erkannte Fähigkeiten der Zentrale; bis zur Erkennung und nach Verbindungsverlust {@link StationCapabilities#UNKNOWN}. */
    public StationCapabilities capabilities() { guard.check("Fähigkeiten lesen"); return capabilities; }

    /** Letzter von der Zentrale gemeldeter Zustand einer Adresse (ihr Speicher, keine Messung). */
    public Optional<LocoState> reportedLoco(int address) {
        guard.check("Lokmeldung lesen");
        return Optional.ofNullable(reportedLocos.get(address));
    }

    /** Von RailCom erkannte Adressen an einem Melder; leer = nichts erkannt oder nichts bekannt. */
    public List<Integer> railCom(int contact) {
        guard.check("RailCom lesen");
        return railCom.getOrDefault(contact, List.of());
    }

    /** Von der Zentrale gemeldete Weichenlage (ihr Speicher, keine Endlage); unbekannt, wenn nichts gemeldet. */
    public TurnoutPosition reportedTurnout(int address) {
        guard.check("Weichenmeldung lesen");
        return reportedTurnouts.getOrDefault(address, TurnoutPosition.UNKNOWN);
    }

    public Optional<PlantSnapshot.HandControllerView> handController(int address) {
        guard.check("Handregler lesen");
        return Optional.ofNullable(handControllers.get(address));
    }

    long version() { return version; }

    // ------------------------------------------------------------------ ändern (nur Executor-Thread)

    /** Übergang über die festgelegten Regeln der StateMachine; unerlaubte Übergänge werden abgelehnt. */
    boolean transition(OperatingState next, String why) {
        guard.check("Betriebszustand ändern");
        OperatingState before = stateMachine.state();
        boolean ok = stateMachine.transition(next, why);
        if (ok && before != next) version++;
        return ok;
    }

    void latchEmergency(String why) {
        guard.check("Nothalt setzen");
        if (emergencyLatched) return;
        emergencyLatched = true;
        version++;
        journal.critical("nothalt", "EMERGENCY_LATCHED", "Nothalt gesetzt (" + why + ") – Aufhebung nur durch Bediener");
    }

    void clearEmergencyLatch(String operator) {
        guard.check("Nothalt aufheben");
        if (!emergencyLatched) return;
        emergencyLatched = false;
        version++;
        journal.warn("nothalt", "EMERGENCY_ACKNOWLEDGED", "Nothalt quittiert durch " + operator);
    }

    void setTrackPower(TrackPower p) {
        guard.check("Gleisspannung ändern");
        if (p == trackPower) return;
        journal.info("zentrale", "TRACK_POWER", "Gleisspannung " + trackPower + " → " + p);
        trackPower = p;
        version++;
    }

    void setCommunication(CommunicationWatchdog.Status s) {
        guard.check("Verbindung ändern");
        if (s == communication) return;
        communication = s;   // Protokollierung erfolgt im CommunicationWatchdog
        version++;
    }

    void setSensor(int contact, SensorState s) {
        guard.check("Melder ändern");
        SensorState before = sensors.getOrDefault(contact, SensorState.UNKNOWN);
        if (before == s && sensors.containsKey(contact)) return;
        sensors.put(contact, s);
        version++;
        journal.debug("melder", "SENSOR", Contacts.format(contact) + ": " + before.german() + " → " + s.german());
    }

    /** Alle bekannten Melder auf „unbekannt“ (z. B. nach Verbindungsverlust). */
    void allSensorsUnknown(String why) {
        guard.check("Melder zurücksetzen");
        int changed = 0;
        for (var e : sensors.entrySet()) {
            if (e.getValue() != SensorState.UNKNOWN) { e.setValue(SensorState.UNKNOWN); changed++; }
        }
        if (changed > 0) version++;
        journal.warn("melder", "SENSORS_UNKNOWN", "Alle Melder auf „unbekannt“ (" + changed + " geändert): " + why);
    }

    void registerTrain(Train train) {
        guard.check("Zug anmelden");
        trains.put(train.id(), train);
        trainStatus.put(train.id(), TrainStatus.UNCONFIRMED);
        version++;
        journal.info("zug:" + train.displayName(), "TRAIN_REGISTERED", "Zug angemeldet (Adresse " + train.address() + "), unbestätigt");
    }

    /** Alle Züge auf „unbestätigt“ (z. B. nach Verbindungsverlust; Position nicht mehr gesichert). */
    void allTrainsUnconfirmed(String why) {
        guard.check("Züge zurücksetzen");
        int changed = 0;
        for (var e : trainStatus.entrySet()) {
            if (e.getValue() != TrainStatus.UNCONFIRMED) { e.setValue(TrainStatus.UNCONFIRMED); changed++; }
        }
        if (changed > 0) {
            version++;
            journal.warn("zug", "TRAINS_UNCONFIRMED", changed + " Züge auf „unbestätigt“: " + why);
        }
    }

    void setCapabilities(StationCapabilities c) {
        guard.check("Fähigkeiten setzen");
        java.util.Objects.requireNonNull(c);
        if (c.equals(capabilities)) return;
        capabilities = c;
        version++;
        if (c.detected()) journal.info("zentrale", "CAPABILITIES", "Fähigkeiten der Zentrale erkannt: " + c.describe());
        else journal.warn("zentrale", "CAPABILITIES_UNKNOWN", "Fähigkeiten der Zentrale unbekannt – keine wird genutzt");
    }

    void setReportedLoco(LocoState s) {
        guard.check("Lokmeldung setzen");
        LocoState before = reportedLocos.put(s.address(), s);
        if (s.equals(before)) return;
        version++;
        journal.debug("zentrale", "LOCO_REPORTED", "Lok " + s.address() + " laut Zentrale: Stufe " + s.speedStep()
                + " " + s.direction());
    }

    void setRailCom(int contact, List<Integer> addresses) {
        guard.check("RailCom setzen");
        List<Integer> a = List.copyOf(addresses);
        List<Integer> before = railCom.put(contact, a);
        if (a.equals(before)) return;
        version++;
        journal.debug("melder", "RAILCOM", Contacts.format(contact) + ": RailCom " + (a.isEmpty() ? "keine Lok" : a));
    }

    void setReportedTurnout(int address, TurnoutPosition p) {
        guard.check("Weichenmeldung setzen");
        TurnoutPosition before = reportedTurnouts.put(address, p);
        if (p == before) return;
        version++;
        journal.debug("zentrale", "TURNOUT_REPORTED", "Weiche " + address + " laut Zentrale: " + p);
    }

    /** Handregler-Adressen eintragen; sie starten „unbekannt“ (Grundsatz 5). */
    void defineHandControllers(java.util.Collection<Integer> addresses) {
        guard.check("Handregler eintragen");
        for (int a : addresses)
            if (!handControllers.containsKey(a)) handControllers.put(a, new PlantSnapshot.HandControllerView(a, null, false));
        version++;
    }

    void setHandController(int address, LocoState state, boolean current) {
        guard.check("Handregler setzen");
        PlantSnapshot.HandControllerView before = handControllers.get(address);
        if (before == null) throw new IllegalArgumentException("Keine Handregler-Adresse: " + address);
        PlantSnapshot.HandControllerView now = new PlantSnapshot.HandControllerView(address, state, current);
        if (now.equals(before)) return;
        handControllers.put(address, now);
        version++;
        if (state != null && (before.state() == null || before.state().speedStep() != state.speedStep()
                || before.state().direction() != state.direction()))
            journal.info("hand", "HAND_CONTROLLER", "Handregler " + address + ": Stufe " + state.speedStep() + " " + state.direction());
        if (before.current() && !current)
            journal.warn("hand", "HAND_CONTROLLER_STALE", "Handregler " + address + ": keine aktuelle Antwort der Zentrale – Wert veraltet");
    }

    /**
     * Verbindungsverlust: Was die Zentrale gemeldet hat, gilt nicht mehr (Lokmeldungen, RailCom, Weichenlagen,
     * Fähigkeiten); Handregler werden „unbekannt“.
     */
    void clearStationViews(String why) {
        guard.check("Sicht auf die Zentrale zurücksetzen");
        reportedLocos.clear();
        railCom.clear();
        reportedTurnouts.clear();
        capabilities = StationCapabilities.UNKNOWN;
        handControllers.replaceAll((a, v) -> new PlantSnapshot.HandControllerView(a, null, false));
        version++;
        journal.warn("zentrale", "STATION_VIEWS_CLEARED", "Meldungen der Zentrale verworfen (Lokzustände, RailCom, Weichenlagen, "
                + "Fähigkeiten, Handregler): " + why);
    }

    /** Status des Befehlsplaners für die Momentaufnahme (nur bei Überlastwechsel und periodischem Bericht). */
    void setScheduler(SchedulerMetrics status) {
        guard.check("Befehlsplaner-Status setzen");
        scheduler = java.util.Objects.requireNonNull(status);
        version++;
    }

    PlantSnapshot snapshot(long timeNanos, boolean executorStalled) {
        guard.check("Momentaufnahme erstellen");
        return build(timeNanos, executorStalled);
    }

    /** Erste Momentaufnahme, bevor ein Steuerungs-Thread gebunden ist (nur beim Anlegen des Executors). */
    PlantSnapshot initialSnapshot(long timeNanos) {
        if (guard.isBound()) throw new IllegalStateException("Erste Momentaufnahme nur vor dem Start");
        return build(timeNanos, false);
    }

    private PlantSnapshot build(long timeNanos, boolean executorStalled) {
        Map<String, PlantSnapshot.TrainView> views = new HashMap<>();
        for (Train t : trains.values())
            views.put(t.id(), new PlantSnapshot.TrainView(t.id(), t.displayName(), t.address(), trainStatus.get(t.id())));
        return new PlantSnapshot(version, timeNanos, stateMachine.state(), stateMachine.reason(), emergencyLatched,
                executorStalled, trackPower, communication, sensors, views, scheduler, capabilities, reportedLocos,
                railCom, reportedTurnouts, handControllers);
    }
}
