package at.schmoegl.railcore.sim;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.CommandStationListener;
import at.schmoegl.railcore.train.SpeedProfile;
import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/** Simulationskern: Lokphysik, Meldepunkte mit Verzögerung, Befehlsverzögerung, Reproduzierbarkeit. */
public class SimWorldTest {

    private static final long MS = 1_000_000L;

    private static void assertNear(double e, double a, double tol, String what) {
        if (Math.abs(e - a) > tol) throw new AssertionError(what + ": erwartet " + e + " ± " + tol + ", erhalten " + a);
    }

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    private static SpeedProfile linear() {
        return SpeedProfile.linear(SpeedSteps.S128, 126, 150, 150);   // 1 mm/s je Fahrstufe
    }

    public void testLocoFollowsStepWithoutRampAndRunsOutExponentially() {
        SimLoco l = new SimLoco(3, linear(), 1.02, 0.1);
        l.place(0, 0, 100, Direction.FORWARD);
        assertNear(102 * 0.5, l.position(500 * MS), 1e-9, "wahre Geschwindigkeit = Faktor × Profil");
        l.drive(500 * MS, 20, Direction.FORWARD);                  // ohne Rampe
        assertNear(20.4, l.speed(500 * MS), 1e-9, "sofort neue Fahrstufe");
        l.drive(1_000 * MS, 0, Direction.FORWARD);
        double at = l.position(1_000 * MS);
        assertNear(at + 20.4 * 0.1, l.restPosition(1_000 * MS), 1e-9, "Auslaufweg v · τ");
        assertTrue(l.stopped(3_000 * MS), "steht nach Auslauf");
        long t = l.timeOfPosition(at + 1.0, 1_000 * MS);
        assertTrue(l.position(t) >= at + 1.0 - 1e-6 && l.position(t - 1_000) < at + 1.0, "Zeitpunkt im Auslauf exakt");
        assertEquals(Long.MAX_VALUE, l.timeOfPosition(at + 5, 1_000 * MS));   // wird nie erreicht
    }

    public void testDetectorFiresAtExactCrossingPlusDelay() {
        TimeSource.Manual clock = new TimeSource.Manual();
        SimWorld w = new SimWorld(clock, new Random(1));
        SimLoco l = w.addLoco(new SimLoco(3, linear(), 1.0, 0.1));
        l.place(0, 0, 100, Direction.FORWARD);
        SimDetector d = w.addDetector(new SimDetector(7, 123.4, Distribution.constant(40), Distribution.constant(0)));
        List<Long> got = new ArrayList<>();
        w.setFeedbackSink((c, occ) -> got.add(w.now()));
        w.advanceTo(5_000 * MS);
        assertEquals(1, got.size());
        assertNear(1_234 * MS, d.crossingNanos(), 1, "Überfahren bei 123,4 mm / 100 mm/s");
        assertNear(1_274 * MS, got.get(0), 1, "Meldung 40 ms später");
    }

    public void testCommandDelayKeepsOrderAndIsReproducible() {
        long[] first = run(42);
        long[] second = run(42);
        assertEquals(first[0], second[0]);
        assertEquals(first[1], second[1]);
        assertTrue(first[1] >= first[0], "späterer Befehl überholt nie einen früheren");
    }

    private long[] run(long seed) {
        TimeSource.Manual clock = new TimeSource.Manual();
        SimWorld w = new SimWorld(clock, new Random(seed));
        SimLoco l = w.addLoco(new SimLoco(3, linear(), 1.0, 0.1));
        l.place(0, 0, 0, Direction.FORWARD);
        SimCommandStation st = new SimCommandStation(w, Distribution.uniform(20, 50), 200);
        st.setLocoDrive(3, 50, SpeedSteps.S128, Direction.FORWARD);
        clock.advanceMillis(1);
        st.setLocoDrive(3, 10, SpeedSteps.S128, Direction.FORWARD);
        w.advanceTo(200 * MS);
        var d = st.drives();
        for (var x : d) assertTrue(x.arrivalNanos() - x.issuedNanos() <= 50 * MS, "Verzögerung höchstens 50 ms");
        return new long[]{d.get(0).arrivalNanos(), d.get(1).arrivalNanos()};
    }

    public void testHeartbeatsAndEmergencyStop() {
        TimeSource.Manual clock = new TimeSource.Manual();
        SimWorld w = new SimWorld(clock, new Random(3));
        SimLoco l = w.addLoco(new SimLoco(3, linear(), 1.0, 0.1));
        l.place(0, 0, 100, Direction.FORWARD);
        SimCommandStation st = new SimCommandStation(w, Distribution.uniform(20, 50), 200);
        List<Long> beats = new ArrayList<>();
        st.addListener(new CommandStationListener() {
            @Override public void onHeartbeat(long ts) { beats.add(ts); }
        });
        st.connect();
        w.advanceTo(1_000 * MS);
        assertEquals(6, beats.size());   // 0, 200, … 1000 ms
        st.emergencyStop();
        w.advanceTo(3_000 * MS);
        assertTrue(l.stopped(3_000 * MS), "Nothalt hält die Lok an");
        assertEquals(1, st.emergencyStops());
    }

    public void testDistributionMoments() {
        Distribution u = Distribution.uniform(20, 50);
        assertNear(35, u.mean(), 1e-9, "Mittel");
        assertNear(30 / Math.sqrt(12), u.sigma(), 1e-9, "Streuung");
        Random r = new Random(5);
        Distribution n = Distribution.normal(40, 10, 10, 100);
        for (int i = 0; i < 1000; i++) {
            double v = n.sample(r);
            assertTrue(v >= 10 && v <= 100, "begrenzt");
        }
    }
}
