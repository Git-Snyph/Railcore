package at.schmoegl.railcore.safety;

import at.schmoegl.railcore.core.Contacts;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.util.TimeSource;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Entprellt Rückmelder: "belegt" wird sofort weitergegeben (sicherer Fall), "frei" erst, wenn der Melder
 * releaseDelay lang frei geblieben ist. Kurzes Flackern wird gezählt und ab einem Schwellwert gemeldet.
 * Muss regelmäßig mit {@link #poll()} aufgerufen werden.
 * <p>
 * Ein Melder ohne bisherige Meldung ist „unbekannt“ ({@link SensorState#UNKNOWN}), nie frei. Auch der Übergang
 * unbekannt → frei wird erst nach releaseDelay weitergegeben. Im Betrieb an den Steuerungs-Executor gebunden;
 * die Synchronisierung bleibt, damit Statistiken auch von anderen Threads gelesen werden können.
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
    private final FlickerListener flicker;
    private final Map<Integer, Boolean> stable = new HashMap<>();
    private final Map<Integer, Long> pendingRelease = new HashMap<>();
    private final Map<Integer, Stats> stats = new HashMap<>();

    /** Meldung je gezähltem Flacker-Wechsel (für die Diagnose); läuft im Thread, der {@link #raw} aufruft. */
    @FunctionalInterface
    public interface FlickerListener {
        /** @param warned true, wenn der Schwellwert erreicht oder überschritten ist */
        void onFlicker(int contact, int count, boolean warned);
    }

    public FeedbackFilter(TimeSource clock, Journal journal, long releaseDelayMs, int flickerWarnThreshold, Consumer<Event> out) {
        this(clock, journal, releaseDelayMs, flickerWarnThreshold, out, (c, n, w) -> { });
    }

    public FeedbackFilter(TimeSource clock, Journal journal, long releaseDelayMs, int flickerWarnThreshold, Consumer<Event> out,
                          FlickerListener flicker) {
        this.clock = clock;
        this.journal = journal;
        this.releaseDelayNanos = releaseDelayMs * 1_000_000;
        this.flickerWarnThreshold = flickerWarnThreshold;
        this.out = out;
        this.flicker = java.util.Objects.requireNonNull(flicker);
    }

    public synchronized void raw(int contact, boolean occupied, long ts) {
        Stats st = stats.computeIfAbsent(contact, k -> new Stats());
        Boolean current = stable.get(contact);   // null = unbekannt
        if (occupied) {
            Long pending = pendingRelease.remove(contact);
            if (pending != null && Boolean.TRUE.equals(current)) {
                st.flickerCount++;
                if (st.flickerCount == flickerWarnThreshold || st.flickerCount % (flickerWarnThreshold * 10) == 0)
                    journal.warn("melder", "SENSOR_FLICKER", "Rückmelder " + Contacts.format(contact) + " flackert (" + st.flickerCount + "×) – Kontakt/Radschleifer prüfen");
                flicker.onFlicker(contact, st.flickerCount, st.flickerCount >= flickerWarnThreshold);
                return;  // war noch nicht als frei gemeldet → bleibt belegt
            }
            if (!Boolean.TRUE.equals(current)) {
                stable.put(contact, true);
                st.occupiedCount++;
                st.lastChangeNanos = ts;
                out.accept(new Event(contact, true, ts));
            }
        } else if (!Boolean.FALSE.equals(current)) {
            // belegt oder unbekannt → frei erst nach Ablauf der Freimeldeverzögerung
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

    /** Entprellter Zustand; ohne bisherige Meldung „unbekannt“ (nie frei). */
    public synchronized SensorState state(int contact) {
        Boolean b = stable.get(contact);
        return b == null ? SensorState.UNKNOWN : b ? SensorState.OCCUPIED : SensorState.FREE;
    }

    public synchronized Map<Integer, Stats> stats() { return Map.copyOf(stats); }

    /** Nach Verbindungsverlust: Zustand verwerfen (alle Melder wieder unbekannt), damit neu eingelesene Werte als Änderung erscheinen. */
    public synchronized void reset() {
        stable.clear();
        pendingRelease.clear();
    }

    /**
     * Einen Melder vergessen (wieder „unbekannt“), z. B. nach Ausfall seiner Versorgung: Eine anstehende Freimeldung
     * entfällt, die nächste Meldung erscheint als Änderung, unbekannt → frei erst nach der Freimeldeverzögerung.
     * Die Flacker-Statistik bleibt erhalten.
     */
    public synchronized void forget(int contact) {
        stable.remove(contact);
        pendingRelease.remove(contact);
    }
}
