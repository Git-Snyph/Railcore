package at.schmoegl.railcore.layout.graph;

/** Art eines Knotens im Gleisgraph. */
public enum NodeKind {
    /** Gleisende oder Prellbock: keine Fortsetzung. */
    BUFFER_STOP,
    /** Stoß zwischen zwei Gleisstücken, elektrisch durchverbunden. */
    JOINT,
    /** Trennstelle: Stoß mit elektrischer Trennung (Grenze von Meldeabschnitten, ab B2). */
    ISOLATING_JOINT,
    /** Einfache Weiche, Knoten an der Spitze; Fortsetzung hängt von der Lage ab. */
    TURNOUT,
    /** Kreuzung: zwei feste Paare, kein Übergang zwischen den Strängen. */
    CROSSING,
    /** Eine Seite einer Doppelkreuzungsweiche (zwei gekoppelte Weichenhälften). */
    DOUBLE_SLIP
}
