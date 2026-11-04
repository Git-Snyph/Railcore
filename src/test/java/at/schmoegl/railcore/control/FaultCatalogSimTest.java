package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.DistrictPower;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.StationCapabilities;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.DetectorCondition;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.safety.SensorState;
import at.schmoegl.railcore.sim.Distribution;
import at.schmoegl.railcore.sim.SimCommandStation;
import at.schmoegl.railcore.sim.SimFaultScript;
import at.schmoegl.railcore.sim.SimWorld;
import at.schmoegl.railcore.train.Train;
import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/**
 * Fehlerkatalog-Abnahme ohne Gleisgraph (Lastenheft 10 „jede Störung führt zur festgelegten Reaktion“, Schritt A aus
 * Entscheidung 35): je Störung Auslösung im Simulator ({@link SimFaultScript} bzw. {@link SimCommandStation}),
 * Erkennung und Reaktion der Steuerung, deterministisch mit {@code TimeSource.Manual}.
 */
public class FaultCatalogSimTest {

    private static final List<Integer> CONTACTS = contacts(1, 16);   // Module 1 und 2

    private final TimeSource.Manual clock = new TimeSource.Manual();
    private final Journal journal = Journal.inMemory();
    private final SimWorld world = new SimWorld(clock, SimWorld.randomForRun(11));
    private final SimCommandStation station = new SimCommandStation(world, Distribution.uniform(20, 50), 200);
    private final Set<Integer> allowed = new HashSet<>(Set.of(3, 4));
    private ControlRuntime rt;
    private ControlExecutor ex;
    private SimWorld.Controller controller;

    private static List<Integer> contacts(int from, int to) {
        List<Integer> l = new ArrayList<>();
        for (int i = from; i <= to; i++) l.add(i);
        return l;
    }

    private static void assertTrue(boolean b, String what) { if (!b) throw new AssertionError(what); }

    private static Train train(String id, int address) {
        return new Train(id, "Zug " + id, address, SpeedSteps.S128, 1000, 120, 1, null);
    }

    private void create(ControlConfig control, SupervisionConfig supervision) {
        rt = ControlRuntime.create(control, SchedulerConfig.defaults(), supervision, HandControllerConfig.defaults(), clock,
                journal, station, CONTACTS, allowed::contains);
        ex = rt.executor();
        controller = new SimWorld.Controller() {
            @Override public long nextDueNanos() { return ex.nextDueNanos(); }
            @Override public void pump() { ex.pump(); }
            @Override public void watchdogCheck() { ex.watchdog().check(); }
            @Override public void injectInternalError(String what) {
                ex.enqueue("Simulierter Fehler", () -> { throw new IllegalStateException(what); });
            }
        };
    }

    private void runMs(long ms) { world.run(controller, () -> false, clock.nanoTime() + ms * 1_000_000L); }

    private void script(String text) { SimFaultScript.parse(text).schedule(world, station); }

    /** Zentrale mit Melderabfrage, Züge A (3) und B (4), Melder frei, Startnothalt quittiert, Zustand „Bereit“. */
    private void bringReady(boolean detectorQuery) {
        if (rt == null) create(ControlConfig.defaults(), SupervisionConfig.defaults().withDetectorQuery(1_000, 3_000));
        EnumSet<StationCapabilities.Feature> f = EnumSet.of(StationCapabilities.Feature.CAN_DETECTORS);
        if (detectorQuery) f.add(StationCapabilities.Feature.DETECTOR_STATUS_QUERY);
        station.setCapabilities(new StationCapabilities("Simulator", f));
        station.defineContacts(CONTACTS, false);
        station.assignDistrict(1, List.of(3), contacts(1, 8));
        station.assignDistrict(2, List.of(4), contacts(9, 16));
        rt.submit(new ControlCommand.RegisterTrain(train("A", 3), "Test"));
        rt.submit(new ControlCommand.RegisterTrain(train("B", 4), "Test"));
        try { rt.connect(); } catch (java.io.IOException e) { throw new AssertionError(e); }
        runMs(50);
        station.reportTrackPower(TrackPower.ON);
        if (!detectorQuery) for (int c : CONTACTS) station.feedback(c, false);
        runMs(1_000);
        rt.submit(new ControlCommand.AcknowledgeEmergency("Bediener"));
        rt.submit(new ControlCommand.RequestReady("Bediener"));
        runMs(50);
        assertEquals(OperatingState.READY, rt.snapshot().operatingState());
        for (int c : CONTACTS) assertEquals(SensorState.FREE, rt.snapshot().sensor(c));
    }

    private int lastStep(int address) {
        var d = station.drives().stream().filter(x -> x.address() == address).toList();
        return d.isEmpty() ? -1 : d.get(d.size() - 1).step();
    }

    private PlantSnapshot.TrainView trainView(String id) { return rt.snapshot().trains().get(id); }

    // ================================================================== Verbindung verspätet

    public void testDelayedConnectionWarnsAndBlocksNewMovementWithoutEmergency() {
        bringReady(true);
        int stops = station.emergencyStops();
        script("0 verspaetung 3500");
        runMs(3_200);
        assertEquals(CommunicationWatchdog.Status.LATE, rt.snapshot().communication());
        assertEquals(1, journal.byCode("COMM_LATE").size());
        assertEquals(false, ex.commands().drive(3, 20, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        assertEquals(stops, station.emergencyStops());                           // nur Warnung, kein Nothalt
        assertEquals(OperatingState.READY, rt.snapshot().operatingState());
        runMs(500);                                                              // Meldungen kommen nach
        assertEquals(CommunicationWatchdog.Status.OK, rt.snapshot().communication());
        assertEquals(true, ex.commands().drive(3, 20, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        runMs(100);
        assertEquals(20, lastStep(3));
    }

    public void testConnectionTimesAreConfigurable() {
        create(new ControlConfig(50, 200, 20, 1_000, 2_000, 500, 5, 10_000, 1_000, 60),
                SupervisionConfig.defaults().withDetectorQuery(1_000, 3_000));
        bringReady(true);
        script("0 verspaetung 1500");
        runMs(1_200);
        assertEquals(CommunicationWatchdog.Status.LATE, rt.snapshot().communication());
        runMs(1_000);
        assertEquals(CommunicationWatchdog.Status.OK, rt.snapshot().communication());
        script("0 verbindung-weg 3000");
        runMs(2_200);
        assertEquals(CommunicationWatchdog.Status.LOST, rt.snapshot().communication());
        assertEquals(OperatingState.FAULT, rt.snapshot().operatingState());
    }

    // ================================================================== Verbindung verloren

    public void testLostConnectionFaultAndEmergencyStopAfterReconnect() {
        bringReady(true);
        assertEquals(true, ex.commands().drive(3, 40, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        runMs(100);
        int delivered = station.emergencyStopsDelivered();
        int drivesBefore = station.drives().size();
        script("0 verbindung-weg 6000");
        runMs(5_200);
        PlantSnapshot s = rt.snapshot();
        assertEquals(CommunicationWatchdog.Status.LOST, s.communication());
        assertEquals(OperatingState.FAULT, s.operatingState());
        assertEquals(true, s.emergencyLatched());
        assertEquals(SensorState.UNKNOWN, s.sensor(1));
        assertEquals(TrainStatus.UNCONFIRMED, s.trains().get("A").status());
        assertEquals(delivered, station.emergencyStopsDelivered());              // Nothalt erreicht die Zentrale nicht

        runMs(1_200);                                                            // Verbindung wieder da
        s = rt.snapshot();
        assertEquals(CommunicationWatchdog.Status.OK, s.communication());
        assertEquals(delivered + 1, station.emergencyStopsDelivered());          // sofort Nothalt nach Wiederverbindung
        assertEquals(OperatingState.FAULT, s.operatingState());                  // Freigabe nur durch Bediener
        assertTrue(!s.stateReason().startsWith("Programmstart"), "kein Startnothalt nach Wiederverbindung: " + s.stateReason());
        var after = station.drives().subList(drivesBefore, station.drives().size());
        assertTrue(after.stream().anyMatch(d -> d.address() == 3 && d.step() == 0), "Halt an bekannte Lok neu übertragen");
        assertTrue(after.stream().allMatch(d -> d.step() == 0), "nach Wiederverbindung nie eine Fahrstufe");

        rt.submit(new ControlCommand.AcknowledgeEmergency("Bediener"));
        runMs(1_500);
        rt.submit(new ControlCommand.RequestReady("Bediener"));
        runMs(50);
        assertEquals(OperatingState.READY, rt.snapshot().operatingState());
        // Entscheidung 37 (4): nach der Wiederverbindung ohne Neuverbinden werden Fähigkeiten und Melder neu eingelesen
        assertEquals(1, journal.byCode("STATE_REFRESH").size());
        assertTrue(rt.snapshot().capabilities().has(StationCapabilities.Feature.DETECTOR_STATUS_QUERY), "Fähigkeiten neu");
        assertEquals(SensorState.FREE, rt.snapshot().sensor(1));
    }

    public void testStatesAreNotTakenOverBeforeRefreshAfterReconnect() {
        bringReady(true);
        script("0 verbindung-weg 6000");
        runMs(6_000);
        for (int i = 0; i < 500 && rt.snapshot().communication() != CommunicationWatchdog.Status.OK; i++) runMs(1);
        assertEquals(CommunicationWatchdog.Status.OK, rt.snapshot().communication());    // gerade wieder da;
        // die Antwort auf das Neueinlesen ist noch unterwegs
        assertEquals(SensorState.UNKNOWN, rt.snapshot().sensor(1));              // nie aus dem alten Stand übernommen
        runMs(1_000);
        assertEquals(SensorState.FREE, rt.snapshot().sensor(1));                  // erst nach Neueinlesen + Freimeldeverzögerung
    }

    // ================================================================== Paketverlust

    public void testModeratePacketLossKeepsLinkWithoutFalseAlarm() {
        bringReady(true);
        script("0 paketverlust 0.3 5");
        runMs(30_000);
        assertTrue(station.lostFromStation() > 0, "Pakete gingen verloren");
        assertEquals(0, journal.byCode("COMM_LATE").size());
        assertEquals(OperatingState.READY, rt.snapshot().operatingState());
    }

    public void testTotalPacketLossIsConnectionLoss() {
        bringReady(true);
        script("0 paketverlust 1");
        runMs(5_200);
        assertEquals(CommunicationWatchdog.Status.LOST, rt.snapshot().communication());
        assertEquals(OperatingState.FAULT, rt.snapshot().operatingState());
    }

    // ================================================================== Kurzschluss / Überlast je Bezirk

    public void testDistrictShortMarksTrainsUnsafeAndAllowsOnlyBraking() {
        bringReady(true);
        assertEquals(true, ex.commands().drive(3, 40, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        runMs(100);
        int stops = station.emergencyStops();
        long beforeShort = clock.nanoTime();
        script("0 bezirk 1 kurzschluss");
        runMs(50);
        PlantSnapshot s = rt.snapshot();
        assertEquals(DistrictPower.SHORT_CIRCUIT, s.districts().get(1));
        assertEquals(Set.of(TrainCaution.POWER_FAULT), s.trains().get("A").cautions());   // Zuordnung unbekannt: alle Züge
        assertEquals(Set.of(TrainCaution.POWER_FAULT), s.trains().get("B").cautions());
        assertEquals(TrainStatus.UNCONFIRMED, s.trains().get("A").status());
        assertEquals(SensorState.UNKNOWN, s.sensor(1));                                  // Melder ohne Versorgung
        assertEquals(DetectorCondition.SUPPLY_LOST, s.detectorCondition(1));
        assertEquals(SensorState.FREE, s.sensorDiagnostics().get(1).lastValid());        // letzter gültiger Zustand
        assertTrue(s.sensorDiagnostics().get(1).lastValidNanos() < beforeShort, "Zeitstempel des letzten gültigen Zustands");
        assertEquals(stops, station.emergencyStops());                                   // kein Nothalt: geordnet halten
        assertEquals(OperatingState.READY, s.operatingState());

        assertEquals(false, ex.commands().drive(3, 50, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        assertEquals(false, ex.commands().drive(3, 40, SpeedSteps.S128, Direction.REVERSE, CommandPriority.MANUAL));
        assertEquals(false, ex.commands().drive(4, 10, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        assertEquals(true, ex.commands().drive(3, 20, SpeedSteps.S128, Direction.FORWARD, CommandPriority.AUTOMATIC));
        runMs(100);
        assertEquals(20, lastStep(3));                                                    // Bremsen erlaubt

        rt.submit(new ControlCommand.ReleaseTrainCaution("A", "Bediener"));
        runMs(10);
        assertEquals(Set.of(TrainCaution.POWER_FAULT), trainView("A").cautions());      // Bezirk noch spannungslos

        script("0 bezirk 1 ein");
        runMs(2);
        assertEquals(SensorState.UNKNOWN, rt.snapshot().sensor(1));                       // Rückkehr ist keine Freimeldung
        assertEquals(DetectorCondition.RECHECK, rt.snapshot().detectorCondition(1));
        runMs(1_500);
        assertEquals(DetectorCondition.NORMAL, rt.snapshot().detectorCondition(1));
        assertEquals(SensorState.FREE, rt.snapshot().sensor(1));
        assertEquals(Set.of(TrainCaution.POWER_FAULT), trainView("A").cautions());      // nie automatisch aufgehoben

        rt.submit(new ControlCommand.ReleaseTrainCaution("A", "Bediener"));
        runMs(10);
        assertEquals(Set.of(), trainView("A").cautions());
        assertEquals(TrainStatus.UNCONFIRMED, trainView("A").status());
        assertEquals(true, ex.commands().drive(3, 50, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
    }

    public void testDistrictOverloadStopsAutomationInOrder() {
        bringReady(true);
        ex.plant().transition(OperatingState.RUNNING, "Test: Automatik läuft");
        script("0 bezirk 2 ueberlast");
        runMs(50);
        assertEquals(OperatingState.STOPPING, rt.snapshot().operatingState());           // Automatik hält geordnet
        assertEquals(false, rt.snapshot().emergencyLatched());
        assertEquals(DistrictPower.OVERLOAD, rt.snapshot().districts().get(2));
        assertEquals(Set.of(TrainCaution.POWER_FAULT), trainView("B").cautions());
    }

    public void testShortCircuitAtCommandStationIsFaultAndMarksTrains() {
        bringReady(true);
        script("0 gleisspannung kurzschluss");
        runMs(50);
        PlantSnapshot s = rt.snapshot();
        assertEquals(OperatingState.FAULT, s.operatingState());
        assertEquals(Set.of(TrainCaution.POWER_FAULT), s.trains().get("A").cautions());
        assertEquals(DetectorCondition.SUPPLY_LOST, s.detectorCondition(16));
    }

    // ================================================================== Flackernder Melder

    public void testFlickeringSensorStaysOccupiedCountsAndWarns() {
        bringReady(true);
        station.feedback(5, true);
        runMs(50);
        script("0 flackern 1:5 12 40");
        for (int i = 0; i < 12; i++) {
            runMs(40);
            assertEquals(SensorState.OCCUPIED, rt.snapshot().sensor(5));              // Entprellung hält belegt
        }
        runMs(1_000);
        var d = rt.snapshot().sensorDiagnostics().get(5);
        assertEquals(6, d.flickerCount());
        assertEquals(true, d.flickering());
        assertEquals(1, journal.byCode("SENSOR_FLICKER").size());                      // ab Schwellwert 5 einmal
        assertEquals(SensorState.OCCUPIED, rt.snapshot().sensor(5));
    }

    // ================================================================== Ausfall der 10808-Versorgung

    public void testSingleDetectorSupplyLossAndRecheckOnReturn() {
        bringReady(true);
        long t0 = clock.nanoTime();
        script("0 versorgung-aus 1:2");
        runMs(3_500);
        PlantSnapshot s = rt.snapshot();
        assertEquals(SensorState.UNKNOWN, s.sensor(2));
        assertEquals(DetectorCondition.SILENT, s.detectorCondition(2));
        assertEquals(SensorState.FREE, s.sensorDiagnostics().get(2).lastValid());
        assertTrue(s.sensorDiagnostics().get(2).lastValidNanos() < t0, "letzter gültiger Zustand mit Zeitstempel");
        assertEquals(SensorState.FREE, s.sensor(1));                                     // Nachbar unberührt
        assertTrue(journal.byCode("DETECTOR_SILENT").get(0).message().contains("1:2"), "Journal nennt den Melder");

        script("0 versorgung-ein 1:2");                                                  // meldet sofort „frei“
        runMs(2);
        assertEquals(SensorState.UNKNOWN, rt.snapshot().sensor(2));                       // Rückkehr ist keine Freimeldung
        assertEquals(DetectorCondition.RECHECK, rt.snapshot().detectorCondition(2));
        runMs(1_500);
        assertEquals(DetectorCondition.NORMAL, rt.snapshot().detectorCondition(2));
        assertEquals(SensorState.FREE, rt.snapshot().sensor(2));
        assertEquals(1, journal.byCode("DETECTOR_RECHECKED").size());
    }

    public void testWholeCanModuleFailureIsReportedAsOne() {
        bringReady(true);
        script("0 versorgung-aus modul:2");
        runMs(3_500);
        for (int c = 9; c <= 16; c++) assertEquals(SensorState.UNKNOWN, rt.snapshot().sensor(c));
        for (int c = 1; c <= 8; c++) assertEquals(SensorState.FREE, rt.snapshot().sensor(c));
        var silent = journal.byCode("DETECTOR_SILENT");
        assertEquals(1, silent.size());
        assertTrue(silent.get(0).message().contains("CAN-Bereich Modul 2 (alle 8 Melder)"), silent.get(0).message());
    }

    public void testRecheckRequiresAgreeingAnswers() {
        bringReady(true);
        script("0 versorgung-aus 1:3");
        runMs(3_500);
        station.feedback(3, true);                                                        // Zug fährt ein, Melder stumm
        script("0 versorgung-ein 1:3");
        runMs(1_500);
        assertEquals(SensorState.OCCUPIED, rt.snapshot().sensor(3));
        assertEquals(DetectorCondition.NORMAL, rt.snapshot().detectorCondition(3));
        assertEquals(0, journal.byCode("DETECTOR_RECHECK_MISMATCH").size());

        // Widerspruch zwischen den Antworten: erneut abfragen, erst übereinstimmend wieder in Betrieb
        script("0 versorgung-aus 1:3");
        runMs(3_500);
        script("0 versorgung-ein 1:3");                                                  // meldet „belegt“
        world.after(7_000_000L, () -> station.feedback(3, false));                       // Zug fährt zwischen den Abfragen aus
        runMs(100);
        assertEquals(1, journal.byCode("DETECTOR_RECHECK_MISMATCH").size());
        assertEquals(SensorState.UNKNOWN, rt.snapshot().sensor(3));                       // frei erst nach Verzögerung
        runMs(1_000);
        assertEquals(SensorState.FREE, rt.snapshot().sensor(3));
    }

    public void testTrackPowerOffWithoutDetectorQueryKeepsSensorsUnknown() {
        bringReady(false);
        script("0 gleisspannung aus\n500 gleisspannung ein");
        runMs(2_000);
        assertEquals(SensorState.UNKNOWN, rt.snapshot().sensor(1));                       // ohne erneute Abfrage nie frei
        assertEquals(DetectorCondition.RECHECK, rt.snapshot().detectorCondition(1));
        assertEquals(1, journal.byCode("DETECTOR_QUERY_UNAVAILABLE").size());
    }

    // ================================================================== Lok fährt anders als gesendet

    public void testForeignControlResendsAllowedValueAndMarksTrain() {
        bringReady(true);
        station.setLocoBroadcast(true);
        assertEquals(true, ex.commands().drive(3, 40, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        runMs(1_000);
        long sentTo3 = station.drives().stream().filter(d -> d.address() == 3).count();
        script("0 fremdsteuerung 3 90 vor");
        runMs(100);
        assertEquals(1, journal.byCode("LOCO_DIVERGED").size());
        assertEquals(sentTo3 + 1, station.drives().stream().filter(d -> d.address() == 3).count());
        assertEquals(40, lastStep(3));                                                     // erlaubter Wert erneut gesendet
        assertEquals(Set.of(TrainCaution.FOREIGN_CONTROL), trainView("A").cautions());
        assertEquals(TrainStatus.UNCONFIRMED, trainView("A").status());
        assertEquals(Set.of(), trainView("B").cautions());
    }

    public void testForeignControlFoundByQueryAndHaltWhenMovementNoLongerAllowed() {
        bringReady(true);
        station.setAnswerQueries(true);
        assertEquals(true, ex.commands().drive(3, 40, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        runMs(1_000);
        allowed.remove(3);                                                                 // Reservierung inzwischen weg
        script("0 fremdsteuerung 3 60 rueck");
        runMs(100);
        assertEquals(0, journal.byCode("LOCO_DIVERGED").size());                          // Z21: kein Broadcast
        ex.commands().requestLocoState(3, CommandPriority.MANUAL);
        runMs(100);
        assertEquals(1, journal.byCode("LOCO_DIVERGED").size());
        assertEquals(0, lastStep(3));                                                      // Fahren gesperrt: Halt
    }

    public void testOwnLateEchoIsNoDivergence() {
        bringReady(true);
        station.setAnswerQueries(true);
        assertEquals(true, ex.commands().drive(3, 40, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        runMs(1_000);
        ex.commands().requestLocoState(3, CommandPriority.MANUAL);
        runMs(100);
        assertEquals(0, journal.byCode("LOCO_DIVERGED").size());                          // Meldung = gesendet
        assertEquals(Set.of(), trainView("A").cautions());
    }

    // ================================================================== Interner Fehler / Takt überschritten

    public void testStallTriggersEmergencyFaultAndDiagnosticReport() {
        bringReady(true);
        int delivered = station.emergencyStopsDelivered();
        script("100 haenger 400");
        runMs(1_000);
        assertEquals(delivered + 1, station.emergencyStopsDelivered());
        assertEquals(OperatingState.FAULT, rt.snapshot().operatingState());
        var reports = journal.byCode("DIAGNOSTIC_REPORT");
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).message().contains("Takt überschritten"), reports.get(0).message());
        assertTrue(reports.get(0).message().contains("Warteschlange"), reports.get(0).message());
    }

    public void testInternalErrorTriggersEmergencyFaultAndDiagnosticReport() {
        bringReady(true);
        int delivered = station.emergencyStopsDelivered();
        script("100 interner-fehler Testfehler im Kern");
        runMs(500);
        assertEquals(delivered + 1, station.emergencyStopsDelivered());
        assertEquals(OperatingState.FAULT, rt.snapshot().operatingState());
        assertEquals(true, rt.snapshot().emergencyLatched());
        var reports = journal.byCode("DIAGNOSTIC_REPORT");
        assertEquals(1, reports.size());
        String m = reports.get(0).message();
        assertTrue(m.contains("Testfehler im Kern") && m.contains("bei "), m);
    }
}
