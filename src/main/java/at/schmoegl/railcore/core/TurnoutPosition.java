package at.schmoegl.railcore.core;

/**
 * Lage einer Weiche nach RCN-213: STRAIGHT = gerade/grün (DCC "P=1", Z21-Rückmeldung ZZ=10),
 * DIVERGING = abzweigend/rot (DCC "P=0", ZZ=01). An der echten Z21 mit 10836 bestätigt (Prüfprogramm, 04.11.2026).
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
