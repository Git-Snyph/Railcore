package at.schmoegl.railcore.layout.graph;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Physisches Gleisstück als Katalogeintrag (Entscheidung 40/E2, 41): Art, Maße und Anschlüsse. Aus den verlegten
 * Gleisstücken berechnet {@link TrackGraph.Builder} Knoten und Kanten.
 *
 * <p>Gerade Stücke und Bögen: Länge aus dem Katalogeintrag, beim Verlegen optional überschreibbar (Entscheidung 42).
 * Weichen, Doppelkreuzungsweichen und Kreuzungen: maßgeblich sind die je verlegtem Stück eingetragenen Werte, der
 * Typ liefert nur einen Vorschlag (Entscheidungen 41, 42).
 * Im Code stehen keine Herstellermaße.
 * Neue Arten (z. B. Dreiwegweiche, Drehscheibe) werden als weitere Variante ergänzt.
 */
public sealed interface TrackPiece
        permits TrackPiece.PlainPiece, TrackPiece.TurnoutPiece, TrackPiece.CrossingPiece, TrackPiece.DoubleSlipPiece {

    /** Typbezeichnung im Katalog, z. B. Artikelnummer oder „Flex 345“. */
    String id();

    /** Anschlüsse, an denen das Stück mit anderen verbunden wird. */
    List<Port> ports();

    static PlainPiece plain(String id, TrackGeometry geometry) { return new PlainPiece(id, geometry); }

    /**
     * Gerades Stück, gebogenes Stück oder Flexgleis: ein Strang von {@code A} nach {@code B}. Die Länge aus der
     * Geometrie gilt, solange beim Verlegen keine eigene Länge angegeben ist
     * ({@link TrackGraph.Builder#place(String, TrackPiece, double)}, Entscheidung 42).
     */
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
     * Weichentyp: Spitze {@code TIP}, gerader Strang {@code STRAIGHT}, Abzweig {@code DIVERGING}.
     * Bei einer Y-Weiche ist „gerade“ der Strang, den die Lage {@code STRAIGHT} einstellt.
     *
     * <p>Maßgeblich sind die Zweiglängen und Grenzzeichen der <b>verlegten</b> Weiche, die der Nutzer je Weiche
     * einträgt ({@link TrackGraph.Builder#placeTurnout}, Entscheidung 41). Der Typ liefert nur einen
     * {@code suggestion}-Vorschlag, den ein Editor beim Anlegen übernehmen kann; der Graph liest ihn nie selbst.
     * Weichenlängen werden bewusst nicht aus Radius und Winkel berechnet, weil reale Weichen davon abweichen.
     */
    record TurnoutPiece(String id, Hand hand, TurnoutBranches suggestion) implements TrackPiece {
        public TurnoutPiece {
            requireId(id);
            if (hand == null || suggestion == null)
                throw new IllegalArgumentException("Weiche " + id + ": Bauform oder Vorschlagswerte fehlen");
        }

        @Override public List<Port> ports() { return List.of(Port.TIP, Port.STRAIGHT, Port.DIVERGING); }
    }

    /**
     * Kreuzungstyp: zwei Stränge A1–A2 und B1–B2, die sich in einem Punkt schneiden; kein Übergang zwischen den
     * Strängen. Maßgeblich sind die je verlegter Kreuzung eingetragenen Maße
     * ({@link TrackGraph.Builder#placeCrossing}, Entscheidung 42); der Typ liefert nur {@code suggestion}.
     */
    record CrossingPiece(String id, CrossingDimensions suggestion) implements TrackPiece {
        public CrossingPiece {
            requireId(id);
            if (suggestion == null) throw new IllegalArgumentException("Kreuzung " + id + ": Vorschlagswerte fehlen");
        }

        @Override public List<Port> ports() { return List.of(Port.A1, Port.A2, Port.B1, Port.B2); }
    }

    /**
     * Maße einer Kreuzung in mm: Länge der Stränge A (A1–A2) und B (B1–B2), Lage des Kreuzungspunkts hinter A1 bzw. B1.
     */
    record CrossingDimensions(double lineALengthMm, double lineBLengthMm, double crossingFromA1Mm,
                              double crossingFromB1Mm) {
        public CrossingDimensions {
            requirePositive(lineALengthMm, "Länge Strang A");
            requirePositive(lineBLengthMm, "Länge Strang B");
            requireInside(crossingFromA1Mm, lineALengthMm, "A");
            requireInside(crossingFromB1Mm, lineBLengthMm, "B");
        }

        /** Kreuzungspunkt jeweils in der Mitte der Stränge. */
        public static CrossingDimensions symmetric(double lineALengthMm, double lineBLengthMm) {
            return new CrossingDimensions(lineALengthMm, lineBLengthMm, lineALengthMm / 2, lineBLengthMm / 2);
        }

        private static void requireInside(double at, double length, String line) {
            if (!(at > 0) || !(at < length))
                throw new IllegalArgumentException("Kreuzungspunkt auf Strang " + line + " muss zwischen 0 und "
                        + length + " mm liegen: " + at);
        }
    }

    /**
     * Typ einer Doppelkreuzungsweiche: Außenanschlüsse A1, A2 (Seite A) und B1, B2 (Seite B), vier Fahrwege, gestellt
     * über zwei gekoppelte Weichenhälften (Seite A, Seite B) nach {@link DoubleSlipLogic} der Bauart.
     *
     * <p>Wie bei der einfachen Weiche sind die Fahrweglängen der <b>verlegten</b> DKW maßgeblich
     * ({@link TrackGraph.Builder#placeDoubleSlip}, Entscheidung 41); der Typ liefert nur {@code suggestion} und
     * {@code clearanceSuggestion} (Grenzzeichen je Außenanschluss, {@link DoubleSlipClearances}).
     */
    record DoubleSlipPiece(String id, DoubleSlipLengths suggestion, DoubleSlipClearances clearanceSuggestion,
                           DoubleSlipLogic logic) implements TrackPiece {
        public DoubleSlipPiece {
            requireId(id);
            if (logic == null || suggestion == null || clearanceSuggestion == null)
                throw new IllegalArgumentException("Doppelkreuzungsweiche " + id + ": Stelllogik oder Vorschlagswerte fehlen");
        }

        @Override public List<Port> ports() { return List.of(Port.A1, Port.A2, Port.B1, Port.B2); }
    }

    /** Längen der vier Fahrwege einer Doppelkreuzungsweiche in mm (Schlüssel {@code A1_B1} … {@code A2_B2}). */
    record DoubleSlipLengths(Map<Port, Double> lengthsMm) {
        public DoubleSlipLengths {
            Map<Port, Double> copy = new EnumMap<>(Port.class);
            copy.putAll(lengthsMm);
            for (Map.Entry<Port, Double> e : copy.entrySet()) {
                if (!e.getKey().isDoubleSlipRoute())
                    throw new IllegalArgumentException("Kein Fahrweg der Doppelkreuzungsweiche: " + e.getKey());
                Double v = e.getValue();
                if (v == null || !(v > 0) || !Double.isFinite(v))
                    throw new IllegalArgumentException("Länge des Fahrwegs " + e.getKey() + " muss größer 0 sein: " + v);
            }
            if (copy.size() != 4)
                throw new IllegalArgumentException("Alle vier Fahrwege der Doppelkreuzungsweiche brauchen eine Länge");
            lengthsMm = Map.copyOf(copy);
        }

        public double lengthMm(Port route) {
            Double v = lengthsMm.get(route);
            if (v == null) throw new IllegalArgumentException("Kein Fahrweg der Doppelkreuzungsweiche: " + route);
            return v;
        }
    }

    private static void requirePositive(double value, String what) {
        if (!(value > 0) || !Double.isFinite(value))
            throw new IllegalArgumentException(what + " muss eine endliche Zahl größer 0 sein: " + value);
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Gleisstück ohne Typbezeichnung");
    }
}
