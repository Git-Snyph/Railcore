package at.schmoegl.bahnsteuerung.util;

/** Zeitquelle in Nanosekunden. Im Betrieb System.nanoTime, in Tests eine manuelle Uhr. */
@FunctionalInterface
public interface TimeSource {
    long nanoTime();

    TimeSource SYSTEM = System::nanoTime;

    /** Manuell weiterstellbare Uhr für Tests und Simulation. */
    final class Manual implements TimeSource {
        private long now;

        @Override public synchronized long nanoTime() { return now; }

        public synchronized void advanceMillis(double ms) { now += (long) (ms * 1_000_000); }

        public synchronized void advanceSeconds(double s) { now += (long) (s * 1_000_000_000L); }
    }
}
