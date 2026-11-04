package at.schmoegl.railcore.layout.graph;

/**
 * Ort im Gleisgraph: Kante plus mm ab dem Kantenanfang (Bezugsrichtung der Kante). Ob die Angabe innerhalb der
 * Kantenlänge liegt, prüft {@link TrackGraph#requireLocation}.
 */
public record TrackLocation(String edgeId, double offsetMm) {
    public TrackLocation {
        if (edgeId == null || edgeId.isBlank()) throw new IllegalArgumentException("Ort ohne Kante");
        if (!Double.isFinite(offsetMm) || offsetMm < 0)
            throw new IllegalArgumentException("Ort auf " + edgeId + ": ungültiger Abstand " + offsetMm);
    }
}
