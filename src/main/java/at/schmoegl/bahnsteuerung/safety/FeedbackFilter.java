package at.schmoegl.bahnsteuerung.safety;

import at.schmoegl.bahnsteuerung.core.Contacts;
import at.schmoegl.bahnsteuerung.diag.Journal;
import at.schmoegl.bahnsteuerung.util.TimeSource;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Entprellt Rückmelder: "belegt" wird sofort weitergegeben (sicherer Fall), "frei" erst, wenn der Melder
 * releaseDelay lang frei geblieben ist. Kurzes Flackern wird gezählt und ab einem Schwellwert gemeldet.
 * Muss regelmäßig mit {@link #poll()} aufgerufen werden.
 */
public final class FeedbackFilter {

    /** Gefiltertes Rückmelderereignis. timestampNanos = Zeitpunkt des ursprünglichen Ereignisses. */
    public record Event(int contact, boolean occupied, long timestampNanos) {}

    public static final class Stats {
        public int occupiedCount, flickerCount;
        public long lastChangeNanos;
    }

    private final TimeSource clock;
    private final Journal journal;
    private final long releaseDelayNanos;
    private final int flickerWarnThreshold;
    private final Consumer<Event> out;
    private final Map<Integer, Boolean> stable = new HashMap<>();
    private final Map<Integer, Long> pendingRelease = new HashMap<>();
    private final Map<Integer, Stats> stats = new HashMap<>();

    public FeedbackFilter(TimeSource clock, Journal journal, long releaseDelayMs, int flickerWarnThreshold, Consumer<Event> out) {
        this.clock = clock;
        this.journal = journal;
        this.releaseDelayNanos = releaseDelayMs * 1_000_000;
        this.flickerWarnThreshold = flickerWarnThreshold;
        this.out = out;
    }

    public synchronized void raw(int contact, boolean occupied, long ts) {
        Stats st = stats.computeIfAbsent(contact, k -> new Stats());
        boolean isOcc = stable.getOrDefault(contact, false);
        if (occupied) {
            if (pendingRelease.remove(contact) != null) {
                st.flickerCount++;
                if (st.flickerCount == flickerWarnThreshold || st.flickerCount % (flickerWarnThreshold * 10) == 0)
                    journal.warn("melder", "SENSOR_FLICKER", "Rückmelder " + Contacts.format(contact) + " flackert (" + st.flickerCount + "×) – Kontakt/Radschleifer prüfen");
                return;  // war noch nicht als frei gemeldet → bleibt belegt
            }
            if (!isOcc) {
                stable.put(contact, true);
                st.occupiedCount++;
                st.lastChangeNanos = ts;
                out.accept(new Event(contact, true, ts));
            }
        } else if (isOcc) {
            pendingRelease.putIfAbsent(contact, ts);
        }
    }

    /** Fällige Freimeldungen ausgeben. */
    public void poll() {
        long now = clock.nanoTime();
        java.util.List<Event> due = new java.util.ArrayList<>();
        synchronized (this) {
            var it = pendingRelease.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (now - e.getValue() >= releaseDelayNanos) {
                    it.remove();
                    stable.put(e.getKey(), false);
                    stats.computeIfAbsent(e.getKey(), k -> new Stats()).lastChangeNanos = e.getValue();
                    due.add(new Event(e.getKey(), false, e.getValue()));
                }
            }
        }
        due.forEach(out);
    }

    public synchronized boolean isOccupied(int contact) { return stable.getOrDefault(contact, false); }

    public synchronized Map<Integer, Stats> stats() { return Map.copyOf(stats); }

    /** Nach Verbindungsverlust: Zustand verwerfen, damit neu eingelesene Werte als Änderung erscheinen. */
    public synchronized void reset() {
        stable.clear();
        pendingRelease.clear();
    }
}
