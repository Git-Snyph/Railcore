package at.schmoegl.railcore.control;

/**
 * Bestätigungszustand eines Zuges. Nach Start und nach Verbindungsverlust ist jeder Zug „unbestätigt“
 * (Grundsatz 5); bestätigt wird er erst durch Abgleich mit Rückmeldern oder RailCom (Positionsverfolgung, folgt).
 */
public enum TrainStatus {
    UNCONFIRMED, CONFIRMED;

    public String german() { return this == UNCONFIRMED ? "unbestätigt" : "bestätigt"; }
}
