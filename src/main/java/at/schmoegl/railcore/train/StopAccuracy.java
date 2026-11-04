package at.schmoegl.railcore.train;

import java.util.Locale;
import java.util.Optional;

/**
 * Erwartete Haltegenauigkeit eines Bahnsteig-Haltepunkts (Entscheidung 24). Ohne Annahme über Anzahl oder Lage der
 * Melder: maßgeblich ist der letzte Meldepunkt vor dem Haltepunkt (ohne Meldepunkt der Startabgleich). Unabhängige
 * Fehlerquellen als Standardabweichungen (mm), quadratisch addiert:
 * <ul>
 *   <li>{@code sigmaSensorMm} – Messstreuung des Melders (bzw. Unsicherheit der Startposition)</li>
 *   <li>{@code sigmaSensorDelayMm} – Streuung der Melderverzögerung × Überfahrtempo laut geplanter Bremskurve</li>
 *   <li>{@code sigmaOdometryMm} – Abweichung im Fahrprofil × Strecke vom Meldepunkt bis zum Haltepunkt</li>
 *   <li>{@code sigmaCommandMm} – Streuung der Befehlsverzögerung × Geschwindigkeitssprünge (Bremsstufen, Halt)</li>
 *   <li>{@code sigmaRunoutMm} – Streuung des Auslaufwegs aus dem Annäherungstempo</li>
 * </ul>
 * Erwartete Genauigkeit (95 %) = 1,96 · σ.
 */
public record StopAccuracy(String stopPointId, double targetToleranceMm, double expectedToleranceMm, double sigmaMm,
                           int fixContact, double fixPositionMm, double fixDistanceMm, double passSpeedMmS,
                           double sigmaSensorMm, double sigmaSensorDelayMm, double sigmaOdometryMm,
                           double sigmaCommandMm, double sigmaRunoutMm) {

    /** Faktor für 95 % bei Normalverteilung. */
    public static final double Z95 = 1.96;

    /** Kein Meldepunkt vor dem Haltepunkt: Abgleich ist die Startposition. */
    public static final int NO_CONTACT = -1;

    /** Fehlerquelle mit dem größten Anteil. */
    public enum Source {
        SENSOR_SPREAD("Messstreuung des Melders"),
        SENSOR_DELAY("Streuung der Melderverzögerung beim Überfahren"),
        ODOMETRY("Strecke seit dem Meldepunkt"),
        COMMAND_DELAY("Streuung der Befehlsverzögerung"),
        RUNOUT("Streuung des Auslaufwegs");

        private final String german;

        Source(String german) { this.german = german; }

        public String german() { return german; }
    }

    public static StopAccuracy of(String stopPointId, double targetToleranceMm, int fixContact, double fixPositionMm,
                                  double fixDistanceMm, double passSpeedMmS, double sigmaSensorMm,
                                  double sigmaSensorDelayMm, double sigmaOdometryMm, double sigmaCommandMm,
                                  double sigmaRunoutMm) {
        double sigma = Math.sqrt(sq(sigmaSensorMm) + sq(sigmaSensorDelayMm) + sq(sigmaOdometryMm)
                + sq(sigmaCommandMm) + sq(sigmaRunoutMm));
        return new StopAccuracy(stopPointId, targetToleranceMm, Z95 * sigma, sigma, fixContact, fixPositionMm,
                fixDistanceMm, passSpeedMmS, sigmaSensorMm, sigmaSensorDelayMm, sigmaOdometryMm, sigmaCommandMm,
                sigmaRunoutMm);
    }

    /** Ist die Zielgenauigkeit (Standard ±10 mm) erreichbar? */
    public boolean reachable() { return expectedToleranceMm <= targetToleranceMm; }

    public Source dominantSource() {
        Source best = Source.SENSOR_SPREAD;
        double max = sigmaSensorMm;
        if (sigmaSensorDelayMm > max) { max = sigmaSensorDelayMm; best = Source.SENSOR_DELAY; }
        if (sigmaOdometryMm > max) { max = sigmaOdometryMm; best = Source.ODOMETRY; }
        if (sigmaCommandMm > max) { max = sigmaCommandMm; best = Source.COMMAND_DELAY; }
        if (sigmaRunoutMm > max) { best = Source.RUNOUT; }
        return best;
    }

    /** Hinweis, wenn die Zielgenauigkeit nicht erreichbar ist (Anzeige später in RailCore Studio). */
    public Optional<AccuracyNotice> notice() {
        if (reachable()) return Optional.empty();
        Source d = dominantSource();
        String hint = switch (d) {
            case ODOMETRY -> "Meldepunkt näher am Haltepunkt würde helfen";
            case SENSOR_DELAY -> "langsameres Überfahren oder Meldepunkt näher am Haltepunkt würde helfen";
            case SENSOR_SPREAD -> fixContact == NO_CONTACT ? "Meldepunkt vor dem Haltepunkt würde helfen"
                    : "genauerer Melder (geringere Messstreuung) würde helfen";
            case COMMAND_DELAY -> "geringere Befehlsverzögerung (Last der Zentrale) würde helfen";
            case RUNOUT -> "Auslaufweg der Lok neu einmessen";
        };
        int tol = (int) Math.ceil(expectedToleranceMm);
        String text = "erwartet ±" + tol + " mm – " + hint;
        return Optional.of(new AccuracyNotice(stopPointId, expectedToleranceMm, targetToleranceMm, d, text));
    }

    public String format() {
        return String.format(Locale.ROOT, "Haltepunkt %s: erwartet ±%.1f mm (Ziel ±%.0f mm), σ %.2f mm; Meldepunkt %s, "
                        + "%.0f mm davor, Überfahrt %.0f mm/s; σ Melder %.2f, Verzögerung %.2f, Strecke %.2f, Befehl %.2f, Auslauf %.2f",
                stopPointId, expectedToleranceMm, targetToleranceMm, sigmaMm,
                fixContact == NO_CONTACT ? "keiner (Start)" : String.valueOf(fixContact), fixDistanceMm, passSpeedMmS,
                sigmaSensorMm, sigmaSensorDelayMm, sigmaOdometryMm, sigmaCommandMm, sigmaRunoutMm);
    }

    private static double sq(double v) { return v * v; }
}
