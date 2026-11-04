package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.diag.Journal;
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
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/**
 * Befehlsplaner unter Last im Simulator (Lastenheft 6: Messung bei 10, 25, 50 und 100 aktiven Loks). Jede Lok fährt
 * auf eigener Wegkoordinate einen Bahnsteighalt (Meldepunkt 300 mm davor, Freigabe bis 2700 mm) aus 100, 160 oder
 * 230 km/h; die Starts sind über {@link #START_WINDOW_S} s verteilt, sodass sich Bremsungen überlagern. Dazu schaltet
 * jede Lok etwa alle 5 s eine Komfortfunktion. Zentrale wie in der Halte-Abnahme (Befehlsverzögerung 20–50 ms).
 * <p>
 * Geprüft wird die Sicherheit (kein Zug hinter dem Freigabeende, alle stehen, Sicherheit vor Komfort, Überlast wird
 * gemeldet); Wartezeiten und Haltegenauigkeit werden je Lokzahl berichtet.
 */
public class SchedulerLoadSimTest {

    private static final long MS = 1_000_000L;
    private static final double START_WINDOW_S = 20;
    private static final double EOA_MM = 2700;
    private static final DelayStats SENSOR_DELAY = new DelayStats(40, 10);
    private static final DelayStats COMMAND_DELAY = new DelayStats(35, 30 / Math.sqrt(12));

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    /** Ergebnis eines Lastlaufs. */
    record LoadResult(int locos, SchedulerMetrics metrics, int within10, int unplanned, double maxErrorMm,
                      double maxRestMm, int overloadReports, boolean brakingDemandReported, boolean allStopped,
                      Journal journal) {
        String format() {
            StringBuilder b = new StringBuilder(String.format(Locale.ROOT,
                    "Last %3d Loks: Budget %.1f/s, Auslastung %.0f %%, Rückstau max %d, gesendet %d, zusammengefasst %d, "
                            + "Überlast %d× (Bremsbedarf über Budget: %s); Halt ±10 mm %d/%d, ungeplant %d, |Fehler| max %.1f mm, "
                            + "Zugspitze max %.1f mm (Freigabe %.0f)",
                    locos, metrics.budgetPerSecond(), metrics.utilization() * 100, metrics.maxBacklog(), metrics.sent(),
                    metrics.coalesced(), metrics.overloadEvents(), brakingDemandReported ? "ja" : "nein", within10, locos,
                    unplanned, maxErrorMm, maxRestMm, EOA_MM));
            for (CommandPriority p : CommandPriority.QUEUED) {
                TimingStatistics.Summary s = metrics.waitOf(p);
                if (s.count() == 0) continue;
                b.append(String.format(Locale.ROOT, "%n    Wartezeit %-10s n=%5d  min %6.1f  mittel %6.1f  p95 %6.1f  max %7.1f ms",
                        p.german(), s.count(), s.minMs(), s.meanMs(), s.p95Ms(), s.maxMs()));
            }
            return b.toString();
        }
    }

    static LoadResult load(int n, long seed) {
        TimeSource.Manual clock = new TimeSource.Manual();
        Journal journal = Journal.inMemory();
        Random rnd = SimWorld.randomForRun(seed);
        SimWorld world = new SimWorld(clock, rnd);
        SimCommandStation station = new SimCommandStation(world, Distribution.uniform(20, 50), 200);
        SpeedProfile profile = StopPlanningTest.profile();
        List<Integer> contacts = new ArrayList<>();
        for (int i = 1; i <= n; i++) contacts.add(1000 + i);
        ControlExecutor ex = ControlExecutor.create(ControlConfig.defaults(), clock, journal, station, contacts,
                address -> address >= 1 && address <= n);   // Platzhalter für die Reservierung
        SimWorld.Controller controller = new SimWorld.Controller() {
            @Override public long nextDueNanos() { return ex.nextDueNanos(); }
            @Override public void pump() { ex.pump(); }
        };
        station.connect();
        ex.pump();
        ex.submit(new ControlCommand.RequestReady("Test"));
        ex.pump();
        assertEquals(OperatingState.READY, ex.snapshot().operatingState());

        double[] kmh = { 100, 160, 230 };
        SimLoco[] locos = new SimLoco[n];
        DriveOrder[] orders = new DriveOrder[n];
        TrainDriver[] drivers = new TrainDriver[n];
        double[] trueStart = new double[n];
        for (int i = 0; i < n; i++) {
            int addr = i + 1;
            double factor = Distribution.normal(1.0, 0.01, 0.97, 1.03).sample(rnd);
            double tau = Distribution.normal(0.1, 0.015, 0.05, 0.15).sample(rnd);
            locos[i] = world.addLoco(new SimLoco(addr, profile, factor, tau));
            world.addDetector(new SimDetector(1000 + addr, 2200, Distribution.normal(40, 10, 10, 100),
                    Distribution.normal(0, 3, -12, 12), addr));
            trueStart[i] = Distribution.normal(0, 5, -20, 20).sample(rnd);
            orders[i] = new DriveOrder("Z" + addr, addr, SpeedSteps.S128, Direction.FORWARD, profile, ModelScale.H0,
                    ModelScale.H0.mmPerSecond(kmh[i % 3]), 0, 5,
                    List.of(new MeasurementPoint(1000 + addr, 2200, SENSOR_DELAY)), StopPoint.platform("Bstg " + addr, 2500),
                    EOA_MM, COMMAND_DELAY);
        }
        long t0 = world.now();
        for (int i = 0; i < n; i++) {
            int k = i;
            long start = t0 + 100 * MS + (long) (START_WINDOW_S * 1e9 * i / n);
            world.at(start, () -> {
                locos[k].place(world.now(), trueStart[k], profile.stepFor(Direction.FORWARD, orders[k].lineSpeedMmS()),
                        Direction.FORWARD);
                drivers[k] = TrainDriver.start(ex, orders[k], StopPlanner.Settings.defaults());
            });
            // Komfort: etwa alle 5 s eine Funktion umschalten (bis zum Ende der Fahrten)
            long first = t0 + (long) (rnd.nextDouble() * 5e9);
            for (long t = first; t < t0 + (long) ((START_WINDOW_S + 15) * 1e9); t += 5_000 * MS) {
                boolean on = ((t - first) / (5_000 * MS)) % 2 == 0;
                world.at(t, () -> ex.commands().function(k + 1, 1, on, CommandPriority.COMFORT));
            }
        }
        boolean done = world.run(controller, () -> {
            for (int i = 0; i < n; i++)
                if (drivers[i] == null || !drivers[i].finished() || !locos[i].stopped(world.now())) return false;
            return true;
        }, t0 + (long) ((START_WINDOW_S + 60) * 1e9));
        world.run(controller, () -> false, world.now() + 16_000 * MS);   // Komfort abarbeiten lassen

        int within = 0, unplanned = 0;
        double maxErr = 0, maxRest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double rest = locos[i].position(world.now());
            double err = rest - 2500;
            maxRest = Math.max(maxRest, rest);
            maxErr = Math.max(maxErr, Math.abs(err));
            if (Math.abs(err) <= 10) within++;
            if (drivers[i].unplannedHalt()) unplanned++;
        }
        boolean demand = journal.byCode("SCHEDULER_OVERLOAD").stream().anyMatch(e -> e.message().contains("Bremskurven"));
        return new LoadResult(n, ex.scheduler().metrics(), within, unplanned, maxErr, maxRest,
                journal.byCode("SCHEDULER_OVERLOAD").size(), demand, done, journal);
    }

    private static LoadResult checked(int n) {
        LoadResult r = load(n, 4_000L + n);
        System.out.println(r.format());
        r.journal().byCode("SCHEDULER_OVERLOAD").forEach(e -> System.out.println("    Meldung: " + e.message()));
        assertTrue(r.allStopped(), n + " Loks: nicht alle Züge stehen");
        assertTrue(r.maxRestMm() < EOA_MM, n + " Loks: Freigabeende überfahren bei " + r.maxRestMm() + " mm");
        assertEquals(0L, r.metrics().droppedOnEmergency());
        TimingStatistics.Summary safety = r.metrics().waitOf(CommandPriority.SAFETY);
        TimingStatistics.Summary comfort = r.metrics().waitOf(CommandPriority.COMFORT);
        assertTrue(safety.count() > 0 && comfort.count() > 0, "Messwerte je Klasse vorhanden");
        assertTrue(r.metrics().sent() <= r.metrics().submitted(), "nie mehr gesendet als angenommen");
        return r;
    }

    public void testLoad010Locos() {
        LoadResult r = checked(10);
        // höchstens eine kurze Meldung (Bremsstufen aller Züge im selben Takt), nie Bremsbedarf über dem Budget
        assertTrue(r.metrics().overloadEvents() <= 1, "10 Loks: Überlast " + r.metrics().overloadEvents() + "×");
        assertTrue(!r.brakingDemandReported(), "10 Loks: Bremsbedarf innerhalb des Budgets");
        assertTrue(r.within10() >= 9, "10 Loks: Haltegenauigkeit " + r.within10() + "/10");
    }

    public void testLoad025Locos() { checked(25); }

    public void testLoad050Locos() { checked(50); }

    public void testLoad100Locos() {
        LoadResult r = checked(100);
        // Budget 25/s bei 100 aktiven Loks: der Bedarf der Bremskurven übersteigt es – das muss gemeldet werden
        assertTrue(r.metrics().overloadEvents() > 0, "Überlast erkannt");
        assertTrue(r.brakingDemandReported(), "Bremsbedarf über Budget gemeldet");
        assertTrue(r.overloadReports() <= 1 + (START_WINDOW_S + 80) / 10, "Journal gedrosselt: " + r.overloadReports());
        // bei Überlast wird Komfort zurückgestellt, Sicherheit hat Vorrang
        assertTrue(r.metrics().waitOf(CommandPriority.COMFORT).meanMs() > r.metrics().waitOf(CommandPriority.SAFETY).meanMs(),
                "Komfort wartet länger als Sicherheit");
    }

    /**
     * Ein Zug, aber knappes Budget (Paketdauer 50 ms → 8,5 Befehle/s): Bremsstufen warten im Befehlsplaner und
     * werden zusammengefasst. Die Halteplanung muss trotzdem sicher halten – Bahnsteig vor dem Freigabeende, Strecke
     * vor dem Signal; die Genauigkeit wird berichtet.
     */
    public void testStopsStaySafeWithScarceBudget() {
        SchedulerConfig scarce = SchedulerConfig.defaults().withPacketMillis(50);
        assertEquals(8.5, scarce.budgetPerSecond(1));
        int runs = 100;
        for (double kmh : new double[] { 100, 230 }) {
            double[] err = new double[runs];
            double maxSafetyWait = 0;
            for (int i = 0; i < runs; i++) {
                PrecisionStopSimTest.Run run = new PrecisionStopSimTest.Run(800_000 + i,
                        PrecisionStopSimTest.Scenario.platform(kmh, 2500, 2200, 2700), scarce);
                run.start();
                PrecisionStopSimTest.Result r = run.finish(Long.MIN_VALUE);
                assertTrue(r.stopped(), kmh + " km/h Lauf " + i + ": steht nicht");
                assertTrue(r.restMm() < 2700, kmh + " km/h Lauf " + i + ": hinter dem Freigabeende bei " + r.restMm());
                err[i] = r.errorMm();
                maxSafetyWait = Math.max(maxSafetyWait, run.ex.scheduler().metrics().waitOf(CommandPriority.SAFETY).maxMs());
            }
            PrecisionStopSimTest.Stats s = PrecisionStopSimTest.stats(err, 10);
            System.out.println(s.format(String.format(Locale.ROOT, "Knappes Budget 8,5/s, Bahnsteig aus %.0f km/h", kmh), 10)
                    + String.format(Locale.ROOT, "; Wartezeit Sicherheit max %.0f ms", maxSafetyWait));
        }
        double maxRest = 0;
        for (int i = 0; i < runs; i++) {
            PrecisionStopSimTest.Scenario sc = new PrecisionStopSimTest.Scenario(i % 2 == 0 ? 100 : 160,
                    StopPoint.line("Sig A", 2400, 20), 1000, true, 2500, Double.NaN);
            PrecisionStopSimTest.Run run = new PrecisionStopSimTest.Run(900_000 + i, sc, scarce);
            run.start();
            PrecisionStopSimTest.Result r = run.finish(Long.MIN_VALUE);
            assertTrue(r.stopped(), "Strecke Lauf " + i + ": steht nicht");
            assertTrue(r.restMm() < 2500, "Strecke Lauf " + i + ": hinter dem Freigabeende bei " + r.restMm());
            assertTrue(r.restMm() < 2400, "Strecke Lauf " + i + ": hinter dem Signal bei " + r.restMm());
            maxRest = Math.max(maxRest, r.restMm());
        }
        System.out.println(String.format(Locale.ROOT, "Knappes Budget 8,5/s, Halt vor Signal (2400 mm): Zugspitze max %.1f mm", maxRest));
    }

    public void testLoadRunIsDeterministic() {
        LoadResult a = load(25, 99), b = load(25, 99);
        assertEquals(a.maxRestMm(), b.maxRestMm());
        assertEquals(a.metrics().sent(), b.metrics().sent());
    }
}
