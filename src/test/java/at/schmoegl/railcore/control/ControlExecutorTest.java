package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.safety.SensorState;
import at.schmoegl.railcore.train.Train;
import at.schmoegl.railcore.util.TimeSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;

/** Steuerungs-Executor deterministisch im Schrittbetrieb mit TimeSource.Manual (ohne eigenen Thread). */
public class ControlExecutorTest {

    private final TimeSource.Manual clock = new TimeSource.Manual();
    private final Journal journal = Journal.inMemory();
    private final FakeStation station = new FakeStation();
    private final ControlExecutor ex = ControlExecutor.create(ControlConfig.defaults(), clock, journal, station,
            List.of(1, 2, 3));

    private static Train train(String id, int address) {
        return new Train(id, "Zug " + id, address, SpeedSteps.S128, 1000, 120, 1, null);
    }

    /** Verbinden, Lebenszeichen, Freigabe „Bereit“. */
    private void bringReady() {
        station.connected();
        ex.step();
        heartbeatStep(10);
        ex.submit(new ControlCommand.RequestReady("Test"));
        ex.step();
        assertEquals(OperatingState.READY, ex.snapshot().operatingState());
    }

    /** Zeit fortschreiben, mit Lebenszeichen der Zentrale, einen Schritt rechnen. */
    private void heartbeatStep(double ms) {
        clock.advanceMillis(ms);
        station.heartbeat(clock.nanoTime());
        ex.step();
    }

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    // ------------------------------------------------------------------ Startzustand

    public void testStartsOfflineWithUnknownSensorsAndUnconfirmedTrains() {
        PlantSnapshot s = ex.snapshot();
        assertEquals(OperatingState.OFFLINE, s.operatingState());
        assertEquals(TrackPower.UNKNOWN, s.trackPower());
        assertEquals(SensorState.UNKNOWN, s.sensor(1));
        assertEquals(SensorState.UNKNOWN, s.sensor(3));
        assertEquals(SensorState.UNKNOWN, s.sensor(999));   // nie erfasst → unbekannt, nicht frei
        assertEquals(false, s.sensor(1).isFree());
        assertEquals(true, s.sensor(1).treatAsOccupied());
        assertEquals(false, s.manualMayMove());

        ex.submit(new ControlCommand.RegisterTrain(train("RJ", 3), "Test"));
        ex.step();
        assertEquals(TrainStatus.UNCONFIRMED, ex.snapshot().trains().get("RJ").status());
    }

    public void testProxyAddressRangeRejectedForTrains() {
        ex.submit(new ControlCommand.RegisterTrain(train("X", 9001), "Test"));
        ex.step();
        assertEquals(null, ex.snapshot().trains().get("X"));
        assertEquals(1, journal.byCode("COMMAND_REJECTED").size());
    }

    public void testFeedbackFromUnknownToFreeOnlyAfterReleaseDelay() {
        bringReady();
        station.feedback(1, true, clock.nanoTime());
        station.feedback(2, false, clock.nanoTime());
        heartbeatStep(10);
        assertEquals(SensorState.OCCUPIED, ex.snapshot().sensor(1));
        assertEquals(SensorState.UNKNOWN, ex.snapshot().sensor(2));
        heartbeatStep(600);
        assertEquals(SensorState.FREE, ex.snapshot().sensor(2));
    }

    // ------------------------------------------------------------------ Nothalt

    public void testEmergencyStopIsSentImmediatelyBeforeQueueIsProcessed() {
        bringReady();
        ex.submit(new ControlCommand.RegisterTrain(train("A", 3), "Test"));
        ex.submit(new ControlCommand.RegisterTrain(train("B", 4), "Test"));

        ex.emergencyStop("test", "Taste Nothalt");
        // gesendet, obwohl der Executor noch keinen Schritt gemacht hat
        assertEquals(1, station.emergencyStops.get());
        assertEquals(1, journal.byCode("EMERGENCY_STOP_SENT").size());
        assertEquals(OperatingState.READY, ex.snapshot().operatingState());
        assertEquals(null, ex.snapshot().trains().get("A"));

        ex.step();
        PlantSnapshot s = ex.snapshot();
        assertEquals(OperatingState.EMERGENCY_STOP, s.operatingState());
        assertEquals(true, s.emergencyLatched());
        assertEquals(false, s.manualMayMove());
        // Nothalt wurde vorrangig nachgezogen, danach die Warteschlange
        List<String> codes = journal.recent(1000).stream().map(Journal.Entry::code).toList();
        assertTrue(codes.indexOf("EMERGENCY_LATCHED") < codes.indexOf("TRAIN_REGISTERED"), "Nothalt vor Warteschlange");
    }

    public void testEmergencyStopFromForeignThreadIsSentWithoutExecutor() throws Exception {
        bringReady();
        Thread t = new Thread(() -> ex.emergencyStop("oberflaeche", "Nothalt-Knopf"));
        t.start();
        t.join();
        assertEquals(1, station.emergencyStops.get());
        ex.step();
        assertEquals(OperatingState.EMERGENCY_STOP, ex.snapshot().operatingState());
    }

    public void testEmergencyStopIsNeverClearedAutomatically() {
        bringReady();
        ex.emergencyStop("test", "Nothalt");
        for (int i = 0; i < 200; i++) heartbeatStep(50);   // 10 s normaler Betrieb
        station.trackPower(TrackPower.ON);
        heartbeatStep(50);
        assertEquals(OperatingState.EMERGENCY_STOP, ex.snapshot().operatingState());
        assertEquals(true, ex.snapshot().emergencyLatched());

        // „Bereit“ ohne Quittierung abgewiesen
        ex.submit(new ControlCommand.RequestReady("Test"));
        heartbeatStep(50);
        assertEquals(OperatingState.EMERGENCY_STOP, ex.snapshot().operatingState());
    }

    public void testEmergencyStopClearedOnlyByExplicitAcknowledge() {
        bringReady();
        ex.emergencyStop("test", "Nothalt");
        heartbeatStep(50);
        ex.submit(new ControlCommand.AcknowledgeEmergency("Bediener"));
        heartbeatStep(50);
        assertEquals(OperatingState.RECOVERY, ex.snapshot().operatingState());   // nicht direkt in den Betrieb
        assertEquals(false, ex.snapshot().emergencyLatched());
        assertEquals(false, ex.snapshot().manualMayMove());
        ex.submit(new ControlCommand.RequestReady("Bediener"));
        heartbeatStep(50);
        assertEquals(OperatingState.READY, ex.snapshot().operatingState());
    }

    public void testEmergencySendFailureStillSetsEmergencyAndFault() {
        bringReady();
        station.failEmergencyStop = true;
        ex.emergencyStop("test", "Nothalt");
        assertEquals(1, journal.byCode("EMERGENCY_STOP_SEND_FAILED").size());
        ex.step();
        assertEquals(OperatingState.FAULT, ex.snapshot().operatingState());
        assertEquals(true, ex.snapshot().emergencyLatched());
    }

    public void testEmergencyReportedByCommandStationLatches() {
        bringReady();
        station.trackPower(TrackPower.EMERGENCY_STOP);
        heartbeatStep(10);
        assertEquals(OperatingState.EMERGENCY_STOP, ex.snapshot().operatingState());
        assertEquals(true, ex.snapshot().emergencyLatched());
    }

    // ------------------------------------------------------------------ Watchdog, Verbindung, interne Fehler

    public void testExecutorStallTriggersEmergencyAndFault() {
        bringReady();
        clock.advanceMillis(240);   // Takt 50 + Toleranz 200 noch nicht überschritten
        assertEquals(false, ex.watchdog().check());
        assertEquals(0, station.emergencyStops.get());

        clock.advanceMillis(20);    // 260 ms ohne Takt → Hänger
        assertEquals(true, ex.watchdog().check());
        assertEquals(1, station.emergencyStops.get());   // sofort, ohne Executor
        assertEquals(true, ex.isStalled());
        ex.watchdog().check();
        assertEquals(1, station.emergencyStops.get());   // nur einmal je Hänger

        // Executor läuft wieder an: Zustand Störung, Nothalt bleibt
        station.heartbeat(clock.nanoTime());
        ex.step();
        assertEquals(OperatingState.FAULT, ex.snapshot().operatingState());
        assertEquals(true, ex.snapshot().emergencyLatched());
        assertEquals(false, ex.watchdog().check());
        assertEquals(1, journal.byCode("EXECUTOR_RESUMED").size());
        heartbeatStep(10);
        assertEquals(OperatingState.FAULT, ex.snapshot().operatingState());
        assertEquals(true, ex.snapshot().emergencyLatched());
    }

    public void testConnectionLossTriggersEmergencyFaultAndUnknownSensors() {
        bringReady();
        ex.submit(new ControlCommand.RegisterTrain(train("RJ", 3), "Test"));
        station.trackPower(TrackPower.ON);
        station.feedback(1, true, clock.nanoTime());
        station.feedback(2, false, clock.nanoTime());
        heartbeatStep(600);
        heartbeatStep(10);
        assertEquals(SensorState.OCCUPIED, ex.snapshot().sensor(1));
        assertEquals(SensorState.FREE, ex.snapshot().sensor(2));

        for (int i = 0; i < 70; i++) { clock.advanceMillis(50); ex.step(); }   // 3,5 s still
        assertEquals(CommunicationWatchdog.Status.LATE, ex.snapshot().communication());
        assertEquals(false, ex.snapshot().manualMayMove());
        assertEquals(0, station.emergencyStops.get());
        for (int i = 0; i < 40; i++) { clock.advanceMillis(50); ex.step(); }   // > 5 s still

        PlantSnapshot s = ex.snapshot();
        assertEquals(1, station.emergencyStops.get());
        assertEquals(OperatingState.FAULT, s.operatingState());
        assertEquals(true, s.emergencyLatched());
        assertEquals(SensorState.UNKNOWN, s.sensor(1));
        assertEquals(SensorState.UNKNOWN, s.sensor(2));
        assertEquals(TrackPower.UNKNOWN, s.trackPower());
        assertEquals(TrainStatus.UNCONFIRMED, s.trains().get("RJ").status());

        // Quittieren bei verlorener Verbindung abgewiesen
        ex.submit(new ControlCommand.AcknowledgeEmergency("Bediener"));
        clock.advanceMillis(50);
        ex.step();
        assertEquals(OperatingState.FAULT, ex.snapshot().operatingState());

        // Wiederverbindung: sofort erneut Nothalt, Störung bleibt, Melder bleiben unbekannt
        heartbeatStep(50);
        assertEquals(2, station.emergencyStops.get());
        assertEquals(OperatingState.FAULT, ex.snapshot().operatingState());
        assertEquals(SensorState.UNKNOWN, ex.snapshot().sensor(2));
    }

    public void testDisconnectEventIsTreatedAsConnectionLoss() {
        bringReady();
        station.feedback(1, true, clock.nanoTime());
        heartbeatStep(10);
        station.disconnected();
        ex.step();
        assertEquals(1, station.emergencyStops.get());
        assertEquals(OperatingState.FAULT, ex.snapshot().operatingState());
        assertEquals(SensorState.UNKNOWN, ex.snapshot().sensor(1));
    }

    public void testInternalErrorTriggersEmergencyAndFault() {
        bringReady();
        ex.enqueue("Testfehler", () -> { throw new IllegalStateException("absichtlich"); });
        ex.step();
        assertEquals(1, station.emergencyStops.get());
        assertEquals(OperatingState.FAULT, ex.snapshot().operatingState());
        assertEquals(1, journal.byCode("INTERNAL_ERROR").size());
    }

    public void testQueueOverflowTriggersEmergency() {
        ControlExecutor small = ControlExecutor.create(ControlConfig.defaults().withQueueCapacity(16), clock, journal,
                station, List.of());
        boolean last = true;
        for (int i = 0; i < 17; i++) last = small.submit(new ControlCommand.RequestReady("Flut"));
        assertEquals(false, last);
        assertEquals(1, station.emergencyStops.get());
        assertEquals(1, journal.byCode("QUEUE_OVERFLOW").size());
    }

    // ------------------------------------------------------------------ Thread-Bindung, Momentaufnahmen

    public void testForeignThreadAccessIsRejected() throws Exception {
        ex.step();   // Test-Thread wird Steuerungs-Thread
        assertEquals(SensorState.UNKNOWN, ex.plant().sensor(1));

        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try { ex.plant().sensor(1); } catch (Throwable e) { caught.set(e); }
        }, "fremd");
        t.start();
        t.join();
        assertTrue(caught.get() instanceof WrongThreadException, "erwartet WrongThreadException, erhalten " + caught.get());

        AtomicReference<Throwable> caught2 = new AtomicReference<>();
        Thread t2 = new Thread(() -> {
            try { ex.step(); } catch (Throwable e) { caught2.set(e); }
        }, "fremd2");
        t2.start();
        t2.join();
        assertTrue(caught2.get() instanceof WrongThreadException, "Schritt von fremdem Thread");
        assertEquals(2, journal.byCode("THREAD_VIOLATION").size());
        assertThrows(IllegalStateException.class, ex::start);   // bereits an den Test-Thread gebunden
    }

    public void testAccessBeforeBindingIsRejected() {
        assertThrows(WrongThreadException.class, () -> ex.plant().operatingState());
    }

    public void testSnapshotsAreImmutable() {
        bringReady();
        PlantSnapshot before = ex.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> before.sensors().put(1, SensorState.FREE));
        assertThrows(UnsupportedOperationException.class, () -> before.trains().clear());

        station.feedback(1, true, clock.nanoTime());
        heartbeatStep(10);
        assertEquals(SensorState.UNKNOWN, before.sensor(1));   // alte Aufnahme unverändert
        assertEquals(SensorState.OCCUPIED, ex.snapshot().sensor(1));
        assertTrue(ex.snapshot().version() > before.version(), "Version steigt");
    }

    // ------------------------------------------------------------------ Sicherheitsschranke der Befehlsausgabe

    public void testCommandGateBlocksMovementWithoutReservationAndInEmergency() {
        bringReady();
        assertEquals(false, ex.commands().drive(3, 10, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));   // keine Reservierung
        assertEquals(true, ex.commands().drive(3, 0, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));     // Halt immer erlaubt
        assertEquals(List.of("drive 3 0 FORWARD"), station.sent);

        ex.emergencyStop("test", "Nothalt");
        heartbeatStep(10);
        assertEquals(false, ex.commands().trackPower(true));
        assertEquals(false, ex.commands().turnout(5, TurnoutPosition.DIVERGING, CommandPriority.AUTOMATIC));
        assertEquals(true, ex.commands().trackPower(false));
        assertEquals(List.of("drive 3 0 FORWARD", "power false"), station.sent);
    }
}
