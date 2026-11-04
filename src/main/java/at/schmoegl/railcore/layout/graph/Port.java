package at.schmoegl.railcore.layout.graph;

/**
 * Anschluss eines Gleisstücks bzw. eines Knotens. Welche Anschlüsse es gibt, hängt von der Art ab:
 * <ul>
 *   <li>gerades Stück, Bogen, Stoß/Trennstelle, Gleisende: {@link #A}, {@link #B} (Gleisende nur {@code A})</li>
 *   <li>Weiche: {@link #TIP} (Spitze), {@link #STRAIGHT} (gerader Strang), {@link #DIVERGING} (Abzweig)</li>
 *   <li>Kreuzung: Strang A von {@link #A1} nach {@link #A2}, Strang B von {@link #B1} nach {@link #B2}</li>
 *   <li>Doppelkreuzungsweiche: außen {@link #A1}, {@link #A2} (Seite A) und {@link #B1}, {@link #B2} (Seite B);
 *       innen je Fahrweg ein Anschluss {@link #A1_B1}, {@link #A1_B2}, {@link #A2_B1}, {@link #A2_B2}</li>
 * </ul>
 */
public enum Port {
    A, B,
    TIP, STRAIGHT, DIVERGING,
    A1, A2, B1, B2,
    A1_B1, A1_B2, A2_B1, A2_B2;

    /** Innenanschluss eines Fahrwegs der Doppelkreuzungsweiche von {@code a} (A1/A2) nach {@code b} (B1/B2). */
    public static Port route(Port a, Port b) {
        if (a == A1 && b == B1) return A1_B1;
        if (a == A1 && b == B2) return A1_B2;
        if (a == A2 && b == B1) return A2_B1;
        if (a == A2 && b == B2) return A2_B2;
        throw new IllegalArgumentException("Kein Fahrweg der Doppelkreuzungsweiche: " + a + "–" + b);
    }

    public boolean isDoubleSlipRoute() {
        return this == A1_B1 || this == A1_B2 || this == A2_B1 || this == A2_B2;
    }

    /** Außenanschluss auf Seite A eines Fahrwegs der Doppelkreuzungsweiche. */
    public Port routeSideA() {
        return switch (this) {
            case A1_B1, A1_B2 -> A1;
            case A2_B1, A2_B2 -> A2;
            default -> throw new IllegalStateException("Kein Fahrweg der Doppelkreuzungsweiche: " + this);
        };
    }

    /** Außenanschluss auf Seite B eines Fahrwegs der Doppelkreuzungsweiche. */
    public Port routeSideB() {
        return switch (this) {
            case A1_B1, A2_B1 -> B1;
            case A1_B2, A2_B2 -> B2;
            default -> throw new IllegalStateException("Kein Fahrweg der Doppelkreuzungsweiche: " + this);
        };
    }
}
