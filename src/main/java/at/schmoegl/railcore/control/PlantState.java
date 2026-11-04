package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Contacts;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.safety.SensorState;
import at.schmoegl.railcore.safety.StateMachine;
import at.schmoegl.railcore.train.Train;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
                executorStalled, trackPower, communication, sensors, views, scheduler);
    }
}
