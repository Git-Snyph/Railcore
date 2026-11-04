package at.schmoegl.railcore.z21;

import at.schmoegl.railcore.control.ControlConfig;
import at.schmoegl.railcore.control.ControlRuntime;
import at.schmoegl.railcore.control.HandControllerConfig;
import at.schmoegl.railcore.control.MovementAuthority;
import at.schmoegl.railcore.control.SchedulerConfig;
import at.schmoegl.railcore.core.Contacts;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.safety.SensorState;
import at.schmoegl.railcore.util.TimeSource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/**
 * Dünner Test im echten Thread-Betrieb: Empfangs-Thread des Z21-Treibers, Steuerungs-Thread und Executor-Watchdog
 * mit Systemuhr – gegen die simulierte Z21 ohne Netzwerk.
 */
public class Z21RuntimeThreadTest {

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Zeitüberschreitung: " + what);
            Thread.sleep(5);
        }
    }

    public void testThreadedRuntimeOverSimulatedZ21() throws Exception {
        Journal journal = Journal.inMemory();
        SimulatedZ21Link z21 = new SimulatedZ21Link(new TimeSource.Manual());   // Uhr steht: Antworten sofort fällig
        z21.replyDelayMs = 0;
        Z21CommandStation station = new Z21CommandStation("Z21 (Simulation)", z21.factory(), TimeSource.SYSTEM, 150, 200,
                Z21CommandStation.DEFAULT_RELINK_AFTER_MS, true);
        int c1 = Contacts.of(1, 1);
        ControlRuntime rt = ControlRuntime.create(ControlConfig.defaults(), SchedulerConfig.defaults(),
                HandControllerConfig.defaults().withAddresses(Set.of(9001)), TimeSource.SYSTEM, journal, station,
                List.of(c1), MovementAuthority.NONE);
        rt.start();
        try {
            rt.connect();
            await(() -> rt.snapshot().operatingState() == OperatingState.CONNECTING, "verbunden");
            await(() -> rt.snapshot().capabilities().detected(), "Fähigkeiten erkannt");
            await(() -> z21.count("09 00 40 00 E3 F0 E3 29 D9") >= 2, "Handregler wird abgefragt");

            z21.push(SimulatedZ21Link.canOccupancy(1, 0, true));      // vom Empfangs-Thread in die Warteschlange
            await(() -> rt.snapshot().sensor(c1) == SensorState.OCCUPIED, "Melder über den Executor übernommen");

            rt.emergencyStop("Test", "Nothalt im Thread-Betrieb");
            assertEquals(true, z21.sentHex().contains("06 00 40 00 80 80"));
            await(() -> rt.snapshot().emergencyLatched(), "Nothalt nachgezogen");   // aus „Verbinde“: Störung
        } finally {
            rt.close();
        }
        assertEquals(true, z21.closed);
        assertEquals(true, z21.sentHex().contains("04 00 30 00"));     // Abmeldung (LAN_LOGOFF)
    }
}
