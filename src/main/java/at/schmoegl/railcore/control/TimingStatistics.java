package at.schmoegl.railcore.control;

import java.util.Arrays;

/**
 * Misst Abweichungen zwischen geplantem und tatsächlichem Ausführungszeitpunkt (Taktschwankung, Verspätung
 * eingeplanter Befehle). Minimum, Mittelwert und Maximum exakt; Perzentile aus einem Histogramm mit
 * 0,1-ms-Klassen bis 500 ms (darüber eine Überlaufklasse, gemeldet als Maximum). Nicht thread-sicher; gehört dem
 * Steuerungs-Thread. Nach außen gehen nur unveränderliche {@link Summary}-Werte.
 */
public final class TimingStatistics {

    /** Zusammenfassung eines Messfensters, alle Zeiten in Millisekunden. */
    public record Summary(long count, double minMs, double meanMs, double maxMs, double p50Ms, double p95Ms,
                          double p99Ms, long overruns) {
        public static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0, 0, 0);

        public String format() {
            if (count == 0) return "keine Messwerte";
            return String.format(java.util.Locale.ROOT,
                    "n=%d, min %.2f ms, mittel %.2f ms, max %.2f ms, p50 %.1f ms, p95 %.1f ms, p99 %.1f ms, Überläufe %d",
                    count, minMs, meanMs, maxMs, p50Ms, p95Ms, p99Ms, overruns);
        }
    }

    private static final long BUCKET_NANOS = 100_000;   // 0,1 ms
    private static final int BUCKETS = 5_000;          // bis 500 ms

    private final long[] histogram = new long[BUCKETS + 1];
    private long count;
    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;
    private double sum;
    private long overruns;

    /** Eine Abweichung erfassen (tatsächlich − geplant, in ns; negative Werte zählen als 0 im Histogramm). */
    public void record(long deviationNanos) {
        count++;
        min = Math.min(min, deviationNanos);
        max = Math.max(max, deviationNanos);
        sum += deviationNanos;
        long b = Math.max(0, deviationNanos) / BUCKET_NANOS;
        histogram[(int) Math.min(BUCKETS, b)]++;
    }

    /** Ein ganzer Takt wurde ausgelassen (Steuerung kam mehr als einen Takt zu spät). */
    public void recordOverrun() { overruns++; }

    public long count() { return count; }

    public long overruns() { return overruns; }

    public Summary summary() {
        if (count == 0) return overruns == 0 ? Summary.EMPTY : new Summary(0, 0, 0, 0, 0, 0, 0, overruns);
        return new Summary(count, min / 1e6, sum / count / 1e6, max / 1e6,
                percentile(0.50), percentile(0.95), percentile(0.99), overruns);
    }

    public void reset() {
        Arrays.fill(histogram, 0);
        count = 0;
        min = Long.MAX_VALUE;
        max = Long.MIN_VALUE;
        sum = 0;
        overruns = 0;
    }

    /** Obere Klassengrenze des p-Perzentils in ms (konservativ); in der Überlaufklasse das Maximum. */
    private double percentile(double p) {
        long rank = (long) Math.ceil(p * count);
        long seen = 0;
        for (int i = 0; i <= BUCKETS; i++) {
            seen += histogram[i];
            if (seen >= rank) {
                if (i == BUCKETS) return max / 1e6;
                return Math.min((i + 1) * BUCKET_NANOS, Math.max(max, 0)) / 1e6;
            }
        }
        return max / 1e6;
    }
}
