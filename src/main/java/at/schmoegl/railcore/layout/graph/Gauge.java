package at.schmoegl.railcore.layout.graph;

import java.util.Comparator;
import java.util.OptionalDouble;

/**
 * Spurweite eines Gleises (Entscheidung 46, Option A): eine Spur nach NEM 010 oder {@link NemGauge#CUSTOM} mit
 * eigenem mm-Wert. In der Datei als NEM-Bezeichnung ({@code "H0"}) bzw. als
 * {@code {"gauge": "CUSTOM", "customGaugeMm": 10.5}}.
 *
 * @param nem      Spur nach NEM 010 oder {@link NemGauge#CUSTOM}
 * @param customMm eigene Spurweite in mm, nur und genau bei {@link NemGauge#CUSTOM}
 */
public record Gauge(NemGauge nem, OptionalDouble customMm) {

    /** Reihenfolge in der Datei: nach Spurweite, bei gleicher nach der Aufzählung (CUSTOM zuletzt). */
    public static final Comparator<Gauge> ORDER = Comparator.comparingDouble(Gauge::mm).thenComparing(Gauge::nem);

    public Gauge {
        if (nem == null) throw new IllegalArgumentException("Spurweite fehlt");
        if (customMm == null) customMm = OptionalDouble.empty();
        if (nem == NemGauge.CUSTOM) {
            if (customMm.isEmpty()) throw new IllegalArgumentException("Spurweite CUSTOM ohne eigenen mm-Wert");
            double mm = customMm.getAsDouble();
            if (!(mm > 0) || !Double.isFinite(mm))
                throw new IllegalArgumentException("eigene Spurweite muss größer 0 sein: " + mm);
        } else if (customMm.isPresent()) {
            throw new IllegalArgumentException("Spurweite " + nem.nemName() + ": eigener mm-Wert nur bei CUSTOM");
        }
    }

    public static Gauge of(NemGauge nem) { return new Gauge(nem, OptionalDouble.empty()); }

    public static Gauge custom(double mm) { return new Gauge(NemGauge.CUSTOM, OptionalDouble.of(mm)); }

    /** Spurweite in mm (NEM-Wert oder eigener). */
    public double mm() { return nem == NemGauge.CUSTOM ? customMm.getAsDouble() : nem.gaugeMm(); }

    @Override public String toString() { return nem == NemGauge.CUSTOM ? "CUSTOM " + mm() + " mm" : nem.nemName(); }
}
