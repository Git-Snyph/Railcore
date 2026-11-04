package at.schmoegl.railcore.train;

import java.util.Objects;

/**
 * Parameter der Positionsunsicherheit eines Zuges (Lastenheft Kapitel 6, „Abgleich und Unsicherheit“). Die
 * Unsicherheit wächst mit der Strecke seit dem letzten Abgleich; Startwert 1 % der Strecke + 3 mm.
 * <ul>
 *   <li>{@code distanceFraction} – Abweichung im Fahrprofil als Anteil der seit dem Abgleich gefahrenen Strecke
 *       (Standardabweichung, Startwert 0,01 = 1 %)</li>
 *   <li>{@code sensorSpreadMm} – Messstreuung des Melders am Meldepunkt (Standardabweichung, Startwert 3 mm). Die
 *       Streuung der Melder<b>verzögerung</b> kommt geschwindigkeitsabhängig hinzu (siehe {@link MeasurementPoint}).</li>
 * </ul>
 * Die Startwerte gelten nur bis zu echten Messungen. Sie sind je Zug überschreibbar
 * ({@link SpeedProfile#setUncertainty}) und werden später durch aus der Statistik gelernte Werte ersetzt
 * ({@link Source#LEARNED}); nichts davon ist in der Rechnung fest einprogrammiert.
 */
public record PositionUncertainty(double distanceFraction, double sensorSpreadMm, Source source) {

    /** Herkunft der Werte. */
    public enum Source { START_VALUE, CONFIGURED, LEARNED }

    public static final double START_DISTANCE_FRACTION = 0.01;
    public static final double START_SENSOR_SPREAD_MM = 3.0;

    public PositionUncertainty {
        Objects.requireNonNull(source);
        if (!(distanceFraction >= 0 && distanceFraction <= 0.5))
            throw new IllegalArgumentException("Streckenanteil der Unsicherheit unplausibel: " + distanceFraction);
        if (!(sensorSpreadMm >= 0 && sensorSpreadMm <= 500))
            throw new IllegalArgumentException("Messstreuung des Melders unplausibel: " + sensorSpreadMm);
    }

    /** Startwerte bis zu echten Messungen: 1 % der Strecke, 3 mm Melder-Messstreuung. */
    public static PositionUncertainty startValues() {
        return new PositionUncertainty(START_DISTANCE_FRACTION, START_SENSOR_SPREAD_MM, Source.START_VALUE);
    }

    /** Von Hand eingestellte Werte (je Zug). */
    public static PositionUncertainty configured(double distanceFraction, double sensorSpreadMm) {
        return new PositionUncertainty(distanceFraction, sensorSpreadMm, Source.CONFIGURED);
    }

    /** Aus der Statistik des Zuges gelernte Werte (vorgesehen für die laufende Nachmessung). */
    public static PositionUncertainty learned(double distanceFraction, double sensorSpreadMm) {
        return new PositionUncertainty(distanceFraction, sensorSpreadMm, Source.LEARNED);
    }

    /** Standardabweichung aus dem Fahrprofil nach {@code distanceMm} seit dem Abgleich. */
    public double odometrySigmaMm(double distanceMm) { return distanceFraction * Math.max(0, distanceMm); }

    /** Unsicherheitsband wie im Lastenheft (linear addiert): Streckenanteil · Strecke + Melder-Messstreuung. */
    public double bandMm(double distanceMm) { return odometrySigmaMm(distanceMm) + sensorSpreadMm; }
}
