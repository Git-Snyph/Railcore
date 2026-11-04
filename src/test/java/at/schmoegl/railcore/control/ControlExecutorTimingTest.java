package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.train.Train;
import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/**
 * Zeitverhalten des Steuerungs-Executors (Entscheidung 24): Ereignisse sofort, Takt nur für zeitabhängige Aufgaben
 * mit tatsächlich vergangener Zeit, eingeplante Befehle exakt zum Sollzeitpunkt, Taktschwankung gemessen.
 */
public class ControlExecutorTimingTest {

    private static final long MS = 1_000_000L;

    private final TimeSource.Manual clock = new TimeSource.Manual();
    private final Journal journal = Journal.inMemory();
    private final FakeStation station = new FakeStation();
    private final ControlExecutor ex = ControlExecutor.create(ControlConfig.defaults().withJitterReportSeconds(1),
            clock, journal, station, CommandSink.direct(station), List.of(1, 2), address -> address == 3);

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    /** Uhr exakt auf einen Zeitpunkt stellen und einen Durchlauf rechnen. */
    private void pumpAt(long nanos) {
        clock.advanceNanos(nanos - clock.nanoTime());
        ex.pump();
    }

    private void bringReady() {
        station.connected();
        pumpAt(clock.nanoTime() + MS);
        station.heartbeat(clock.nanoTime());
        pumpAt(ex.nextDueNanos());
        ex.submit(new ControlCommand.RequestReady("Test"));
        pumpAt(clock.nanoTime() + MS);
        assertEquals(OperatingState.READY, ex.snapshot().operatingState());
    }

    public void testEventsAreProcessedImmediatelyNotOnNextTick() {
        ex.pump();                                   // bindet den Testthread als Steuerungs-Thread
        long firstTick = ex.nextDueNanos();
        assertEquals(50 * MS, firstTick);
        clock.advanceMillis(7);
        ex.submit(new ControlCommand.RegisterTrain(new Train("RJ", "Railjet", 3, SpeedSteps.S128, 1000, 230, 1, null), "Test"));
        ex.pump();
        assertTrue(ex.snapshot().trains().containsKey("RJ"), "Ereignis nach 7 ms verarbeitet, vor dem ersten Takt");
        assertEquals(0L, ex.tickJitter().count());   // kein Takt gerechnet
        assertEquals(firstTick, ex.nextDueNanos());   // Taktraster unverändert
    }

    public void testScheduledActionRunsAtExactTimeNotOnTickGrid() {
        ex.pump();
        AtomicLong ranAt = new AtomicLong(-1);
        long due = 17 * MS + 300_000;               // 17,3 ms – zwischen zwei Takten
        ScheduledAction a = ex.schedule(due, "Halt (Test)", () -> ranAt.set(clock.nanoTime()));
        assertEquals(due, ex.nextDueNanos());
        pumpAt(due - 1);
        assertEquals(-1L, ranAt.get());
        pumpAt(ex.nextDueNanos());
        assertEquals(due, ranAt.get());
        assertEquals(true, a.isDone());
        assertEquals(due, a.executedNanos());
        assertEquals(0.0, ex.timerLateness().maxMs());
        assertEquals(50 * MS, ex.nextDueNanos());   // danach wieder der Takt
    }

    public void testCancelledActionDoesNotRun() {
        ex.pump();
        AtomicLong runs = new AtomicLong();
        ScheduledAction a = ex.schedule(10 * MS, "x", runs::incrementAndGet);
        a.cancel();
        assertEquals(50 * MS, ex.nextDueNanos());
        pumpAt(60 * MS);
        assertEquals(0L, runs.get());
        assertEquals(false, a.isDone());
    }

    public void testActionsRunInDueOrderAndMayRescheduleSameInstant() {
        ex.pump();
        List<String> order = new ArrayList<>();
        ex.schedule(20 * MS, "b", () -> order.add("b"));
        ex.schedule(10 * MS, "a", () -> {
            order.add("a");
            ex.schedule(10 * MS, "a2", () -> order.add("a2"));   // sofort fällig, im selben Durchlauf
        });
        pumpAt(10 * MS);
        assertEquals(List.of("a", "a2"), order);
        pumpAt(20 * MS);
        assertEquals(List.of("a", "a2", "b"), order);
    }

    public void testTickTasksReceiveActualElapsedTime() {
        ex.pump();
        List<Long> elapsed = new ArrayList<>();
        ex.addTickTask((now, dt) -> elapsed.add(dt));
        pumpAt(50 * MS);            // pünktlich
        pumpAt(107 * MS);           // Takt 100 ms, 7 ms zu spät
        pumpAt(150 * MS);           // Takt 150 ms pünktlich
        assertEquals(List.of(50 * MS, 57 * MS, 43 * MS), elapsed);
    }

    public void testTickJitterIsMeasuredAndReportedThrottled() {
        ex.pump();
        long[] late = {0, 1, 2, 3, 10, 0, 0, 0, 0, 4};
        long due = ex.nextDueNanos();
        for (long l : late) {
            pumpAt(due + l * MS);
            due = ex.nextDueNanos();
        }
        TimingStatistics.Summary s = ex.tickJitter();
        assertEquals(10L, s.count());
        assertEquals(0.0, s.minMs());
        assertEquals(10.0, s.maxMs());
        assertTrue(Math.abs(s.meanMs() - 2.0) < 1e-9, "Mittel 2 ms, war " + s.meanMs());
        assertTrue(s.p95Ms() >= 10.0 && s.p95Ms() <= 10.1, "p95 ≈ 10 ms, war " + s.p95Ms());
        assertEquals(0, journal.byCode("TICK_JITTER").size());   // noch keine Sekunde vergangen

        // Bis zum Ende des Berichtsfensters pünktlich weitertakten: genau ein Bericht, nicht einer je Takt
        while (clock.nanoTime() < 1_400 * MS) pumpAt(ex.nextDueNanos());
        assertEquals(1, journal.byCode("TICK_JITTER").size());
        assertEquals(10.0, ex.lastTickJitterReport().maxMs());
        assertTrue(ex.tickJitter().count() < 10, "Messfenster nach dem Bericht neu begonnen");
    }

    public void testTickOverrunIsCountedAndWarnedOncePerWindow() {
        ex.pump();
        pumpAt(50 * MS);
        pumpAt(230 * MS);           // Takt 100 ms um 130 ms verpasst
        pumpAt(ex.nextDueNanos());
        assertEquals(280 * MS, clock.nanoTime());   // neu aufgesetzt, nicht nachgeholt
        pumpAt(600 * MS);           // nochmals verpasst
        assertEquals(2L, ex.tickJitter().overruns());
        assertEquals(1, journal.byCode("TICK_OVERRUN").size());
    }

    public void testFailingScheduledActionTriggersEmergencyAndFault() {
        bringReady();
        ex.schedule(clock.nanoTime() + 3 * MS, "defekt", () -> { throw new IllegalStateException("Test"); });
        pumpAt(ex.nextDueNanos());
        assertEquals(1, station.emergencyStops.get());
        assertEquals(OperatingState.FAULT, ex.snapshot().operatingState());
    }

    public void testEmergencyStopStaysImmediateAndBlocksScheduledMovement() {
        bringReady();
        long due = clock.nanoTime() + 30 * MS;
        ex.schedule(due, "Fahrstufe 20", () -> ex.commands().drive(3, 20, SpeedSteps.S128, Direction.FORWARD));
        ex.emergencyStop("test", "Nothalt-Taste");
        assertEquals(1, station.emergencyStops.get());           // sofort, vor jedem Durchlauf
        pumpAt(due);
        assertEquals(OperatingState.EMERGENCY_STOP, ex.snapshot().operatingState());
        assertEquals(false, station.sent.contains("drive 3 20 FORWARD"));
        assertTrue(!journal.byCode("COMMAND_REJECTED").isEmpty(), "eingeplante Fahrt nach Nothalt abgewiesen");
    }

    public void testMovementAuthorityStillRequired() {
        bringReady();
        assertEquals(true, ex.commands().drive(3, 5, SpeedSteps.S128, Direction.FORWARD));
        assertEquals(false, ex.commands().drive(4, 5, SpeedSteps.S128, Direction.FORWARD));
    }

    // ------------------------------------------------------------------ echter Thread

    public void testThreadedExecutorProcessesEventsImmediatelyAndRunsScheduledActionsOnTime() throws Exception {
        Journal j = Journal.inMemory();
        FakeStation st = new FakeStation();
        // Takt 1 s: ein Ereignis darf nicht bis zum nächsten Takt warten
        ControlConfig cfg = ControlConfig.defaults().withWatchdog(2_000, 20).withTickMillis(1_000);
        ControlExecutor tex = ControlExecutor.create(cfg, TimeSource.SYSTEM, j, st, CommandSink.direct(st), List.of(1));
        tex.start();
        try {
            Thread.sleep(50);
            long t0 = System.nanoTime();
            tex.submit(new ControlCommand.RegisterTrain(new Train("T", "T", 3, SpeedSteps.S128, 500, 100, 1, null), "Test"));
            while (!tex.snapshot().trains().containsKey("T")) {
                if (System.nanoTime() - t0 > TimeUnit.MILLISECONDS.toNanos(300))
                    throw new AssertionError("Ereignis nicht sofort verarbeitet");
                Thread.onSpinWait();
            }

            AtomicLong lateness = new AtomicLong(Long.MIN_VALUE);
            long due = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(130);
            tex.enqueue("einplanen", () -> tex.schedule(due, "Test", () -> lateness.set(System.nanoTime() - due)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (lateness.get() == Long.MIN_VALUE && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(lateness.get() >= 0, "eingeplante Aufgabe nicht vor ihrem Zeitpunkt");
            // großzügige Grenze gegen Fehlalarme auf belasteten Rechnern; Taktraster wäre bis 1000 ms daneben
            assertTrue(lateness.get() < TimeUnit.MILLISECONDS.toNanos(40), "Verspätung " + lateness.get() / 1e6 + " ms");
        } finally {
            tex.stop();
        }
    }
}
