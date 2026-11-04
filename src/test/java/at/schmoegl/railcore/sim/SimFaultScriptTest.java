package at.schmoegl.railcore.sim;

import at.schmoegl.railcore.core.CommandStationListener;
import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.DistrictPower;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.List;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/** Störungsskript und Störungsauslöser der simulierten Zentrale. */
public class SimFaultScriptTest {

    private static final long MS = 1_000_000L;

    private final TimeSource.Manual clock = new TimeSource.Manual();
    private final SimWorld world = new SimWorld(clock, SimWorld.randomForRun(3));
    private final SimCommandStation station = new SimCommandStation(world, Distribution.constant(10), 200);
    private final List<String> events = new ArrayList<>();

    private static void assertTrue(boolean b, String what) { if (!b) throw new AssertionError(what); }

    private void listen() {
        station.addListener(new CommandStationListener() {
            @Override public void onHeartbeat(long ts) { events.add("hb " + ts / MS); }
            @Override public void onFeedback(int c, boolean o, long ts) { events.add("fb " + c + " " + o); }
            @Override public void onPowerDistrict(int d, DistrictPower p, long ts) { events.add("bezirk " + d + " " + p); }
        });
        station.connect();
    }

    private static String parseError(String script) {
        try {
            SimFaultScript.parse(script);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        throw new AssertionError("Skript hätte abgewiesen werden müssen: " + script);
    }

    public void testParsesAllFaultsAndRejectsErrorsWithLineNumber() {
        SimFaultScript s = SimFaultScript.parse("""
                # Kommentar
                1000 verspaetung 3500
                6000 verbindung-weg 6000
                0 paketverlust 0.2 7
                2000 bezirk 2 kurzschluss
                2100 bezirk 2 ueberlast
                2200 bezirk 2 ein
                2000 gleisspannung aus
                500 flackern 2:3 10 40
                3000 versorgung-aus 2:1,2:2
                9000 versorgung-ein modul:3
                4000 fremdsteuerung 3 40 vor
                7000 haenger 400
                8000 interner-fehler Testfehler   # mit Text
                """);
        assertEquals(13, s.steps().size());
        assertEquals(List.of("Testfehler"), s.steps().get(12).args());
        assertTrue(parseError("x verspaetung 10").contains("Zeile 1"), "Zeit unlesbar");
        assertTrue(parseError("\n10 unbekannt").contains("Zeile 2"), "unbekannte Störung");
        assertTrue(parseError("10 paketverlust 1.5").contains("zwischen 0 und 1"), "Anteil");
        assertTrue(parseError("10 bezirk 1 blitz").contains("kurzschluss"), "Bezirkszustand");
        assertTrue(parseError("10 verspaetung 0").contains("positiv"), "Dauer");
        assertTrue(parseError("10 flackern 1:9 2 10").contains("unlesbar"), "Melder");
        assertTrue(parseError("-5 haenger 10").contains("negativ"), "Zeit");
    }

    public void testDelayHoldsRepliesAndDeadLinkDropsThem() {
        listen();
        SimFaultScript.parse("100 verspaetung 500\n1000 verbindung-weg 500").schedule(world, station);
        world.advanceTo(550 * MS);
        assertEquals(List.of("hb 0"), events);                              // ab 100 ms zurückgehalten
        world.advanceTo(700 * MS);
        assertEquals(List.of("hb 0", "hb 600", "hb 600", "hb 600"), events);   // nachgeliefert (200, 400, 600)
        world.advanceTo(1_450 * MS);
        assertEquals(List.of("hb 0", "hb 600", "hb 600", "hb 600", "hb 800"), events);
        assertTrue(station.lostFromStation() >= 2, "verloren, nicht nachgeliefert: " + station.lostFromStation());
        world.advanceTo(1_650 * MS);
        assertEquals("hb 1600", events.get(events.size() - 1));             // Verbindung wieder da
    }

    public void testDistrictCutsDetectorSupplyAndReportsOnReturn() {
        station.defineContacts(List.of(1, 2, 9), false);
        station.assignDistrict(1, List.of(), List.of(1, 2));
        listen();
        events.clear();
        station.districtPower(1, DistrictPower.SHORT_CIRCUIT);
        station.feedback(1, true);                                          // ohne Versorgung: keine Meldung
        station.feedback(9, true);                                          // anderer Bezirk meldet
        station.districtPower(1, DistrictPower.ON);
        assertEquals(List.of("bezirk 1 SHORT_CIRCUIT", "fb 9 true", "bezirk 1 ON", "fb 1 true", "fb 2 false"), events);
    }

    public void testFlickerEndsInStartStateAndLossIsDeterministic() {
        station.defineContacts(List.of(5), true);
        listen();
        events.clear();
        station.flicker(5, 4, 10);
        world.advanceTo(100 * MS);
        assertEquals(List.of("fb 5 false", "fb 5 true", "fb 5 false", "fb 5 true"), events);

        SimCommandStation a = lossy(), b = lossy();
        assertEquals(a.lostToStation(), b.lostToStation());
        assertTrue(a.lostToStation() > 20 && a.lostToStation() < 80, "etwa 50 % von 100: " + a.lostToStation());
        assertEquals(100 - a.lostToStation(), a.drives().size());
    }

    private static SimCommandStation lossy() {
        SimWorld w = new SimWorld(new TimeSource.Manual(), SimWorld.randomForRun(1));
        SimCommandStation s = new SimCommandStation(w, Distribution.constant(10), 200);
        s.setPacketLoss(0.5, 42);
        for (int i = 0; i < 100; i++) s.setLocoDrive(3, i % 10, SpeedSteps.S128, Direction.FORWARD);
        return s;
    }
}
