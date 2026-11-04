package at.schmoegl.railcore.safety;

import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.List;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/** Entprellung der Rückmelder mit dreiwertigem Zustand (unbekannt nie als frei). */
public class FeedbackFilterTest {

    private final TimeSource.Manual clock = new TimeSource.Manual();
    private final Journal journal = Journal.inMemory();
    private final List<FeedbackFilter.Event> out = new ArrayList<>();
    private final FeedbackFilter f = new FeedbackFilter(clock, journal, 500, 3, out::add);

    public void testUnreportedContactIsUnknown() {
        assertEquals(SensorState.UNKNOWN, f.state(7));
    }

    public void testUnknownToFreeOnlyAfterDelay() {
        f.raw(7, false, clock.nanoTime());
        f.poll();
        assertEquals(SensorState.UNKNOWN, f.state(7));
        clock.advanceMillis(499);
        f.poll();
        assertEquals(SensorState.UNKNOWN, f.state(7));
        clock.advanceMillis(1);
        f.poll();
        assertEquals(SensorState.FREE, f.state(7));
        assertEquals(1, out.size());
    }

    public void testUnknownToOccupiedImmediately() {
        f.raw(7, false, clock.nanoTime());
        f.raw(7, true, clock.nanoTime());   // vorher unbekannt: kein Flackern, sofort belegt
        assertEquals(SensorState.OCCUPIED, f.state(7));
        assertEquals(0, f.stats().get(7).flickerCount);
    }

    public void testFlickerKeepsOccupied() {
        f.raw(7, true, clock.nanoTime());
        for (int i = 0; i < 3; i++) {
            clock.advanceMillis(100);
            f.raw(7, false, clock.nanoTime());
            clock.advanceMillis(100);
            f.raw(7, true, clock.nanoTime());
            f.poll();
        }
        assertEquals(SensorState.OCCUPIED, f.state(7));
        assertEquals(3, f.stats().get(7).flickerCount);
        assertEquals(1, journal.byCode("SENSOR_FLICKER").size());
    }

    public void testResetMakesUnknown() {
        f.raw(7, true, clock.nanoTime());
        f.reset();
        assertEquals(SensorState.UNKNOWN, f.state(7));
    }
}
