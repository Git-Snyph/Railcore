package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.core.TurnoutPosition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gleisgraph (Lastenheft Kapitel 4, Entscheidung 40): Knoten und Kanten mit Längen in mm, unveränderlich, berechnet
 * aus verlegten Gleisstücken ({@link Builder}). Ein Ort ist (Kante, mm ab Kantenanfang); befahren wird über
 * {@link DirectedEdge}. Welche Fortsetzung gilt, hängt allein von den übergebenen Weichenlagen ab
 * ({@link #next}); dadurch braucht weder eine Kehrschleife noch ein Oval einen Sonderfall.
 *
 * <p>Der Graph hält keinen Betriebszustand und ist damit aus jedem Thread lesbar.
 */
public final class TrackGraph {

    /** Mögliche Fortsetzung ab einer befahrenen Kante samt verlangter Weichenlagen. */
    public record Step(DirectedEdge next, List<TurnoutRequirement> requires) {
        public Step {
            requires = List.copyOf(requires);
        }
    }

    /** Ergebnis von {@link #follow}: gefahrener Weg und, falls er kürzer als verlangt ist, der Grund. */
    public record FollowResult(GraphPath path, Optional<Continuation> stop) {
        public boolean complete() { return stop.isEmpty(); }
    }

    private record NodePort(String node, Port port) {}

    private record Attachment(TrackEdge edge, boolean atFrom) {}

    private record ClearanceKey(String node, TurnoutPosition branch) {}

    private final Map<String, TrackNode> nodes;
    private final Map<String, TrackEdge> edges;
    private final Map<NodePort, Attachment> attachments;
    private final Map<ClearanceKey, List<TrackLocation>> clearances;
    private final List<String> notices;

    private TrackGraph(Map<String, TrackNode> nodes, Map<String, TrackEdge> edges, Map<NodePort, Attachment> attachments,
                       Map<ClearanceKey, List<TrackLocation>> clearances) {
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.edges = Collections.unmodifiableMap(new LinkedHashMap<>(edges));
        this.attachments = Map.copyOf(attachments);
        this.clearances = Map.copyOf(clearances);
        List<String> n = new ArrayList<>();
        for (TrackNode node : this.nodes.values())
            if (node instanceof TrackNode.DoubleSlipNode d && d.sideA() && !d.logic().hardwareVerified())
                n.add("Doppelkreuzungsweiche " + d.id() + ": Stelllogik nicht an Hardware geprüft (vorläufige Annahme,"
                        + " Entscheidung 41)");
        this.notices = List.copyOf(n);
    }

    public static Builder builder() { return new Builder(); }

    /**
     * Hinweise zum Graph, die den Bau nicht verhindern, aber nicht als gesichert gelten (Grundlage für den
     * Prüfbericht ab B3), z. B. Doppelkreuzungsweichen mit ungeprüfter Stelllogik.
     */
    public List<String> notices() { return notices; }

    public Collection<TrackNode> nodes() { return nodes.values(); }

    public Collection<TrackEdge> edges() { return edges.values(); }

    public TrackNode node(String id) {
        TrackNode n = nodes.get(id);
        if (n == null) throw new IllegalArgumentException("Unbekannter Knoten: " + id);
        return n;
    }

    public TrackEdge edge(String id) {
        TrackEdge e = edges.get(id);
        if (e == null) throw new IllegalArgumentException("Unbekannte Kante: " + id);
        return e;
    }

    public boolean containsEdge(TrackEdge edge) { return edge.equals(edges.get(edge.id())); }

    /** Summe aller Kantenlängen in mm. */
    public double totalLengthMm() {
        double sum = 0;
        for (TrackEdge e : edges.values()) sum += e.lengthMm();
        return sum;
    }

    /** Die Kante, die am Anschluss {@code port} des Knotens beginnt, in Richtung vom Knoten weg. */
    public DirectedEdge leaving(String nodeId, Port port) {
        Attachment a = attachments.get(new NodePort(nodeId, port));
        if (a == null) throw new IllegalArgumentException("Knoten " + nodeId + " hat keinen Anschluss " + port);
        return new DirectedEdge(a.edge(), a.atFrom());
    }

    /** Ort gültig (Kante bekannt, Abstand innerhalb der Kantenlänge)? Sonst Ausnahme. */
    public void requireLocation(TrackLocation location) {
        TrackEdge e = edge(location.edgeId());
        if (location.offsetMm() > e.lengthMm())
            throw new IllegalArgumentException("Ort " + location + " liegt hinter dem Ende der Kante (" + e.lengthMm() + " mm)");
    }

    /** Alle topologisch möglichen Fortsetzungen am Ende von {@code arriving}, unabhängig von der Weichenlage. */
    public List<Step> steps(DirectedEdge arriving) {
        TrackNode node = node(arriving.endNode());
        List<Step> out = new ArrayList<>();
        for (TrackNode.Transition t : node.transitions(arriving.endPort()))
            out.add(new Step(leaving(node.id(), t.out()), t.requires()));
        return out;
    }

    /**
     * Fortsetzung am Ende von {@code arriving} bei den übergebenen Lagen. Unbekannte Lage oder nicht passende Lage
     * ergibt {@link Continuation.Blocked}, nie eine angenommene Fortsetzung.
     */
    public Continuation next(DirectedEdge arriving, TurnoutStates states) {
        String nodeId = arriving.endNode();
        List<Step> steps = steps(arriving);
        if (steps.isEmpty()) return new Continuation.EndOfTrack(nodeId);
        List<Step> matched = new ArrayList<>();
        boolean unknown = false;
        for (Step s : steps) {
            boolean all = true;
            for (TurnoutRequirement r : s.requires()) {
                TurnoutPosition p = states.position(r.turnoutId());
                if (p == null || p == TurnoutPosition.UNKNOWN) unknown = true;
                if (p != r.position()) all = false;
            }
            if (all) matched.add(s);
        }
        if (matched.size() == 1) return new Continuation.Next(matched.getFirst().next(), matched.getFirst().requires());
        if (matched.size() > 1) return new Continuation.Blocked(nodeId, Continuation.Reason.AMBIGUOUS);
        return new Continuation.Blocked(nodeId,
                unknown ? Continuation.Reason.POSITION_UNKNOWN : Continuation.Reason.POSITION_MISMATCH);
    }

    /**
     * Folgt dem Gleis ab {@code startAlongMm} auf {@code start} über {@code lengthMm} bei festen Lagen. Endet der
     * Weg vorher (Gleisende, unbekannte oder falsche Weichenlage), endet der gelieferte Weg am letzten befahrbaren
     * Kantenende und {@link FollowResult#stop()} nennt den Grund.
     */
    public FollowResult follow(DirectedEdge start, double startAlongMm, double lengthMm, TurnoutStates states) {
        if (!containsEdge(start.edge())) throw new IllegalArgumentException("Kante " + start.edgeId() + " gehört nicht zu diesem Gleisgraph");
        if (!Double.isFinite(lengthMm) || lengthMm < 0) throw new IllegalArgumentException("Ungültige Länge: " + lengthMm);
        if (!Double.isFinite(startAlongMm) || startAlongMm < 0 || startAlongMm > start.lengthMm())
            throw new IllegalArgumentException("Anfang außerhalb der Kante " + start.edgeId() + ": " + startAlongMm);
        List<DirectedEdge> list = new ArrayList<>();
        list.add(start);
        DirectedEdge current = start;
        double remaining = lengthMm;
        double available = start.lengthMm() - startAlongMm;
        int zeroLengthSteps = 0;
        while (remaining > available) {
            remaining -= available;
            Continuation c = next(current, states);
            if (!(c instanceof Continuation.Next n))
                return new FollowResult(GraphPath.of(this, list, startAlongMm, current.lengthMm()), Optional.of(c));
            current = n.edge();
            list.add(current);
            available = current.lengthMm();
            zeroLengthSteps = available > 0 ? 0 : zeroLengthSteps + 1;
            if (zeroLengthSteps > edges.size())
                throw new IllegalStateException("Gleisgraph enthält einen Ring ohne Länge bei " + current);
        }
        double endAlong = (list.size() == 1 ? startAlongMm : 0) + remaining;
        return new FollowResult(GraphPath.of(this, list, startAlongMm, endAlong), Optional.empty());
    }

    /**
     * Grenzzeichen des Schenkels {@code branch} der Weiche (Knoten {@code turnoutNodeId}). Liegt es hinter einem
     * weiteren verzweigenden Knoten, gibt es je möglicher Fortsetzung einen Ort (vorsichtig: alle gelten).
     */
    public List<TrackLocation> clearancePoints(String turnoutNodeId, TurnoutPosition branch) {
        if (!(node(turnoutNodeId) instanceof TrackNode.TurnoutNode))
            throw new IllegalArgumentException("Knoten " + turnoutNodeId + " ist keine Weiche");
        return clearances.get(new ClearanceKey(turnoutNodeId, TurnoutBranches.requireBranch(branch)));
    }

    private Map<ClearanceKey, List<TrackLocation>> resolveClearances(List<String> problems) {
        Map<ClearanceKey, List<TrackLocation>> clearances = new HashMap<>();
        for (TrackNode n : nodes.values()) {
            if (!(n instanceof TrackNode.TurnoutNode t)) continue;
            for (TurnoutPosition branch : List.of(TurnoutPosition.STRAIGHT, TurnoutPosition.DIVERGING)) {
                Port port = branch == TurnoutPosition.STRAIGHT ? Port.STRAIGHT : Port.DIVERGING;
                List<TrackLocation> found = new ArrayList<>();
                collectClearance(leaving(t.id(), port), t.branches().clearanceMm(branch), found, t, branch, problems, 0);
                clearances.put(new ClearanceKey(t.id(), branch), List.copyOf(found));
            }
        }
        return clearances;
    }

    private void collectClearance(DirectedEdge e, double remaining, List<TrackLocation> out, TrackNode.TurnoutNode t,
                                  TurnoutPosition branch, List<String> problems, int depth) {
        if (remaining <= e.lengthMm()) {
            out.add(e.locationAt(remaining));
            return;
        }
        List<Step> steps = steps(e);
        if (steps.isEmpty() || depth > edges.size()) {
            problems.add("Grenzzeichen der Weiche " + t.id() + " (" + branch + ") liegt hinter dem Gleisende "
                    + e.endNode());
            return;
        }
        for (Step s : steps) collectClearance(s.next(), remaining - e.lengthMm(), out, t, branch, problems, depth + 1);
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * Baut den Graph aus verlegten Gleisstücken. Jedes Stück bekommt eine Kennung (ohne „.“, „:“, „~“ und Leerzeichen);
     * jeder Anschluss muss genau einmal verbunden oder als Gleisende angegeben sein. Fehler werden gesammelt und
     * beim {@link #build()} als {@link TrackGraphException} gemeldet.
     *
     * <p>Erzeugte Kennungen: Kante eines einfachen Stücks = Stückkennung; Weichenzweige {@code w.STRAIGHT},
     * {@code w.DIVERGING}; Kreuzung {@code k.A1} … ; Doppelkreuzungsweiche Knoten {@code d.A}, {@code d.B}, Fahrwege
     * {@code d.A1_B1} …; Stoß/Trennstelle {@code joint:x.B~y.A}; Gleisende {@code end:x.A}; Verbindungskanten ohne
     * Länge {@code conn:…}.
     */
    public static final class Builder {

        private static final Pattern INSTANCE_ID = Pattern.compile("[^.:~\\s]+");

        private record Placement(String instanceId, TrackPiece piece, List<String> turnoutIds, TurnoutBranches branches,
                                 TrackPiece.DoubleSlipLengths routeLengths) {}

        private record PortRef(String instanceId, Port port) {
            @Override public String toString() { return instanceId + "." + port; }
        }

        private record Connection(PortRef a, PortRef b, boolean isolating) {}

        private final Map<String, Placement> placements = new LinkedHashMap<>();
        private final List<Connection> connections = new ArrayList<>();
        private final List<PortRef> bufferStops = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        private Builder() {}

        /**
         * Verlegt ein einfaches Stück (gerade, Bogen, Flexgleis) oder eine Kreuzung. Weichen und
         * Doppelkreuzungsweichen brauchen eigene Maße je verlegtem Stück und werden mit {@link #placeTurnout} bzw.
         * {@link #placeDoubleSlip} verlegt; hier sind sie ein Fehler, damit nie stillschweigend der Typ-Vorschlag gilt.
         */
        public Builder place(String instanceId, TrackPiece piece) {
            if (piece instanceof TrackPiece.TurnoutPiece || piece instanceof TrackPiece.DoubleSlipPiece) {
                problems.add("Stück " + instanceId + ": Weiche braucht eigene Längen und Grenzzeichen je verlegter Weiche"
                        + " (Entscheidung 41) – mit placeTurnout bzw. placeDoubleSlip verlegen");
                return this;
            }
            return add(instanceId, piece, List.of(), null, null);
        }

        /**
         * Verlegt eine Weiche. {@code branches} (Zweiglängen und Grenzzeichen dieser Weiche) sind maßgeblich
         * (Entscheidung 41); der Vorschlag des Typs ({@link TrackPiece.TurnoutPiece#suggestion()}) wird nicht gelesen.
         *
         * @param turnoutId logische Weiche (Zubehör), deren Lage diese Weiche stellt
         */
        public Builder placeTurnout(String instanceId, TrackPiece.TurnoutPiece piece, String turnoutId,
                                    TurnoutBranches branches) {
            if (branches == null) {
                problems.add("Weiche " + instanceId + ": Zweiglängen und Grenzzeichen fehlen");
                return this;
            }
            return add(instanceId, piece, List.of(turnoutId), branches, null);
        }

        /**
         * Verlegt eine Doppelkreuzungsweiche mit den Fahrweglängen dieser DKW (maßgeblich, Entscheidung 41) und den
         * logischen Kennungen beider Hälften.
         */
        public Builder placeDoubleSlip(String instanceId, TrackPiece.DoubleSlipPiece piece, String turnoutIdA,
                                       String turnoutIdB, TrackPiece.DoubleSlipLengths routeLengths) {
            if (routeLengths == null) {
                problems.add("Doppelkreuzungsweiche " + instanceId + ": Fahrweglängen fehlen");
                return this;
            }
            return add(instanceId, piece, List.of(turnoutIdA, turnoutIdB), null, routeLengths);
        }

        private Builder add(String instanceId, TrackPiece piece, List<String> turnoutIds, TurnoutBranches branches,
                            TrackPiece.DoubleSlipLengths routeLengths) {
            if (instanceId == null || !INSTANCE_ID.matcher(instanceId).matches()) {
                problems.add("Ungültige Stückkennung: '" + instanceId + "'");
                return this;
            }
            if (piece == null) {
                problems.add("Stück " + instanceId + ": Gleisstück fehlt");
                return this;
            }
            for (String t : turnoutIds)
                if (t == null || t.isBlank()) {
                    problems.add("Stück " + instanceId + ": Weichenkennung fehlt");
                    return this;
                }
            if (placements.putIfAbsent(instanceId, new Placement(instanceId, piece, turnoutIds, branches, routeLengths)) != null)
                problems.add("Stückkennung doppelt: " + instanceId);
            return this;
        }

        /** Stoß zwischen zwei Anschlüssen. */
        public Builder connect(String a, Port portA, String b, Port portB) {
            connections.add(new Connection(new PortRef(a, portA), new PortRef(b, portB), false));
            return this;
        }

        /** Trennstelle (elektrisch getrennter Stoß) zwischen zwei Anschlüssen. */
        public Builder connectIsolated(String a, Port portA, String b, Port portB) {
            connections.add(new Connection(new PortRef(a, portA), new PortRef(b, portB), true));
            return this;
        }

        /** Gleisende bzw. Prellbock an einem Anschluss. */
        public Builder bufferStop(String instanceId, Port port) {
            bufferStops.add(new PortRef(instanceId, port));
            return this;
        }

        // --- Bau ----------------------------------------------------------------------------------------------------

        private static final class EdgeDraft {
            final String id;
            final double length;
            final String instance;
            final TrackEdge.Role role;
            String fromNode;
            Port fromPort;
            String toNode;
            Port toPort;

            EdgeDraft(String id, double length, String instance, TrackEdge.Role role) {
                this.id = id;
                this.length = length;
                this.instance = instance;
                this.role = role;
            }
        }

        private sealed interface Attach permits EdgeEnd, NodeAttach {}

        private record EdgeEnd(EdgeDraft draft, boolean atFrom) implements Attach {}

        private record NodeAttach(String node, Port port) implements Attach {}

        public TrackGraph build() {
            List<String> errors = new ArrayList<>(problems);
            Map<String, TrackNode> nodes = new LinkedHashMap<>();
            Map<String, EdgeDraft> drafts = new LinkedHashMap<>();
            Map<PortRef, Attach> pieceAttach = new HashMap<>();

            for (Placement p : placements.values()) expand(p, nodes, drafts, pieceAttach);

            Set<PortRef> used = new HashSet<>();
            for (Connection c : connections) {
                boolean ok = checkRef(c.a(), errors) & checkRef(c.b(), errors);
                if (!ok) continue;
                if (c.a().equals(c.b())) {
                    errors.add("Anschluss " + c.a() + " mit sich selbst verbunden");
                    continue;
                }
                if (!used.add(c.a()) | !used.add(c.b())) {
                    errors.add("Anschluss mehrfach verbunden: " + c.a() + " ~ " + c.b());
                    continue;
                }
                join(pieceAttach.get(c.a()), pieceAttach.get(c.b()), c.isolating(), c.a() + "~" + c.b(), nodes, drafts);
            }
            for (PortRef r : bufferStops) {
                if (!checkRef(r, errors)) continue;
                if (!used.add(r)) {
                    errors.add("Gleisende an bereits verbundenem Anschluss: " + r);
                    continue;
                }
                String endId = "end:" + r;
                nodes.put(endId, new TrackNode.BufferStopNode(endId));
                switch (pieceAttach.get(r)) {
                    case EdgeEnd e -> setEnd(e, endId, Port.A);
                    case NodeAttach n -> connector("conn:" + r + "~end", n.node(), n.port(), endId, Port.A, drafts);
                }
            }
            for (Placement p : placements.values())
                for (Port port : p.piece().ports()) {
                    PortRef r = new PortRef(p.instanceId(), port);
                    if (!used.contains(r))
                        errors.add("Offener Anschluss " + r + " (verbinden oder als Gleisende angeben)");
                }
            if (!errors.isEmpty()) throw new TrackGraphException(errors);

            Map<String, TrackEdge> edges = new LinkedHashMap<>();
            Map<NodePort, Attachment> attachments = new HashMap<>();
            for (EdgeDraft d : drafts.values()) {
                TrackEdge e = new TrackEdge(d.id, d.fromNode, d.fromPort, d.toNode, d.toPort, d.length, d.instance, d.role);
                edges.put(e.id(), e);
                attach(attachments, new NodePort(e.fromNode(), e.fromPort()), new Attachment(e, true), errors);
                attach(attachments, new NodePort(e.toNode(), e.toPort()), new Attachment(e, false), errors);
            }
            for (TrackNode n : nodes.values())
                for (Port port : n.ports())
                    if (!attachments.containsKey(new NodePort(n.id(), port)))
                        errors.add("Knoten " + n.id() + ": Anschluss " + port + " ohne Kante");
            if (!errors.isEmpty()) throw new TrackGraphException(errors);

            // Grenzzeichen auf einem vorläufigen Graph auflösen, dann unveränderlich einbauen (sichere Veröffentlichung).
            TrackGraph topology = new TrackGraph(nodes, edges, attachments, Map.of());
            Map<ClearanceKey, List<TrackLocation>> clearances = topology.resolveClearances(errors);
            if (!errors.isEmpty()) throw new TrackGraphException(errors);
            return new TrackGraph(nodes, edges, attachments, clearances);
        }

        private boolean checkRef(PortRef r, List<String> errors) {
            Placement p = placements.get(r.instanceId());
            if (p == null) {
                errors.add("Unbekanntes Stück: " + r.instanceId());
                return false;
            }
            if (!p.piece().ports().contains(r.port())) {
                errors.add("Stück " + r.instanceId() + " hat keinen Anschluss " + r.port());
                return false;
            }
            return true;
        }

        private static void attach(Map<NodePort, Attachment> map, NodePort key, Attachment a, List<String> errors) {
            if (map.putIfAbsent(key, a) != null)
                errors.add("Knoten " + key.node() + ": Anschluss " + key.port() + " mehrfach belegt");
        }

        private static void expand(Placement p, Map<String, TrackNode> nodes, Map<String, EdgeDraft> drafts,
                                   Map<PortRef, Attach> pieceAttach) {
            String id = p.instanceId();
            switch (p.piece()) {
                case TrackPiece.PlainPiece plain -> {
                    EdgeDraft d = draft(drafts, id, plain.lengthMm(), id, TrackEdge.Role.PLAIN);
                    pieceAttach.put(new PortRef(id, Port.A), new EdgeEnd(d, true));
                    pieceAttach.put(new PortRef(id, Port.B), new EdgeEnd(d, false));
                }
                case TrackPiece.TurnoutPiece _ -> {
                    TurnoutBranches br = p.branches();
                    nodes.put(id, new TrackNode.TurnoutNode(id, p.turnoutIds().getFirst(), br));
                    pieceAttach.put(new PortRef(id, Port.TIP), new NodeAttach(id, Port.TIP));
                    EdgeDraft s = draft(drafts, id + ".STRAIGHT", br.straightLengthMm(), id, TrackEdge.Role.TURNOUT_STRAIGHT);
                    s.fromNode = id;
                    s.fromPort = Port.STRAIGHT;
                    pieceAttach.put(new PortRef(id, Port.STRAIGHT), new EdgeEnd(s, false));
                    EdgeDraft d = draft(drafts, id + ".DIVERGING", br.divergingLengthMm(), id, TrackEdge.Role.TURNOUT_DIVERGING);
                    d.fromNode = id;
                    d.fromPort = Port.DIVERGING;
                    pieceAttach.put(new PortRef(id, Port.DIVERGING), new EdgeEnd(d, false));
                }
                case TrackPiece.CrossingPiece c -> {
                    nodes.put(id, new TrackNode.CrossingNode(id));
                    halfEdge(id, Port.A1, c.crossingFromA1Mm(), drafts, pieceAttach);
                    halfEdge(id, Port.A2, c.lineA().lengthMm() - c.crossingFromA1Mm(), drafts, pieceAttach);
                    halfEdge(id, Port.B1, c.crossingFromB1Mm(), drafts, pieceAttach);
                    halfEdge(id, Port.B2, c.lineB().lengthMm() - c.crossingFromB1Mm(), drafts, pieceAttach);
                }
                case TrackPiece.DoubleSlipPiece dk -> {
                    String a = id + ".A";
                    String b = id + ".B";
                    String tA = p.turnoutIds().get(0);
                    String tB = p.turnoutIds().get(1);
                    nodes.put(a, new TrackNode.DoubleSlipNode(a, true, tA, tB, dk.logic()));
                    nodes.put(b, new TrackNode.DoubleSlipNode(b, false, tA, tB, dk.logic()));
                    for (Port r : List.of(Port.A1_B1, Port.A1_B2, Port.A2_B1, Port.A2_B2)) {
                        EdgeDraft d = draft(drafts, id + "." + r, p.routeLengths().lengthMm(r), id, TrackEdge.Role.DOUBLE_SLIP_ROUTE);
                        d.fromNode = a;
                        d.fromPort = r;
                        d.toNode = b;
                        d.toPort = r;
                    }
                    for (Port outer : List.of(Port.A1, Port.A2))
                        pieceAttach.put(new PortRef(id, outer), new NodeAttach(a, outer));
                    for (Port outer : List.of(Port.B1, Port.B2))
                        pieceAttach.put(new PortRef(id, outer), new NodeAttach(b, outer));
                }
            }
        }

        private static void halfEdge(String id, Port port, double length, Map<String, EdgeDraft> drafts,
                                     Map<PortRef, Attach> pieceAttach) {
            EdgeDraft d = draft(drafts, id + "." + port, length, id, TrackEdge.Role.CROSSING_HALF);
            d.fromNode = id;
            d.fromPort = port;
            pieceAttach.put(new PortRef(id, port), new EdgeEnd(d, false));
        }

        private static EdgeDraft draft(Map<String, EdgeDraft> drafts, String id, double length, String instance,
                                       TrackEdge.Role role) {
            EdgeDraft d = new EdgeDraft(id, length, instance, role);
            drafts.put(id, d);
            return d;
        }

        private static void setEnd(EdgeEnd e, String node, Port port) {
            if (e.atFrom()) {
                e.draft().fromNode = node;
                e.draft().fromPort = port;
            } else {
                e.draft().toNode = node;
                e.draft().toPort = port;
            }
        }

        private static void connector(String id, String fromNode, Port fromPort, String toNode, Port toPort,
                                      Map<String, EdgeDraft> drafts) {
            EdgeDraft d = draft(drafts, id, 0, null, TrackEdge.Role.CONNECTOR);
            d.fromNode = fromNode;
            d.fromPort = fromPort;
            d.toNode = toNode;
            d.toPort = toPort;
        }

        /**
         * Verbindet zwei Anschlüsse. Kantenende an Kantenende: Stoß-/Trennstellen-Knoten. Kantenende an Knoten
         * (z. B. Weichenspitze): direkt, bei Trennstelle über einen Trennstellen-Knoten und eine Verbindungskante
         * ohne Länge. Knoten an Knoten: Verbindungskante ohne Länge (bei Trennstelle zwei mit Knoten dazwischen).
         *
         * <p>Die Verbindungskanten ohne Länge sind eine vorläufige Modellierung (Entscheidung 41, Punkt 3) und
         * dürfen später überarbeitet werden; Code außerhalb dieses Pakets soll sich nicht auf sie verlassen.
         */
        private static void join(Attach a, Attach b, boolean isolating, String name, Map<String, TrackNode> nodes,
                                 Map<String, EdgeDraft> drafts) {
            String jointId = "joint:" + name;
            if (a instanceof EdgeEnd ea && b instanceof EdgeEnd eb) {
                nodes.put(jointId, new TrackNode.JointNode(jointId, isolating));
                setEnd(ea, jointId, Port.A);
                setEnd(eb, jointId, Port.B);
                return;
            }
            if (a instanceof NodeAttach na && b instanceof NodeAttach nb) {
                if (!isolating) {
                    connector("conn:" + name, na.node(), na.port(), nb.node(), nb.port(), drafts);
                } else {
                    nodes.put(jointId, new TrackNode.JointNode(jointId, true));
                    connector("conn:" + name + "/A", na.node(), na.port(), jointId, Port.A, drafts);
                    connector("conn:" + name + "/B", jointId, Port.B, nb.node(), nb.port(), drafts);
                }
                return;
            }
            EdgeEnd e = a instanceof EdgeEnd x ? x : (EdgeEnd) b;
            NodeAttach n = a instanceof NodeAttach x ? x : (NodeAttach) b;
            if (!isolating) {
                setEnd(e, n.node(), n.port());
            } else {
                nodes.put(jointId, new TrackNode.JointNode(jointId, true));
                setEnd(e, jointId, Port.A);
                connector("conn:" + name, jointId, Port.B, n.node(), n.port(), drafts);
            }
        }
    }
}
