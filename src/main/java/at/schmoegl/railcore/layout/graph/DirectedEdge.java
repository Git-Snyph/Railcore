package at.schmoegl.railcore.layout.graph;

/**
 * Kante mit Fahrrichtung: {@code forward = true} in Bezugsrichtung (von → nach), sonst dagegen.
 * „Weg auf der Kante“ meint immer mm ab dem Anfang in Fahrrichtung.
 */
public record DirectedEdge(TrackEdge edge, boolean forward) {

    public DirectedEdge {
        if (edge == null) throw new IllegalArgumentException("Kante fehlt");
    }

    public String edgeId() { return edge.id(); }

    public double lengthMm() { return edge.lengthMm(); }

    public String startNode() { return forward ? edge.fromNode() : edge.toNode(); }

    public Port startPort() { return forward ? edge.fromPort() : edge.toPort(); }

    public String endNode() { return forward ? edge.toNode() : edge.fromNode(); }

    public Port endPort() { return forward ? edge.toPort() : edge.fromPort(); }

    public DirectedEdge reversed() { return new DirectedEdge(edge, !forward); }

    /** Umrechnung „mm in Fahrrichtung“ → „mm in Bezugsrichtung der Kante“ (und zurück, die Abbildung ist symmetrisch). */
    public double toEdgeOffset(double alongMm) { return forward ? alongMm : edge.lengthMm() - alongMm; }

    /** Ort auf der Kante, {@code alongMm} hinter dem Anfang in Fahrrichtung. */
    public TrackLocation locationAt(double alongMm) { return new TrackLocation(edge.id(), toEdgeOffset(alongMm)); }

    @Override
    public String toString() { return edge.id() + (forward ? "→" : "←"); }
}
