package at.schmoegl.bahnsteuerung.core;

/**
 * Lage einer Weiche. STRAIGHT entspricht Ausgang 1 (DCC "P=0"), DIVERGING Ausgang 2 (DCC "P=1").
 * Ist eine Weiche andersherum angeschlossen, wird das im Gleisplan mit "inverted" ausgeglichen.
 */
public enum TurnoutPosition {
    STRAIGHT, DIVERGING, UNKNOWN;

    public TurnoutPosition inverted() {
        return switch (this) {
            case STRAIGHT -> DIVERGING;
            case DIVERGING -> STRAIGHT;
            case UNKNOWN -> UNKNOWN;
        };
    }
}
