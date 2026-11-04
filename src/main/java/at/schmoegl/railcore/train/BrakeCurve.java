package at.schmoegl.railcore.train;

/**
 * Reine Rechenfunktionen der Bremskurven (Wegkoordinate in mm, Geschwindigkeit in mm/s, Verzögerung in mm/s²).
 * Keine Zustände, keine Zeitquelle.
 */
public final class BrakeCurve {

    private BrakeCurve() {}

    /**
     * Höchstgeschwindigkeit an Stelle {@code x}, damit der Zug bei {@code curveEnd} genau {@code endSpeed} erreicht
     * (Verzögerung {@code decel}); ab {@code curveEnd} gilt {@code endSpeed}.
     */
    public static double towards(double x, double curveEnd, double endSpeed, double decel) {
        if (x >= curveEnd) return endSpeed;
        return Math.sqrt(endSpeed * endSpeed + 2 * decel * (curveEnd - x));
    }

    /** Stelle, ab der die Kurve {@link #towards} unter {@code v} fällt. */
    public static double pointWhere(double v, double curveEnd, double endSpeed, double decel) {
        if (v <= endSpeed) return Double.POSITIVE_INFINITY;
        return curveEnd - (v * v - endSpeed * endSpeed) / (2 * decel);
    }

    /**
     * Überwachungsgeschwindigkeit: größte Geschwindigkeit v, bei der Reaktionsweg (v · reactionSeconds) plus Bremsweg
     * (v² / 2·decel) in {@code remainingMm} passt. Kein Platz mehr → 0.
     */
    public static double supervision(double remainingMm, double decel, double reactionSeconds) {
        if (remainingMm <= 0) return 0;
        double br = decel * reactionSeconds;
        return -br + Math.sqrt(br * br + 2 * decel * remainingMm);
    }

    /** Weg, den {@link #supervision} für Geschwindigkeit v braucht. */
    public static double supervisionDistance(double v, double decel, double reactionSeconds) {
        return v * reactionSeconds + v * v / (2 * decel);
    }
}
