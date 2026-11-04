package at.schmoegl.railcore.safety;

import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.util.TimeSource;

/**
 * Überwacht die Verbindung zur Zentrale. Jede empfangene Nachricht zählt als Lebenszeichen.
 * Nach warnAfter ohne Lebenszeichen: Warnung; nach faultAfter: Störung (Aufrufer löst Nothalt aus).
 */
public final class CommunicationWatchdog {

    public enum Status { OK, LATE, LOST }

    private final TimeSource clock;
    private final Journal journal;
    private final long warnNanos, faultNanos;
    private volatile long lastAlive;
    private volatile Status status = Status.OK;
    private long gaps;
    private long maxGapNanos;

    public CommunicationWatchdog(TimeSource clock, Journal journal, long warnAfterMs, long faultAfterMs) {
        this.clock = clock;
        this.journal = journal;
        this.warnNanos = warnAfterMs * 1_000_000;
        this.faultNanos = faultAfterMs * 1_000_000;
        this.lastAlive = clock.nanoTime();
    }

    public void alive() { alive(clock.nanoTime()); }

    /**
     * Lebenszeichen mit Empfangszeitpunkt. Der Zeitpunkt wird auf „jetzt“ begrenzt, damit ein Zeitstempel aus
     * einer fremden Zeitbasis die Überwachung nie verlängern kann; Zeitpunkte vor dem letzten Lebenszeichen zählen
     * nicht zurück.
     */
    public void alive(long timestampNanos) {
        long now = Math.max(lastAlive, Math.min(timestampNanos, clock.nanoTime()));
        long gap = now - lastAlive;
        lastAlive = now;
        synchronized (this) { if (gap > maxGapNanos) maxGapNanos = gap; }
        if (status != Status.OK) {
            journal.info("verbindung", "COMM_RESTORED", "Zentrale antwortet wieder (Aussetzer " + gap / 1_000_000 + " ms)");
            status = Status.OK;
        }
    }

    /** Regelmäßig aufrufen; liefert den neuen Status. */
    public Status check() {
        long silent = clock.nanoTime() - lastAlive;
        Status s = silent >= faultNanos ? Status.LOST : silent >= warnNanos ? Status.LATE : Status.OK;
        if (s != status) {
            if (s == Status.LATE) {
                synchronized (this) { gaps++; }
                journal.warn("verbindung", "COMM_LATE", "Zentrale seit " + silent / 1_000_000 + " ms ohne Antwort");
            } else if (s == Status.LOST) {
                journal.critical("verbindung", "COMM_LOST", "Verbindung zur Zentrale verloren (" + silent / 1_000_000 + " ms ohne Antwort)");
            }
            status = s;
        }
        return s;
    }

    public Status status() { return status; }

    public void reset() { lastAlive = clock.nanoTime(); status = Status.OK; }

    public synchronized long gapCount() { return gaps; }

    public synchronized long maxGapMillis() { return maxGapNanos / 1_000_000; }
}
