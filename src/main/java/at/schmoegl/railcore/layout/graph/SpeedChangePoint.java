package at.schmoegl.railcore.layout.graph;

/**
 * Geschwindigkeitswechselpunkt (Lastenheft Kapitel 4): ab hier gilt für Züge in Fahrtrichtung {@code at} die
 * Höchstgeschwindigkeit {@code speedKmh} (Vorbild-km/h), bezogen auf Zugspitze oder Zugschluss.
 */
public record SpeedChangePoint(String id, DirectedLocation at, double speedKmh, Reference reference) {

    /** Welches Zugende den Wechsel auslöst. */
    public enum Reference {
        /** gilt, sobald die Zugspitze den Punkt erreicht (typisch: Absenkung) */
        HEAD,
        /** gilt erst, wenn der Zugschluss den Punkt passiert hat (typisch: Anhebung) */
        TAIL
    }

    public SpeedChangePoint {
        LayoutIds.require(id, "Geschwindigkeitswechselpunkt");
        if (at == null || reference == null)
            throw new IllegalArgumentException("Geschwindigkeitswechselpunkt " + id + ": Lage oder Bezug fehlt");
        if (!(speedKmh > 0) || !Double.isFinite(speedKmh))
            throw new IllegalArgumentException("Geschwindigkeitswechselpunkt " + id + ": ungültige Geschwindigkeit " + speedKmh);
    }
}
