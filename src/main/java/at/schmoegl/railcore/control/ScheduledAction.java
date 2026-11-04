package at.schmoegl.railcore.control;

/**
 * Eine im Steuerungs-Executor auf einen festen Zeitpunkt eingeplante Aufgabe (z. B. Brems- oder Haltebefehl).
 * Sie läuft auf dem Steuerungs-Thread, sobald ihr Zeitpunkt erreicht ist – unabhängig vom Takt. Eingeplant und
 * zurückgezogen wird nur vom Steuerungs-Thread aus.
 */
public final class ScheduledAction {

    private final long dueNanos;
    private final long sequence;
    private final String label;
    private final Runnable action;
    private boolean cancelled;
    private boolean done;
    private long executedNanos = Long.MIN_VALUE;

    ScheduledAction(long dueNanos, long sequence, String label, Runnable action) {
        this.dueNanos = dueNanos;
        this.sequence = sequence;
        this.label = label;
        this.action = action;
    }

    /** Geplanter Zeitpunkt (TimeSource-Nanosekunden). */
    public long dueNanos() { return dueNanos; }

    public String label() { return label; }

    /** Zurückziehen; eine bereits ausgeführte Aufgabe bleibt ausgeführt. */
    public void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }

    /** Wurde die Aufgabe ausgeführt? */
    public boolean isDone() { return done; }

    /** Tatsächlicher Ausführungszeitpunkt oder {@code Long.MIN_VALUE}. */
    public long executedNanos() { return executedNanos; }

    /** Weder ausgeführt noch zurückgezogen. */
    public boolean isPending() { return !done && !cancelled; }

    long sequence() { return sequence; }

    Runnable action() { return action; }

    void markDone(long nowNanos) { done = true; executedNanos = nowNanos; }
}
