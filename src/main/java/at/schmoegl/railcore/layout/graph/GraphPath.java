package at.schmoegl.railcore.layout.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * Fahrweg: Folge gerichteter Kanten, die im Graph aufeinander folgen dürfen, beginnend {@code startOffsetMm} hinter
 * dem Anfang der ersten und endend {@code endOffsetMm} hinter dem Anfang der letzten Kante (jeweils in
 * Fahrrichtung). Die Wegkoordinate {@code s} läuft von 0 (Anfang) bis {@link #lengthMm()} (Ende) und wird mit
 * {@link #locate} auf einen Ort (Kante, mm) abgebildet, mit {@link #coordinatesOf} zurück.
 *
 * <p>Ob die Kanten aufeinander folgen dürfen, wird topologisch geprüft (für irgendeine Weichenlage); welche Lagen
 * der Weg braucht, liefert {@link #turnoutRequirements()} in Fahrreihenfolge. Ein Weg darf dieselbe Kante mehrmals
 * enthalten (Kehrschleife, Oval), auch in Gegenrichtung.
 *
 * <p>An einer Kantengrenze liefert {@link #locate} den Anfang der nächsten Kante (Kanten ohne Länge werden dabei
 * übersprungen), am Wegende das Ende der letzten Kante.
 */
public final class GraphPath {

    private static final double EPS = 1e-6;

    private final List<DirectedEdge> edges;
    private final double startOffsetMm;
    private final double endOffsetMm;
    private final double[] cumulativeStart;
    private final double lengthMm;
    private final List<TurnoutRequirement> requirements;

    private GraphPath(List<DirectedEdge> edges, double startOffsetMm, double endOffsetMm,
                      List<TurnoutRequirement> requirements) {
        this.edges = List.copyOf(edges);
        this.startOffsetMm = startOffsetMm;
        this.endOffsetMm = endOffsetMm;
        this.requirements = List.copyOf(requirements);
        this.cumulativeStart = new double[this.edges.size()];
        double c = 0;
        for (int i = 0; i < this.edges.size(); i++) {
            cumulativeStart[i] = c;
            c += this.edges.get(i).lengthMm();
        }
        this.lengthMm = cumulativeStart[this.edges.size() - 1] + endOffsetMm - startOffsetMm;
    }

    /**
     * Prüft und erzeugt einen Weg. Fehler: leere Folge, Kanten nicht aus diesem Graph, nicht aufeinanderfolgende
     * Kanten oder Übergänge, die der Knoten nie zulässt (z. B. Wechsel des Strangs an einer Kreuzung), Abstände
     * außerhalb der Kante, Ende vor Anfang.
     */
    public static GraphPath of(TrackGraph graph, List<DirectedEdge> edges, double startOffsetMm, double endOffsetMm) {
        if (edges == null || edges.isEmpty()) throw new IllegalArgumentException("Fahrweg ohne Kanten");
        for (DirectedEdge e : edges)
            if (!graph.containsEdge(e.edge()))
                throw new IllegalArgumentException("Kante " + e.edgeId() + " gehört nicht zu diesem Gleisgraph");
        DirectedEdge first = edges.getFirst();
        DirectedEdge last = edges.getLast();
        requireWithin(startOffsetMm, first, "Anfang");
        requireWithin(endOffsetMm, last, "Ende");
        if (edges.size() == 1 && endOffsetMm < startOffsetMm)
            throw new IllegalArgumentException("Fahrweg endet vor seinem Anfang");
        List<TurnoutRequirement> reqs = new ArrayList<>();
        for (int i = 0; i + 1 < edges.size(); i++) {
            DirectedEdge from = edges.get(i);
            DirectedEdge to = edges.get(i + 1);
            TrackGraph.Step step = graph.steps(from).stream().filter(s -> s.next().equals(to)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Kein Übergang von " + from + " nach " + to
                            + " am Knoten " + from.endNode()));
            reqs.addAll(step.requires());
        }
        return new GraphPath(edges, startOffsetMm, endOffsetMm, reqs);
    }

    /** Weg über die vollen Kanten. */
    public static GraphPath whole(TrackGraph graph, List<DirectedEdge> edges) {
        if (edges == null || edges.isEmpty()) throw new IllegalArgumentException("Fahrweg ohne Kanten");
        return of(graph, edges, 0, edges.getLast().lengthMm());
    }

    private static void requireWithin(double along, DirectedEdge e, String what) {
        if (!Double.isFinite(along) || along < 0 || along > e.lengthMm())
            throw new IllegalArgumentException(what + " des Fahrwegs außerhalb der Kante " + e.edgeId() + ": " + along);
    }

    public List<DirectedEdge> edges() { return edges; }

    public double startOffsetMm() { return startOffsetMm; }

    public double endOffsetMm() { return endOffsetMm; }

    public double lengthMm() { return lengthMm; }

    /** Für den Weg nötige Weichenlagen in Fahrreihenfolge (dieselbe Weiche kann mehrmals vorkommen). */
    public List<TurnoutRequirement> turnoutRequirements() { return requirements; }

    /** Ob der ganze Weg bei diesen Lagen befahrbar ist; unbekannte Lagen zählen nie als passend. */
    public boolean passableWith(TurnoutStates states) {
        for (TurnoutRequirement r : requirements) if (!r.satisfiedBy(states)) return false;
        return true;
    }

    public TrackLocation start() { return edges.getFirst().locationAt(startOffsetMm); }

    public TrackLocation end() { return edges.getLast().locationAt(endOffsetMm); }

    /** Ort bei Wegkoordinate {@code s} (0 … Länge). */
    public TrackLocation locate(double s) {
        int i = indexAt(s);
        return edges.get(i).locationAt(along(i, s));
    }

    /** Befahrene Kante (mit Fahrrichtung) bei Wegkoordinate {@code s}. */
    public DirectedEdge directedEdgeAt(double s) { return edges.get(indexAt(s)); }

    /** Alle Wegkoordinaten, an denen der Weg den Ort berührt, aufsteigend (leer = Ort liegt nicht auf dem Weg). */
    public List<Double> coordinatesOf(TrackLocation location) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            DirectedEdge e = edges.get(i);
            if (!e.edgeId().equals(location.edgeId())) continue;
            if (location.offsetMm() > e.lengthMm() + EPS) continue;
            double s = cumulativeStart[i] + e.toEdgeOffset(location.offsetMm()) - startOffsetMm;
            if (s < -EPS || s > lengthMm + EPS) continue;
            double clamped = Math.min(Math.max(s, 0), lengthMm);
            if (out.isEmpty() || Math.abs(out.getLast() - clamped) > EPS) out.add(clamped);
        }
        return out;
    }

    /** Derselbe Weg in Gegenrichtung; Wegkoordinate {@code s} wird zu {@code Länge − s}. */
    public GraphPath reversed() {
        List<DirectedEdge> rev = new ArrayList<>(edges.size());
        for (int i = edges.size() - 1; i >= 0; i--) rev.add(edges.get(i).reversed());
        double newStart = edges.getLast().lengthMm() - endOffsetMm;
        double newEnd = edges.getFirst().lengthMm() - startOffsetMm;
        // Übergänge sind in beide Richtungen gleich (TrackNode), daher dieselben Lagen in umgekehrter Folge.
        return new GraphPath(rev, newStart, newEnd, requirements.reversed());
    }

    /** Bereiche je Kante (Bezugsrichtung) für den ganzen Weg; Kanten ohne Länge entfallen. */
    public List<TrackRange> ranges() { return ranges(0, lengthMm); }

    /** Bereiche je Kante (Bezugsrichtung) zwischen den Wegkoordinaten {@code fromS} und {@code toS}. */
    public List<TrackRange> ranges(double fromS, double toS) {
        requireCoordinate(fromS);
        requireCoordinate(toS);
        if (toS < fromS) throw new IllegalArgumentException("Bereich endet vor seinem Anfang: " + fromS + "–" + toS);
        List<TrackRange> out = new ArrayList<>();
        double from = fromS + startOffsetMm;
        double to = toS + startOffsetMm;
        for (int i = 0; i < edges.size(); i++) {
            DirectedEdge e = edges.get(i);
            double a = Math.max(from, cumulativeStart[i]) - cumulativeStart[i];
            double b = Math.min(to, cumulativeStart[i] + e.lengthMm()) - cumulativeStart[i];
            if (b - a <= EPS) continue;
            double x = e.toEdgeOffset(a);
            double y = e.toEdgeOffset(b);
            out.add(new TrackRange(e.edgeId(), Math.max(0, Math.min(x, y)), Math.min(e.lengthMm(), Math.max(x, y))));
        }
        return out;
    }

    private int indexAt(double s) {
        requireCoordinate(s);
        double u = s + startOffsetMm;
        for (int i = 0; i < edges.size() - 1; i++)
            if (u < cumulativeStart[i] + edges.get(i).lengthMm()) return i;
        return edges.size() - 1;
    }

    private double along(int i, double s) {
        double a = s + startOffsetMm - cumulativeStart[i];
        return Math.min(Math.max(a, 0), edges.get(i).lengthMm());
    }

    private void requireCoordinate(double s) {
        if (!Double.isFinite(s) || s < -EPS || s > lengthMm + EPS)
            throw new IllegalArgumentException("Wegkoordinate außerhalb des Fahrwegs (0–" + lengthMm + " mm): " + s);
    }

    @Override
    public String toString() {
        return "GraphPath" + edges + " ab " + startOffsetMm + " bis " + endOffsetMm + " (" + lengthMm + " mm)";
    }
}
