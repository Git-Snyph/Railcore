package at.schmoegl.railcore.safety;

import at.schmoegl.railcore.diag.Journal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** Verwaltet den Betriebszustand; unerlaubte Übergänge werden protokolliert und abgelehnt. */
public final class StateMachine {
    private final Journal journal;
    private final List<BiConsumer<OperatingState, OperatingState>> listeners = new CopyOnWriteArrayList<>();
    private volatile OperatingState state = OperatingState.OFFLINE;
    private volatile String reason = "";

    public StateMachine(Journal journal) { this.journal = journal; }

    public OperatingState state() { return state; }

    public String reason() { return reason; }

    public void addListener(BiConsumer<OperatingState, OperatingState> l) { listeners.add(l); }

    /** @return true, wenn der Übergang erfolgt ist */
    public boolean transition(OperatingState next, String why) {
        OperatingState prev;
        synchronized (this) {
            prev = state;
            if (prev == next) return true;
            if (!prev.allowedNext().contains(next)) {
                journal.warn("zustand", "TRANSITION_REJECTED", prev.german() + " → " + next.german() + " nicht erlaubt (" + why + ")");
                return false;
            }
            state = next;
            reason = why;
        }
        var sev = next == OperatingState.EMERGENCY_STOP || next == OperatingState.FAULT
                ? at.schmoegl.railcore.diag.Severity.CRITICAL : at.schmoegl.railcore.diag.Severity.INFO;
        journal.log(sev, "zustand", "STATE", prev.german() + " → " + next.german() + ": " + why);
        for (var l : listeners) l.accept(prev, next);
        return true;
    }
}
