package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.DistrictPower;
import at.schmoegl.railcore.core.LocoState;
import at.schmoegl.railcore.core.StationCapabilities;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.DetectorCondition;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.safety.SensorState;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Unveränderliche Momentaufnahme des Anlagenzustands. Wird vom Steuerungs-Executor nach jedem Schritt veröffentlicht;
 * Oberfläche und andere Threads lesen nur diese Aufnahmen und ändern nie direkt Zustände.
 *
 * @param version          fortlaufende Zustandsversion (steigt mit jeder Änderung)
 * @param timeNanos        Zeitpunkt der Aufnahme (TimeSource)
 * @param emergencyLatched Nothalt ausgelöst und noch nicht vom Bediener quittiert
 * @param executorStalled  Executor-Watchdog hat einen Hänger erkannt (Wert zum Zeitpunkt der Aufnahme)
 * @param scheduler        Status des Befehlsplaners (Überlast mit Befund, Budget, Wartezeiten des Messfensters);
 *                         aktualisiert bei Überlastwechsel und periodischem Bericht, nicht je Befehl
 * @param capabilities     erkannte Fähigkeiten der Zentrale ({@link StationCapabilities#UNKNOWN} bis zur Erkennung)
 * @param reportedLocos    letzter von der Zentrale gemeldeter Lokzustand je Adresse (Speicher der Zentrale, keine
 *                         Messung am Fahrzeug); nach Verbindungsverlust leer
 * @param railCom          von RailCom erkannte Lokadressen je Melder; fehlender Eintrag = nichts bekannt
 * @param reportedTurnouts von der Zentrale gemeldete Weichenlage (ihr Speicher, keine Endlage)
 * @param handControllers  Handregler auf Stellvertreter-Adressen 9000–9999 (abgefragt)
 * @param sensorDiagnostics Diagnose je Melder (Überwachungszustand, letzter gültiger Zustand mit Zeitstempel,
 *                         Flacker-Zähler); nur Melder, zu denen es etwas zu berichten gibt
 * @param districts        zuletzt gemeldeter Zustand je Booster-Bezirk; fehlender Eintrag = nichts gemeldet
 */
public record PlantSnapshot(long version, long timeNanos, OperatingState operatingState, String stateReason,
                            boolean emergencyLatched, boolean executorStalled, TrackPower trackPower,
                            CommunicationWatchdog.Status communication,
                            Map<Integer, SensorState> sensors, Map<String, TrainView> trains,
                            SchedulerMetrics scheduler, StationCapabilities capabilities,
                            Map<Integer, LocoState> reportedLocos, Map<Integer, List<Integer>> railCom,
                            Map<Integer, TurnoutPosition> reportedTurnouts,
                            Map<Integer, HandControllerView> handControllers,
                            Map<Integer, SensorDiagnostics> sensorDiagnostics,
                            Map<Integer, DistrictPower> districts) {

    /**
     * Unveränderliche Sicht auf einen Zug.
     *
     * @param cautions Kennzeichnungen nach Störungen (leer = keine); nur der Bediener hebt sie auf
     */
    public record TrainView(String id, String name, int address, TrainStatus status, Set<TrainCaution> cautions) {
        public TrainView {
            cautions = cautions.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(cautions));
        }
    }

    /**
     * Handregler auf einer Stellvertreter-Adresse.
     *
     * @param state    zuletzt gelesener Zustand; {@code null} = unbekannt (Start, Verbindungsverlust)
     * @param current  die letzte Abfrage ist rechtzeitig beantwortet worden; false = Wert veraltet oder unbekannt
     */
    public record HandControllerView(int address, LocoState state, boolean current) {}

    /**
     * Diagnose eines Melders (Lastenheft 7: Melderstatistik, Ausfall der Rückmelder-Versorgung).
     *
     * @param condition       Zustand der Melderüberwachung; außer {@link DetectorCondition#NORMAL} ist der Melder „unbekannt“
     * @param lastValid       letzter gültiger (entprellter) Zustand; {@link SensorState#UNKNOWN} = noch keiner
     * @param lastValidNanos  Zeitpunkt der Meldung, die zum letzten gültigen Zustand führte (TimeSource)
     * @param flickerCount    gezählte Flacker-Wechsel seit dem Programmstart
     * @param flickering      Flacker-Schwellwert erreicht (Warnung im Journal)
     */
    public record SensorDiagnostics(int contact, DetectorCondition condition, SensorState lastValid, long lastValidNanos,
                                    int flickerCount, boolean flickering) {
        public boolean hasLastValid() { return lastValid != SensorState.UNKNOWN; }
    }

    public PlantSnapshot {
        sensors = Collections.unmodifiableMap(new TreeMap<>(sensors));
        trains = Collections.unmodifiableMap(new TreeMap<>(trains));
        java.util.Objects.requireNonNull(scheduler);
        java.util.Objects.requireNonNull(capabilities);
        reportedLocos = Collections.unmodifiableMap(new TreeMap<>(reportedLocos));
        TreeMap<Integer, List<Integer>> rc = new TreeMap<>();
        railCom.forEach((k, v) -> rc.put(k, List.copyOf(v)));
        railCom = Collections.unmodifiableMap(rc);
        reportedTurnouts = Collections.unmodifiableMap(new TreeMap<>(reportedTurnouts));
        handControllers = Collections.unmodifiableMap(new TreeMap<>(handControllers));
        sensorDiagnostics = Collections.unmodifiableMap(new TreeMap<>(sensorDiagnostics));
        districts = Collections.unmodifiableMap(new TreeMap<>(districts));
    }

    /** Zustand eines Melders; nicht erfasste Melder sind „unbekannt“, nie frei. */
    public SensorState sensor(int contact) { return sensors.getOrDefault(contact, SensorState.UNKNOWN); }

    /** Überwachungszustand eines Melders; ohne Eintrag {@link DetectorCondition#NORMAL}. */
    public DetectorCondition detectorCondition(int contact) {
        SensorDiagnostics d = sensorDiagnostics.get(contact);
        return d == null ? DetectorCondition.NORMAL : d.condition();
    }

    /** Dürfen Züge von Hand bewegt werden? (zusätzlich ist stets eine Reservierung nötig, Grundsatz 2) */
    public boolean manualMayMove() {
        return operatingState.manualMayMove() && !emergencyLatched && !executorStalled
                && communication == CommunicationWatchdog.Status.OK;
    }
}
