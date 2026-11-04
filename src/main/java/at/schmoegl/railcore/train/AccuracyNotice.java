package at.schmoegl.railcore.train;

import java.util.Objects;

/**
 * Hinweis „Zielgenauigkeit nicht erreichbar“ für einen Haltepunkt (Entscheidung 24). Reines Datenobjekt; die
 * Anzeige folgt später in RailCore Studio. Der Halt funktioniert trotzdem, nur weniger genau.
 *
 * @param text  z. B. „erwartet ±17 mm – Meldepunkt näher am Haltepunkt würde helfen“
 */
public record AccuracyNotice(String stopPointId, double expectedToleranceMm, double targetToleranceMm,
                             StopAccuracy.Source dominantSource, String text) {
    public AccuracyNotice {
        Objects.requireNonNull(stopPointId);
        Objects.requireNonNull(dominantSource);
        Objects.requireNonNull(text);
    }
}
