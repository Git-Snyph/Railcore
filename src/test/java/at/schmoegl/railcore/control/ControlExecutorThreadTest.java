package at.schmoegl.railcore.control;

import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.util.TimeSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/** Dünner Test des echten Thread-Betriebs (Steuerungs-Thread und Executor-Watchdog mit Systemuhr). */
public class ControlExecutorThreadTest {

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Zeitüberschreitung: " + what);
            Thread.sleep(5);
        }
    }

    public void testThreadedExecutorHangTriggersWatchdog() throws Exception {
        Journal journal = Journal.inMemory();
        FakeStation station = new FakeStation();
        ControlConfig cfg = ControlConfig.defaults().withTickMillis(10).withWatchdog(50, 5);
        ControlExecutor ex = ControlExecutor.create(cfg, TimeSource.SYSTEM, journal, station, List.of(1));
        ex.start();
        try {
            station.connected();
            await(() -> ex.snapshot().operatingState() == OperatingState.CONNECTING, "Verbinde");

            // Executor blockieren: der Watchdog muss Nothalt auslösen, ohne auf ihn zu warten
            CountDownLatch release = new CountDownLatch(1);
            ex.enqueue("Hänger (Test)", () -> {
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            await(() -> station.emergencyStops.get() >= 1, "Nothalt durch Watchdog");
            assertEquals(true, ex.isStalled());

            // auch der Bediener-Nothalt geht sofort durch, obwohl der Executor hängt
            int before = station.emergencyStops.get();
            ex.emergencyStop("test", "Nothalt während Hänger");
            assertEquals(before + 1, station.emergencyStops.get());

            release.countDown();
            await(() -> ex.snapshot().operatingState() == OperatingState.FAULT && ex.snapshot().emergencyLatched(), "Störung nachgezogen");
            await(() -> !ex.isStalled(), "Executor läuft wieder");
            assertEquals(OperatingState.FAULT, ex.snapshot().operatingState());
        } finally {
            ex.stop();
        }
    }
}
