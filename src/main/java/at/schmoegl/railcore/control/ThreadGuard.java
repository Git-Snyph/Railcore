package at.schmoegl.railcore.control;

import at.schmoegl.railcore.diag.Journal;

/**
 * Bindet veränderlichen Anlagenzustand an genau einen Thread (den Steuerungs-Executor). Ein Zugriff von einem
 * anderen Thread – oder bevor ein Thread gebunden ist – wird abgewiesen und im Journal gemeldet, nie still geduldet.
 */
final class ThreadGuard {

    private final Journal journal;
    private volatile Thread owner;

    ThreadGuard(Journal journal) { this.journal = journal; }

    /** Bindet einmalig an einen Thread. Eine Umbindung auf einen anderen Thread ist nicht erlaubt. */
    synchronized void bind(Thread thread) {
        if (owner == thread) return;
        if (owner != null)
            throw new IllegalStateException("Steuerungszustand ist bereits an Thread „" + owner.getName() + "“ gebunden");
        owner = thread;
    }

    boolean isBound() { return owner != null; }

    boolean isOwner() { return owner == Thread.currentThread(); }

    /** @throws WrongThreadException bei Zugriff von einem fremden Thread */
    void check(String what) {
        Thread current = Thread.currentThread();
        Thread o = owner;
        if (o == current) return;
        String msg = "Zugriff auf Anlagenzustand (" + what + ") von Thread „" + current.getName() + "“ abgewiesen; "
                + (o == null ? "kein Steuerungs-Thread gebunden" : "nur „" + o.getName() + "“ darf ihn ändern");
        journal.error("steuerung", "THREAD_VIOLATION", msg);
        throw new WrongThreadException(msg);
    }
}
