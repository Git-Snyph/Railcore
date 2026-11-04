package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.core.TurnoutPosition;

import java.util.Map;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;

/** Geometrie der Gleisstücke, Katalog, Weichen-Zweiglängen und Grenzzeichen (reine Rechnung). */
public class TrackGeometryTest {

    static void assertNear(double expected, double actual, String what) {
        if (Math.abs(expected - actual) > 1e-6)
            throw new AssertionError(what + ": erwartet " + expected + ", erhalten " + actual);
    }

    public void testStraightLength() {
        assertNear(230, TrackGeometry.straight(230).lengthMm(), "gerade");
    }

    public void testArcLengthFromRadiusAndAngle() {
        assertNear(Math.PI * 250, TrackGeometry.arc(500, 90).lengthMm(), "Viertelkreis r=500");
        assertNear(2 * Math.PI * 100, TrackGeometry.arc(100, 360).lengthMm(), "Vollkreis r=100");
        assertNear(358.0 * Math.PI / 6, TrackGeometry.arc(358, 30).lengthMm(), "30° r=358");
    }

    public void testCompoundSumsParts() {
        TrackGeometry g = TrackGeometry.compound(TrackGeometry.arc(400, 15), TrackGeometry.straight(100));
        assertNear(400 * Math.PI / 12 + 100, g.lengthMm(), "Bogen + gerade");
    }

    public void testInvalidGeometryRejected() {
        assertThrows(IllegalArgumentException.class, () -> TrackGeometry.straight(0));
        assertThrows(IllegalArgumentException.class, () -> TrackGeometry.straight(-5));
        assertThrows(IllegalArgumentException.class, () -> TrackGeometry.straight(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> TrackGeometry.straight(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> TrackGeometry.arc(0, 30));
        assertThrows(IllegalArgumentException.class, () -> TrackGeometry.arc(500, 0));
        assertThrows(IllegalArgumentException.class, () -> TrackGeometry.arc(500, 361));
        assertThrows(IllegalArgumentException.class, TrackGeometry::compound);
    }

    public void testTurnoutBranchesAreEnteredValues() {
        // Weichenlängen werden eingetragen, nicht aus Radius und Winkel berechnet (Entscheidung 41).
        TurnoutBranches b = new TurnoutBranches(200, 204.7, 150, 250);
        assertNear(200, b.lengthMm(TurnoutPosition.STRAIGHT), "gerader Strang");
        assertNear(204.7, b.lengthMm(TurnoutPosition.DIVERGING), "Abzweig");
        assertNear(150, b.clearanceMm(TurnoutPosition.STRAIGHT), "Grenzzeichen gerade");
        assertNear(250, b.clearanceMm(TurnoutPosition.DIVERGING), "Grenzzeichen Abzweig");
        assertThrows(IllegalArgumentException.class, () -> b.lengthMm(TurnoutPosition.UNKNOWN));
    }

    public void testClearanceMarkIsMandatory() {
        assertThrows(IllegalArgumentException.class, () -> new TurnoutBranches(200, 210, 0, 250));
        assertThrows(IllegalArgumentException.class, () -> new TurnoutBranches(200, 210, 150, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new TurnoutBranches(0, 210, 150, 250));
        assertThrows(IllegalArgumentException.class, () -> new TrackPiece.TurnoutPiece("X", TrackPiece.Hand.RIGHT, null));
    }

    public void testCrossingPointInsideLines() {
        TrackPiece.CrossingDimensions k = TrackPiece.CrossingDimensions.symmetric(300, 240);
        assertNear(150, k.crossingFromA1Mm(), "Mitte A");
        assertNear(120, k.crossingFromB1Mm(), "Mitte B");
        assertThrows(IllegalArgumentException.class, () -> new TrackPiece.CrossingDimensions(300, 300, 300, 150));
        assertThrows(IllegalArgumentException.class, () -> new TrackPiece.CrossingDimensions(0, 300, 10, 150));
        assertThrows(IllegalArgumentException.class, () -> new TrackPiece.CrossingPiece("K", null));
    }

    public void testDoubleSlipLogicRejectsAmbiguity() {
        DoubleSlipLogic.Setting ss = new DoubleSlipLogic.Setting(TurnoutPosition.STRAIGHT, TurnoutPosition.STRAIGHT);
        DoubleSlipLogic.Setting dd = new DoubleSlipLogic.Setting(TurnoutPosition.DIVERGING, TurnoutPosition.DIVERGING);
        // A1_B1 und A1_B2 verlangen dieselbe Lage: ab A1 wäre die Fortsetzung mehrdeutig.
        assertThrows(IllegalArgumentException.class, () -> new DoubleSlipLogic(Map.of(
                Port.A1_B1, ss, Port.A1_B2, ss, Port.A2_B1, dd, Port.A2_B2, dd), true));
        assertThrows(IllegalArgumentException.class, () -> new DoubleSlipLogic(Map.of(Port.A1_B1, ss), true));
        assertEquals(dd, DoubleSlipLogic.provisionalAssumption().setting(Port.A2_B1));
        assertEquals(false, DoubleSlipLogic.provisionalAssumption().hardwareVerified());
    }

    public void testDoubleSlipLengthsNeedAllFourRoutes() {
        assertThrows(IllegalArgumentException.class, () -> new TrackPiece.DoubleSlipLengths(Map.of(Port.A1_B1, 230.0)));
        assertThrows(IllegalArgumentException.class, () -> new TrackPiece.DoubleSlipLengths(Map.of(
                Port.A1_B1, 230.0, Port.A2_B2, 230.0, Port.A1_B2, 0.0, Port.A2_B1, 236.0)));
        assertThrows(IllegalArgumentException.class, () -> new TrackPiece.DoubleSlipLengths(Map.of(
                Port.A1, 230.0, Port.A2_B2, 230.0, Port.A1_B2, 236.0, Port.A2_B1, 236.0)));
    }

    public void testCatalog() {
        TrackPiece g = TrackPiece.plain("TEST-G1", TrackGeometry.straight(100));
        TrackPieceCatalog c = TrackPieceCatalog.of(g, TrackPiece.plain("TEST-R1", TrackGeometry.arc(300, 30)));
        assertEquals(2, c.size());
        assertEquals(g, c.piece("TEST-G1"));
        assertEquals(true, c.find("nix").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> c.piece("nix"));
        assertThrows(IllegalArgumentException.class, () -> TrackPieceCatalog.of(g, g));
    }

    public void testTurnoutStates() {
        TurnoutStates s = TurnoutStates.of(Map.of("W1", TurnoutPosition.DIVERGING));
        assertEquals(TurnoutPosition.DIVERGING, s.position("W1"));
        assertEquals(TurnoutPosition.UNKNOWN, s.position("W2"));
        assertEquals(TurnoutPosition.UNKNOWN, TurnoutStates.allUnknown().position("W1"));
    }

    public void testValueTypesRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new TrackLocation("e", -1));
        assertThrows(IllegalArgumentException.class, () -> new TrackRange("e", 5, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackEdge("e", "a", Port.A, "b", Port.B, 0, "x", TrackEdge.Role.PLAIN));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackEdge("e", "a", Port.A, "b", Port.B, 5, null, TrackEdge.Role.CONNECTOR));
        assertThrows(IllegalArgumentException.class,
                () -> new TurnoutRequirement("W1", TurnoutPosition.UNKNOWN));
    }
}
