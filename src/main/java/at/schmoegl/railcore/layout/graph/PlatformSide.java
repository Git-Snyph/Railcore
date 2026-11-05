package at.schmoegl.railcore.layout.graph;

/**
 * Bahnsteigseite (Entscheidung 46, Option A): auf welcher Seite des Gleises ein Bahnsteig liegt, gesehen in einer
 * bestimmten Fahrtrichtung – beim {@link Block} in Richtung A → B, beim {@link StopPoint} in der Fahrtrichtung des
 * Haltepunkts. Reine Angabe im Gleisplan; ausgewertet wird sie erst mit der Gleiswahl (Meilenstein 4).
 */
public enum PlatformSide {
    /** keine Angabe (Standard): die Angabe schränkt nichts ein */
    UNSPECIFIED,
    /** ausdrücklich kein Bahnsteig */
    NONE,
    LEFT,
    RIGHT,
    BOTH;

    /** Dieselbe Seite, gesehen in der Gegenrichtung (links und rechts tauschen). */
    public PlatformSide mirrored() {
        return switch (this) {
            case LEFT -> RIGHT;
            case RIGHT -> LEFT;
            default -> this;
        };
    }
}
