package at.schmoegl.railcore.train;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.layout.graph.Block;
import at.schmoegl.railcore.layout.graph.BoosterDistrict;
import at.schmoegl.railcore.layout.graph.DirectedEdge;
import at.schmoegl.railcore.layout.graph.DirectedLocation;
import at.schmoegl.railcore.layout.graph.GraphPath;
import at.schmoegl.railcore.layout.graph.ReportPoint;
import at.schmoegl.railcore.layout.graph.TrackGraph;
import at.schmoegl.railcore.layout.graph.TrackLayout;
import at.schmoegl.railcore.layout.graph.TurnoutRequirement;
import at.schmoegl.railcore.layout.graph.TurnoutStates;

import java.util.ArrayList;
import java.util.List;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;
import static at.schmoegl.railcore.train.GraphLayouts.RESPONSE_MS;
import static at.schmoegl.railcore.train.GraphLayouts.W_DIVERGING_SET;
import static at.schmoegl.railcore.train.GraphLayouts.W_STRAIGHT;
import static at.schmoegl.railcore.train.GraphLayouts.W_STRAIGHT_SET;

/**
 * Fahrauftrag aus einem Fahrweg im Gleisgraph (Schritt C1): Meldepunkte aus den Abschnittsgrenzen je Richtung,
 * Haltepunkt, Signal, Ende der Freigabe vor nicht anliegender Weiche, Abweisung im Zweifel, Zusammenspiel mit dem
 * unveränderten {@link StopPlanner}.
 */
public class DriveOrderFactoryTest {

    private static final double PICKUP = DriveOrderFactory.Settings.DEFAULT_LEADING_PICKUP_OFFSET_MM;
    private static final DelayStats COMMAND_DELAY = new DelayStats(35, 9);

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    private static void assertNear(double expected, double actual, String what) {
        if (Math.abs(expected - actual) > 1e-6) throw new AssertionError(what + ": erwartet " + expected + ", erhalten " + actual);
    }

    private static Train train(double lengthMm) {
        return new Train("RJ", "Railjet", 3, SpeedSteps.S128, lengthMm, 230, 1, StopPlanningTest.profile());
    }

    private static TrainPosition at(String edge, double mm, boolean forward) {
        return TrainPosition.confirmed(DirectedLocation.of(edge, mm, forward), Direction.FORWARD, 5);
    }

    private static GraphPath path(TrackGraph g, double start, double end, Object... edgeAndForward) {
        List<DirectedEdge> edges = new ArrayList<>();
        for (int i = 0; i < edgeAndForward.length; i += 2)
            edges.add(new DirectedEdge(g.edge((String) edgeAndForward[i]), (Boolean) edgeAndForward[i + 1]));
        return GraphPath.of(g, edges, start, end);
    }

    private static DriveOrderFactory.Request request(double length, TrainPosition p, GraphPath path, TurnoutStates states,
                                                     DriveOrderFactory.Target target) {
        return DriveOrderFactory.Request.of(train(length), p, path, states, target, ModelScale.H0.mmPerSecond(100),
                ModelScale.H0, COMMAND_DELAY);
    }

    /** Bahnhof: Spitze a1 @ 500 Richtung Weiche, Weg über den geraden Strang bis zum Prellbock am Ende von s2. */
    private static GraphPath stationPath(TrackGraph g) {
        return path(g, 500, 1000, "a1", true, "a2", true, "w.STRAIGHT", true, "s1", true, "s2", true);
    }

    // --- Meldepunkte --------------------------------------------------------------------------------------------

    public void testMeasurementPointsFromSectionBoundariesOnOval() {
        TrackLayout l = GraphLayouts.oval();
        TrackGraph g = l.graph();
        TrainPosition p = at("e1", 100, true);
        GraphPath path = g.follow(p.headEdge(g), p.headAlongMm(g), 3500, TurnoutStates.allUnknown()).path();
        DriveOrderFactory.Result r = new DriveOrderFactory(l).create(request(300, p, path, TurnoutStates.allUnknown(),
                new DriveOrderFactory.AtPathEnd(20)));
        DriveOrder o = r.order();
        assertEquals(List.of(2, 3, 4), o.points().stream().map(MeasurementPoint::contact).toList());
        assertEquals(List.of(900 + PICKUP, 1900 + PICKUP, 2900 + PICKUP), o.points().stream().map(MeasurementPoint::positionMm).toList());
        assertEquals(new DelayStats(RESPONSE_MS, DriveOrderFactory.Settings.DEFAULT_SENSOR_DELAY_SIGMA_MS), o.points().getFirst().delay());
        assertNear(0, o.startPositionMm(), "Start = Zugspitze");
        assertNear(5, o.startSigmaMm(), "Startunsicherheit aus der Position");
        assertNear(3500, o.endOfAuthorityMm(), "Freigabe bis Wegende");
        assertEquals(StopPoint.Kind.LINE, o.stop().kind());
        assertNear(3480, o.stop().targetMm(), "Halt mit Sicherheitsabstand vor dem Freigabeende");
        assertEquals(Direction.FORWARD, o.direction());
        assertTrue(r.limitedBy().isEmpty() && !r.stopReplaced(), "nicht begrenzt");
        assertMatchesLayout(l, r);
    }

    public void testMeasurementPointsMatchBoundariesAcrossTurnoutAndPlatformStop() {
        TrackLayout l = GraphLayouts.station();
        TrackGraph g = l.graph();
        DriveOrderFactory.Result r = new DriveOrderFactory(l).create(request(400, at("a1", 500, true), stationPath(g),
                W_STRAIGHT_SET, DriveOrderFactory.AtStopPoint.of("hS1")));
        DriveOrder o = r.order();
        // Grenzen: a2 @ 0 (500), Weiche an der Spitze (1500), s1 @ 0 (1700), s2 @ 0 (2700); Kontakte a2=2, st=7, s1=3, s2=4
        assertEquals(List.of("sa2", "st", "ss1", "ss2"), r.points().stream().map(DriveOrderFactory.PlannedPoint::sectionId).toList());
        assertEquals(List.of(2, 7, 3, 4), o.points().stream().map(MeasurementPoint::contact).toList());
        assertEquals(List.of(500.0, 1500.0, 1700.0, 2700.0), r.points().stream().map(DriveOrderFactory.PlannedPoint::boundaryMm).toList());
        assertMatchesLayout(l, r);
        // Haltepunkt s1 @ 500 = Wegkoordinate 500 + 1000 + 200 + 500
        assertEquals(StopPoint.Kind.PLATFORM, o.stop().kind());
        assertNear(500 + 1000 + W_STRAIGHT + 500, o.stop().targetMm(), "Haltepunkt");
        assertNear(500 + 1000 + W_STRAIGHT + 2000, o.endOfAuthorityMm(), "Freigabe bis Prellbock");
        // Rückrechnung: Haltepunkt der Wegkoordinate liegt wieder auf s1 @ 500 in Fahrtrichtung
        assertTrue(r.headAt(o.stop().targetMm()).sameAs(DirectedLocation.of("s1", 500, true), 1e-6), "Haltepunkt im Graph");
        // Zusammenspiel mit der unveränderten Halteplanung: maßgeblicher Meldepunkt ist s1 (Kontakt 3)
        StopPlanner.StopPlan plan = StopPlanner.plan(o, StopPlanner.Settings.defaults(), 0.05);
        assertEquals(3, plan.fix().contact());
        assertNear(1700 + PICKUP, plan.fix().positionMm(), "Meldepunkt mit Achsabstand");
        assertTrue(!plan.targetPulledBack(), "Ziel nicht zurückgenommen");
    }

    public void testPickupOffsetOverride() {
        TrackLayout l = GraphLayouts.station();
        DriveOrderFactory.Result r = new DriveOrderFactory(l).create(request(400, at("a1", 500, true), stationPath(l.graph()),
                W_STRAIGHT_SET, DriveOrderFactory.AtStopPoint.of("hS1")).withLeadingPickupOffset(0));
        assertEquals(List.of(500.0, 1500.0, 1700.0, 2700.0), r.order().points().stream().map(MeasurementPoint::positionMm).toList());
    }

    public void testPointsOfSectionsStillOccupiedByOwnTrainAreSkipped() {
        // 3600 mm langer Zug im 4000-mm-Oval: wenn die Spitze einen Abschnitt erreicht, steht der Schluss noch darin
        TrackLayout l = GraphLayouts.oval();
        TrackGraph g = l.graph();
        TrainPosition p = at("e1", 500, true);
        GraphPath path = g.follow(p.headEdge(g), p.headAlongMm(g), 3000, TurnoutStates.allUnknown()).path();
        DriveOrderFactory.Result r = new DriveOrderFactory(l).create(request(3600, p, path, TurnoutStates.allUnknown(),
                new DriveOrderFactory.AtPathEnd(20)));
        assertEquals(List.of(), r.order().points());
        assertEquals(List.of("se2", "se3", "se4"), r.skippedPoints().stream().map(DriveOrderFactory.SkippedPoint::sectionId).toList());
        assertEquals(4, r.startExtent().sectionIds().size());
        // kürzerer Zug: alle drei Meldepunkte bleiben
        DriveOrderFactory.Result shortTrain = new DriveOrderFactory(l).create(request(400, p, path, TurnoutStates.allUnknown(),
                new DriveOrderFactory.AtPathEnd(20)));
        assertEquals(3, shortTrain.order().points().size());
    }

    // --- Ende der Freigabe und Ziel -----------------------------------------------------------------------------

    public void testUnknownTurnoutLimitsAuthorityBeforeTurnout() {
        TrackLayout l = GraphLayouts.station();
        DriveOrderFactory.Result r = new DriveOrderFactory(l).create(request(400, at("a1", 500, true), stationPath(l.graph()),
                TurnoutStates.allUnknown(), DriveOrderFactory.AtStopPoint.of("hS1")));
        DriveOrder o = r.order();
        assertNear(1500, o.endOfAuthorityMm(), "Freigabe endet vor dem ersten Gleisstück der Weiche");
        assertEquals(new TurnoutRequirement("w", TurnoutPosition.STRAIGHT), r.limitedBy().orElseThrow());
        assertTrue(r.stopReplaced(), "Bahnsteighalt hinter der Weiche nicht erreichbar");
        assertEquals(StopPoint.Kind.LINE, o.stop().kind());
        assertNear(1500 - DriveOrderFactory.Settings.DEFAULT_FALLBACK_SAFETY_DISTANCE_MM, o.stop().targetMm(), "Halt vor der Weiche");
        assertEquals(List.of(2), o.points().stream().map(MeasurementPoint::contact).toList());
        assertEquals(3, r.skippedPoints().size());
        // gegen die Lage (abzweigend) genauso
        assertNear(1500, new DriveOrderFactory(l).create(request(400, at("a1", 500, true), stationPath(l.graph()),
                W_DIVERGING_SET, new DriveOrderFactory.AtPathEnd(20))).order().endOfAuthorityMm(), "falsche Lage");
    }

    public void testTrailingTurnoutLimitsAuthorityBeforeBranch() {
        // Spitze s2 @ 500 Richtung Weiche, Weg rückwärts über den geraden Strang nach a1
        TrackLayout l = GraphLayouts.station();
        TrackGraph g = l.graph();
        GraphPath back = path(g, 500, 1000, "s2", false, "s1", false, "w.STRAIGHT", false, "a2", false, "a1", false);
        TrainPosition p = at("s2", 500, false);
        DriveOrderFactory f = new DriveOrderFactory(l);
        DriveOrderFactory.Result wrong = f.create(request(300, p, back, W_DIVERGING_SET, new DriveOrderFactory.AtPathEnd(20)));
        assertNear(1500, wrong.order().endOfAuthorityMm(), "Halt vor dem Weichenzweig, kein Auffahren");
        assertTrue(!wrong.stopReplaced(), "Halt auf der Strecke folgt dem Freigabeende");
        DriveOrderFactory.Result right = f.create(request(300, p, back, W_STRAIGHT_SET, new DriveOrderFactory.AtPathEnd(20)));
        assertNear(500 + 1000 + W_STRAIGHT + 2000, right.order().endOfAuthorityMm(), "Lage passt");
        // Meldepunkte in dieser Richtung: Ende von s1, Weichenabschnitt am geraden Zweig, a2, a1
        assertEquals(List.of("ss1", "st", "sa2", "sa1"), right.points().stream().map(DriveOrderFactory.PlannedPoint::sectionId).toList());
        assertMatchesLayout(l, right);
    }

    public void testSignalStopAndExplicitAuthority() {
        TrackLayout l = GraphLayouts.station();
        TrackGraph g = l.graph();
        GraphPath p = path(g, 200, 500, "a1", true, "a2", true, "w.STRAIGHT", true, "s1", true);
        DriveOrderFactory f = new DriveOrderFactory(l);
        DriveOrder o = f.create(request(100, at("a1", 200, true), p, W_STRAIGHT_SET,
                new DriveOrderFactory.AtSignal("sigA", 20))).order();
        assertNear(1800, o.stop().positionMm(), "Signal am Ende B von A");
        assertNear(1780, o.stop().targetMm(), "Sicherheitsabstand");
        assertNear(2500, o.endOfAuthorityMm(), "Freigabe bis Wegende");
        DriveOrder limited = f.create(request(100, at("a1", 200, true), p, W_STRAIGHT_SET,
                new DriveOrderFactory.AtSignal("sigA", 20)).withEndOfAuthority(1900)).order();
        assertNear(1900, limited.endOfAuthorityMm(), "vorgegebene Freigabe");
        assertTrue(limited.points().stream().allMatch(m -> m.positionMm() <= 1900), "keine Meldepunkte hinter der Freigabe");
    }

    public void testReverseLocoDirectionIsPassedOn() {
        TrackLayout l = GraphLayouts.station();
        TrainPosition p = new TrainPosition(DirectedLocation.of("a1", 500, true), Direction.REVERSE, 5, 0,
                TrainPosition.Status.CONFIRMED);
        DriveOrder o = new DriveOrderFactory(l).create(request(400, p, stationPath(l.graph()), W_STRAIGHT_SET,
                DriveOrderFactory.AtStopPoint.of("hS1"))).order();
        assertEquals(Direction.REVERSE, o.direction());
    }

    // --- Abweisung ----------------------------------------------------------------------------------------------

    public void testRejectsWhenInDoubt() {
        TrackLayout l = GraphLayouts.station();
        TrackGraph g = l.graph();
        DriveOrderFactory f = new DriveOrderFactory(l);
        DriveOrderFactory.Target end = new DriveOrderFactory.AtPathEnd(20);
        // unbestätigte Position (Neustart)
        TrainPosition unconfirmed = TrainPosition.unconfirmed(DirectedLocation.of("a1", 500, true), Direction.FORWARD, 5);
        assertThrows(IllegalStateException.class, () -> f.create(request(400, unconfirmed, stationPath(g), W_STRAIGHT_SET, end)));
        // mehrdeutige Lage: Zug kam über eine Weiche unbekannter Lage
        GraphPath toBuffer = path(g, 500, 1000, "a2", false, "a1", false);
        assertThrows(IllegalStateException.class, () -> f.create(request(800, at("a2", 500, false), toBuffer,
                TurnoutStates.allUnknown(), end)));
        // Weg beginnt nicht an der Zugspitze
        assertThrows(IllegalArgumentException.class, () -> f.create(request(400, at("a1", 400, true), stationPath(g), W_STRAIGHT_SET, end)));
        // Spitze steht schon an der Weiche, deren Lage unbekannt ist
        GraphPath fromTip = path(g, 1000, 500, "a2", true, "w.STRAIGHT", true, "s1", true);
        assertThrows(IllegalStateException.class, () -> f.create(request(400, at("a2", 1000, true), fromTip,
                TurnoutStates.allUnknown(), end)));
        // Haltepunkt nicht in Fahrtrichtung auf dem Weg
        GraphPath back = path(g, 500, 1000, "s2", false, "s1", false, "w.STRAIGHT", false, "a2", false, "a1", false);
        assertThrows(IllegalArgumentException.class, () -> f.create(request(300, at("s2", 500, false), back,
                W_STRAIGHT_SET, DriveOrderFactory.AtStopPoint.of("hS1"))));
        assertThrows(IllegalArgumentException.class, () -> f.create(request(400, at("a1", 500, true), stationPath(g),
                W_STRAIGHT_SET, DriveOrderFactory.AtStopPoint.of("gibtsnicht"))));
        // Gleisplan mit Fehlern
        TrackLayout broken = TrackLayout.builder(g).district(BoosterDistrict.of(1, "x"))
                .block(Block.of("X", DirectedLocation.of("a1", 0, true))).build();
        assertTrue(!broken.permitsAutomatic(), "fehlerhafter Gleisplan");
        assertThrows(IllegalStateException.class, () -> new DriveOrderFactory(broken).create(request(400,
                at("a1", 500, true), stationPath(g), W_STRAIGHT_SET, end)));
        assertThrows(IllegalArgumentException.class, () -> new DriveOrderFactory.Settings(-1, 10, 20));
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Jeder Meldepunkt liegt genau an einer abgeleiteten Abschnittsgrenze des Gleisplans, in Fahrtrichtung. */
    private static void assertMatchesLayout(TrackLayout l, DriveOrderFactory.Result r) {
        assertTrue(!r.points().isEmpty(), "Meldepunkte erwartet");
        for (DriveOrderFactory.PlannedPoint p : r.points()) {
            ReportPoint rp = p.source();
            assertTrue(l.reportPoints(p.sectionId()).contains(rp), "Meldepunkt stammt aus dem Gleisplan: " + rp);
            assertTrue(r.headAt(p.boundaryMm()).sameAs(rp.at(), 1e-6) || sameJoint(l.graph(), r.headAt(p.boundaryMm()), rp.at()),
                    "Wegkoordinate " + p.boundaryMm() + " ↔ " + rp.at() + " / " + r.headAt(p.boundaryMm()));
            assertEquals(l.section(p.sectionId()).orElseThrow().contact(), p.point().contact());
            assertNear(p.boundaryMm() + PICKUP, p.point().positionMm(), "Achsabstand eingerechnet");
        }
    }

    /** Derselbe Punkt an einer Kantengrenze kann als Ende der einen oder Anfang der nächsten Kante erscheinen. */
    private static boolean sameJoint(TrackGraph g, DirectedLocation a, DirectedLocation b) {
        DirectedEdge ea = new DirectedEdge(g.edge(a.edgeId()), a.forward());
        DirectedEdge eb = new DirectedEdge(g.edge(b.edgeId()), b.forward());
        boolean aEnd = Math.abs(ea.toEdgeOffset(a.offsetMm()) - ea.lengthMm()) < 1e-6;
        boolean bStart = Math.abs(eb.toEdgeOffset(b.offsetMm())) < 1e-6;
        return aEnd && bStart && ea.endNode().equals(eb.startNode());
    }
}
