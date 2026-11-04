package at.schmoegl.railcore.layout.graph;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Grenzzeichen einer Doppelkreuzungsweiche (Schritt B2), je Außenanschluss {@code A1}, {@code A2}, {@code B1},
 * {@code B2} in mm, gemessen ab diesem Außenanschluss nach außen auf das anschließende Gleis.
 *
 * <p>Auf jeder Seite laufen die beiden Außenanschlüsse auseinander wie die Schenkel einer Weiche; das Grenzzeichen
 * eines Anschlusses ist die Stelle, ab der ein Fahrzeug auf dem dort anschließenden Gleis die Fahrwege über den
 * anderen Anschluss derselben Seite nicht mehr behindert. Es gilt unabhängig davon, welcher Fahrweg durch die DKW
 * führt. Wie bei der einfachen Weiche ({@link TurnoutBranches}) ist es Pflichtangabe je verlegter DKW
 * (Entscheidung 41), es gibt keinen Standardwert, und der Wert muss größer 0 sein (ein zu früh angegebenes
 * Grenzzeichen ist unsicher).
 *
 * <p>Vorläufige Festlegung (Bezugspunkt Außenanschluss statt Fahrwegbeginn), ausdrücklich später änderbar.
 */
public record DoubleSlipClearances(Map<Port, Double> clearancesMm) {

    /** Außenanschlüsse der Doppelkreuzungsweiche. */
    public static final List<Port> OUTER_PORTS = List.of(Port.A1, Port.A2, Port.B1, Port.B2);

    public DoubleSlipClearances {
        Map<Port, Double> copy = new EnumMap<>(Port.class);
        copy.putAll(clearancesMm);
        for (Map.Entry<Port, Double> e : copy.entrySet()) {
            if (!OUTER_PORTS.contains(e.getKey()))
                throw new IllegalArgumentException("Kein Außenanschluss der Doppelkreuzungsweiche: " + e.getKey());
            Double v = e.getValue();
            if (v == null || !(v > 0) || !Double.isFinite(v))
                throw new IllegalArgumentException("Grenzzeichen an " + e.getKey() + " muss eine endliche Zahl größer 0 sein: " + v);
        }
        if (copy.size() != OUTER_PORTS.size())
            throw new IllegalArgumentException("Alle vier Außenanschlüsse der Doppelkreuzungsweiche brauchen ein Grenzzeichen");
        clearancesMm = Map.copyOf(copy);
    }

    /** Gleiches Grenzzeichen an allen vier Außenanschlüssen. */
    public static DoubleSlipClearances uniform(double mm) {
        return new DoubleSlipClearances(Map.of(Port.A1, mm, Port.A2, mm, Port.B1, mm, Port.B2, mm));
    }

    public double clearanceMm(Port outer) {
        Double v = clearancesMm.get(outer);
        if (v == null) throw new IllegalArgumentException("Kein Außenanschluss der Doppelkreuzungsweiche: " + outer);
        return v;
    }
}
