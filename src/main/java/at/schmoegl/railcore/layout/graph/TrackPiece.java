package at.schmoegl.railcore.layout.graph;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Physisches Gleisstück als Katalogeintrag (Entscheidung 40/E2): Art, Geometrie und Anschlüsse. Aus den verlegten
 * Gleisstücken berechnet {@link TrackGraph.Builder} Knoten und Kanten.
 *
 * <p>Maße kommen immer aus dem Katalog bzw. der Konfiguration; im Code stehen keine Herstellermaße.
 * Neue Arten (z. B. Dreiwegweiche, Drehscheibe) werden als weitere Variante ergänzt.
 */
public sealed interface TrackPiece
        permits TrackPiece.PlainPiece, TrackPiece.TurnoutPiece, TrackPiece.CrossingPiece, TrackPiece.DoubleSlipPiece {

    /** Typbezeichnung im Katalog, z. B. Artikelnummer oder „Flex 345“. */
    String id();

    /** Anschlüsse, an denen das Stück mit anderen verbunden wird. */
    List<Port> ports();

    static PlainPiece plain(String id, TrackGeometry geometry) { return new PlainPiece(id, geometry); }

    /** Gerades Stück, gebogenes Stück oder Flexgleis: ein Strang von {@code A} nach {@code B}. */
    record PlainPiece(String id, TrackGeometry geometry) implements TrackPiece {
        public PlainPiece {
            requireId(id);
            if (geometry == null) throw new IllegalArgumentException("Gleisstück " + id + ": Geometrie fehlt");
        }

        public double lengthMm() { return geometry.lengthMm(); }

        @Override public List<Port> ports() { return List.of(Port.A, Port.B); }
    }

    /** Bauform einer einfachen Weiche (nur zur Beschreibung, für die Längen ohne Bedeutung). */
    enum Hand { LEFT, RIGHT, SYMMETRIC }

    /**
     * Einfache Weiche: Spitze {@code TIP}, gerader Strang {@code STRAIGHT}, Abzweig {@code DIVERGING}, jeder Strang
     * mit eigener Geometrie (Länge ab Spitze). Grenzzeichen je Schenkel in mm ab Spitze sind Pflichtangaben.
     * Bei einer Y-Weiche ist „gerade“ der Strang, den die Lage {@code STRAIGHT} einstellt.
     */
    record TurnoutPiece(String id, Hand hand, TrackGeometry straight, TrackGeometry diverging,
                        double straightClearanceMm, double divergingClearanceMm) implements TrackPiece {
        public TurnoutPiece {
            requireId(id);
            if (hand == null || straight == null || diverging == null)
                throw new IllegalArgumentException("Weiche " + id + ": Bauform oder Geometrie fehlt");
            branches(straight, diverging, straightClearanceMm, divergingClearanceMm);
        }

        /** Aus der Geometrie berechnete Zweiglängen mit den Grenzzeichen. */
        public TurnoutBranches branches() {
            return branches(straight, diverging, straightClearanceMm, divergingClearanceMm);
        }

        private static TurnoutBranches branches(TrackGeometry s, TrackGeometry d, double cs, double cd) {
            return new TurnoutBranches(s.lengthMm(), d.lengthMm(), cs, cd);
        }

        @Override public List<Port> ports() { return List.of(Port.TIP, Port.STRAIGHT, Port.DIVERGING); }
    }

    /**
     * Kreuzung: zwei Stränge A1–A2 und B1–B2, die sich in einem Punkt schneiden; kein Übergang zwischen den
     * Strängen. Der Kreuzungspunkt liegt {@code crossingFromA1Mm} hinter A1 bzw. {@code crossingFromB1Mm} hinter B1.
     */
    record CrossingPiece(String id, TrackGeometry lineA, TrackGeometry lineB,
                         double crossingFromA1Mm, double crossingFromB1Mm) implements TrackPiece {
        public CrossingPiece {
            requireId(id);
            if (lineA == null || lineB == null) throw new IllegalArgumentException("Kreuzung " + id + ": Geometrie fehlt");
            requireInside(crossingFromA1Mm, lineA.lengthMm(), id, "A");
            requireInside(crossingFromB1Mm, lineB.lengthMm(), id, "B");
        }

        /** Kreuzungspunkt jeweils in der Mitte der Stränge. */
        public static CrossingPiece symmetric(String id, TrackGeometry lineA, TrackGeometry lineB) {
            return new CrossingPiece(id, lineA, lineB, lineA.lengthMm() / 2, lineB.lengthMm() / 2);
        }

        private static void requireInside(double at, double length, String id, String line) {
            if (!(at > 0) || !(at < length))
                throw new IllegalArgumentException("Kreuzung " + id + ": Kreuzungspunkt auf Strang " + line
                        + " muss zwischen 0 und " + length + " mm liegen: " + at);
        }

        @Override public List<Port> ports() { return List.of(Port.A1, Port.A2, Port.B1, Port.B2); }
    }

    /**
     * Doppelkreuzungsweiche: Außenanschlüsse A1, A2 (Seite A) und B1, B2 (Seite B), vier Fahrwege mit eigener
     * Geometrie, gestellt über zwei gekoppelte Weichenhälften (Seite A, Seite B) nach {@link DoubleSlipLogic}.
     * Grenzzeichen sind hier noch nicht erfasst (offen für B2).
     */
    record DoubleSlipPiece(String id, Map<Port, TrackGeometry> routes, DoubleSlipLogic logic) implements TrackPiece {
        public DoubleSlipPiece {
            requireId(id);
            if (logic == null) throw new IllegalArgumentException("Doppelkreuzungsweiche " + id + ": Stelllogik fehlt");
            Map<Port, TrackGeometry> copy = new EnumMap<>(Port.class);
            copy.putAll(routes);
            for (Port r : copy.keySet())
                if (!r.isDoubleSlipRoute())
                    throw new IllegalArgumentException("Doppelkreuzungsweiche " + id + ": kein Fahrweg " + r);
            if (copy.size() != 4)
                throw new IllegalArgumentException("Doppelkreuzungsweiche " + id + ": alle vier Fahrwege brauchen eine Geometrie");
            routes = Map.copyOf(copy);
        }

        public double lengthMm(Port route) {
            TrackGeometry g = routes.get(route);
            if (g == null) throw new IllegalArgumentException("Kein Fahrweg der Doppelkreuzungsweiche: " + route);
            return g.lengthMm();
        }

        @Override public List<Port> ports() { return List.of(Port.A1, Port.A2, Port.B1, Port.B2); }
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Gleisstück ohne Typbezeichnung");
    }
}
