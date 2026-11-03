package at.schmoegl.railcore.core;

/** Fahrtrichtung aus Sicht des Decoders. */
public enum Direction {
    FORWARD, REVERSE;

    public Direction opposite() { return this == FORWARD ? REVERSE : FORWARD; }
}
