package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.core.TurnoutPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;
import static at.schmoegl.railcore.layout.graph.TrackGeometryTest.assertNear;

/**
 * Gleisgraph auf synthetischen, im Test aufgebauten Anlagen: Oval, Weiche, Kreuzung, Kehrschleife,
 * Doppelkreuzungsweiche; Wegkoordinate, Umkehrung, Längensumme und Prüfung beim Bau.
 * Alle Maße sind synthetische Testwerte, keine Herstellerangaben.
 */
public class TrackGraphTest {

    private static final TurnoutStates STRAIGHT = TurnoutStates.of(Map.of("w", TurnoutPosition.STRAIGHT));
    private static final TurnoutStates DIVERGING = TurnoutStates.of(Map.of("w", TurnoutPosition.DIVERGING));

    static final TrackPiece G1000 = TrackPiece.plain("TEST-G1000", TrackGeometry.straight(1000));
    static final TrackPiece R500 = TrackPiece.plain("TEST-R500-45", TrackGeometry.arc(500, 45));
    static final double W_DIV = 204.7;
    /** Weichentyp mit Vorschlagswerten; maßgeblich sind die Werte je verlegter Weiche (Entscheidung 41). */
    static final TrackPiece.TurnoutPiece W = new TrackPiece.TurnoutPiece("TEST-W", TrackPiece.Hand.LEFT,
            new TurnoutBranches(200, W_DIV, 150, 250));
    static final TurnoutBranches W_BR = W.suggestion();
    static final double R500_LEN = 500 * Math.PI / 4;

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    private static void assertLocation(TrackLocation expected, TrackLocation actual) {
        assertEquals(expected.edgeId(), actual.edgeId());
        assertNear(expected.offsetMm(), actual.offsetMm(), "Ort auf " + expected.edgeId());
    }

    // --- Anlagen ------------------------------------------------------------------------------------------------

    /** Oval: s1, c1–c4 (Halbkreis), s2, c5–c8 (Halbkreis), geschlossen. */
    static TrackGraph oval() {
        TrackGraph.Builder b = TrackGraph.builder();
        List<String> ring = List.of("s1", "c1", "c2", "c3", "c4", "s2", "c5", "c6", "c7", "c8");
        for (String id : ring) b.place(id, id.startsWith("s") ? G1000 : R500);
        for (int i = 0; i < ring.size(); i++) b.connect(ring.get(i), Port.B, ring.get((i + 1) % ring.size()), Port.A);
        return b.build();
    }

    /** Prellbock – g0 – Weiche w; gerader Strang → gs – Prellbock; Abzweig → gd – Prellbock. */
    static TrackGraph turnoutYard(TurnoutBranches branches) {
        return TrackGraph.builder()
                .place("g0", G1000).placeTurnout("w", W, "w", branches).place("gs", G1000).place("gd", G1000)
                .bufferStop("g0", Port.A).connect("g0", Port.B, "w", Port.TIP)
                .connect("w", Port.STRAIGHT, "gs", Port.A).bufferStop("gs", Port.B)
                .connect("w", Port.DIVERGING, "gd", Port.A).bufferStop("gd", Port.B)
                .build();
    }

    /** Kehrschleife: Prellbock – g0 – w; gerader Strang → l1, Bogen c1–c4, l2 → zurück an den Abzweig von w. */
    static TrackGraph reversingLoop() {
        TrackGraph.Builder b = TrackGraph.builder()
                .place("g0", G1000).placeTurnout("w", W, "w", W_BR).place("l1", G1000).place("l2", G1000)
                .bufferStop("g0", Port.A).connect("g0", Port.B, "w", Port.TIP)
                .connect("w", Port.STRAIGHT, "l1", Port.A);
        String prev = "l1";
        for (String c : List.of("c1", "c2", "c3", "c4")) {
            b.place(c, R500).connect(prev, Port.B, c, Port.A);
            prev = c;
        }
        return b.connect(prev, Port.B, "l2", Port.A).connect("l2", Port.B, "w", Port.DIVERGING).build();
    }

    // --- Oval ---------------------------------------------------------------------------------------------------

    public void testOvalIsClosedRingWithComputedLengths() {
        TrackGraph g = oval();
        assertEquals(10, g.edges().size());
        assertEquals(10, g.nodes().size());
        for (TrackNode n : g.nodes()) assertEquals(NodeKind.JOINT, n.kind());
        assertNear(2000 + 1000 * Math.PI, g.totalLengthMm(), "Umfang aus Geometrie");
        assertNear(R500_LEN, g.edge("c1").lengthMm(), "Bogenlänge c1");
    }

    public void testOvalFollowWrapsAroundWithoutStop() {
        TrackGraph g = oval();
        double lap = g.totalLengthMm();
        TrackGraph.FollowResult r = g.follow(g.edge("s1").forward(), 0, 2 * lap, TurnoutStates.allUnknown());
        assertTrue(r.complete(), "Oval ohne Weichen hat kein Ende");
        assertNear(2 * lap, r.path().lengthMm(), "zwei Runden");
        assertEquals(20, r.path().edges().size());
        assertLocation(new TrackLocation("c8", R500_LEN), r.path().end());
        List<Double> s = r.path().coordinatesOf(new TrackLocation("s1", 500));
        assertEquals(2, s.size());
        assertNear(500, s.get(0), "erste Runde");
        assertNear(lap + 500, s.get(1), "zweite Runde");
        // Gegenrichtung genauso ohne Sonderfall
        TrackGraph.FollowResult back = g.follow(g.edge("s1").backward(), 0, 1500, TurnoutStates.allUnknown());
        assertTrue(back.complete(), "rückwärts");
        assertEquals("c8", back.path().edges().get(1).edgeId());
        assertEquals(false, back.path().edges().get(1).forward());
    }

    // --- Weiche -------------------------------------------------------------------------------------------------

    public void testTurnoutNodeCarriesBranchLengths() {
        TrackGraph g = turnoutYard(W_BR);
        TrackNode.TurnoutNode w = (TrackNode.TurnoutNode) g.node("w");
        assertEquals(NodeKind.TURNOUT, w.kind());
        assertEquals("w", w.turnoutId());
        assertNear(200, w.branches().straightLengthMm(), "Zweiglänge gerade");
        assertNear(W_DIV, w.branches().divergingLengthMm(), "Zweiglänge Abzweig");
        assertNear(200, g.edge("w.STRAIGHT").lengthMm(), "Kante gerade");
        assertNear(W_DIV, g.edge("w.DIVERGING").lengthMm(), "Kante Abzweig");
        assertEquals(TrackEdge.Role.TURNOUT_DIVERGING, g.edge("w.DIVERGING").role());
        assertNear(3000 + 200 + W_DIV, g.totalLengthMm(), "Summe");
    }

    public void testTurnoutFacingDependsOnPosition() {
        TrackGraph g = turnoutYard(W_BR);
        DirectedEdge in = g.edge("g0").forward();
        assertEquals(new Continuation.Next(g.edge("w.STRAIGHT").forward(),
                List.of(new TurnoutRequirement("w", TurnoutPosition.STRAIGHT))), g.next(in, STRAIGHT));
        assertEquals(g.edge("w.DIVERGING").forward(), ((Continuation.Next) g.next(in, DIVERGING)).edge());
        assertEquals(new Continuation.Blocked("w", Continuation.Reason.POSITION_UNKNOWN),
                g.next(in, TurnoutStates.allUnknown()));
    }

    public void testTurnoutTrailingNeedsMatchingPosition() {
        TrackGraph g = turnoutYard(W_BR);
        // gs → Stoß → gerader Strang rückwärts → Spitze
        Continuation toBranch = g.next(g.edge("gs").backward(), TurnoutStates.allUnknown());
        DirectedEdge branch = ((Continuation.Next) toBranch).edge();
        assertEquals(g.edge("w.STRAIGHT").backward(), branch);
        assertEquals(g.edge("g0").backward(), ((Continuation.Next) g.next(branch, STRAIGHT)).edge());
        assertEquals(new Continuation.Blocked("w", Continuation.Reason.POSITION_MISMATCH), g.next(branch, DIVERGING));
        assertEquals(new Continuation.Blocked("w", Continuation.Reason.POSITION_UNKNOWN),
                g.next(branch, TurnoutStates.allUnknown()));
        DirectedEdge divBranch = g.edge("w.DIVERGING").backward();
        assertEquals(g.edge("g0").backward(), ((Continuation.Next) g.next(divBranch, DIVERGING)).edge());
        assertEquals(new Continuation.Blocked("w", Continuation.Reason.POSITION_MISMATCH), g.next(divBranch, STRAIGHT));
        assertEquals(new Continuation.EndOfTrack("end:g0.A"), g.next(g.edge("g0").backward(), STRAIGHT));
    }

    public void testFollowThroughTurnoutBothBranches() {
        TrackGraph g = turnoutYard(W_BR);
        TrackGraph.FollowResult s = g.follow(g.edge("g0").forward(), 500, 1000, STRAIGHT);
        assertTrue(s.complete(), "gerade");
        assertEquals(List.of("g0", "w.STRAIGHT", "gs"), s.path().edges().stream().map(DirectedEdge::edgeId).toList());
        assertLocation(new TrackLocation("gs", 300), s.path().end());
        TrackGraph.FollowResult d = g.follow(g.edge("g0").forward(), 500, 1000, DIVERGING);
        assertLocation(new TrackLocation("gd", 1000 - 500 - W_DIV), d.path().end());
        TrackGraph.FollowResult toEnd = g.follow(g.edge("g0").forward(), 0, 99_999, DIVERGING);
        assertEquals(new Continuation.EndOfTrack("end:gd.B"), toEnd.stop().orElseThrow());
        assertNear(2000 + W_DIV, toEnd.path().lengthMm(), "bis Prellbock");
        TrackGraph.FollowResult unknown = g.follow(g.edge("g0").forward(), 0, 5000, TurnoutStates.allUnknown());
        assertEquals(new Continuation.Blocked("w", Continuation.Reason.POSITION_UNKNOWN), unknown.stop().orElseThrow());
        assertNear(1000, unknown.path().lengthMm(), "Halt vor der Weiche mit unbekannter Lage");
    }

    public void testClearancePointsOnBranchAndBeyond() {
        TrackGraph g = turnoutYard(W_BR);
        List<TrackLocation> s = g.clearancePoints("w", TurnoutPosition.STRAIGHT);
        assertEquals(1, s.size());
        assertLocation(new TrackLocation("w.STRAIGHT", 150), s.getFirst());
        // Grenzzeichen Abzweig (250 mm) liegt hinter dem Weichenende auf dem Anschlussgleis
        List<TrackLocation> d = g.clearancePoints("w", TurnoutPosition.DIVERGING);
        assertEquals(1, d.size());
        assertLocation(new TrackLocation("gd", 250 - W_DIV), d.getFirst());
        assertThrows(IllegalArgumentException.class, () -> g.clearancePoints("g0", TurnoutPosition.STRAIGHT));
    }

    public void testClearanceBehindBufferStopIsError() {
        TurnoutBranches far = new TurnoutBranches(200, 200, 150, 1500);
        TrackGraphException e = expectGraphError(() -> turnoutYard(far));
        assertTrue(e.problems().getFirst().contains("Grenzzeichen"), "Meldung nennt Grenzzeichen: " + e.problems());
    }

    public void testClearanceBehindFurtherTurnoutYieldsAllContinuations() {
        // Gerader Strang von w1 führt direkt an die Spitze von w2; Grenzzeichen 250 mm hinter der Spitze von w1.
        TurnoutBranches w1 = new TurnoutBranches(200, 210, 250, 150);
        TrackGraph g = TrackGraph.builder()
                .placeTurnout("w1", W, "w1", w1).placeTurnout("w2", W, "w2", W_BR).place("a", G1000).place("b", G1000).place("c", G1000).place("d", G1000)
                .bufferStop("a", Port.A).connect("a", Port.B, "w1", Port.TIP)
                .connect("w1", Port.STRAIGHT, "w2", Port.TIP)
                .connect("w1", Port.DIVERGING, "b", Port.A).bufferStop("b", Port.B)
                .connect("w2", Port.STRAIGHT, "c", Port.A).bufferStop("c", Port.B)
                .connect("w2", Port.DIVERGING, "d", Port.A).bufferStop("d", Port.B)
                .build();
        List<TrackLocation> p = g.clearancePoints("w1", TurnoutPosition.STRAIGHT);
        assertEquals(2, p.size());
        assertLocation(new TrackLocation("w2.STRAIGHT", 50), p.get(0));
        assertLocation(new TrackLocation("w2.DIVERGING", 50), p.get(1));
    }

    // --- Kreuzung -----------------------------------------------------------------------------------------------

    /** Kreuzungstyp mit Vorschlagswerten; maßgeblich sind die Maße je verlegter Kreuzung (Entscheidung 42). */
    static final TrackPiece.CrossingPiece K = new TrackPiece.CrossingPiece("TEST-K",
            TrackPiece.CrossingDimensions.symmetric(300, 300));

    static TrackGraph crossing() { return crossing(K.suggestion()); }

    static TrackGraph crossing(TrackPiece.CrossingDimensions dimensions) {
        TrackGraph.Builder b = TrackGraph.builder().placeCrossing("k", K, dimensions);
        for (Port p : List.of(Port.A1, Port.A2, Port.B1, Port.B2)) {
            String id = "x" + p;
            b.place(id, G1000).connect("k", p, id, Port.A).bufferStop(id, Port.B);
        }
        return b.build();
    }

    public void testCrossingKeepsPairs() {
        TrackGraph g = crossing();
        assertEquals(NodeKind.CROSSING, g.node("k").kind());
        assertNear(150, g.edge("k.A1").lengthMm(), "halber Strang");
        // xA1 rückwärts fährt auf die Kreuzung zu
        TrackGraph.FollowResult r = g.follow(g.edge("xA1").backward(), 0, 1400, TurnoutStates.allUnknown());
        assertTrue(r.complete(), "Kreuzung braucht keine Weichenlage");
        assertEquals(List.of("xA1", "k.A1", "k.A2", "xA2"), r.path().edges().stream().map(DirectedEdge::edgeId).toList());
        assertNear(1400, r.path().lengthMm(), "Länge");
        assertEquals(List.of(), r.path().turnoutRequirements());
        // Strangwechsel A1 → B2 gibt es nicht
        assertThrows(IllegalArgumentException.class, () -> GraphPath.whole(g, List.of(
                g.edge("xA1").backward(), g.edge("k.A1").backward(), g.edge("k.B2").forward())));
        assertEquals(1, g.steps(g.edge("k.B1").backward()).size());
        assertEquals(g.edge("k.B2").forward(), g.steps(g.edge("k.B1").backward()).getFirst().next());
    }

    // --- Kehrschleife -------------------------------------------------------------------------------------------

    public void testReversingLoopWithoutSpecialCase() {
        TrackGraph g = reversingLoop();
        // Mit Lage gerade in die Schleife; zurück an der Weiche passt die Lage nicht mehr → Halt.
        TrackGraph.FollowResult in = g.follow(g.edge("g0").forward(), 0, 99_999, STRAIGHT);
        assertEquals(new Continuation.Blocked("w", Continuation.Reason.POSITION_MISMATCH), in.stop().orElseThrow());
        DirectedEdge last = in.path().edges().getLast();
        assertEquals(g.edge("w.DIVERGING").backward(), last);
        // Nach dem Umstellen auf abzweigend geht es auf derselben Kante g0 in Gegenrichtung weiter.
        assertEquals(g.edge("g0").backward(), ((Continuation.Next) g.next(last, DIVERGING)).edge());

        List<DirectedEdge> all = new ArrayList<>(in.path().edges());
        all.add(g.edge("g0").backward());
        GraphPath p = GraphPath.whole(g, all);
        assertTrue(p.edges().getFirst().forward() && !p.edges().getLast().forward(), "Richtungswechsel auf g0");
        assertEquals(List.of(new TurnoutRequirement("w", TurnoutPosition.STRAIGHT),
                new TurnoutRequirement("w", TurnoutPosition.DIVERGING)), p.turnoutRequirements());
        assertEquals(false, p.passableWith(STRAIGHT));
        assertEquals(false, p.passableWith(DIVERGING));
        double len = 1000 + 200 + 1000 + 4 * R500_LEN + 1000 + W_DIV + 1000;
        assertNear(len, p.lengthMm(), "Länge der Kehrschleifenfahrt");
        List<Double> s = p.coordinatesOf(new TrackLocation("g0", 400));
        assertEquals(2, s.size());
        assertNear(400, s.get(0), "Hinweg");
        assertNear(len - 400, s.get(1), "Rückweg");
    }

    // --- Wegkoordinate, Umkehrung, Längensumme ------------------------------------------------------------------

    private static GraphPath loopPath(TrackGraph g) {
        List<DirectedEdge> e = new ArrayList<>(g.follow(g.edge("g0").forward(), 0, 99_999, STRAIGHT).path().edges());
        e.add(g.edge("g0").backward());
        return GraphPath.of(g, e, 250, 700);
    }

    public void testCoordinateRoundTrip() {
        TrackGraph g = reversingLoop();
        GraphPath p = loopPath(g);
        assertLocation(new TrackLocation("g0", 250), p.start());
        assertLocation(new TrackLocation("g0", 300), p.end());
        for (double s = 0; s <= p.lengthMm(); s += 37.5) {
            TrackLocation loc = p.locate(s);
            g.requireLocation(loc);
            final double target = s;
            assertTrue(p.coordinatesOf(loc).stream().anyMatch(x -> Math.abs(x - target) < 1e-6),
                    "Rückrechnung bei s=" + s + " → " + loc + " → " + p.coordinatesOf(loc));
        }
        // Kantengrenze: Anfang der nächsten Kante
        assertLocation(new TrackLocation("w.STRAIGHT", 0), p.locate(750));
        assertEquals(List.of(), p.coordinatesOf(new TrackLocation("g0", 100)));
        assertThrows(IllegalArgumentException.class, () -> p.locate(-1));
        assertThrows(IllegalArgumentException.class, () -> p.locate(p.lengthMm() + 1));
    }

    public void testReversedPath() {
        TrackGraph g = reversingLoop();
        GraphPath p = loopPath(g);
        GraphPath r = p.reversed();
        assertNear(p.lengthMm(), r.lengthMm(), "gleiche Länge");
        assertLocation(p.end(), r.start());
        assertLocation(p.start(), r.end());
        for (double s = 1; s < p.lengthMm(); s += 53.1)
            assertLocation(p.locate(s), r.locate(r.lengthMm() - s));
        GraphPath rr = r.reversed();
        assertEquals(p.edges(), rr.edges());
        assertNear(p.startOffsetMm(), rr.startOffsetMm(), "Anfang");
        assertNear(p.endOffsetMm(), rr.endOffsetMm(), "Ende");
        // Lagen der Umkehrung stimmen mit einer frisch geprüften Umkehrung überein
        GraphPath checked = GraphPath.of(g, r.edges(), r.startOffsetMm(), r.endOffsetMm());
        assertEquals(checked.turnoutRequirements(), r.turnoutRequirements());
    }

    public void testLengthSumOfRanges() {
        TrackGraph g = reversingLoop();
        GraphPath p = loopPath(g);
        double sum = 0;
        for (TrackRange r : p.ranges()) sum += r.lengthMm();
        assertNear(p.lengthMm(), sum, "Summe der Bereiche");
        List<TrackRange> part = p.ranges(100, 1500);
        double partSum = 0;
        for (TrackRange r : part) partSum += r.lengthMm();
        assertNear(1400, partSum, "Teilbereich");
        assertEquals(new TrackRange("g0", 350, 1000), part.get(0));
        // Rückweg auf g0 (gegen Bezugsrichtung) liegt als Bereich 300–1000 auf g0
        TrackRange back = p.ranges().getLast();
        assertEquals("g0", back.edgeId());
        assertNear(300, back.fromMm(), "Rückweg von");
        assertNear(1000, back.toMm(), "Rückweg bis");
        // Gesamtlänge des Graphen = Summe der Gleisstücke
        assertNear(3000 + 200 + W_DIV + 4 * R500_LEN, g.totalLengthMm(), "Summe Gleisstücke");
    }

    public void testPathValidation() {
        TrackGraph g = turnoutYard(W_BR);
        assertThrows(IllegalArgumentException.class, () -> GraphPath.whole(g, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> GraphPath.whole(g, List.of(g.edge("g0").forward(), g.edge("gs").forward())));
        assertThrows(IllegalArgumentException.class, () -> GraphPath.of(g, List.of(g.edge("g0").forward()), 600, 500));
        assertThrows(IllegalArgumentException.class, () -> GraphPath.of(g, List.of(g.edge("g0").forward()), 0, 1001));
        TrackGraph other = turnoutYard(W_BR);
        TrackEdge foreign = new TrackEdge("g0", "x", Port.A, "y", Port.B, 5, "g0", TrackEdge.Role.PLAIN);
        assertThrows(IllegalArgumentException.class, () -> GraphPath.whole(other, List.of(foreign.forward())));
        assertThrows(IllegalArgumentException.class, () -> g.requireLocation(new TrackLocation("g0", 1000.5)));
    }

    // --- Stoß, Trennstelle, Verbindungen ------------------------------------------------------------------------

    public void testIsolatingJointsAndConnectors() {
        TrackGraph g = TrackGraph.builder()
                .place("a", G1000).place("b", G1000).placeTurnout("w", W, "w", W_BR).place("c", G1000).place("d", G1000)
                .bufferStop("a", Port.A).connectIsolated("a", Port.B, "b", Port.A)
                .connectIsolated("b", Port.B, "w", Port.TIP)
                .connect("w", Port.STRAIGHT, "c", Port.A).bufferStop("c", Port.B)
                .connect("w", Port.DIVERGING, "d", Port.A).bufferStop("d", Port.B)
                .build();
        assertEquals(NodeKind.ISOLATING_JOINT, g.node("joint:a.B~b.A").kind());
        assertEquals(NodeKind.ISOLATING_JOINT, g.node("joint:b.B~w.TIP").kind());
        TrackEdge conn = g.edge("conn:b.B~w.TIP");
        assertEquals(TrackEdge.Role.CONNECTOR, conn.role());
        assertNear(0, conn.lengthMm(), "Verbindung ohne Länge");
        GraphPath p = g.follow(g.edge("a").forward(), 0, 2100, STRAIGHT).path();
        assertEquals(List.of("a", "b", "conn:b.B~w.TIP", "w.STRAIGHT"),
                p.edges().stream().map(DirectedEdge::edgeId).toList());
        assertLocation(new TrackLocation("w.STRAIGHT", 0), p.locate(2000));
        assertLocation(new TrackLocation("w.STRAIGHT", 100), p.end());
    }

    public void testTurnoutsTipToTip() {
        TrackGraph g = TrackGraph.builder()
                .placeTurnout("w1", W, "w1", W_BR).placeTurnout("w2", W, "w2", W_BR)
                .connect("w1", Port.TIP, "w2", Port.TIP)
                .place("a", G1000).place("b", G1000).place("c", G1000).place("d", G1000)
                .connect("w1", Port.STRAIGHT, "a", Port.A).bufferStop("a", Port.B)
                .connect("w1", Port.DIVERGING, "b", Port.A).bufferStop("b", Port.B)
                .connect("w2", Port.STRAIGHT, "c", Port.A).bufferStop("c", Port.B)
                .connect("w2", Port.DIVERGING, "d", Port.A).bufferStop("d", Port.B)
                .build();
        TurnoutStates states = TurnoutStates.of(Map.of("w1", TurnoutPosition.STRAIGHT, "w2", TurnoutPosition.DIVERGING));
        TrackGraph.FollowResult r = g.follow(g.edge("a").backward(), 0, 99_999, states);
        assertEquals(new Continuation.EndOfTrack("end:d.B"), r.stop().orElseThrow());
        assertEquals(List.of(new TurnoutRequirement("w1", TurnoutPosition.STRAIGHT),
                new TurnoutRequirement("w2", TurnoutPosition.DIVERGING)), r.path().turnoutRequirements());
        assertNear(2000 + 200 + W_DIV, r.path().lengthMm(), "Länge");
    }

    // --- Doppelkreuzungsweiche (Grundmodell) --------------------------------------------------------------------

    public void testDoubleSlipRoutesFollowBothHalves() {
        TrackPiece.DoubleSlipPiece dkw = new TrackPiece.DoubleSlipPiece("TEST-DKW",
                new TrackPiece.DoubleSlipLengths(Map.of(Port.A1_B1, 230.0, Port.A2_B2, 230.0, Port.A1_B2, 230.0, Port.A2_B1, 230.0)),
                DoubleSlipLogic.provisionalAssumption());
        // Instanzwerte der verlegten DKW sind maßgeblich, nicht der Typ-Vorschlag (Entscheidung 41)
        TrackPiece.DoubleSlipLengths lengths = new TrackPiece.DoubleSlipLengths(Map.of(
                Port.A1_B1, 231.0, Port.A2_B2, 231.0, Port.A1_B2, 236.5, Port.A2_B1, 236.5));
        TrackGraph.Builder b = TrackGraph.builder().placeDoubleSlip("d", dkw, "d.A", "d.B", lengths);
        for (Port p : List.of(Port.A1, Port.A2, Port.B1, Port.B2)) {
            String id = "x" + p;
            b.place(id, G1000).connect("d", p, id, Port.A).bufferStop(id, Port.B);
        }
        TrackGraph g = b.build();
        assertEquals(NodeKind.DOUBLE_SLIP, g.node("d.A").kind());
        assertNear(236.5, g.edge("d.A1_B2").lengthMm(), "Bogenfahrweg aus Instanzwert");
        assertEquals(1, g.notices().size());
        assertTrue(g.notices().getFirst().contains("nicht an Hardware geprüft"), g.notices().toString());
        DirectedEdge fromA1 = g.edge("xA1").backward();
        // xA1 hängt direkt am Knoten d.A (Anschluss A1); fährt man xA1 rückwärts, kommt man über A1 an.
        TurnoutStates ss = TurnoutStates.of(Map.of("d.A", TurnoutPosition.STRAIGHT, "d.B", TurnoutPosition.STRAIGHT));
        TurnoutStates dd = TurnoutStates.of(Map.of("d.A", TurnoutPosition.DIVERGING, "d.B", TurnoutPosition.DIVERGING));
        TurnoutStates sd = TurnoutStates.of(Map.of("d.A", TurnoutPosition.STRAIGHT, "d.B", TurnoutPosition.DIVERGING));
        assertEquals(g.edge("d.A1_B1").forward(), ((Continuation.Next) g.next(fromA1, ss)).edge());
        assertEquals(g.edge("d.A1_B2").forward(), ((Continuation.Next) g.next(fromA1, dd)).edge());
        assertEquals(new Continuation.Blocked("d.A", Continuation.Reason.POSITION_MISMATCH), g.next(fromA1, sd));
        assertEquals(new Continuation.Blocked("d.A", Continuation.Reason.POSITION_UNKNOWN),
                g.next(fromA1, TurnoutStates.allUnknown()));
        TrackGraph.FollowResult r = g.follow(fromA1, 0, 1500, dd);
        assertEquals(List.of("xA1", "d.A1_B2", "xB2"), r.path().edges().stream().map(DirectedEdge::edgeId).toList());
    }

    // --- Instanzwerte statt Typwerte (Entscheidung 41) ---------------------------------------------------------

    public void testInstanceValuesOverrideTypeSuggestion() {
        // Typ schlägt 200/204.7 mm vor; die verlegte Weiche hat eingetragene, abweichende Werte.
        TurnoutBranches entered = new TurnoutBranches(239.07, 241.5, 120, 260);
        TrackGraph g = turnoutYard(entered);
        assertNear(239.07, g.edge("w.STRAIGHT").lengthMm(), "gerader Strang aus Instanzwert");
        assertNear(241.5, g.edge("w.DIVERGING").lengthMm(), "Abzweig aus Instanzwert");
        assertEquals(entered, ((TrackNode.TurnoutNode) g.node("w")).branches());
        assertLocation(new TrackLocation("gd", 260 - 241.5), g.clearancePoints("w", TurnoutPosition.DIVERGING).getFirst());
        assertEquals(List.of(), g.notices());
    }

    public void testCrossingInstanceDimensionsOverrideType() {
        TrackGraph g = crossing(new TrackPiece.CrossingDimensions(310, 290, 120, 145));
        assertNear(120, g.edge("k.A1").lengthMm(), "A1 bis Kreuzungspunkt");
        assertNear(190, g.edge("k.A2").lengthMm(), "Kreuzungspunkt bis A2");
        assertNear(145, g.edge("k.B1").lengthMm(), "B1 bis Kreuzungspunkt");
        assertNear(145, g.edge("k.B2").lengthMm(), "Kreuzungspunkt bis B2");
        assertNear(4000 + 310 + 290, g.totalLengthMm(), "Summe");
        expectGraphError(() -> TrackGraph.builder().place("k", K).build());
        expectGraphError(() -> TrackGraph.builder().placeCrossing("k", K, null).build());
    }

    public void testPlainPieceOptionalInstanceLength() {
        // Bogen-Typ 500 mm / 45° (392,7 mm); verlegt mit gemessener Länge 388 mm, Gerade gekürzt auf 870 mm.
        TrackGraph g = TrackGraph.builder()
                .place("a", G1000, 870).place("c", R500, 388).place("b", G1000)
                .bufferStop("a", Port.A).connect("a", Port.B, "c", Port.A).connect("c", Port.B, "b", Port.A)
                .bufferStop("b", Port.B).build();
        assertNear(870, g.edge("a").lengthMm(), "Gerade mit eigener Länge");
        assertNear(388, g.edge("c").lengthMm(), "Bogen mit eigener Länge");
        assertNear(1000, g.edge("b").lengthMm(), "ohne eigene Länge gilt der Typ");
        assertNear(870 + 388 + 1000, g.follow(g.edge("a").forward(), 0, 99_999, TurnoutStates.allUnknown())
                .path().lengthMm(), "Fahrweg");
        expectGraphError(() -> TrackGraph.builder().place("a", G1000, 0).bufferStop("a", Port.A).bufferStop("a", Port.B).build());
        expectGraphError(() -> TrackGraph.builder().place("a", G1000, Double.NaN).build());
        expectGraphError(() -> TrackGraph.builder().place("k", K, 300).build());
    }

    public void testTurnoutWithoutInstanceValuesIsRejected() {
        TrackGraphException e = expectGraphError(() -> TrackGraph.builder().place("w", W).build());
        assertTrue(e.problems().getFirst().contains("eigene Maße"), e.problems().toString());
        expectGraphError(() -> TrackGraph.builder().placeTurnout("w", W, "w", null).build());
    }

    // --- Prüfung beim Bau ---------------------------------------------------------------------------------------

    private static TrackGraphException expectGraphError(Runnable r) {
        try {
            r.run();
        } catch (TrackGraphException e) {
            return e;
        }
        throw new AssertionError("TrackGraphException erwartet");
    }

    public void testBuildValidation() {
        TrackGraphException open = expectGraphError(() -> TrackGraph.builder().place("a", G1000).bufferStop("a", Port.A).build());
        assertTrue(open.problems().getFirst().contains("Offener Anschluss a.B"), open.problems().toString());
        TrackGraphException twice = expectGraphError(() -> TrackGraph.builder()
                .place("a", G1000).place("b", G1000).place("c", G1000)
                .connect("a", Port.B, "b", Port.A).connect("a", Port.B, "c", Port.A).build());
        assertTrue(twice.problems().stream().anyMatch(p -> p.contains("mehrfach")), twice.problems().toString());
        TrackGraphException unknown = expectGraphError(() -> TrackGraph.builder()
                .place("a", G1000).connect("a", Port.B, "zz", Port.A).bufferStop("a", Port.A).build());
        assertTrue(unknown.problems().stream().anyMatch(p -> p.contains("Unbekanntes Stück: zz")), unknown.problems().toString());
        TrackGraphException badPort = expectGraphError(() -> TrackGraph.builder()
                .place("a", G1000).bufferStop("a", Port.TIP).build());
        assertTrue(badPort.problems().stream().anyMatch(p -> p.contains("keinen Anschluss TIP")), badPort.problems().toString());
        expectGraphError(() -> TrackGraph.builder().place("a.b", G1000).build());
        expectGraphError(() -> TrackGraph.builder().place("a", G1000).place("a", G1000).build());
        expectGraphError(() -> TrackGraph.builder().place("a", G1000).connect("a", Port.A, "a", Port.A).build());
    }
}
