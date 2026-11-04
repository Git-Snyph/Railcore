package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.safety.SensorState;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Unveränderliche Momentaufnahme des Anlagenzustands. Wird vom Steuerungs-Executor nach jedem Schritt veröffentlicht;
 * Oberfläche und andere Threads lesen nur diese Aufnahmen und ändern nie direkt Zustände.
 *
 * @param version          fortlaufende Zustandsversion (steigt mit jeder Änderung)
 * @param timeNanos        Zeitpunkt der Aufnahme (TimeSource)
 * @param emergencyLatched Nothalt ausgelöst und noch nicht vom Bediener quittiert
 * @param executorStalled  Executor-Watchdog hat einen Hänger erkannt (Wert zum Zeitpunkt der Aufnahme)
 */
public record PlantSnapshot(long version, long timeNanos, OperatingState operatingState, String stateReason,
                            boolean emergencyLatched, boolean executorStalled, TrackPower trackPower,
                            CommunicationWatchdog.Status communication,
                            Map<Integer, SensorState> sensors, Map<String, TrainView> trains) {

    /** Unveränderliche Sicht auf einen Zug. */
    public record TrainView(String id, String name, int address, TrainStatus status) {}

    public PlantSnapshot {
        sensors = Collections.unmodifiableMap(new TreeMap<>(sensors));
        trains = Collections.unmodifiableMap(new TreeMap<>(trains));
    }

    /** Zustand eines Melders; nicht erfasste Melder sind „unbekannt“, nie frei. */
    public SensorState sensor(int contact) { return sensors.getOrDefault(contact, SensorState.UNKNOWN); }

    /** Dürfen Züge von Hand bewegt werden? (zusätzlich ist stets eine Reservierung nötig, Grundsatz 2) */
    public boolean manualMayMove() {
        return operatingState.manualMayMove() && !emergencyLatched && !executorStalled
                && communication == CommunicationWatchdog.Status.OK;
    }
}
