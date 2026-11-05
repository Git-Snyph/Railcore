package at.schmoegl.railcore.layout.graph;

/**
 * Ort im Gleisgraph mit Fahrtrichtung: gilt für Züge, die die Kante in Bezugsrichtung ({@code forward = true}) bzw.
 * dagegen befahren. Grundlage für alle richtungsabhängigen Auflagen (Meldepunkt, Haltepunkt,
 * Geschwindigkeitswechselpunkt, Blockende).
 */
public record DirectedLocation(TrackLocation location, boolean forward) {

    public DirectedLocation {
        if (location == null) throw new IllegalArgumentException("Ort fehlt");
    }

    public static DirectedLocation of(String edgeId, double offsetMm, boolean forward) {
        return new DirectedLocation(new TrackLocation(edgeId, offsetMm), forward);
    }

    public String edgeId() { return location.edgeId(); }

    public double offsetMm() { return location.offsetMm(); }

    /** Derselbe Ort in Gegenrichtung. */
    public DirectedLocation reversed() { return new DirectedLocation(location, !forward); }

    /** Gleicher Ort und gleiche Richtung, Abstand bis {@code toleranceMm}. */
    public boolean sameAs(DirectedLocation other, double toleranceMm) {
        return other != null && forward == other.forward && edgeId().equals(other.edgeId())
                && Math.abs(offsetMm() - other.offsetMm()) <= toleranceMm;
    }

    @Override
    public String toString() { return edgeId() + "@" + offsetMm() + (forward ? "→" : "←"); }
}
