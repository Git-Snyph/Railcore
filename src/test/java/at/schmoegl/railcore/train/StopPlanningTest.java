package at.schmoegl.railcore.train;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;

import java.util.List;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;

/** Maßstab, Unsicherheit, Bremskurve, Positionsschätzer, Genauigkeitsrechnung und Halteplanung (reine Rechnung). */
public class StopPlanningTest {

    private static final long MS = 1_000_000L;

    private static void assertNear(double expected, double actual, double tol, String what) {
        if (Math.abs(expected - actual) > tol)
            throw new AssertionError(what + ": erwartet " + expected + " ± " + tol + ", erhalten " + actual);
    }

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    /** Gekrümmte Fahrstufentabelle wie bei Decodern mit Kennlinie: 280 km/h Vorbild bei Stufe 126. */
    public static SpeedProfile profile() {
        SpeedProfile p = new SpeedProfile(SpeedSteps.S128);
        double vmax = ModelScale.H0.mmPerSecond(280);
        for (int s = 1; s <= 126; s++) {
            double v = vmax * Math.pow(s / 126.0, 1.3);
            p.set(Direction.FORWARD, s, v);
            p.set(Direction.REVERSE, s, v);
        }
        p.setAccel(150);
        p.setDecel(150);
        p.setStopRunout(1.35, 0.2);
        p.setCalibrated(true);
        return p;
    }

    public static DriveOrder order(SpeedProfile p, double lineKmh, double stopAt, List<MeasurementPoint> points, double eoa) {
        return new DriveOrder("RJ", 3, SpeedSteps.S128, Direction.FORWARD, p, ModelScale.H0,
                ModelScale.H0.mmPerSecond(lineKmh), 0, 5, points, StopPoint.platform("Bstg 1", stopAt), eoa,
                new DelayStats(35, 30 / Math.sqrt(12)));
    }

    public void testScaleH0() {
        assertNear(734.4, ModelScale.H0.mmPerSecond(230), 0.1, "230 km/h");
        assertNear(15.96, ModelScale.H0.mmPerSecond(5), 0.01, "5 km/h");
        assertNear(100, ModelScale.H0.prototypeKmh(ModelScale.H0.mmPerSecond(100)), 1e-9, "Rückrechnung");
    }

    public void testUncertaintyStartValuesConfigurableAndLearned() {
        PositionUncertainty u = PositionUncertainty.startValues();
        assertEquals(0.01, u.distanceFraction());
        assertEquals(3.0, u.sensorSpreadMm());
        assertEquals(PositionUncertainty.Source.START_VALUE, u.source());
        assertNear(13.0, u.bandMm(1000), 1e-9, "1 % von 1000 mm + 3 mm");
        assertNear(10.0, u.odometrySigmaMm(1000), 1e-9, "Fahrprofil-Anteil");

        SpeedProfile p = new SpeedProfile(SpeedSteps.S128);
        assertEquals(u, p.uncertainty());                     // Startwerte je Zug
        p.setUncertainty(PositionUncertainty.learned(0.004, 1.5));
        assertEquals(PositionUncertainty.Source.LEARNED, p.uncertainty().source());
        assertEquals(PositionUncertainty.Source.LEARNED, p.copy().uncertainty().source());
        assertThrows(IllegalArgumentException.class, () -> PositionUncertainty.configured(-0.1, 3));
        assertThrows(IllegalArgumentException.class, () -> PositionUncertainty.configured(0.01, -1));
    }

    public void testUncertaintyFlowsIntoAccuracy() {
        SpeedProfile p = profile();
        List<MeasurementPoint> pts = List.of(new MeasurementPoint(7, 1700, new DelayStats(40, 10)));
        StopAccuracy start = StopPlanner.plan(order(p, 100, 2000, pts, 2200), StopPlanner.Settings.defaults(), 0.05).accuracy();
        assertNear(3.0, start.sigmaOdometryMm(), 1e-9, "1 % von 300 mm");
        assertNear(3.0, start.sigmaSensorMm(), 1e-9, "Melder-Messstreuung");
        p.setUncertainty(PositionUncertainty.configured(0.02, 5));
        StopAccuracy worse = StopPlanner.plan(order(p, 100, 2000, pts, 2200), StopPlanner.Settings.defaults(), 0.05).accuracy();
        assertNear(6.0, worse.sigmaOdometryMm(), 1e-9, "2 % von 300 mm");
        assertNear(5.0, worse.sigmaSensorMm(), 1e-9, "eingestellte Messstreuung");
        assertTrue(worse.expectedToleranceMm() > start.expectedToleranceMm(), "größere Unsicherheit → schlechtere Genauigkeit");
    }

    public void testBrakeCurve() {
        assertNear(16, BrakeCurve.towards(1000, 900, 16, 150), 1e-9, "nach Kurvenende");
        double v = BrakeCurve.towards(700, 900, 16, 150);
        assertNear(Math.sqrt(256 + 2 * 150 * 200), v, 1e-9, "auf der Kurve");
        assertNear(700, BrakeCurve.pointWhere(v, 900, 16, 150), 1e-9, "Umkehrung");
        double vs = BrakeCurve.supervision(100, 150, 0.2);
        assertNear(100, BrakeCurve.supervisionDistance(vs, 150, 0.2), 1e-9, "Überwachung passt genau");
        assertEquals(0.0, BrakeCurve.supervision(-1, 150, 0.2));
    }

    public void testEstimatorUsesActualTimeCommandDelayAndRunout() {
        PositionEstimator e = new PositionEstimator(0, 0, 100, 5, 0.1);
        e.advanceTo(37 * MS);                               // ungerade Zeit, kein Taktvielfaches
        assertNear(3.7, e.positionMm(), 1e-9, "100 mm/s × 37 ms");
        e.command(100 * MS, 50, 35 * MS);                   // wirkt bei 135 ms
        e.advanceTo(135 * MS);
        assertNear(13.5, e.positionMm(), 1e-9, "alte Geschwindigkeit bis zur Wirkung");
        e.advanceTo(235 * MS);
        assertNear(18.5, e.positionMm(), 1e-9, "neue Geschwindigkeit danach");
        assertNear(16.0, e.positionAt(185 * MS), 1e-9, "Verlauf rückwärts");
        e.command(235 * MS, 0, 0);
        e.advanceTo(5_000 * MS);
        assertNear(18.5 + 50 * 0.1, e.positionMm(), 1e-3, "Auslauf v · τ");
        assertTrue(e.stopped(), "steht");
    }

    public void testEstimatorCorrectionAndSigmaGrowth() {
        PositionUncertainty u = PositionUncertainty.startValues();
        PositionEstimator e = new PositionEstimator(0, 0, 200, 5, 0.1);
        e.advanceTo(1_000 * MS);                             // 200 mm geschätzt
        assertNear(Math.hypot(5, 2), e.sigmaMm(u), 1e-9, "σ = √(5² + (1 % · 200)²)");
        // Meldepunkt bei 190 mm, überfahren bei 900 ms (geschätzt 180 mm) → Zug war 10 mm weiter
        double d = e.correct(190, 900 * MS, 3);
        assertNear(10, d, 1e-9, "Abweichung");
        assertNear(210, e.positionMm(), 1e-9, "Position nachgeführt");
        assertNear(Math.hypot(3, 0.2), e.sigmaMm(u), 1e-9, "σ nach Abgleich mit 20 mm seit Meldepunkt");
    }

    public void testPredictionIncludesPendingCommands() {
        PositionEstimator e = new PositionEstimator(0, 0, 100, 0, 0.1);
        e.command(0, 20, 50 * MS);
        assertNear(5 + 20 * 0.05, e.predictPositionMm(100 * MS), 1e-9, "Vorhersage über Befehlswirkung");
    }

    public void testAccuracyReachableWithNearSensor() {
        SpeedProfile p = profile();
        DriveOrder o = order(p, 230, 2500, List.of(new MeasurementPoint(7, 2200, new DelayStats(40, 10))), 2700);
        StopPlanner.StopPlan plan = StopPlanner.plan(o, StopPlanner.Settings.defaults(), 0.05);
        StopAccuracy a = plan.accuracy();
        assertTrue(a.reachable(), "±10 mm erreichbar: " + a.format());
        assertEquals(null, plan.notice());
        assertEquals(7, a.fixContact());
        // Überfahrtempo begrenzt, weil die Verzögerungsstreuung bei voller Bremskurve zu viel kostet
        assertTrue(plan.passSpeedCapMmS() < BrakeCurve.towards(2200, plan.brakePointMm(), plan.approachSpeedMmS(), 150),
                "Überfahrtempo begrenzt");
        assertTrue(a.expectedToleranceMm() <= 10 - 1.5 + 1e-6 || a.passSpeedMmS() <= plan.approachSpeedMmS() * 1.5,
                "Planungsreserve oder nahezu bestmöglich");
    }

    public void testAccuracyNoticeForFarSensor() {
        DriveOrder o = order(profile(), 100, 2300, List.of(new MeasurementPoint(7, 300, new DelayStats(40, 10))), 2500);
        StopPlanner.StopPlan plan = StopPlanner.plan(o, StopPlanner.Settings.defaults(), 0.05);
        assertTrue(!plan.accuracy().reachable(), "2000 mm Abstand: nicht erreichbar");
        AccuracyNotice n = plan.notice();
        assertTrue(n != null, "Hinweis vorhanden");
        assertEquals(StopAccuracy.Source.ODOMETRY, n.dominantSource());
        int tol = (int) Math.ceil(plan.accuracy().expectedToleranceMm());
        assertEquals("erwartet ±" + tol + " mm – Meldepunkt näher am Haltepunkt würde helfen", n.text());
        assertTrue(tol >= 39 && tol <= 41, "≈ 1,96 · √(20² + 3² + …) mm, war " + tol);
        assertTrue(Double.isInfinite(plan.passSpeedCapMmS()), "kein sinnloses Kriechen, wenn Ziel ohnehin unerreichbar");
    }

    public void testNoSensorUsesStartPosition() {
        DriveOrder o = order(profile(), 100, 1000, List.of(), 1200);
        StopAccuracy a = StopPlanner.plan(o, StopPlanner.Settings.defaults(), 0.05).accuracy();
        assertEquals(StopAccuracy.NO_CONTACT, a.fixContact());
        assertNear(1000, a.fixDistanceMm(), 1e-9, "Strecke ab Start");
    }

    public void testTargetPulledBackBeforeEndOfAuthority() {
        DriveOrder o = order(profile(), 100, 2300, List.of(new MeasurementPoint(7, 300, new DelayStats(40, 10))), 2310);
        StopPlanner.StopPlan plan = StopPlanner.plan(o, StopPlanner.Settings.defaults(), 0.05);
        assertTrue(plan.targetPulledBack(), "Ziel zurückgenommen");
        assertTrue(plan.targetMm() + 3 * plan.accuracy().sigmaMm() < 2310, "Ziel + 3σ vor dem Freigabeende");
    }

    public void testLineStopHasNoAccuracyTarget() {
        DriveOrder o = new DriveOrder("G", 3, SpeedSteps.S128, Direction.FORWARD, profile(), ModelScale.H0, 300, 0, 5,
                List.of(), StopPoint.line("Sig A", 2000, 20), 2100, new DelayStats(35, 8.7));
        StopPlanner.StopPlan plan = StopPlanner.plan(o, StopPlanner.Settings.defaults(), 0.05);
        assertEquals(null, plan.accuracy());
        assertEquals(1980.0, plan.targetMm());
    }

    public void testInvalidOrdersRejected() {
        assertThrows(IllegalArgumentException.class, () -> order(profile(), 100, 2000, List.of(), 1900));   // Freigabe vor Halt
        assertThrows(IllegalArgumentException.class, () -> order(profile(), 100, -5, List.of(), 100));     // Halt hinter Zug
        assertThrows(IllegalArgumentException.class, () -> StopPoint.platform("x", 100, 5));               // Annäherung zu kurz
    }
}
