package at.schmoegl.railcore.layout.graph;

/** Bereich auf einer Kante, in mm ab dem Kantenanfang (Bezugsrichtung), {@code fromMm ≤ toMm}. */
public record TrackRange(String edgeId, double fromMm, double toMm) {
    public TrackRange {
        if (edgeId == null || edgeId.isBlank()) throw new IllegalArgumentException("Bereich ohne Kante");
        if (!Double.isFinite(fromMm) || !Double.isFinite(toMm) || fromMm < 0 || toMm < fromMm)
            throw new IllegalArgumentException("Bereich auf " + edgeId + ": ungültig " + fromMm + "–" + toMm);
    }

    public double lengthMm() { return toMm - fromMm; }

    public boolean contains(TrackLocation location) {
        return location.edgeId().equals(edgeId) && location.offsetMm() >= fromMm && location.offsetMm() <= toMm;
    }
}
