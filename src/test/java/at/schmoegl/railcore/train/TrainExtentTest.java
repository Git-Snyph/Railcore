package at.schmoegl.railcore.train;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.layout.graph.Block;
import at.schmoegl.railcore.layout.graph.Continuation;
import at.schmoegl.railcore.layout.graph.DirectedLocation;
import at.schmoegl.railcore.layout.graph.GraphPath;
import at.schmoegl.railcore.layout.graph.TrackGraph;
import at.schmoegl.railcore.layout.graph.TrackLayout;
import at.schmoegl.railcore.layout.graph.TrackRange;
import at.schmoegl.railcore.layout.graph.TurnoutStates;

import java.util.List;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;
import static at.schmoegl.railcore.train.GraphLayouts.W_DIV;
import static at.schmoegl.railcore.train.GraphLayouts.W_DIVERGING_SET;
import static at.schmoegl.railcore.train.GraphLayouts.W_STRAIGHT;
import static at.schmoegl.railcore.train.GraphLayouts.W_STRAIGHT_SET;

/**
 * Zugposition und Ausdehnung im Gleisgraph (Schritt C1): Zugschluss, Belegung über Blöcke, Weichen und Bezirke,
 * mehrdeutige Lage bei unbekannter Weiche, Kehrschleife mit Richtungswechsel, Hin- und Rückrechnung.
 */
public class TrainExtentTest {

    private static final double TOL = 1e-6;

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    private static void assertNear(double expected, double actual, String what) {
        if (Math.abs(expected - actual) > 1e-6) throw new AssertionError(what + ": erwartet " + expected + ", erhalten " + actual);
    }

    private static void assertAt(DirectedLocation expected, DirectedLocation actual) {
        assertTrue(expected.sameAs(actual, TOL), "erwartet " + expected + ", erhalten " + actual);
    }

    private static TrainPosition at(String edge, double mm, boolean forward) {
        return TrainPosition.confirmed(DirectedLocation.of(edge, mm, forward), Direction.FORWARD, 5);
    }

    // --- Zugschluss und Belegung --------------------------------------------------------------------------------

    public void testTailInSameEdge() {
        TrackLayout l = GraphLayouts.oval();
        TrainExtent x = new TrainExtent.Resolver(l).extent(at("e1", 700, true), 300, TurnoutStates.allUnknown());
        assertAt(DirectedLocation.of("e1", 400, true), x.tail().orElseThrow());
        assertEquals(List.of(new TrackRange("e1", 400, 700)), x.ranges());
        assertEquals(List.of("se1"), x.sectionIds());
        assertEquals(List.of("O1"), x.blockIds());
        assertEquals(List.of(1), x.districts());
        assertTrue(x.certain(), "eindeutig");
        assertNear(300, x.body().orElseThrow().lengthMm(), "Weg vom Schluss zur Spitze");
    }

    public void testTailStillInPreviousBlockAcrossTurnout() {
        // Zug kam aus A über den geraden Strang nach S: Spitze s1 @ 300, 800 mm lang → Schluss auf a2
        TrackLayout l = GraphLayouts.station();
        TrainExtent x = new TrainExtent.Resolver(l).extent(at("s1", 300, true), 800, W_STRAIGHT_SET);
        assertAt(DirectedLocation.of("a2", 1000 - (800 - 300 - W_STRAIGHT), true), x.tail().orElseThrow());
        assertEquals(List.of("S", "A"), x.blockIds());                 // von der Spitze zum Schluss
        assertEquals(List.of("ss1", "st", "sa2"), x.sectionIds());
        assertEquals(List.of("w"), x.turnoutIds());
        assertTrue(x.occupiesBlock("A") && x.occupiesTurnout("w"), "noch im vorherigen Block und auf der Weiche");
        assertNear(800, x.occupiedLengthMm(), "Summe der Bereiche");
        assertTrue(!x.conflict(), "Weiche passt");
        // rückwärts vom Zweig zur Spitze ist der Weg auch ohne bekannte Lage eindeutig
        TrainExtent unknown = new TrainExtent.Resolver(l).extent(at("s1", 300, true), 800, TurnoutStates.allUnknown());
        assertTrue(unknown.certain(), "eindeutig ohne Lage");
        assertEquals(x.ranges(), unknown.ranges());
    }

    public void testTurnoutUnderTrainKnownAgainstIsConflict() {
        TrackLayout l = GraphLayouts.station();
        TrainExtent x = new TrainExtent.Resolver(l).extent(at("s1", 300, true), 800, W_DIVERGING_SET);
        assertTrue(x.conflict(), "Weiche liegt unter dem Zug falsch");
        assertTrue(!x.certain() && x.tail().isEmpty(), "kein gesicherter Zugschluss");
        assertEquals(List.of("S", "A"), x.blockIds());                 // Belegung bleibt
        assertTrue(x.notes().getFirst().contains("Weiche w"), x.notes().toString());
    }

    public void testUnknownTurnoutBehindTrainOccupiesAllBranches() {
        // Zug fuhr von S über w nach A und steht mit der Spitze auf a2 @ 500 Richtung Prellbock
        TrackLayout l = GraphLayouts.station();
        TrainExtent.Resolver r = new TrainExtent.Resolver(l);
        TrainPosition p = at("a2", 500, false);
        TrainExtent known = r.extent(p, 800, W_STRAIGHT_SET);
        assertAt(DirectedLocation.of("s1", 100, false), known.tail().orElseThrow());
        assertEquals(List.of("A", "S"), known.blockIds());

        TrainExtent unknown = r.extent(p, 800, TurnoutStates.allUnknown());
        assertTrue(unknown.ambiguous() && !unknown.certain(), "mehrdeutig");
        assertEquals(2, unknown.tails().size());
        assertAt(DirectedLocation.of("s1", 100, false), unknown.tails().get(0));
        assertAt(DirectedLocation.of("d1", 800 - 500 - W_DIV, false), unknown.tails().get(1));
        assertEquals(List.of("A", "S", "D"), unknown.blockIds());       // im Zweifel belegt
        assertTrue(unknown.occupiesSection("sd1") && unknown.occupiesSection("ss1"), unknown.sectionIds().toString());
        assertTrue(unknown.tail().isEmpty() && unknown.body().isEmpty(), "kein eindeutiger Schluss");
    }

    public void testTurnoutFouledUpToClearanceBehindItsEnd() {
        // Grenzzeichen Abzweig 250 mm ab Spitze = 45,3 mm auf d1
        TrackLayout l = GraphLayouts.station();
        TrainExtent.Resolver r = new TrainExtent.Resolver(l);
        TrainExtent fouling = r.extent(at("d1", 520, true), 500, W_DIVERGING_SET);
        assertAt(DirectedLocation.of("d1", 20, true), fouling.tail().orElseThrow());
        assertEquals(List.of("w"), fouling.turnoutIds());
        assertEquals(List.of("sd1"), fouling.sectionIds());             // auf der Weiche selbst steht er nicht
        TrainExtent clear = r.extent(at("d1", 570, true), 500, W_DIVERGING_SET);
        assertEquals(List.of(), clear.turnoutIds());
    }

    public void testLongTrainOverSeveralShortBlocks() {
        // Entscheidung 40/E6: 2300 mm langer Zug, Blöcke je 1000 mm, Spitze t6 @ 400 → Schluss t2 @ 100
        TrackLayout l = GraphLayouts.longTrack();
        TrainExtent x = new TrainExtent.Resolver(l).extent(at("t6", 400, true), 2300, TurnoutStates.allUnknown());
        assertAt(DirectedLocation.of("t2", 100, true), x.tail().orElseThrow());
        assertEquals(List.of("K3", "K2", "K1"), x.blockIds());
        assertEquals(List.of("st6", "st5", "st4", "st3", "st2"), x.sectionIds());
        assertNear(3000, l.holdingAreaLengthMm("H").orElseThrow(), "Haltebereich lang genug");
        assertTrue(x.certain(), "eindeutig");
    }

    public void testTrainLongerThanTrackBehindIsIncomplete() {
        TrackLayout l = GraphLayouts.longTrack();
        TrainExtent x = new TrainExtent.Resolver(l).extent(at("t2", 200, true), 1000, TurnoutStates.allUnknown());
        assertTrue(!x.complete() && !x.certain() && x.tail().isEmpty(), "unplausibel: Zug ragt über den Prellbock");
        assertTrue(x.notes().getFirst().contains("300.0 mm"), x.notes().toString());
        assertEquals(List.of("K1"), x.blockIds());
    }

    public void testMarginsExtendBothEnds() {
        TrackLayout l = GraphLayouts.longTrack();
        TrainExtent.Resolver r = new TrainExtent.Resolver(l);
        TrainPosition p = at("t3", 490, true);                          // 10 mm vor Block K2/t4
        TrainExtent bare = r.extent(p, 400, TurnoutStates.allUnknown());
        assertEquals(List.of("K2"), bare.blockIds());
        assertEquals(List.of("st3"), bare.sectionIds());
        TrainExtent withMargin = r.extent(p, 400, TurnoutStates.allUnknown(), 20, 100);
        assertEquals(List.of("st3", "st4", "st2"), withMargin.sectionIds());
        assertEquals(List.of("K2", "K1"), withMargin.blockIds());
        assertAt(DirectedLocation.of("t3", 90, true), withMargin.tail().orElseThrow());  // Schluss ohne Zuschlag

        // Unsicherheit: Abgleich σ 5 mm, 1000 mm seither, 1 % → σ = √(25 + 100) ≈ 11,2 mm; 3σ ≈ 33,5 mm
        TrainPosition drifted = new TrainPosition(p.head(), Direction.FORWARD, 5, 1000, TrainPosition.Status.CONFIRMED);
        double sigma = drifted.sigmaMm(PositionUncertainty.startValues());
        assertNear(Math.hypot(5, 10), sigma, "σ");
        TrainExtent u = r.extentWithUncertainty(drifted, 400, TurnoutStates.allUnknown(), PositionUncertainty.startValues(), 3);
        assertNear(400 + 6 * sigma, u.occupiedLengthMm(), "Länge mit 3σ vorn und hinten");
    }

    // --- Kehrschleife -------------------------------------------------------------------------------------------

    public void testReversingLoopTailPassageWithDirectionChange() {
        TrackLayout l = GraphLayouts.reversingLoop();
        TrackGraph g = l.graph();
        TrainExtent.Resolver r = new TrainExtent.Resolver(l);
        BlockDirections dirs = new BlockDirections(l);
        double length = 600;

        // Einfahrt: Spitze auf g0 Richtung Weiche, Block G in A → B
        TrainPosition p = at("g0", 800, true);
        assertEquals(Block.Direction.A_TO_B, dirs.head(p));

        // durch den geraden Strang in die Schleife, bis kurz vor die Ausfahrt (l2 @ 900)
        double toLoopExit = 200 + W_STRAIGHT + 1000 + 4 * 400 + 900;
        TrainPosition.Movement m = p.advance(g, toLoopExit, W_STRAIGHT_SET);
        assertTrue(m.complete(), "Fahrt in die Schleife");
        assertAt(DirectedLocation.of("l2", 900, true), m.position().head());
        assertEquals(List.of("L"), r.extent(m.position(), length, W_STRAIGHT_SET).blockIds());

        // Ausfahrt bei gerader Lage gesperrt: Schätzung bleibt an der Weichenspitze stehen (Halt ist Sache des Aufrufers)
        TrainPosition.Movement blocked = m.position().advance(g, 400, W_STRAIGHT_SET);
        assertTrue(!blocked.complete(), "Ausfahrt gegen die Weichenlage");
        assertTrue(blocked.stop().orElseThrow() instanceof Continuation.Blocked, blocked.stop().toString());

        // Weiche umgestellt (Zug steht nicht auf ihr), Ausfahrt über den Abzweig: Spitze g0 @ 800 Richtung Prellbock
        TrainPosition out = m.position().advance(g, 100 + W_DIV + 200, W_DIVERGING_SET).position();
        assertAt(DirectedLocation.of("g0", 800, false), out.head());
        assertEquals(Block.Direction.B_TO_A, dirs.head(out));        // Richtungswechsel im Block G
        TrainExtent onTurnout = r.extent(out, length, W_DIVERGING_SET);
        assertAt(DirectedLocation.of("l2", 1000 - (length - 200 - W_DIV), true), onTurnout.tail().orElseThrow());
        assertEquals(List.of("G", "L"), onTurnout.blockIds());
        assertEquals(List.of("sg3", "st", "sl2"), onTurnout.sectionIds());
        assertEquals(List.of(1, 2), onTurnout.districts());
        assertEquals(List.of("w"), onTurnout.turnoutIds());
        // Zugschluss im Block L noch in Richtung A → B, Spitze in G in B → A
        assertEquals(Block.Direction.A_TO_B, dirs.of("L", onTurnout.tail().orElseThrow()));

        // Zugschluss verlässt Abzweig und Grenzzeichen (45,3 mm auf l2): danach keine Weiche, keine Schleife mehr
        TrainPosition gone = out.advance(g, length - 200, W_DIVERGING_SET).position();
        TrainExtent clear = r.extent(gone, length, W_DIVERGING_SET);
        assertAt(DirectedLocation.of("g0", 1000, false), clear.tail().orElseThrow());
        assertEquals(List.of(), clear.turnoutIds());
        assertEquals(List.of("G"), clear.blockIds());
        assertEquals(List.of(1), clear.districts());

        // Richtungswechsel des Zuges: Schluss wird Spitze, Decoder-Richtung kehrt sich um
        TrainPosition back = gone.reversedAt(clear.tail().orElseThrow());
        assertAt(DirectedLocation.of("g0", 1000, true), back.head());
        assertEquals(Direction.REVERSE, back.towardHead());
        assertAt(DirectedLocation.of("g0", 400, true), r.extent(back, length, W_DIVERGING_SET).tail().orElseThrow());
    }

    public void testReversingLoopUnknownTurnoutIsAmbiguous() {
        TrackLayout l = GraphLayouts.reversingLoop();
        TrainExtent x = new TrainExtent.Resolver(l).extent(at("g0", 800, false), 600, TurnoutStates.allUnknown());
        assertTrue(x.ambiguous(), "Zug kann über beide Zweige gekommen sein");
        assertEquals(List.of("G", "L"), x.blockIds());
        assertTrue(x.occupiesSection("sl1") && x.occupiesSection("sl2"), x.sectionIds().toString());
    }

    // --- Hin- und Rückrechnung ----------------------------------------------------------------------------------

    public void testPositionRoundTripOnPath() {
        TrackLayout l = GraphLayouts.oval();
        TrackGraph g = l.graph();
        TrainPosition p = at("e1", 100, true);
        GraphPath path = g.follow(p.headEdge(g), p.headAlongMm(g), 5000, TurnoutStates.allUnknown()).path();
        assertEquals(List.of(0.0, 4000.0), p.coordinatesOn(path));     // Oval: Spitze nach einer Runde wieder
        for (double s : new double[] {0, 899.5, 900, 2750, 3900, 4100, 5000}) {
            DirectedLocation h = TrainPosition.headAt(path, s);
            List<Double> back = p.corrected(h, 3).coordinatesOn(path);
            assertTrue(back.stream().anyMatch(c -> Math.abs(c - s) < TOL), "Rückrechnung " + s + " → " + back);
            // Vorrücken um s landet an derselben Wegkoordinate (an Kantengrenzen evtl. als Ende der vorigen Kante)
            List<Double> moved = p.advance(g, s, TurnoutStates.allUnknown()).position().coordinatesOn(path);
            assertTrue(moved.stream().anyMatch(c -> Math.abs(c - s) < TOL), "Vorrücken " + s + " → " + moved);
        }
        assertEquals(List.of(200.0, 4200.0), at("e1", 300, true).coordinatesOn(path));
        // Gegenrichtung liegt nicht auf dem Weg
        assertEquals(List.of(), at("e2", 300, false).coordinatesOn(path));
    }

    public void testPositionStatusAndUncertainty() {
        DirectedLocation h = DirectedLocation.of("e1", 10, true);
        TrainPosition p = TrainPosition.unconfirmed(h, Direction.FORWARD, 8);
        assertTrue(!p.isConfirmed(), "nach Neustart unbestätigt");
        assertTrue(p.confirmed().isConfirmed() && !p.confirmed().unconfirmed().isConfirmed(), "Status");
        TrainPosition moved = p.advance(GraphLayouts.oval().graph(), 2000, TurnoutStates.allUnknown()).position();
        assertNear(2000, moved.distanceSinceFixMm(), "Strecke seit Abgleich");
        assertNear(Math.hypot(8, 20), moved.sigmaMm(PositionUncertainty.startValues()), "σ wächst mit der Strecke");
        TrainPosition fixed = moved.corrected(DirectedLocation.of("e3", 0, true), 3);
        assertNear(3, fixed.sigmaMm(PositionUncertainty.startValues()), "σ nach Abgleich");
        assertThrows(IllegalArgumentException.class, () -> new TrainPosition(h, Direction.FORWARD, -1, 0, TrainPosition.Status.CONFIRMED));
        assertThrows(IllegalArgumentException.class, () -> p.advance(GraphLayouts.oval().graph(), -1, TurnoutStates.allUnknown()));
        assertThrows(IllegalArgumentException.class, () -> new TrainExtent.Resolver(GraphLayouts.oval()).extent(p, 0, TurnoutStates.allUnknown()));
        assertThrows(IllegalArgumentException.class, () -> new TrainExtent.Resolver(GraphLayouts.oval())
                .extent(at("e1", 1200, true), 100, TurnoutStates.allUnknown()));
    }

    /** Fahrtrichtung im Block für eine Stelle mit Richtung. */
    private record BlockDirections(TrackLayout layout) {
        Block.Direction head(TrainPosition p) {
            List<Block> blocks = layout.blocksAt(p.head().location());
            return of(blocks.getFirst().id(), p.head());
        }

        Block.Direction of(String blockId, DirectedLocation at) {
            return layout.geometry(blockId).orElseThrow().direction(at.edgeId(), at.forward()).orElseThrow();
        }
    }
}
