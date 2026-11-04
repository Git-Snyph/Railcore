package at.schmoegl.railcore.train;

/**
 * Eingemessene Verzögerung (Mittelwert und Standardabweichung in ms), z. B. Befehlsverzögerung bis zur Wirkung am
 * Decoder oder Ansprechverzögerung eines Melders bis zum Empfang. Die Steuerung kennt nur diese Statistik, nie den
 * Einzelwert eines Ereignisses.
 */
public record DelayStats(double meanMillis, double sigmaMillis) {

    public DelayStats {
        if (!(meanMillis >= 0 && meanMillis <= 10_000)) throw new IllegalArgumentException("Mittlere Verzögerung unplausibel: " + meanMillis);
        if (!(sigmaMillis >= 0 && sigmaMillis <= 10_000)) throw new IllegalArgumentException("Streuung der Verzögerung unplausibel: " + sigmaMillis);
    }

    public long meanNanos() { return Math.round(meanMillis * 1e6); }

    public double meanSeconds() { return meanMillis / 1e3; }

    public double sigmaSeconds() { return sigmaMillis / 1e3; }

    /** Obere Abschätzung (Mittel + k·Streuung) in Sekunden. */
    public double upperSeconds(double k) { return (meanMillis + k * sigmaMillis) / 1e3; }
}
