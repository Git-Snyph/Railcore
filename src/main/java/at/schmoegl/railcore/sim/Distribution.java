package at.schmoegl.railcore.sim;

import java.util.Locale;
import java.util.Random;

/**
 * Zufallsverteilung für die Simulation (Verzögerungen in ms, Streuungen in mm, Faktoren). Gezogen wird immer aus
 * einem übergebenen {@link Random} mit festem Seed, damit Läufe reproduzierbar sind. {@link #mean()} und
 * {@link #sigma()} sind die theoretischen Kennwerte – genau das, was eine Steuerung aus dem Einmessen kennen kann.
 */
public final class Distribution {

    private enum Kind { CONSTANT, UNIFORM, NORMAL }

    private final Kind kind;
    private final double a, b, min, max;

    private Distribution(Kind kind, double a, double b, double min, double max) {
        this.kind = kind;
        this.a = a;
        this.b = b;
        this.min = min;
        this.max = max;
    }

    public static Distribution constant(double value) {
        return new Distribution(Kind.CONSTANT, value, 0, value, value);
    }

    /** Gleichverteilt in [min, max]. */
    public static Distribution uniform(double min, double max) {
        if (!(max >= min)) throw new IllegalArgumentException("Gleichverteilung: max < min");
        return new Distribution(Kind.UNIFORM, min, max, min, max);
    }

    /** Normalverteilt, auf [min, max] begrenzt (Werte außerhalb werden neu gezogen). */
    public static Distribution normal(double mean, double sigma, double min, double max) {
        if (!(sigma >= 0 && max > min && mean >= min && mean <= max)) throw new IllegalArgumentException("Normalverteilung unplausibel");
        return new Distribution(Kind.NORMAL, mean, sigma, min, max);
    }

    public double sample(Random r) {
        return switch (kind) {
            case CONSTANT -> a;
            case UNIFORM -> a + (b - a) * r.nextDouble();
            case NORMAL -> {
                for (int i = 0; i < 1_000; i++) {
                    double v = a + b * r.nextGaussian();
                    if (v >= min && v <= max) yield v;
                }
                yield a;
            }
        };
    }

    /** Erwartungswert (bei begrenzter Normalverteilung der Mittelwert vor der Begrenzung). */
    public double mean() {
        return switch (kind) {
            case CONSTANT, NORMAL -> a;
            case UNIFORM -> (a + b) / 2;
        };
    }

    /** Standardabweichung (bei begrenzter Normalverteilung die vor der Begrenzung, also eher zu groß). */
    public double sigma() {
        return switch (kind) {
            case CONSTANT -> 0;
            case UNIFORM -> (b - a) / Math.sqrt(12);
            case NORMAL -> b;
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case CONSTANT -> String.format(Locale.ROOT, "konstant %.3f", a);
            case UNIFORM -> String.format(Locale.ROOT, "gleichverteilt %.3f–%.3f", a, b);
            case NORMAL -> String.format(Locale.ROOT, "normal %.3f ± %.3f (begrenzt %.3f–%.3f)", a, b, min, max);
        };
    }
}
