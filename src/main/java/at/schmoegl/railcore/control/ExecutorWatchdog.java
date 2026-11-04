package at.schmoegl.railcore.control;

import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.util.TimeSource;

/**
 * Überwacht den Steuerungs-Executor von außen (eigener Thread, außerhalb der Steuerschleife). Hat der Executor
 * länger als Takt + Toleranz keinen Takt abgeschlossen, löst der Watchdog den Nothalt-Pfad aus und meldet eine
 * Störung. Der Nothalt wird dabei nie aufgehoben – auch nicht, wenn der Executor wieder anläuft.
 * <p>
 * {@link #check()} ist ohne Thread aufrufbar (Tests mit TimeSource.Manual); {@link #start()} legt den Thread darüber.
 */
public final class ExecutorWatchdog {

    private final ControlExecutor executor;
    private final TimeSource clock;
    private final Journal journal;
    private final long stallNanos;
    private final long checkMillis;
    private volatile boolean tripped;
    private volatile boolean running;
    private Thread thread;

    ExecutorWatchdog(ControlExecutor executor, TimeSource clock, Journal journal, ControlConfig config) {
        this.executor = executor;
        this.clock = clock;
        this.journal = journal;
        this.stallNanos = config.stallNanos();
        this.checkMillis = config.watchdogCheckMillis();
    }

    /**
     * Eine Prüfung. Thread-sicher, blockiert nie auf den Executor.
     *
     * @return true, wenn der Executor gerade als hängend gilt
     */
    public synchronized boolean check() {
        long silent = clock.nanoTime() - executor.lastTickNanos();
        if (silent > stallNanos) {
            if (!tripped) {
                tripped = true;
                String why = "Steuerungs-Executor ohne Takt seit " + silent / 1_000_000 + " ms";
                journal.critical("watchdog", "EXECUTOR_STALLED", why);
                executor.markStalled(true);
                executor.emergencyStop("watchdog", why);
                executor.reportFault("watchdog", why);
                executor.diagnosticReport("Takt überschritten: " + why, null);   // Lastenheft 7, „Interner Fehler“
            }
        } else if (tripped) {
            tripped = false;
            executor.markStalled(false);
            journal.warn("watchdog", "EXECUTOR_RESUMED", "Steuerungs-Executor taktet wieder – Nothalt bleibt bis zur Quittierung bestehen");
        }
        return tripped;
    }

    public boolean tripped() { return tripped; }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "railcore-executor-watchdog");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    void stop() {
        Thread t;
        synchronized (this) { running = false; t = thread; thread = null; }
        if (t != null) {
            t.interrupt();
            try { t.join(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void loop() {
        while (running) {
            try {
                check();
                Thread.sleep(checkMillis);
            } catch (InterruptedException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                // Der Watchdog darf nie still sterben; Fehler protokollieren und weiter prüfen.
                journal.critical("watchdog", "WATCHDOG_ERROR", "Fehler im Executor-Watchdog: " + e);
            }
        }
    }
}
