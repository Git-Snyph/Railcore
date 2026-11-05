package at.schmoegl.railcore.layout.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unveränderliche Abdeckung von Kanten durch Bereiche mit Eigentümer (Meldeabschnitt, Block, Bezirk), in mm in
 * Bezugsrichtung der Kante. Hilfsklasse für {@link TrackLayout}; Vergleiche mit Toleranz {@link #TOL}.
 */
final class Coverage {

    /** Toleranz für „gleicher Punkt“ bzw. „berührt“ in mm. */
    static final double TOL = 0.01;

    record Interval(double from, double to, String owner) {
        double length() { return to - from; }
    }

    private final Map<String, List<Interval>> raw;
    private final Map<String, List<Interval>> merged;

    private Coverage(Map<String, List<Interval>> raw) {
        Map<String, List<Interval>> r = new LinkedHashMap<>();
        Map<String, List<Interval>> m = new LinkedHashMap<>();
        for (Map.Entry<String, List<Interval>> e : raw.entrySet()) {
            List<Interval> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.comparingDouble(Interval::from).thenComparingDouble(Interval::to));
            r.put(e.getKey(), List.copyOf(sorted));
            List<Interval> u = new ArrayList<>();
            for (Interval i : sorted) {
                if (!u.isEmpty() && i.from() <= u.getLast().to() + TOL) {
                    Interval last = u.removeLast();
                    u.add(new Interval(last.from(), Math.max(last.to(), i.to()), null));
                } else {
                    u.add(new Interval(i.from(), i.to(), null));
                }
            }
            m.put(e.getKey(), List.copyOf(u));
        }
        this.raw = Map.copyOf(r);
        this.merged = Map.copyOf(m);
    }

    static Builder builder() { return new Builder(); }

    static final class Builder {
        private final Map<String, List<Interval>> raw = new LinkedHashMap<>();

        Builder add(TrackRange r, String owner) {
            raw.computeIfAbsent(r.edgeId(), k -> new ArrayList<>()).add(new Interval(r.fromMm(), r.toMm(), owner));
            return this;
        }

        Coverage build() { return new Coverage(raw); }
    }

    Set<String> edges() { return raw.keySet(); }

    /** Einzelne Bereiche mit Eigentümer, nach Anfang sortiert. */
    List<Interval> raw(String edgeId) { return raw.getOrDefault(edgeId, List.of()); }

    /** Vereinigte Bereiche (berührende zusammengefasst), nach Anfang sortiert. */
    List<Interval> merged(String edgeId) { return merged.getOrDefault(edgeId, List.of()); }

    boolean coversAll(String edgeId, double from, double to) {
        for (Interval i : merged(edgeId))
            if (i.from() <= from + TOL && i.to() >= to - TOL) return true;
        return false;
    }

    boolean coversPoint(String edgeId, double offset) {
        for (Interval i : merged(edgeId))
            if (i.from() <= offset + TOL && i.to() >= offset - TOL) return true;
        return false;
    }

    /** Nicht abgedeckte Teile der Kante (länger als {@link #TOL}). */
    List<Interval> gaps(String edgeId, double lengthMm) {
        List<Interval> out = new ArrayList<>();
        double pos = 0;
        for (Interval i : merged(edgeId)) {
            if (i.from() > pos + TOL) out.add(new Interval(pos, i.from(), null));
            pos = Math.max(pos, i.to());
        }
        if (lengthMm > pos + TOL) out.add(new Interval(pos, lengthMm, null));
        return out;
    }

    Set<String> ownersAt(String edgeId, double offset) {
        Set<String> out = new LinkedHashSet<>();
        for (Interval i : raw(edgeId))
            if (i.from() <= offset + TOL && i.to() >= offset - TOL) out.add(i.owner());
        return out;
    }

    /**
     * Eigentümer, deren Bereiche sich mit {@code [from, to]} auf der Kante um mehr als {@link #TOL} überschneiden
     * (bloßes Berühren in einem Punkt zählt nicht), in der Reihenfolge ihrer Bereiche.
     */
    Set<String> ownersOverlapping(String edgeId, double from, double to) {
        Set<String> out = new LinkedHashSet<>();
        for (Interval i : raw(edgeId))
            if (Math.min(to, i.to()) - Math.max(from, i.from()) > TOL) out.add(i.owner());
        return out;
    }

    /** Vereinigter Bereich in Fahrkoordinaten der gerichteten Kante: {@code [anfang, ende]} ab Kantenanfang in Fahrrichtung. */
    static double[] along(DirectedEdge d, Interval i) {
        return d.forward() ? new double[] {i.from(), i.to()}
                           : new double[] {d.lengthMm() - i.to(), d.lengthMm() - i.from()};
    }
}
