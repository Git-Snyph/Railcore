package at.schmoegl.railcore.train;

import java.util.Objects;

/**
 * Meldepunkt auf dem Fahrweg: Rückmelder (Kontakt), dessen Ansprechen die Zugspitze an {@code positionMm} der
 * Wegkoordinate anzeigt. Bei Belegtmeldern ist das der Beginn des Meldeabschnitts, bezogen auf die erste
 * stromabnehmende Achse; deren Abstand zur Zugspitze ist in {@code positionMm} bereits eingerechnet.
 * {@code delay} ist die eingemessene Ansprechverzögerung bis zum Empfang in der Steuerung (Mittel und Streuung).
 */
public record MeasurementPoint(int contact, double positionMm, DelayStats delay) {
    public MeasurementPoint {
        Objects.requireNonNull(delay);
        if (!Double.isFinite(positionMm)) throw new IllegalArgumentException("Position des Meldepunkts fehlt");
    }
}
