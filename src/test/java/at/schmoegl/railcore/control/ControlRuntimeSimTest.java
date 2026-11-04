package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.safety.SensorState;
import at.schmoegl.railcore.sim.Distribution;
import at.schmoegl.railcore.sim.SimCommandStation;
import at.schmoegl.railcore.sim.SimWorld;
import at.schmoegl.railcore.train.Train;
import at.schmoegl.railcore.util.TimeSource;

import java.util.List;
import java.util.Set;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;

/** {@link ControlRuntime} mit dem Simulator als Zentrale (gleiche Verdrahtung wie mit der Z21). */
public class ControlRuntimeSimTest {

    private final TimeSource.Manual clock = new TimeSource.Manual();
    private final Journal journal = Journal.inMemory();
    private final SimWorld world = new SimWorld(clock, SimWorld.randomForRun(7));
    private final SimCommandStation station = new SimCommandStation(world, Distribution.uniform(20, 50), 200);
    private final ControlRuntime rt = ControlRuntime.create(ControlConfig.defaults(), SchedulerConfig.defaults(),
            HandControllerConfig.defaults(), clock, journal, station, List.of(1, 2), MovementAuthority.NONE);
    private final ControlExecutor ex = rt.executor();
    private final SimWorld.Controller controller = new SimWorld.Controller() {
        @Override public long nextDueNanos() { return ex.nextDueNanos(); }
        @Override public void pump() { ex.pump(); }
    };

    private static void assertTrue(boolean b, String what) { if (!b) throw new AssertionError(what); }

    private void runMs(long ms) { world.run(controller, () -> false, clock.nanoTime() + ms * 1_000_000L); }

    private static Train train(String id, int address) {
        return new Train(id, "Zug " + id, address, SpeedSteps.S128, 1000, 120, 1, null);
    }

    public void testConnectHaltsKnownLocosAndNeverMovesWithoutReservation() throws Exception {
        rt.submit(new ControlCommand.RegisterTrain(train("A", 3), "Test"));
        rt.submit(new ControlCommand.RegisterTrain(train("B", 4), "Test"));
        runMs(10);
        assertEquals(List.of(), station.drives());                              // offline nichts gesendet
        rt.connect();
        runMs(100);
        assertEquals(OperatingState.FAULT, rt.snapshot().operatingState());   // Entscheidung 33: Nothalt beim ersten Verbinden
        assertEquals(1, station.emergencyStops());
        assertEquals(Set.of(3, 4), Set.copyOf(station.drives().stream().map(SimCommandStation.SentDrive::address).toList()));
        assertTrue(station.drives().stream().allMatch(d -> d.step() == 0), "nur Halt, nie eine Fahrstufe");
        assertEquals(SensorState.UNKNOWN, rt.snapshot().sensor(1));

        rt.submit(new ControlCommand.RequestReady("Test"));
        runMs(10);
        assertEquals(false, ex.commands().drive(3, 5, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
        runMs(10);
        assertTrue(station.drives().stream().allMatch(d -> d.step() == 0), "ohne Reservierung keine Bewegung");
    }

    public void testEmergencyStopFromForeignThreadAndOrderlyDisconnect() throws Exception {
        rt.connect();
        runMs(50);
        Thread ui = new Thread(() -> rt.emergencyStop("Test", "Nothalt-Taste"), "oberflaeche");
        ui.start();
        ui.join();
        assertEquals(2, station.emergencyStops());                              // sofort, ohne Executor-Schritt (1 = erstes Verbinden)
        runMs(10);
        assertEquals(true, rt.snapshot().emergencyLatched());

        rt.disconnect();                                                         // verbunden: vorher erneut Nothalt
        assertEquals(3, station.emergencyStops());
        runMs(10);
        assertEquals(OperatingState.FAULT, rt.snapshot().operatingState());     // Nothalt aus „Verbinde“ = Störung
        assertEquals(true, rt.snapshot().emergencyLatched());                    // nie automatisch aufgehoben
    }

    public void testExecutorOnlyStateAccessFromOtherThreadsIsRejected() throws Exception {
        runMs(1);                                                                // Testthread ist Steuerungs-Thread
        Throwable[] seen = new Throwable[1];
        Thread t = new Thread(() -> {
            try { ex.commands().turnout(1, at.schmoegl.railcore.core.TurnoutPosition.STRAIGHT, CommandPriority.MANUAL); }
            catch (Throwable e) { seen[0] = e; }
        });
        t.start();
        t.join();
        assertTrue(seen[0] instanceof WrongThreadException, "Befehle nur vom Steuerungs-Thread: " + seen[0]);
    }

    public void testHandControllerConfigValidated() {
        assertEquals(Set.of(9000, 9999), HandControllerConfig.defaults().withAddresses(Set.of(9999, 9000)).addresses());
        assertThrows(IllegalArgumentException.class, () -> HandControllerConfig.defaults().withAddresses(Set.of(8999)));
        assertThrows(IllegalArgumentException.class, () -> HandControllerConfig.defaults().withAddresses(Set.of(10_000)));
        assertThrows(IllegalArgumentException.class, () -> HandControllerConfig.defaults().withTiming(10, 750));
        assertThrows(IllegalArgumentException.class, () -> HandControllerConfig.defaults().withTiming(250, 400));
        assertEquals(250, HandControllerConfig.defaults().pollMillis());
    }
}
