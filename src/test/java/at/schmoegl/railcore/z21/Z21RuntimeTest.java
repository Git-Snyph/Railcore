package at.schmoegl.railcore.z21;

import at.schmoegl.railcore.control.CommandPriority;
import at.schmoegl.railcore.control.ControlCommand;
import at.schmoegl.railcore.control.ControlConfig;
import at.schmoegl.railcore.control.ControlExecutor;
import at.schmoegl.railcore.control.ControlRuntime;
import at.schmoegl.railcore.control.HandControllerConfig;
import at.schmoegl.railcore.control.PlantSnapshot;
import at.schmoegl.railcore.control.SchedulerConfig;
import at.schmoegl.railcore.core.Contacts;
import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.StationCapabilities.Feature;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.safety.SensorState;
import at.schmoegl.railcore.train.Train;
import at.schmoegl.railcore.util.TimeSource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/**
 * Steuerungs-Executor und Befehlsplaner am echten Z21-Treiber, verbunden mit einer simulierten Z21 (kein Netzwerk,
 * keine Hardware). Deterministisch mit {@link TimeSource.Manual}: Treiber ohne Empfangs-Thread, der Testthread ist
 * Steuerungs-Thread.
 */
public class Z21RuntimeTest {

    private static final int C1 = Contacts.of(1, 1), C2 = Contacts.of(1, 2);
    private static final String STOP_ALL = "06 00 40 00 80 80";
    private static final String QUERY_9001 = "09 00 40 00 E3 F0 E3 29 D9";

    private final TimeSource.Manual clock = new TimeSource.Manual();
    private final Journal journal = Journal.inMemory();
    private final SimulatedZ21Link z21 = new SimulatedZ21Link(clock);
    private final Z21CommandStation station = new Z21CommandStation("Z21 (Simulation)", z21.factory(), clock, 150, 1000,
            Z21CommandStation.DEFAULT_RELINK_AFTER_MS, false);
    private final ControlRuntime rt = ControlRuntime.create(ControlConfig.defaults(), SchedulerConfig.defaults(),
            HandControllerConfig.defaults().withAddresses(Set.of(9001)), clock, journal, station, List.of(C1, C2),
            address -> true);   // Platzhalter für die spätere Reservierung
    private final ControlExecutor ex = rt.executor();

    private static void assertTrue(boolean b, String what) { if (!b) throw new AssertionError(what); }

    private static Train train(String id, int address) {
        return new Train(id, "Zug " + id, address, SpeedSteps.S128, 1000, 120, 1, null);
    }

    private static String drive(int address, int step, Direction d) {
        return Z21Protocol.hex(Z21Protocol.setLocoDrive(address, step, SpeedSteps.S128, d));
    }

    /** Alle empfangenen Pakete und fälligen Zeitgeber des Treibers abarbeiten (wie der Empfangs-Thread). */
    private void service() {
        while (station.service(0)) { /* weiter, bis nichts mehr ansteht */ }
    }

    /** Zeit in 1-ms-Schritten fortschreiben; je Schritt Treiber und Executor bedienen. */
    private void run(double ms) {
        long end = clock.nanoTime() + (long) (ms * 1_000_000);
        while (clock.nanoTime() < end) {
            service();
            ex.pump();
            clock.advanceMillis(1);
        }
        service();
        ex.pump();
    }

    private void connect() {
        try { rt.connect(); } catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    private void bringReady() {
        connect();
        run(20);
        // Entscheidung 33 (2): erstes Verbinden → Nothalt für die ganze Anlage, erst quittieren
        assertEquals(true, rt.snapshot().emergencyLatched());
        rt.submit(new ControlCommand.AcknowledgeEmergency("Test"));
        run(5);
        assertEquals(OperatingState.RECOVERY, rt.snapshot().operatingState());
        rt.submit(new ControlCommand.RequestReady("Test"));
        run(5);
        assertEquals(OperatingState.READY, rt.snapshot().operatingState());
    }

    // ------------------------------------------------------------------ Entscheidung 33

    public void testFirstConnectSendsGlobalStopAndBlocksUntilAcknowledged() {
        rt.submit(new ControlCommand.RegisterTrain(train("A", 3), "Test"));
        connect();
        run(20);
        assertEquals(1L, z21.count(STOP_ALL));                                    // Nothalt für die ganze Anlage
        assertEquals(1L, z21.count(drive(3, 0, Direction.FORWARD)));             // zusätzlich Halt je bekannter Lok
        PlantSnapshot s = rt.snapshot();
        assertEquals(true, s.emergencyLatched());
        assertEquals(OperatingState.EMERGENCY_STOP, s.operatingState());          // Entscheidung 34: direkt in „Nothalt“
        assertTrue(s.stateReason().startsWith("Programmstart"), "Grund sichtbar: " + s.stateReason());
        assertTrue(journal.byCode("EMERGENCY_STOP_SENT").stream().anyMatch(e -> e.message().contains("Entscheidung 33")),
                "Ursache im Journal");

        assertEquals(false, ex.commands().drive(3, 5, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        run(1_000);
        assertEquals(0L, z21.count(drive(3, 5, Direction.FORWARD)));             // keine Fahrt bis zum Quittieren
        assertEquals(true, rt.snapshot().emergencyLatched());                    // nie automatisch aufgehoben

        rt.submit(new ControlCommand.AcknowledgeEmergency("Bediener"));
        run(5);
        assertEquals(false, rt.snapshot().emergencyLatched());
        assertEquals(OperatingState.RECOVERY, rt.snapshot().operatingState());   // Quittieren führt in den Wiederanlauf
        rt.submit(new ControlCommand.RequestReady("Bediener"));
        run(5);
        assertEquals(true, ex.commands().drive(3, 5, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        run(20);
        assertEquals(1L, z21.count(drive(3, 5, Direction.FORWARD)));
    }

    public void testReconnectAfterDisconnectHasNoGlobalStopButHaltsKnownLocos() {
        rt.submit(new ControlCommand.RegisterTrain(train("B", 5), "Test"));
        bringReady();
        rt.disconnect();
        run(10);
        z21.clearSent();
        connect();
        run(20);
        assertEquals(0L, z21.count(STOP_ALL));                                    // nur beim ersten Verbinden
        assertEquals(1L, z21.count(drive(5, 0, Direction.FORWARD)));             // Entscheidung 28
        assertEquals(true, rt.snapshot().emergencyLatched());                    // Nothalt vom Trennen bleibt gesetzt
    }

    public void testUnexpectedDisconnectStillAttemptsEmergencyStop() {
        bringReady();
        station.disconnect();                       // nicht über die Laufzeit: kein Nothalt vorher
        run(5);
        assertEquals(false, journal.byCode("EMERGENCY_STOP_SEND_FAILED").isEmpty());   // Versuch und ehrliche Meldung
        assertEquals(true, journal.byCode("EMERGENCY_STOP_BEFORE_DISCONNECT").isEmpty());
        assertEquals(true, rt.snapshot().emergencyLatched());
        assertEquals(OperatingState.FAULT, rt.snapshot().operatingState());
    }

    // ------------------------------------------------------------------ Verbinden und Fähigkeiten

    public void testConnectedOnlyAfterReplyAndCapabilitiesDetected() {
        z21.silent = true;
        connect();
        run(800);
        assertEquals(OperatingState.OFFLINE, rt.snapshot().operatingState());   // UDP offen beweist nichts
        assertEquals(false, station.isConnected());

        z21.silent = false;
        run(400);                                   // Statusabfrage nach 1 s wird beantwortet
        PlantSnapshot s = rt.snapshot();
        assertEquals(true, s.operatingState() != OperatingState.OFFLINE);
        assertEquals(1L, z21.count(STOP_ALL));                                    // erst jetzt der Nothalt (Entscheidung 33)
        assertTrue(s.capabilities().has(Feature.CAN_DETECTORS), "CAN erkannt (1.43): " + s.capabilities());
        assertTrue(s.capabilities().has(Feature.RAILCOM), "RailCom erkannt");
        assertTrue(s.capabilities().has(Feature.FUNCTIONS_F29_F31), "F29–F31 erkannt");
        assertEquals(false, s.capabilities().has(Feature.LOCO_BROADCAST));          // an der Z21 nicht gegeben
        assertEquals(false, s.capabilities().has(Feature.ACCESSORY_END_POSITION));
        assertTrue((z21.broadcastFlags & Z21Protocol.BC_CAN_DETECTOR) != 0, "CAN-Melder nach Erkennung abonniert");
        assertEquals(SensorState.UNKNOWN, s.sensor(C1));                             // Grundsatz 5
    }

    public void testUnknownFirmwareAssumesNothing() {
        z21.firmwareBcd = null;                     // Zentrale kennt die Firmware-Abfrage nicht
        bringReady();
        PlantSnapshot s = rt.snapshot();
        assertEquals(false, s.capabilities().detected());
        assertEquals(0L, z21.broadcastFlags & Z21Protocol.BC_CAN_DETECTOR);        // keine CAN-Melder abonniert
        assertEquals(0L, z21.countPrefix("08 00 C4 00"));                         // keine CAN-Abfrage

        AtomicReference<Boolean> f29 = new AtomicReference<>(), f28 = new AtomicReference<>();
        f29.set(ex.commands().function(3, 29, true, CommandPriority.COMFORT));
        f28.set(ex.commands().function(3, 28, true, CommandPriority.COMFORT));
        run(50);
        assertEquals(false, f29.get());             // F29–F31 nicht erkannt → abgewiesen
        assertEquals(true, f28.get());
        assertEquals(1L, z21.count(Z21Protocol.hex(Z21Protocol.setLocoFunction(3, 28, true))));
        assertEquals(0L, z21.count(Z21Protocol.hex(Z21Protocol.setLocoFunction(3, 29, true))));
    }

    public void testRBusNeverUsed() {
        bringReady();
        assertEquals(0L, z21.countPrefix("05 00 81 00"));                         // keine R-Bus-Abfrage
        assertEquals(0L, z21.broadcastFlags & Z21Protocol.BC_RBUS);
        z21.push(SimulatedZ21Link.rbusModule1Input1());
        run(600);
        assertEquals(SensorState.UNKNOWN, rt.snapshot().sensor(C1));             // R-Bus-Meldung ignoriert
    }

    // ------------------------------------------------------------------ Befehle nur über den Befehlsplaner

    public void testCommandsGoOnlyThroughSchedulerWithinBudget() {
        bringReady();
        z21.clearSent();
        long before = ex.scheduler().metrics().sent();
        for (int f = 1; f <= 12; f++) ex.commands().function(3, f, true, CommandPriority.COMFORT);
        long fnNow = z21.countPrefix("0A 00 40 00 E4 F8");
        assertTrue(fnNow >= 1 && fnNow <= 4, "nur so viele sofort, wie der Token-Bucket hergibt (4): " + fnNow);
        assertTrue(ex.scheduler().backlog() > 0, "Rest wartet im Befehlsplaner");
        run(1000);
        assertEquals(12L, z21.countPrefix("0A 00 40 00 E4 F8"));
        assertTrue(ex.scheduler().metrics().sent() - before >= 12, "alle zwölf über den Befehlsplaner gesendet");
    }

    public void testUnchangedSpeedStepIsNeverSent() {
        bringReady();
        z21.clearSent();
        ex.commands().drive(3, 10, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL);
        run(20);
        ex.commands().drive(3, 10, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL);
        ex.commands().drive(3, 10, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL);
        run(20);
        assertEquals(1L, z21.count(drive(3, 10, Direction.FORWARD)));
        assertTrue(ex.scheduler().metrics().suppressed() >= 2, "unveränderte Fahrstufe unterdrückt");
        assertEquals(10, rt.snapshot().reportedLocos().get(3).speedStep());     // Echo der Z21 eingetragen
    }

    public void testTurnoutSendsRcn213BytesAndIsNeverSuppressed() {
        bringReady();
        z21.clearSent();
        ex.commands().turnout(1, TurnoutPosition.STRAIGHT, CommandPriority.AUTOMATIC);
        run(1);
        assertEquals(1L, z21.count("09 00 40 00 53 00 00 89 DA"));               // P=1 gerade, Ausgang ein
        assertEquals(0L, z21.count("09 00 40 00 53 00 00 81 D2"));
        run(160);
        assertEquals(1L, z21.count("09 00 40 00 53 00 00 81 D2"));               // nach 150 ms Ausgang aus
        assertEquals(TurnoutPosition.STRAIGHT, rt.snapshot().reportedTurnouts().get(1));

        ex.commands().turnout(15, TurnoutPosition.DIVERGING, CommandPriority.AUTOMATIC);
        run(200);
        assertEquals(1L, z21.count("09 00 40 00 53 00 0E 88 D5"));               // P=0 abzweigend
        assertEquals(1L, z21.count("09 00 40 00 53 00 0E 80 DD"));

        ex.commands().turnout(1, TurnoutPosition.STRAIGHT, CommandPriority.AUTOMATIC);   // gleiche Lage erneut
        run(200);
        assertEquals(2L, z21.count("09 00 40 00 53 00 00 89 DA"));               // Weichen nie unterdrückt
    }

    public void testEmergencyStopBypassesScheduler() throws Exception {
        bringReady();
        for (int f = 0; f <= 28; f++) ex.commands().function(3, f, true, CommandPriority.COMFORT);
        for (int f = 0; f <= 28; f++) ex.commands().function(4, f, true, CommandPriority.COMFORT);
        assertTrue(ex.scheduler().backlog() > 40, "Rückstau im Befehlsplaner");
        int backlog = ex.scheduler().backlog();

        Thread other = new Thread(() -> rt.emergencyStop("Test", "Nothalt aus der Oberfläche"), "oberflaeche");
        other.start();
        other.join();
        // ohne einen Executor-Schritt: der Nothalt ist bereits als letztes Paket bei der Zentrale
        assertEquals(STOP_ALL, z21.sentHex().getLast());
        assertEquals(backlog, ex.scheduler().backlog());                          // nicht über die Warteschlange

        run(10);
        PlantSnapshot s = rt.snapshot();
        assertEquals(OperatingState.EMERGENCY_STOP, s.operatingState());
        assertEquals(true, s.emergencyLatched());
    }

    // ------------------------------------------------------------------ Ereignisse nur über den Executor

    public void testEventFromReceiveThreadChangesPlantOnlyInExecutor() throws Exception {
        bringReady();
        z21.push(SimulatedZ21Link.canOccupancy(1, 0, true));
        z21.push(SimulatedZ21Link.canRailCom(1, 0, 3, 0));
        AtomicReference<Throwable> foreignAccess = new AtomicReference<>();
        Thread udp = new Thread(() -> {
            service();                              // wie der Empfangs-Thread des Treibers
            try { ex.plant().sensor(C1); } catch (Throwable t) { foreignAccess.set(t); }
        }, "z21-empfang-test");
        udp.start();
        udp.join();

        assertTrue(foreignAccess.get() instanceof WrongThreadException, "fremder Thread darf den Zustand nicht lesen");
        assertEquals(SensorState.UNKNOWN, rt.snapshot().sensor(C1));            // noch nicht übernommen
        ex.pump();
        assertEquals(SensorState.OCCUPIED, rt.snapshot().sensor(C1));           // erst der Executor ändert
        assertEquals(List.of(3), rt.snapshot().railCom().get(C1));
    }

    // ------------------------------------------------------------------ Verbindungsverlust und Wiederverbinden

    public void testConnectionLossHaltsAndSetsSensorsUnknown() {
        bringReady();
        z21.push(SimulatedZ21Link.canOccupancy(1, 0, true));
        z21.push(SimulatedZ21Link.canRailCom(1, 0, 3, 0));
        run(300);
        assertEquals(SensorState.OCCUPIED, rt.snapshot().sensor(C1));
        long stopsBefore = z21.count(STOP_ALL);

        z21.silent = true;                          // Kabel gezogen
        run(5_100);
        PlantSnapshot s = rt.snapshot();
        assertEquals(CommunicationWatchdog.Status.LOST, s.communication());
        assertEquals(OperatingState.FAULT, s.operatingState());
        assertEquals(true, s.emergencyLatched());
        assertTrue(z21.count(STOP_ALL) > stopsBefore, "Nothalt gesendet");
        assertEquals(SensorState.UNKNOWN, s.sensor(C1));
        assertEquals(SensorState.UNKNOWN, s.sensor(C2));
        assertTrue(s.railCom().isEmpty(), "RailCom verworfen");
        assertTrue(s.reportedLocos().isEmpty(), "Lokmeldungen verworfen");
        assertEquals(false, s.capabilities().detected());
        assertEquals(false, s.handControllers().get(9001).current());
        assertEquals(false, s.manualMayMove());
    }

    public void testReconnectSendsHaltToEveryKnownLocoNeverASpeed() {
        rt.submit(new ControlCommand.RegisterTrain(train("A", 3), "Test"));
        rt.submit(new ControlCommand.RegisterTrain(train("B", 5), "Test"));
        run(1);
        assertEquals(0L, z21.countPrefix("0A 00 40 00 E4 13"));                  // offline: nichts gesendet

        bringReady();                               // erstes Verbinden: jede bekannte Lok einmal Halt
        assertEquals(1L, z21.count(drive(3, 0, Direction.FORWARD)));
        assertEquals(1L, z21.count(drive(5, 0, Direction.FORWARD)));

        ex.commands().drive(3, 10, SpeedSteps.S128, Direction.REVERSE, CommandPriority.MANUAL);
        run(20);
        assertEquals(1L, z21.count(drive(3, 10, Direction.REVERSE)));

        z21.silent = true;
        run(5_100);
        assertEquals(OperatingState.FAULT, rt.snapshot().operatingState());
        z21.clearSent();
        z21.silent = false;
        run(1_500);                                 // nächste Statusabfrage wird beantwortet → wieder verbunden
        assertEquals(CommunicationWatchdog.Status.OK, rt.snapshot().communication());
        assertTrue(z21.count(STOP_ALL) >= 1, "nach Wiederverbindung sofort Nothalt (Lastenheft 7)");
        assertEquals(1L, z21.count(drive(3, 0, Direction.REVERSE)));             // Halt in der zuletzt gesendeten Richtung
        assertEquals(1L, z21.count(drive(5, 0, Direction.FORWARD)));
        long speeds = z21.sentHex().stream().filter(h -> h.startsWith("0A 00 40 00 E4 13"))
                .filter(h -> !h.equals(drive(3, 0, Direction.REVERSE)) && !h.equals(drive(5, 0, Direction.FORWARD))).count();
        assertEquals(0L, speeds);                   // nie eine Fahrstufe
        assertEquals(OperatingState.FAULT, rt.snapshot().operatingState());   // Freigabe nur durch den Bediener
        assertEquals(true, rt.snapshot().emergencyLatched());
        assertTrue(rt.snapshot().capabilities().detected(), "Fähigkeiten neu erkannt");
    }

    public void testTrainRegisteredWhileConnectedGetsHalt() {
        bringReady();
        rt.submit(new ControlCommand.RegisterTrain(train("C", 7), "Test"));
        run(5);
        assertEquals(1L, z21.count(drive(7, 0, Direction.FORWARD)));
    }

    // ------------------------------------------------------------------ Handregler auf Stellvertreter-Adressen

    public void testHandControllerPolledEvery250ms() {
        assertEquals(null, rt.snapshot().handControllers().get(9001));        // vor dem ersten Schritt nicht eingetragen
        run(1);
        assertEquals(null, rt.snapshot().handControllers().get(9001).state()); // unbekannt (Grundsatz 5)
        bringReady();
        z21.clearSent();
        run(1_000);
        long polls = z21.count(QUERY_9001);
        assertTrue(polls >= 4 && polls <= 5, "Abfrage alle 250 ms: " + polls);

        z21.handController(9001, 0x80 | 21);       // Handregler vorwärts, Fahrstufe 20
        run(300);
        PlantSnapshot.HandControllerView v = rt.snapshot().handControllers().get(9001);
        assertEquals(true, v.current());
        assertEquals(20, v.state().speedStep());
        assertEquals(Direction.FORWARD, v.state().direction());
        assertEquals(false, journal.byCode("HAND_CONTROLLER").isEmpty());
        assertTrue(ex.scheduler().activeLocos() >= 1, "fremd aktive Stellvertreter-Adresse zählt zum Budget");

        z21.silent = true;                          // Antworten bleiben aus
        run(1_000);
        assertEquals(false, rt.snapshot().handControllers().get(9001).current());
        assertEquals(false, journal.byCode("HAND_CONTROLLER_STALE").isEmpty());
    }

    public void testDisconnectSendsStopAndSwitchesTurnoutOutputOff() {
        bringReady();
        ex.commands().turnout(2, TurnoutPosition.DIVERGING, CommandPriority.AUTOMATIC);
        run(1);
        assertEquals(1L, z21.count(Z21Protocol.hex(Z21Protocol.setTurnout(2, TurnoutPosition.DIVERGING, true))));
        rt.disconnect();
        assertEquals(1L, z21.count(Z21Protocol.hex(Z21Protocol.setTurnout(2, TurnoutPosition.DIVERGING, false))));
        assertTrue(z21.count(STOP_ALL) >= 1, "Nothalt vor dem Trennen");
        assertEquals(true, z21.closed);
        run(5);
        assertEquals(true, rt.snapshot().emergencyLatched());
        assertEquals(OperatingState.FAULT, rt.snapshot().operatingState());     // Folgen des Verlusts bleiben
        assertEquals(SensorState.UNKNOWN, rt.snapshot().sensor(C1));
        // geordnetes Trennen: kein irreführender Fehlereintrag, stattdessen INFO
        assertEquals(true, journal.byCode("EMERGENCY_STOP_SEND_FAILED").isEmpty());
        assertEquals(1, journal.byCode("EMERGENCY_STOP_BEFORE_DISCONNECT").size());
    }
}
