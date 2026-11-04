package at.schmoegl.railcore.control;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;

/** Grenzen der Executor-Einstellungen. */
public class ControlConfigTest {

    public void testDefaults() {
        ControlConfig c = ControlConfig.defaults();
        assertEquals(50, c.tickMillis());
        assertEquals(250_000_000L, c.stallNanos());
    }

    public void testImplausibleValuesRejected() {
        ControlConfig d = ControlConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> d.withTickMillis(0));
        assertThrows(IllegalArgumentException.class, () -> d.withWatchdog(10, 5));      // Toleranz < Takt
        assertThrows(IllegalArgumentException.class, () -> d.withWatchdog(10_000, 5));  // zu groß
        assertThrows(IllegalArgumentException.class, () -> d.withWatchdog(200, 0));
        assertThrows(IllegalArgumentException.class, () -> d.withQueueCapacity(1));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlConfig(50, 200, 20, 5_000, 3_000, 500, 5, 100, 1_000, 60));      // Warnung nach Verlust
    }
}
