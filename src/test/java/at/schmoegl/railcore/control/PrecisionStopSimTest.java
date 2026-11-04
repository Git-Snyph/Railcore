package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.diag.Severity;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.sim.Distribution;
import at.schmoegl.railcore.sim.SimCommandStation;
import at.schmoegl.railcore.sim.SimDetector;
import at.schmoegl.railcore.sim.SimLoco;
import at.schmoegl.railcore.sim.SimWorld;
import at.schmoegl.railcore.train.DelayStats;
import at.schmoegl.railcore.train.DriveOrder;
import at.schmoegl.railcore.train.MeasurementPoint;
import at.schmoegl.railcore.train.ModelScale;
import at.schmoegl.railcore.train.SpeedProfile;
import at.schmoegl.railcore.train.StopPlanner;
import at.schmoegl.railcore.train.StopPlanningTest;
import at.schmoegl.railcore.train.StopPoint;
import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/**
 * Abnahme „Zielgenaues Halten“ im Simulator (Entscheidung 24, Lastenheft 10). Steuerung (ControlExecutor +
 * TrainDriver) und Welt (SimWorld) laufen deterministisch mit TimeSource.Manual und festen Seeds.
 * <p>
 * <b>Annahmen der Simulation</b> (die Steuerung kennt jeweils nur Mittel und Streuung, nie den Einzelwert):
 * <ul>
 *   <li>Befehlsverzögerung gleichverteilt 20–50 ms (Steuerung: 35 ± 8,66 ms)</li>
 *   <li>Melderverzögerung normal 40 ± 10 ms, begrenzt 10–100 ms (Steuerung: 40 ± 10 ms)</li>
 *   <li>Messstreuung des Melders normal 0 ± 3 mm, begrenzt ±12 mm (Steuerung: Startwert 3 mm)</li>
 *   <li>Abweichung im Fahrprofil je Lauf: wahre Geschwindigkeit = Faktor × Profil, Faktor normal 1 ± 0,01, begrenzt
 *       ±3 % (Steuerung: Startwert 1 % der Strecke)</li>
 *   <li>Auslauf nach Fahrstufe 0: Zeitkonstante normal 0,1 s ± 15 %, begrenzt 0,05–0,15 s (Steuerung:
 *       Auslaufweg aus Annäherungstempo 1,35 ± 0,2 mm)</li>
 *   <li>Startposition normal ± 5 mm (Steuerung: σ 5 mm); Fahrstufen 1–126 mit gekrümmter Kennlinie bis 280 km/h,
 *       Betriebsbremsung 150 mm/s², Annäherungstempo 5 km/h → Fahrstufe 5 (≈ 13,5 mm/s ≈ 4,2 km/h)</li>
 * </ul>
 */
public class PrecisionStopSimTest {

    private static final long MS = 1_000_000L;
    private static final int RUNS = 200;
    private static final int CONTACT = 7;
    private static final DelayStats SENSOR_DELAY = new DelayStats(40, 10);
    private static final DelayStats COMMAND_DELAY = new DelayStats(35, 30 / Math.sqrt(12));

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    // ================================================================== Lauf

    /** Ein Szenario: Strecke, Halt, Meldepunkt (NaN = keiner in der Welt), Freigabeende. */
    record Scenario(double lineKmh, StopPoint stop, double detectorMm, boolean detectorInWorld, double eoaMm,
                    double speedFactorOverride) {
        static Scenario platform(double kmh, double stopMm, double detectorMm, double eoaMm) {
            return new Scenario(kmh, StopPoint.platform("Bstg 1", stopMm), detectorMm, true, eoaMm, Double.NaN);
        }
    }

    /** Ergebnis eines Laufs. */
    record Result(double restMm, double errorMm, boolean stopped, TrainDriver driver, Journal journal,
                  SimCommandStation station, long emergencyAtNanos) {}

    static final class Run {
        final TimeSource.Manual clock = new TimeSource.Manual();
        final Journal journal = Journal.inMemory();
        final SimWorld world;
        final SimCommandStation station;
        final SimLoco loco;
        final ControlExecutor ex;
        final SimWorld.Controller controller;
        final DriveOrder order;
        TrainDriver driver;

        Run(long seed, Scenario sc) { this(seed, sc, SchedulerConfig.defaults()); }

        Run(long seed, Scenario sc, SchedulerConfig schedulerConfig) {
            Random rnd = SimWorld.randomForRun(seed);
            world = new SimWorld(clock, rnd);
            station = new SimCommandStation(world, Distribution.uniform(20, 50), 200);
            SpeedProfile profile = StopPlanningTest.profile();
            double factor = Double.isNaN(sc.speedFactorOverride())
                    ? Distribution.normal(1.0, 0.01, 0.97, 1.03).sample(rnd) : sc.speedFactorOverride();
            double tau = Distribution.normal(0.1, 0.015, 0.05, 0.15).sample(rnd);
            loco = world.addLoco(new SimLoco(3, profile, factor, tau));
            List<MeasurementPoint> points = new ArrayList<>();
            if (!Double.isNaN(sc.detectorMm())) {
                points.add(new MeasurementPoint(CONTACT, sc.detectorMm(), SENSOR_DELAY));
                if (sc.detectorInWorld())
                    world.addDetector(new SimDetector(CONTACT, sc.detectorMm(), Distribution.normal(40, 10, 10, 100),
                            Distribution.normal(0, 3, -12, 12)));
            }
            double trueStart = Distribution.normal(0, 5, -20, 20).sample(rnd);
            ex = ControlExecutor.create(ControlConfig.defaults(), schedulerConfig, clock, journal, station,
                    List.of(CONTACT), address -> address == 3);   // Platzhalter für die Reservierung
            controller = new SimWorld.Controller() {
                @Override public long nextDueNanos() { return ex.nextDueNanos(); }
                @Override public void pump() { ex.pump(); }
            };
            station.connect();
            ex.pump();
            ex.submit(new ControlCommand.RequestReady("Test"));
            ex.pump();
            assertEquals(OperatingState.READY, ex.snapshot().operatingState());
            order = new DriveOrder("RJ", 3, SpeedSteps.S128, Direction.FORWARD, profile, ModelScale.H0,
                    ModelScale.H0.mmPerSecond(sc.lineKmh()), 0, 5, points, sc.stop(), sc.eoaMm(), COMMAND_DELAY);
            loco.place(world.now(), trueStart, profile.stepFor(Direction.FORWARD, order.lineSpeedMmS()), Direction.FORWARD);
        }

        void start() { driver = TrainDriver.start(ex, order, StopPlanner.Settings.defaults()); }

        boolean runUntil(java.util.function.BooleanSupplier done, double seconds) {
            return world.run(controller, done, world.now() + (long) (seconds * 1e9));
        }

        Result finish(long emergencyAt) {
            boolean ok = runUntil(() -> driver.finished() && loco.stopped(world.now()), 120);
            double rest = loco.position(world.now());
            return new Result(rest, rest - order.stop().positionMm(), ok, driver, journal, station, emergencyAt);
        }
    }

    static Result run(long seed, Scenario sc) {
        Run r = new Run(seed, sc);
        r.start();
        return r.finish(Long.MIN_VALUE);
    }

    /** Kennzahlen einer Serie. */
    record Stats(int n, int within, double mean, double sd, double min, double max, double p95Abs) {
        double quote() { return within / (double) n; }

        String format(String name, double tol) {
            return String.format(Locale.ROOT, "%s: %d/%d innerhalb ±%.0f mm (%.1f %%), Fehler mittel %+.2f mm, σ %.2f mm, "
                    + "min %+.2f, max %+.2f, |Fehler| p95 %.2f mm", name, within, n, tol, 100 * quote(), mean, sd, min, max, p95Abs);
        }
    }

    static Stats stats(double[] err, double tol) {
        int within = 0;
        double sum = 0, sq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        double[] abs = new double[err.length];
        for (int i = 0; i < err.length; i++) {
            if (Math.abs(err[i]) <= tol) within++;
            sum += err[i];
            sq += err[i] * err[i];
            min = Math.min(min, err[i]);
            max = Math.max(max, err[i]);
            abs[i] = Math.abs(err[i]);
        }
        Arrays.sort(abs);
        double mean = sum / err.length;
        double sd = Math.sqrt(Math.max(0, sq / err.length - mean * mean));
        return new Stats(err.length, within, mean, sd, min, max, abs[(int) Math.ceil(0.95 * err.length) - 1]);
    }

    private static Stats platformSeries(String name, double kmh, long seedBase) {
        Scenario sc = Scenario.platform(kmh, 2500, 2200, 2700);   // Meldepunkt 300 mm vor dem Haltepunkt
        double[] err = new double[RUNS];
        double expected = Double.NaN;
        for (int i = 0; i < RUNS; i++) {
            Result r = run(seedBase + i, sc);
            assertTrue(r.stopped(), name + " Lauf " + i + ": Zug steht nicht");
            assertTrue(!r.driver().unplannedHalt(), name + " Lauf " + i + ": ungeplanter Halt " + r.driver().haltReason());
            assertTrue(r.restMm() < 2700, name + " Lauf " + i + ": hinter dem Freigabeende");
            assertTrue(r.driver().notice().isEmpty(), "kein Hinweis bei Meldepunkt 300 mm davor");
            assertEquals(0L, r.journal().count(Severity.ERROR));
            err[i] = r.errorMm();
            expected = r.driver().plan().accuracy().expectedToleranceMm();
        }
        Stats s = stats(err, 10);
        System.out.println(s.format(name, 10) + String.format(Locale.ROOT, "; berechnet ±%.1f mm", expected));
        return s;
    }

    // ================================================================== Abnahme Bahnsteighalt

    public void testPlatformStopFrom230KmhWithin10mmIn95Percent() {
        Stats s = platformSeries("Bahnsteighalt aus 230 km/h", 230, 230_000);
        assertTrue(s.quote() >= 0.95, "Quote " + s.quote());
        assertTrue(Math.abs(s.mean()) < 2.0, "kein systematischer Versatz: " + s.mean());
    }

    public void testPlatformStopFrom100KmhWithin10mmIn95Percent() {
        Stats s = platformSeries("Bahnsteighalt aus 100 km/h", 100, 100_000);
        assertTrue(s.quote() >= 0.95, "Quote " + s.quote());
        assertTrue(Math.abs(s.mean()) < 2.0, "kein systematischer Versatz: " + s.mean());
    }

    public void testFarSensorGivesNoticeAndStillStopsSafely() {
        Scenario sc = Scenario.platform(100, 2300, 300, 2450);     // Meldepunkt 2000 mm vor dem Haltepunkt
        double[] err = new double[RUNS];
        double tol = Double.NaN;
        for (int i = 0; i < RUNS; i++) {
            Result r = run(500_000 + i, sc);
            assertTrue(r.stopped(), "Lauf " + i + ": Zug steht nicht");
            assertTrue(!r.driver().unplannedHalt(), "Lauf " + i + ": ungeplanter Halt " + r.driver().haltReason());
            assertTrue(r.restMm() < 2450, "Lauf " + i + ": hinter dem Freigabeende bei " + r.restMm());
            var n = r.driver().notice();
            assertTrue(n.isPresent(), "Hinweis „Genauigkeit nicht erreichbar“");
            tol = n.get().expectedToleranceMm();
            assertTrue(n.get().text().startsWith("erwartet ±") && n.get().text().contains("Meldepunkt näher am Haltepunkt"),
                    n.get().text());
            assertEquals(1, r.journal().byCode("STOP_ACCURACY_NOT_REACHABLE").size());
            err[i] = r.errorMm();
        }
        Stats s = stats(err, tol);
        System.out.println(s.format("Meldepunkt 2000 mm vor Halt (100 km/h)", tol));
        assertTrue(tol > 10, "erwartete Genauigkeit schlechter als ±10 mm");
        // Die berechnete Genauigkeit ist eine 95-%-Aussage. Geprüft wird, ob die Serie mit ihr vereinbar ist:
        // Binomial n = 200, p = 0,95 → höchstens 16 Ausreißer (P(> 16) ≈ 0,5 %), und die gemessene Streuung darf die
        // berechnete nicht übersteigen (10 % Toleranz für die Schätzung aus 200 Läufen).
        assertTrue(s.within() >= 184, "mit ±" + tol + " mm bei 95 % nicht vereinbar: " + s.within() + "/200");
        double sigmaCalc = tol / at.schmoegl.railcore.train.StopAccuracy.Z95;
        assertTrue(s.sd() <= 1.1 * sigmaCalc, "Streuung " + s.sd() + " mm größer als berechnet " + sigmaCalc + " mm");
        assertTrue(Math.abs(s.mean()) < 3 * sigmaCalc / Math.sqrt(RUNS) + 1, "kein systematischer Versatz: " + s.mean());
    }

    // ================================================================== Halt auf der Strecke

    public void testLineStopNeverPassesEndOfAuthority() {
        double[] rest = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            double kmh = i % 2 == 0 ? 100 : 160;
            Scenario sc = new Scenario(kmh, StopPoint.line("Sig A", 2400, 20), 1000, true, 2500, Double.NaN);
            Result r = run(700_000 + i, sc);
            assertTrue(r.stopped(), "Lauf " + i + ": Zug steht nicht");
            assertTrue(!r.driver().unplannedHalt(), "Lauf " + i + ": " + r.driver().haltReason());
            assertTrue(r.restMm() < 2500, "Lauf " + i + ": hinter dem Freigabeende bei " + r.restMm());
            assertTrue(r.restMm() < 2400, "Lauf " + i + ": hinter dem Signal bei " + r.restMm());
            rest[i] = r.restMm();
        }
        double max = Arrays.stream(rest).max().orElseThrow();
        double min = Arrays.stream(rest).min().orElseThrow();
        System.out.println(String.format(Locale.ROOT, "Halt vor Signal (2400 mm, Sicherheitsabstand 20 mm, Freigabe bis 2500 mm): "
                + "Zugspitze %.1f–%.1f mm", min, max));
    }

    public void testFasterTrainThanProfileStillStopsBeforeEndOfAuthority() {
        // 3 % schneller als eingemessen (3σ), kein Meldepunkt: Überwachung muss das Freigabeende halten
        Scenario sc = new Scenario(160, StopPoint.line("Sig A", 2400, 20), Double.NaN, false, 2500, 1.03);
        Result r = run(9, sc);
        assertTrue(r.stopped(), "steht");
        assertTrue(r.restMm() < 2500, "vor dem Freigabeende: " + r.restMm());
    }

    // ================================================================== Zeitplan, Nothalt, im Zweifel halt

    public void testStopCommandIssuedAtPlannedTimeNotOnTickGrid() {
        Result r = run(11, Scenario.platform(100, 2500, 2200, 2700));
        var drives = r.station().drives();
        var stop = drives.get(drives.size() - 1);
        assertEquals(0, stop.step());
        assertEquals(r.driver().plannedStopCommandNanos(), stop.issuedNanos());
        assertTrue(stop.issuedNanos() % (50 * MS) != 0, "Haltebefehl nicht auf dem Taktraster");
        // auch Bremsstufen werden zwischen den Takten ausgegeben
        long offGrid = drives.stream().filter(d -> d.step() > 0 && d.issuedNanos() % (50 * MS) != 0).count();
        assertTrue(offGrid > 0, "Bremsstufen zum vorausberechneten Zeitpunkt");
        assertTrue(r.driver().fixCount() == 1, "ein Abgleich am Meldepunkt");
    }

    public void testEmergencyStopDuringApproachIsImmediateAndEndsDrive() {
        Run run = new Run(13, Scenario.platform(100, 2500, 2200, 2700));
        run.start();
        run.runUntil(() -> run.driver.state() == TrainDriver.State.STOP_SCHEDULED, 60);
        assertEquals(TrainDriver.State.STOP_SCHEDULED, run.driver.state());
        int before = run.station.drives().size();
        long t = run.world.now();
        run.ex.emergencyStop("test", "Nothalt-Taste");
        assertEquals(1, run.station.emergencyStops());              // sofort, ohne Durchlauf
        Result r = run.finish(t);
        assertEquals(TrainDriver.State.ABORTED, r.driver().state());
        assertTrue(r.driver().unplannedHalt(), "Nothalt ist ungeplanter Halt");
        assertTrue(run.station.drives().stream().skip(before).noneMatch(d -> d.step() > 0), "keine Fahrt nach Nothalt");
        assertTrue(r.restMm() < 2700, "vor dem Freigabeende");
        assertEquals(OperatingState.EMERGENCY_STOP, run.ex.snapshot().operatingState());
    }

    public void testLargeDeviationAtSensorHaltsWithPositionUncertain() {
        // Zug 12 % schneller als eingemessen: Abweichung am Meldepunkt weit über 6σ → Halt
        Scenario sc = new Scenario(100, StopPoint.platform("Bstg 1", 2500), 1500, true, 2700, 1.12);
        Result r = run(17, sc);
        assertEquals(1, r.journal().byCode("POSITION_UNCERTAIN").size());
        assertTrue(r.driver().unplannedHalt(), "ungeplanter Halt");
        assertTrue(r.restMm() < 2700, "vor dem Freigabeende");
    }

    public void testMissingSensorHalts() {
        Scenario sc = new Scenario(100, StopPoint.platform("Bstg 1", 2500), 2200, false, 2700, Double.NaN);
        Result r = run(19, sc);
        assertEquals(1, r.journal().byCode("MEASUREMENT_MISSING").size());
        assertTrue(r.driver().unplannedHalt(), "im Zweifel halt");
        assertTrue(r.restMm() < 2700, "vor dem Freigabeende");
    }

    public void testRunsAreDeterministic() {
        Scenario sc = Scenario.platform(230, 2500, 2200, 2700);
        assertEquals(run(77, sc).restMm(), run(77, sc).restMm());
    }
}
