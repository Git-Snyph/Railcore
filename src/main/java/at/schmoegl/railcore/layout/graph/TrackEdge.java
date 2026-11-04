package at.schmoegl.railcore.layout.graph;

/**
 * Kante im Gleisgraph: ein Strang zwischen zwei Knotenanschlüssen mit Länge in mm. Die Richtung „von → nach“ ist
 * nur die Bezugsrichtung für die mm-Angaben ({@link TrackLocation}), sie hat keine betriebliche Bedeutung; eine
 * globale Ausrichtung gibt es nicht. Befahren wird eine Kante als {@link DirectedEdge}.
 *
 * @param pieceInstanceId verlegtes Gleisstück, aus dem die Kante stammt ({@code null} bei Verbindungskanten)
 */
public record TrackEdge(String id, String fromNode, Port fromPort, String toNode, Port toPort, double lengthMm,
                        String pieceInstanceId, Role role) {

    /** Herkunft der Kante. */
    public enum Role {
        /** gerades oder gebogenes Gleisstück */
        PLAIN,
        /** gerader Strang einer Weiche (Spitze → Ende) */
        TURNOUT_STRAIGHT,
        /** Abzweig einer Weiche (Spitze → Ende) */
        TURNOUT_DIVERGING,
        /** halber Strang einer Kreuzung (Kreuzungspunkt → Anschluss) */
        CROSSING_HALF,
        /** Fahrweg einer Doppelkreuzungsweiche (Seite A → Seite B) */
        DOUBLE_SLIP_ROUTE,
        /** Verbindung ohne Länge zwischen zwei Knoten, die unmittelbar aneinanderstoßen */
        CONNECTOR
    }

    public TrackEdge {
        if (id == null || fromNode == null || fromPort == null || toNode == null || toPort == null || role == null)
            throw new IllegalArgumentException("Kante unvollständig: " + id);
        if (!Double.isFinite(lengthMm) || lengthMm < 0)
            throw new IllegalArgumentException("Kante " + id + ": ungültige Länge " + lengthMm);
        if (role == Role.CONNECTOR ? lengthMm != 0 : lengthMm <= 0)
            throw new IllegalArgumentException("Kante " + id + ": Länge " + lengthMm + " passt nicht zur Art " + role);
    }

    public DirectedEdge forward() { return new DirectedEdge(this, true); }

    public DirectedEdge backward() { return new DirectedEdge(this, false); }
}
