package at.schmoegl.railcore.train;

import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.layout.graph.Block;
import at.schmoegl.railcore.layout.graph.DetectionSection;
import at.schmoegl.railcore.layout.graph.DirectedEdge;
import at.schmoegl.railcore.layout.graph.DirectedLocation;
import at.schmoegl.railcore.layout.graph.GraphPath;
import at.schmoegl.railcore.layout.graph.Port;
import at.schmoegl.railcore.layout.graph.TrackEdge;
import at.schmoegl.railcore.layout.graph.TrackGraph;
import at.schmoegl.railcore.layout.graph.TrackLayout;
import at.schmoegl.railcore.layout.graph.TrackNode;
import at.schmoegl.railcore.layout.graph.TrackRange;
import at.schmoegl.railcore.layout.graph.TurnoutRequirement;
import at.schmoegl.railcore.layout.graph.TurnoutStates;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Ausdehnung eines Zuges im Gleisgraph (Lastenheft Kapitel 5 und 6, Schritt C1): von der Zugspitze über die Zuglänge
 * zurück bis zum Zugschluss, daraus die belegten Bereiche je Kante und die belegten Meldeabschnitte, Blöcke, Weichen,
 * Kreuzungen und Bezirke – jeweils in Reihenfolge von der Spitze zum Schluss. Ein Zug darf länger als ein Block sein
 * (Entscheidung 40/E6): belegt sind dann alle Blöcke unter ihm. Berechnet wird mit {@link Resolver}.
 *
 * <p><b>Rückwärts über Weichen:</b> Läuft der Zug von hinten gesehen über eine Weichenspitze in die Zweige (er kam
 * also von einem Zweig), entscheidet die übergebene Weichenlage. Ist sie unbekannt oder passt keine eindeutig, gelten
 * <b>alle</b> möglichen Zweige als belegt ({@link #ambiguous()}, im Zweifel belegt); es gibt dann mehrere mögliche
 * Zugschlüsse. Läuft der Zug rückwärts von einem Zweig zur Spitze, ist der Weg eindeutig; liegt die Weiche dabei
 * bekanntermaßen anders, wird das als Widerspruch vermerkt ({@link #conflict()}), die Belegung bleibt.
 *
 * <p><b>Weichen</b> zählen als belegt, sobald der Zug einen ihrer Zweige oder den Bereich von der Spitze bis zum
 * Grenzzeichen eines Schenkels berührt (das Grenzzeichen darf hinter dem Weichenende liegen); bei
 * Doppelkreuzungsweichen die Fahrwege und der Bereich bis zum Grenzzeichen je Außenanschluss, mit beiden
 * Antriebskennungen. <b>Bereiche</b>, die der Zug nur in einem Punkt berührt, zählen nicht. Sicherheitszuschläge
 * (Unsicherheit, Freigabe nach Entscheidung 14) gibt der Aufrufer über die Zuschläge vorn und hinten an.
 *
 * @param head        Zugspitze
 * @param lengthMm    Zuglänge
 * @param tails       mögliche Zugschlüsse mit der Fahrtrichtung des Zuges (mehrere nur bei {@link #ambiguous()}; bei
 *                    unvollständiger Ausdehnung die Stelle, an der das Gleis endete)
 * @param ranges      belegte Bereiche je Kante (vereinigt, inklusive Zuschlägen), Reihenfolge von der Spitze her
 * @param body        Weg vom Zugschluss zur Spitze, nur bei eindeutiger und vollständiger Ausdehnung
 * @param ambiguous   der Zug könnte auf mehreren Zweigen stehen (unbekannte Weichenlage), alle gelten als belegt
 * @param complete    die ganze Zuglänge passt hinter die Spitze (sonst endete das Gleis vorher – Position unplausibel)
 * @param conflict    eine Weiche unter dem Zug liegt bekanntermaßen nicht passend
 * @param notes       Hinweise für das Journal (Deutsch)
 */
public record TrainExtent(DirectedLocation head, double lengthMm, List<DirectedLocation> tails, List<TrackRange> ranges,
                          List<String> sectionIds, List<String> blockIds, List<String> turnoutIds,
                          List<String> crossingIds, List<Integer> districts, Optional<GraphPath> body,
                          boolean ambiguous, boolean complete, boolean conflict, List<String> notes) {

    /** Toleranz für Längenvergleiche in mm. */
    static final double EPS = 1e-6;

    /** Obergrenze für mögliche Zugschlüsse bei unbekannten Weichenlagen; darüber gilt die Ausdehnung als unvollständig. */
    public static final int MAX_BRANCHES = 256;

    public TrainExtent {
        tails = List.copyOf(tails);
        ranges = List.copyOf(ranges);
        sectionIds = List.copyOf(sectionIds);
        blockIds = List.copyOf(blockIds);
        turnoutIds = List.copyOf(turnoutIds);
        crossingIds = List.copyOf(crossingIds);
        districts = List.copyOf(districts);
        notes = List.copyOf(notes);
        Objects.requireNonNull(body);
    }

    /** Eindeutig und vollständig, ohne Widerspruch: nur dann ist die Ausdehnung Grundlage für einen Fahrauftrag. */
    public boolean certain() { return !ambiguous && complete && !conflict; }

    /** Der Zugschluss, sofern eindeutig und vollständig. */
    public Optional<DirectedLocation> tail() {
        return certain() && tails.size() == 1 ? Optional.of(tails.getFirst()) : Optional.empty();
    }

    public boolean occupiesSection(String sectionId) { return sectionIds.contains(sectionId); }

    public boolean occupiesBlock(String blockId) { return blockIds.contains(blockId); }

    public boolean occupiesTurnout(String turnoutId) { return turnoutIds.contains(turnoutId); }

    /** Summe der belegten Bereiche (bei mehrdeutiger Ausdehnung mehr als die Zuglänge). */
    public double occupiedLengthMm() {
        double sum = 0;
        for (TrackRange r : ranges) sum += r.lengthMm();
        return sum;
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * Berechnet Ausdehnungen auf einem Gleisplan. Die Bereiche, in denen ein Fahrzeug eine Weiche belegt (Zweige bis
     * zum Grenzzeichen), werden einmal beim Anlegen berechnet; danach ist der Resolver unveränderlich und aus jedem
     * Thread nutzbar.
     */
    public static final class Resolver {

        private record Zone(double from, double to, List<String> turnoutIds) {}

        private final TrackLayout layout;
        private final TrackGraph graph;
        private final Map<String, List<Zone>> turnoutZones;

        public Resolver(TrackLayout layout) {
            this.layout = Objects.requireNonNull(layout, "Gleisplan fehlt");
            this.graph = layout.graph();
            this.turnoutZones = buildTurnoutZones();
        }

        public TrackLayout layout() { return layout; }

        /** Ausdehnung ohne Zuschläge. */
        public TrainExtent extent(TrainPosition position, double lengthMm, TurnoutStates states) {
            return extent(position, lengthMm, states, 0, 0);
        }

        /**
         * Ausdehnung mit Positionsunsicherheit: vorn und hinten je {@code sigmas} · σ der Position zusätzlich belegt.
         */
        public TrainExtent extentWithUncertainty(TrainPosition position, double lengthMm, TurnoutStates states,
                                                 PositionUncertainty uncertainty, double sigmas) {
            if (!(sigmas >= 0) || !Double.isFinite(sigmas)) throw new IllegalArgumentException("Faktor ungültig: " + sigmas);
            double margin = sigmas * position.sigmaMm(uncertainty);
            return extent(position, lengthMm, states, margin, margin);
        }

        /**
         * Ausdehnung mit Zuschlägen: {@code aheadMarginMm} vor der Spitze in Fahrtrichtung (an Weichen mit
         * unbekannter Lage alle Zweige), {@code behindMarginMm} hinter dem Zugschluss. Die Zugschlüsse liegen ohne
         * Zuschlag bei der Zuglänge.
         */
        public TrainExtent extent(TrainPosition position, double lengthMm, TurnoutStates states,
                                  double aheadMarginMm, double behindMarginMm) {
            Objects.requireNonNull(position, "Position fehlt");
            Objects.requireNonNull(states, "Weichenlagen fehlen");
            requireLength(lengthMm, "Zuglänge", true);
            requireLength(aheadMarginMm, "Zuschlag vorn", false);
            requireLength(behindMarginMm, "Zuschlag hinten", false);
            DirectedLocation head = position.head();
            graph.requireLocation(head.location());
            DirectedEdge headEdge = position.headEdge(graph);
            double headAlong = position.headAlongMm(graph);

            List<String> notes = new ArrayList<>();
            RangeSet ranges = new RangeSet();

            // Zuschlag vorn: ab der Spitze in Fahrtrichtung (zuerst, damit die Reihenfolge von vorn nach hinten läuft)
            if (aheadMarginMm > 0) {
                Walk ahead = new Walk(states, ranges, notes, false);
                ahead.run(headEdge, headAlong, aheadMarginMm);
            }
            // Zugkörper: von der Spitze gegen die Fahrtrichtung über die Zuglänge
            Walk body = new Walk(states, ranges, notes, true);
            body.run(headEdge.reversed(), headEdge.lengthMm() - headAlong, lengthMm);
            // Zuschlag hinten: ab jedem Zugschluss weiter zurück
            if (behindMarginMm > 0) {
                Walk behind = new Walk(states, ranges, notes, false);
                for (Walk.End e : body.ends) if (e.reachedLength()) behind.run(e.edge(), e.along(), behindMarginMm);
            }

            List<DirectedLocation> tails = new ArrayList<>();
            for (Walk.End e : body.ends) tails.add(new DirectedLocation(e.edge().locationAt(e.along()), !e.edge().forward()));
            boolean ambiguous = body.branched;
            boolean complete = !body.truncated;
            Optional<GraphPath> bodyPath = Optional.empty();
            if (!ambiguous && complete && body.ends.size() == 1) bodyPath = Optional.of(bodyPath(body.ends.getFirst(), headAlong));

            List<TrackRange> merged = ranges.merged();
            Set<String> sections = new LinkedHashSet<>();
            Set<String> blocks = new LinkedHashSet<>();
            Set<String> turnouts = new LinkedHashSet<>();
            Set<String> crossings = new LinkedHashSet<>();
            Set<Integer> districts = new LinkedHashSet<>();
            for (TrackRange r : merged) {
                for (DetectionSection s : layout.sectionsOverlapping(r)) sections.add(s.id());
                for (Block b : layout.blocksOverlapping(r)) blocks.add(b.id());
                districts.addAll(layout.districtsOverlapping(r));
                for (Zone z : turnoutZones.getOrDefault(r.edgeId(), List.of()))
                    if (Math.min(r.toMm(), z.to()) - Math.max(r.fromMm(), z.from()) > TrainExtent.EPS) turnouts.addAll(z.turnoutIds());
                TrackEdge e = graph.edge(r.edgeId());
                if (e.role() == TrackEdge.Role.CROSSING_HALF) crossings.add(e.fromNode());
            }
            return new TrainExtent(head, lengthMm, tails, merged, List.copyOf(sections), List.copyOf(blocks),
                    List.copyOf(turnouts), List.copyOf(crossings), List.copyOf(districts), bodyPath, ambiguous,
                    complete, body.conflict, notes);
        }

        /** Weg vom Zugschluss zur Spitze aus dem (eindeutigen) Rückwärtslauf. */
        private GraphPath bodyPath(Walk.End end, double headAlong) {
            List<DirectedEdge> chain = end.chain();
            List<DirectedEdge> edges = new ArrayList<>(chain.size());
            for (int i = chain.size() - 1; i >= 0; i--) edges.add(chain.get(i).reversed());
            double start = end.edge().lengthMm() - end.along();
            return GraphPath.of(graph, edges, start, headAlong);
        }

        private static void requireLength(double v, String what, boolean positive) {
            if (!Double.isFinite(v) || v < 0 || (positive && v == 0))
                throw new IllegalArgumentException(what + " ungültig: " + v);
        }

        // --- Weichenbereiche bis zum Grenzzeichen ---------------------------------------------------------------

        private Map<String, List<Zone>> buildTurnoutZones() {
            Map<String, List<Zone>> zones = new LinkedHashMap<>();
            for (TrackNode n : graph.nodes()) {
                if (n instanceof TrackNode.TurnoutNode t) {
                    List<String> ids = List.of(t.turnoutId());
                    for (TurnoutPosition branch : List.of(TurnoutPosition.STRAIGHT, TurnoutPosition.DIVERGING)) {
                        Port port = branch == TurnoutPosition.STRAIGHT ? Port.STRAIGHT : Port.DIVERGING;
                        DirectedEdge leg = graph.leaving(t.id(), port);
                        addZone(zones, leg, 0, leg.lengthMm(), ids);
                        collectZone(zones, leg, 0, t.branches().clearanceMm(branch), ids, 0);
                    }
                } else if (n instanceof TrackNode.DoubleSlipNode d) {
                    List<String> ids = List.of(d.turnoutIdA(), d.turnoutIdB());
                    if (d.sideA())
                        for (Port route : List.of(Port.A1_B1, Port.A1_B2, Port.A2_B1, Port.A2_B2)) {
                            DirectedEdge r = graph.leaving(d.id(), route);
                            addZone(zones, r, 0, r.lengthMm(), ids);
                        }
                    for (Port outer : d.outerPorts())
                        collectZone(zones, graph.leaving(d.id(), outer), 0, d.clearances().clearanceMm(outer), ids, 0);
                }
            }
            Map<String, List<Zone>> out = new LinkedHashMap<>();
            zones.forEach((k, v) -> out.put(k, List.copyOf(v)));
            return Map.copyOf(out);
        }

        /** Bereich ab {@code along} über {@code remaining} mm, an Verzweigungen in alle Richtungen (vorsichtig). */
        private void collectZone(Map<String, List<Zone>> zones, DirectedEdge e, double along, double remaining,
                                 List<String> ids, int depth) {
            double available = e.lengthMm() - along;
            if (remaining <= available + EPS) {
                addZone(zones, e, along, Math.min(e.lengthMm(), along + remaining), ids);
                return;
            }
            addZone(zones, e, along, e.lengthMm(), ids);
            if (depth > graph.edges().size()) return;
            for (TrackGraph.Step s : graph.steps(e)) collectZone(zones, s.next(), 0, remaining - available, ids, depth + 1);
        }

        private static void addZone(Map<String, List<Zone>> zones, DirectedEdge e, double fromAlong, double toAlong,
                                    List<String> ids) {
            if (toAlong - fromAlong <= EPS) return;
            double a = e.toEdgeOffset(fromAlong);
            double b = e.toEdgeOffset(toAlong);
            zones.computeIfAbsent(e.edgeId(), k -> new ArrayList<>()).add(new Zone(Math.min(a, b), Math.max(a, b), ids));
        }

        // --- Lauf über den Graph -------------------------------------------------------------------------------

        /** Tiefensuche von einem Ort über eine Länge; an mehrdeutigen Verzweigungen in alle Zweige. */
        private final class Walk {

            /** Durchlaufene Kanten als verkettete Liste (rückwärts), damit Verzweigungen nichts kopieren müssen. */
            record Link(DirectedEdge edge, Link previous) {
                List<DirectedEdge> toList() {
                    List<DirectedEdge> out = new ArrayList<>();
                    for (Link l = this; l != null; l = l.previous()) out.add(l.edge());
                    return out.reversed();
                }
            }

            /** Ende eines Laufs: Kante, mm in Laufrichtung, durchlaufene Kanten, ob die volle Länge erreicht wurde. */
            record End(DirectedEdge edge, double along, Link link, boolean reachedLength) {
                List<DirectedEdge> chain() { return link.toList(); }
            }

            private final TurnoutStates states;
            private final RangeSet ranges;
            private final List<String> notes;
            private final boolean isBody;
            final List<End> ends = new ArrayList<>();
            boolean branched;
            boolean truncated;
            boolean conflict;
            private boolean overflow;

            Walk(TurnoutStates states, RangeSet ranges, List<String> notes, boolean isBody) {
                this.states = states;
                this.ranges = ranges;
                this.notes = notes;
                this.isBody = isBody;
            }

            void run(DirectedEdge e, double along, double remaining) { run(e, along, remaining, null, 0); }

            private void run(DirectedEdge e, double along, double remaining, Link previous, int zeroRun) {
                if (ends.size() >= MAX_BRANCHES) {
                    if (!overflow) {
                        overflow = true;
                        truncated = true;
                        notes.add("Mehr als " + MAX_BRANCHES + " mögliche Enden – Ausdehnung nicht bestimmbar");
                    }
                    return;
                }
                Link here = new Link(e, previous);
                double available = e.lengthMm() - along;
                if (remaining <= available + EPS) {
                    double end = Math.min(e.lengthMm(), along + remaining);
                    ranges.add(e, along, end);
                    ends.add(new End(e, end, here, true));
                    return;
                }
                ranges.add(e, along, e.lengthMm());
                double rest = remaining - available;
                List<TrackGraph.Step> steps = graph.steps(e);
                if (steps.isEmpty()) {
                    if (isBody) {
                        truncated = true;
                        notes.add("Gleisende " + e.endNode() + ": " + fmt(rest) + " mm der Zuglänge passen nicht"
                                + " dahinter – Position unplausibel");
                        ends.add(new End(e, e.lengthMm(), here, false));
                    }
                    return;
                }
                int nextZeroRun = e.lengthMm() > 0 ? 0 : zeroRun + 1;
                if (nextZeroRun > graph.edges().size()) throw new IllegalStateException("Gleisgraph enthält einen Ring ohne Länge bei " + e);
                for (TrackGraph.Step s : choose(e, steps)) run(s.next(), 0, rest, here, nextZeroRun);
            }

            private List<TrackGraph.Step> choose(DirectedEdge arriving, List<TrackGraph.Step> steps) {
                if (steps.size() == 1) {
                    TrackGraph.Step only = steps.getFirst();
                    if (isBody)
                        for (TurnoutRequirement r : only.requires()) {
                            TurnoutPosition p = states.position(r.turnoutId());
                            if (p != null && p != TurnoutPosition.UNKNOWN && p != r.position()) {
                                conflict = true;
                                notes.add("Weiche " + r.turnoutId() + " liegt " + p + ", unter dem Zug wäre " + r.position()
                                        + " nötig");
                            }
                        }
                    return steps;
                }
                List<TrackGraph.Step> matched = new ArrayList<>();
                for (TrackGraph.Step s : steps) {
                    boolean all = true;
                    for (TurnoutRequirement r : s.requires()) if (!r.satisfiedBy(states)) all = false;
                    if (all) matched.add(s);
                }
                if (matched.size() == 1) return matched;
                if (isBody) {
                    branched = true;
                    notes.add("Lage am Knoten " + arriving.endNode() + " nicht eindeutig – alle " + steps.size()
                            + " Zweige gelten als belegt");
                }
                return steps;
            }
        }
    }

    /** Sammelt Bereiche je Kante in Reihenfolge des ersten Auftretens und vereinigt sie. */
    private static final class RangeSet {
        private final Map<String, List<double[]>> byEdge = new LinkedHashMap<>();

        void add(DirectedEdge e, double fromAlong, double toAlong) {
            if (toAlong - fromAlong <= EPS) return;
            double a = e.toEdgeOffset(fromAlong);
            double b = e.toEdgeOffset(toAlong);
            byEdge.computeIfAbsent(e.edgeId(), k -> new ArrayList<>()).add(new double[] {Math.min(a, b), Math.max(a, b)});
        }

        List<TrackRange> merged() {
            List<TrackRange> out = new ArrayList<>();
            for (Map.Entry<String, List<double[]>> en : byEdge.entrySet()) {
                List<double[]> list = new ArrayList<>(en.getValue());
                list.sort(Comparator.comparingDouble(x -> x[0]));
                double from = list.getFirst()[0];
                double to = list.getFirst()[1];
                for (double[] x : list.subList(1, list.size())) {
                    if (x[0] <= to + EPS) to = Math.max(to, x[1]);
                    else {
                        out.add(new TrackRange(en.getKey(), Math.max(0, from), to));
                        from = x[0];
                        to = x[1];
                    }
                }
                out.add(new TrackRange(en.getKey(), Math.max(0, from), to));
            }
            return out;
        }
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }
}
