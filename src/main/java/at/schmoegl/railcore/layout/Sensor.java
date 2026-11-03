package at.schmoegl.railcore.layout;

/**
 * Rückmelder in einem Block. Belegtmelder decken einen Abschnitt ab (fromMm..toMm), Kontaktgleise einen Punkt
 * (fromMm == toMm). Koordinaten ab Blockende A. Positionen können durch die Anlagenvermessung ermittelt werden.
 */
public record Sensor(int contact, double fromMm, double toMm) {
    public Sensor {
        if (toMm < fromMm) throw new IllegalArgumentException("Rückmelder " + contact + ": 'to' < 'from'");
    }
}
