package at.schmoegl.railcore.safety;

/**
 * Zustand eines Meldeabschnitts aus Sicht der Steuerung. Dreiwertig: „unbekannt“ ist der Startwert
 * (Grundsatz 5) und gilt nach Verbindungsverlust; er wird nie als frei behandelt (Grundsatz 1).
 */
public enum SensorState {
    UNKNOWN, FREE, OCCUPIED;

    /** Nur ein ausdrücklich als frei gemeldeter Abschnitt gilt als frei. */
    public boolean isFree() { return this == FREE; }

    /** Für Sicherheitsentscheidungen: unbekannt wird wie belegt behandelt. */
    public boolean treatAsOccupied() { return this != FREE; }

    public String german() {
        return switch (this) {
            case UNKNOWN -> "unbekannt";
            case FREE -> "frei";
            case OCCUPIED -> "belegt";
        };
    }
}
