package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;

/**
 * CommandScheduler (Entscheidung 27): Vorrang, Zusammenfassen, Unterdrücken, Budget, Nothalt, Neuverbinden,
 * Überlast und Messwerte – deterministisch mit TimeSource.Manual über den Zeitplan des Executors.
 */
public class CommandSchedulerTest {

    private static final long MS = 1_000_000L;
    /** Bucket 1, Halt darf nicht überziehen: jeder zweite Befehl muss warten. */
    private static final SchedulerConfig TIGHT = SchedulerConfig.defaults().withBurst(1, 0);

    private final TimeSource.Manual clock = new TimeSource.Manual();
    private final Journal journal = Journal.inMemory();
    private final FakeStation station = new FakeStation(clock);
    private final Set<Integer> revoked = new HashSet<>();
    private ControlExecutor ex;

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    private void setup(SchedulerConfig cfg) {
        ex = ControlExecutor.create(ControlConfig.defaults(), cfg, clock, journal, station, List.of(1),
                address -> !revoked.contains(address));
        station.connected();
        pumpAt(clock.nanoTime() + MS);
        pumpAt(ex.nextDueNanos());
        ex.submit(new ControlCommand.RequestReady("Test"));
        pumpAt(clock.nanoTime() + MS);
        assertEquals(OperatingState.READY, ex.snapshot().operatingState());
        station.sent.clear();
        station.sentAt.clear();
    }

    /** Uhr auf {@code t}, Lebenszeichen der Zentrale, ein Durchlauf. */
    private void pumpAt(long t) {
        clock.advanceNanos(Math.max(0, t - clock.nanoTime()));
        station.heartbeat(clock.nanoTime());
        ex.pump();
    }

    /** Steuerschleife wie im Simulator: jeweils zum nächsten fälligen Zeitpunkt. */
    private void runFor(long nanos) {
        long end = clock.nanoTime() + nanos;
        while (clock.nanoTime() < end) pumpAt(Math.min(ex.nextDueNanos(), end));
    }

    private boolean drive(int address, int step, CommandPriority p) {
        return ex.commands().drive(address, step, SpeedSteps.S128, Direction.FORWARD, p);
    }

    private List<String> sentSince(int index) { return new ArrayList<>(station.sent.subList(index, station.sent.size())); }

    // ================================================================== Budget-Modell

    public void testBudgetModelStartValues() {
        SchedulerConfig c = SchedulerConfig.defaults();
        assertEquals(75.0, c.budgetPerSecond(0));
        assertEquals(70.0, c.budgetPerSecond(10));
        assertEquals(62.5, c.budgetPerSecond(25));
        assertEquals(50.0, c.budgetPerSecond(50));
        assertEquals(25.0, c.budgetPerSecond(100));
        assertEquals(5.0, c.withPacketMillis(50).budgetPerSecond(100));   // Mindestbudget
        assertThrows(IllegalArgumentException.class, () -> c.withPacketMillis(0.5));
        assertThrows(IllegalArgumentException.class, () -> c.withBurst(0, 0));
    }

    public void testBudgetFollowsActiveLocos() {
        setup(SchedulerConfig.defaults());
        for (int a = 1; a <= 10; a++) drive(a, 5, CommandPriority.MANUAL);
        runFor(200 * MS);
        assertEquals(10, ex.scheduler().activeLocos());
        assertEquals(70.0, ex.scheduler().budgetPerSecond());
        for (int a = 1; a <= 10; a++) drive(a, 0, CommandPriority.MANUAL);
        runFor(61_000 * MS);                                   // stehend und 60 s ohne Befehl: nicht mehr aktiv
        assertEquals(0, ex.scheduler().activeLocos());
        assertEquals(75.0, ex.scheduler().budgetPerSecond());
    }

    public void testEchoCorrectionIsInactiveAndNeverRaisesBudget() {
        BudgetCorrection doubling = new BudgetCorrection() {
            @Override public double correct(double model) { return 2 * model; }
            @Override public void echoMeasured(long rtt) { }
        };
        ex = ControlExecutor.create(ControlConfig.defaults(), SchedulerConfig.defaults(), clock, journal, station,
                List.of(1), a -> true, doubling);
        station.connected();
        pumpAt(clock.nanoTime());
        runFor(100 * MS);
        assertEquals(75.0, ex.scheduler().budgetPerSecond());  // nie über dem Rechenmodell
        assertTrue(journal.byCode("SCHEDULER_CREATED").get(0).message().contains("Echo-Nachführung inaktiv"), "Hinweis inaktiv");
    }

    public void testBudgetIsKeptAndEvenlyDistributed() {
        setup(SchedulerConfig.defaults());                     // 75/s, Bucket 4; Funktionen zählen nicht als aktive Lok
        for (int a = 1; a <= 300; a++) ex.commands().function(a, 1, true, CommandPriority.MANUAL);
        runFor(4_100 * MS);
        List<Long> t = station.sentAt;
        assertEquals(300, t.size());
        for (int i = 1; i < 4; i++) assertEquals(t.get(0), t.get(i));   // Bucket: vier sofort
        double spacing = 1e9 / 75;
        for (int i = 4; i < t.size(); i++) {
            long gap = t.get(i) - t.get(i - 1);
            assertTrue(Math.abs(gap - spacing) < 2_000, "gleichmäßig: Abstand " + gap + " ns bei Nr. " + i);
        }
        for (int i = 0; i < t.size(); i++) {                   // jedes 1-s-Fenster: höchstens Budget + Bucket
            int n = 0;
            for (int j = i; j < t.size() && t.get(j) < t.get(i) + 1_000 * MS; j++) n++;
            assertTrue(n <= 75 + 4, "Fenster ab Nr. " + i + ": " + n + " Befehle");
        }
        SchedulerMetrics m = ex.scheduler().metrics();
        assertTrue(m.utilization() > 0.9, "Auslastung " + m.utilization());
    }

    // ================================================================== Vorrang, Zusammenfassen, Unterdrücken

    public void testHigherPriorityReplacesLowerAndLowerNeverOverwritesHigher() {
        setup(TIGHT);
        assertTrue(drive(10, 5, CommandPriority.MANUAL), "sofort gesendet");
        assertTrue(drive(11, 7, CommandPriority.MANUAL), "wartet");
        assertTrue(drive(11, 3, CommandPriority.SAFETY), "höher ersetzt niedriger");
        assertEquals(false, drive(11, 9, CommandPriority.MANUAL));
        assertEquals(false, drive(11, 9, CommandPriority.AUTOMATIC));
        runFor(100 * MS);
        assertEquals(List.of("drive 10 5 FORWARD", "drive 11 3 FORWARD"), station.sent);
        SchedulerMetrics m = ex.scheduler().metrics();
        assertEquals(2L, m.rejectedLowerPriority());
        assertEquals(1L, m.coalesced());
        assertEquals(2, journal.byCode("COMMAND_REJECTED").size());
    }

    public void testPendingHaltIsOnlyReplacedByHalt() {
        setup(TIGHT);
        drive(10, 5, CommandPriority.MANUAL);
        assertTrue(drive(12, 0, CommandPriority.MANUAL), "Halt (mindestens Sicherheit) wartet");
        assertEquals(false, drive(12, 4, CommandPriority.SAFETY));
        assertEquals(false, drive(12, 4, CommandPriority.MANUAL));
        runFor(100 * MS);
        assertEquals(List.of("drive 10 5 FORWARD", "drive 12 0 FORWARD"), station.sent);
    }

    public void testStrictPriorityAndOldestFirstWithinClass() {
        setup(TIGHT);
        drive(1, 5, CommandPriority.MANUAL);                   // nimmt das Token
        ex.commands().function(2, 1, true, CommandPriority.COMFORT);
        drive(3, 5, CommandPriority.MANUAL);
        clock.advanceNanos(1_000);
        drive(4, 5, CommandPriority.TIMETABLE);
        drive(5, 5, CommandPriority.AUTOMATIC);
        drive(6, 4, CommandPriority.SAFETY);
        drive(7, 0, CommandPriority.MANUAL);                   // Halt → Sicherheit, vor Bremsstufen
        drive(8, 5, CommandPriority.MANUAL);                   // jünger als Lok 3
        runFor(300 * MS);
        assertEquals(List.of("drive 1 5 FORWARD", "drive 7 0 FORWARD", "drive 6 4 FORWARD", "drive 5 5 FORWARD",
                "drive 4 5 FORWARD", "drive 3 5 FORWARD", "drive 8 5 FORWARD", "fn 2"), station.sent);
    }

    public void testUnchangedSpeedAndDirectionIsNeverSent() {
        setup(SchedulerConfig.defaults().withBurst(2, 2));     // Bucket 2: Funktion und Weiche müssen warten
        drive(3, 10, CommandPriority.MANUAL);
        assertTrue(drive(3, 10, CommandPriority.MANUAL), "angenommen");
        assertTrue(drive(3, 10, CommandPriority.AUTOMATIC), "angenommen");
        ex.commands().drive(3, 10, SpeedSteps.S128, Direction.REVERSE, CommandPriority.MANUAL);   // Richtung neu
        ex.commands().function(3, 0, true, CommandPriority.COMFORT);
        ex.commands().function(3, 0, true, CommandPriority.COMFORT);
        ex.commands().turnout(5, TurnoutPosition.DIVERGING, CommandPriority.AUTOMATIC);
        ex.commands().turnout(5, TurnoutPosition.DIVERGING, CommandPriority.AUTOMATIC);
        runFor(200 * MS);
        assertEquals(List.of("drive 3 10 FORWARD", "drive 3 10 REVERSE", "turnout 5 DIVERGING", "fn 3"), station.sent);
        assertEquals(2L, ex.scheduler().metrics().suppressed());   // Fahrbefehle gegen den bestätigten Zustand
        assertEquals(2L, ex.scheduler().metrics().coalesced());    // Funktion, Weiche: gleicher Wert wartete noch
    }

    public void testTurnoutIsNeverSuppressedButCoalescedWhilePending() {
        setup(TIGHT);
        drive(1, 5, CommandPriority.MANUAL);                   // Token weg: die Weiche wartet
        ex.commands().turnout(5, TurnoutPosition.DIVERGING, CommandPriority.AUTOMATIC);
        ex.commands().turnout(5, TurnoutPosition.STRAIGHT, CommandPriority.AUTOMATIC);
        ex.commands().turnout(5, TurnoutPosition.DIVERGING, CommandPriority.AUTOMATIC);
        runFor(200 * MS);
        assertEquals(List.of("drive 1 5 FORWARD", "turnout 5 DIVERGING"), station.sent);   // wartend zusammengefasst
        ex.commands().turnout(5, TurnoutPosition.DIVERGING, CommandPriority.AUTOMATIC);   // bestätigt gleich: trotzdem
        runFor(200 * MS);
        assertEquals(List.of("drive 1 5 FORWARD", "turnout 5 DIVERGING", "turnout 5 DIVERGING"), station.sent);
        assertEquals(0L, ex.scheduler().metrics().suppressed());
    }

    public void testReductionsBeforeIncreasesWithinClass() {
        setup(SchedulerConfig.defaults());
        drive(31, 40, CommandPriority.AUTOMATIC);
        drive(32, 40, CommandPriority.AUTOMATIC);
        drive(33, 40, CommandPriority.AUTOMATIC);
        drive(34, 40, CommandPriority.AUTOMATIC);              // Bucket 4 leer
        drive(35, 20, CommandPriority.AUTOMATIC);              // Anfahren (noch nie gesendet), ältester
        clock.advanceNanos(1_000);
        drive(31, 50, CommandPriority.AUTOMATIC);              // Beschleunigen
        clock.advanceNanos(1_000);
        drive(32, 30, CommandPriority.AUTOMATIC);              // Bremsstufe, jünger
        runFor(200 * MS);
        assertEquals(List.of("drive 32 30 FORWARD", "drive 35 20 FORWARD", "drive 31 50 FORWARD"),
                station.sent.subList(4, 7));
    }

    public void testSupervisionReplacesWaitingAutomaticBrakeStep() {
        setup(TIGHT);
        drive(1, 5, CommandPriority.MANUAL);                   // Token weg
        drive(20, 40, CommandPriority.AUTOMATIC);              // planmäßige Bremsstufe wartet
        assertTrue(drive(20, 10, CommandPriority.SAFETY), "Eingriff der Überwachung ersetzt die Bremsstufe");
        assertEquals(false, drive(20, 35, CommandPriority.AUTOMATIC));   // Plan überschreibt den Eingriff nicht
        runFor(100 * MS);
        assertEquals(List.of("drive 1 5 FORWARD", "drive 20 10 FORWARD"), station.sent);
        assertEquals(1L, ex.scheduler().metrics().waitOf(CommandPriority.SAFETY).count());
        assertEquals(1L, ex.scheduler().metrics().waitOf(CommandPriority.AUTOMATIC).count());   // Wartezeit bis zum Ersetzen
    }

    // ================================================================== Aufstufung (Entscheidung 30)

    /** Knappes Budget (7,5/s bei drei aktiven Loks), Bucket 1: jede Lok fährt mit Stufe 50 (gesendet). */
    private void setupSlowWithThreeRunningLocos() {
        setup(SchedulerConfig.defaults().withPacketMillis(50).withBurst(1, 0));
        for (int a = 20; a <= 22; a++) drive(a, 50, CommandPriority.MANUAL);   // Automatik-Statistik bleibt leer
        runFor(1_000 * MS);
        assertEquals(3, station.sent.size());
        station.sent.clear();
        station.sentAt.clear();
    }

    private CommandSink.DriveListener escalationRecorder(List<Long> at) {
        return new CommandSink.DriveListener() {
            @Override public void sent(int step, Direction d, long t) { }
            @Override public void dropped(String reason) { }
            @Override public void escalated() { at.add(clock.nanoTime()); }
        };
    }

    private boolean drive(int address, int step, CommandPriority p, CommandSink.DriveListener l) {
        return ex.commands().drive(address, step, SpeedSteps.S128, Direction.FORWARD, p, l);
    }

    public void testWaitingBrakeStepIsEscalatedExactlyAtItsLimit() {
        setupSlowWithThreeRunningLocos();
        double slotMs = 1_000.0 / ex.scheduler().budgetPerSecond();   // ≈ 133 ms
        List<Long> escalatedAt = new ArrayList<>();
        long t0 = clock.nanoTime();
        drive(20, 40, CommandPriority.AUTOMATIC);              // sofort (Token)
        drive(21, 40, CommandPriority.AUTOMATIC);              // ein Sendeplatz Wartezeit: unter der Grenze
        drive(22, 40, CommandPriority.AUTOMATIC, escalationRecorder(escalatedAt));   // zwei Sendeplätze: darüber
        runFor(1_000 * MS);
        assertEquals(List.of("drive 20 40 FORWARD", "drive 21 40 FORWARD", "drive 22 40 FORWARD"), station.sent);
        assertEquals(List.of(t0 + 150 * MS), escalatedAt);     // genau zur Grenze, nicht erst im Takt
        SchedulerMetrics m = ex.scheduler().metrics();
        assertEquals(1L, m.escalations());
        // nachvollziehbar: 150 ms in Automatik, danach der Rest bis zum Senden in Sicherheit
        assertEquals(1L, m.waitOf(CommandPriority.SAFETY).count());
        assertTrue(Math.abs(m.waitOf(CommandPriority.SAFETY).maxMs() - (2 * slotMs - 150)) < 0.2,
                "Wartezeit in Sicherheit " + m.waitOf(CommandPriority.SAFETY).maxMs());
        assertTrue(Math.abs(m.waitOf(CommandPriority.AUTOMATIC).maxMs() - 150) < 0.2, "Wartezeit in Automatik bis zur Aufstufung");
    }

    public void testEscalatedBrakeStepStaysBehindRealSafetyCommands() {
        setupSlowWithThreeRunningLocos();
        drive(20, 40, CommandPriority.AUTOMATIC);              // sofort
        drive(21, 40, CommandPriority.AUTOMATIC);              // Bremsstufe, älter
        clock.advanceNanos(1_000);
        drive(23, 10, CommandPriority.SAFETY);                 // nimmt den nächsten Platz (neue Lok)
        drive(24, 10, CommandPriority.SAFETY);
        runFor(2_000 * MS);
        // 21 wartet länger als 150 ms → aufgestuft; echte Sicherheitsbefehle bleiben trotzdem davor
        // (Entscheidung 28: Sicherheitseingriffe warten nie hinter dem Normalbetrieb)
        assertEquals(List.of("drive 20 40 FORWARD", "drive 23 10 FORWARD", "drive 24 10 FORWARD", "drive 21 40 FORWARD"),
                station.sent);
        assertEquals(1L, ex.scheduler().metrics().escalations());
    }

    public void testIncreasesAndStartsAreNeverEscalated() {
        setupSlowWithThreeRunningLocos();
        drive(20, 40, CommandPriority.AUTOMATIC);              // sofort
        drive(21, 60, CommandPriority.AUTOMATIC);              // Beschleunigen
        drive(30, 20, CommandPriority.AUTOMATIC);              // Anfahren einer neuen Lok
        drive(22, 55, CommandPriority.AUTOMATIC);
        runFor(2_000 * MS);
        assertEquals(4, station.sent.size());
        assertEquals(0L, ex.scheduler().metrics().escalations());
        assertEquals(0L, ex.scheduler().metrics().waitOf(CommandPriority.SAFETY).count());
        assertTrue(ex.scheduler().metrics().waitOf(CommandPriority.AUTOMATIC).maxMs() > 300, "lange gewartet, trotzdem Automatik");
    }

    public void testEscalatedBrakeStepTakesLaterValuesOfItsClass() {
        setupSlowWithThreeRunningLocos();
        drive(20, 40, CommandPriority.AUTOMATIC);              // sofort
        drive(21, 40, CommandPriority.AUTOMATIC);
        drive(22, 40, CommandPriority.AUTOMATIC);
        runFor(160 * MS);                                      // 22 ist aufgestuft, wartet noch
        assertEquals(1L, ex.scheduler().metrics().escalations());
        assertTrue(drive(22, 30, CommandPriority.AUTOMATIC), "neuere Bremsstufe wird übernommen, nicht abgewiesen");
        assertEquals(0L, ex.scheduler().metrics().rejectedLowerPriority());
        runFor(1_000 * MS);
        assertEquals("drive 22 30 FORWARD", station.sent.get(station.sent.size() - 1));   // nur der neueste Wert
        assertEquals(1L, ex.scheduler().metrics().waitOf(CommandPriority.SAFETY).count());   // 22 in Sicherheit gesendet

        // Rückkehr in die eigene Klasse: eine spätere Erhöhung ist keine Bremsstufe mehr
        drive(20, 30, CommandPriority.AUTOMATIC);              // Token weg
        drive(21, 30, CommandPriority.AUTOMATIC);
        drive(22, 20, CommandPriority.AUTOMATIC);
        runFor(160 * MS);
        assertEquals(2L, ex.scheduler().metrics().escalations());
        drive(22, 45, CommandPriority.AUTOMATIC);              // über dem gesendeten Wert 30: Beschleunigen
        long safetyBefore = ex.scheduler().metrics().waitOf(CommandPriority.SAFETY).count();
        runFor(1_000 * MS);
        assertEquals("drive 22 45 FORWARD", station.sent.get(station.sent.size() - 1));
        assertEquals(safetyBefore, ex.scheduler().metrics().waitOf(CommandPriority.SAFETY).count());   // als Automatik gesendet
    }

    public void testEscalationNeverDelaysEmergencyStop() {
        setupSlowWithThreeRunningLocos();
        drive(20, 40, CommandPriority.AUTOMATIC);
        drive(21, 40, CommandPriority.AUTOMATIC);
        drive(22, 40, CommandPriority.AUTOMATIC);
        runFor(160 * MS);
        assertEquals(1L, ex.scheduler().metrics().escalations());
        int before = station.sent.size();
        ex.emergencyStop("test", "Nothalt-Taste");
        assertEquals(1, station.emergencyStops.get());         // sofort, am Befehlsplaner vorbei
        runFor(1_000 * MS);
        assertEquals(before, station.sent.size());             // aufgestufte Bremsstufe verworfen, nichts nachgesendet
        assertTrue(ex.scheduler().metrics().droppedOnEmergency() >= 1, "beim Nothalt verworfen");
    }

    public void testPhaseOffsetsAreSpreadOverTheTick() {
        long tick = 50 * MS;
        boolean[] used = new boolean[10];
        for (int a = 1; a <= 10; a++) {
            long p = TrainDriver.phaseOffsetNanos(a, tick);
            assertTrue(p >= 0 && p < tick, "im Takt");
            assertEquals(p, TrainDriver.phaseOffsetNanos(a, tick));   // fest
            used[(int) (p / (tick / 10))] = true;
        }
        for (boolean u : used) assertTrue(u, "10 Adressen belegen alle 10 Zehntel des Takts");
    }

    public void testCoalescingSendsOnlyNewestValue() {
        setup(TIGHT);
        drive(20, 50, CommandPriority.SAFETY);
        drive(20, 40, CommandPriority.SAFETY);
        drive(20, 30, CommandPriority.SAFETY);
        drive(20, 20, CommandPriority.SAFETY);
        runFor(100 * MS);
        assertEquals(List.of("drive 20 50 FORWARD", "drive 20 20 FORWARD"), station.sent);
        SchedulerMetrics m = ex.scheduler().metrics();
        assertEquals(2L, m.coalesced());
        TimingStatistics.Summary w = m.waitOf(CommandPriority.SAFETY);
        assertEquals(2L, w.count());
        assertTrue(Math.abs(w.maxMs() - 1_000.0 / 75) < 0.01, "Wartezeit ab dem ersten Einreihen: " + w.maxMs());
        // zurück auf den gesendeten Wert: Wartendes entfällt, nichts wird gesendet
        drive(21, 5, CommandPriority.MANUAL);
        drive(20, 10, CommandPriority.SAFETY);
        drive(20, 20, CommandPriority.SAFETY);
        runFor(100 * MS);
        assertEquals(List.of("drive 20 50 FORWARD", "drive 20 20 FORWARD", "drive 21 5 FORWARD"), station.sent);
    }

    public void testHaltBorrowsBudgetAndIsSentImmediately() {
        setup(SchedulerConfig.defaults().withBurst(1, 2));
        drive(1, 5, CommandPriority.MANUAL);                   // Token verbraucht
        drive(2, 0, CommandPriority.AUTOMATIC);
        drive(3, 0, CommandPriority.MANUAL);
        drive(4, 0, CommandPriority.MANUAL);                   // Überziehen erschöpft → wartet
        assertEquals(List.of("drive 1 5 FORWARD", "drive 2 0 FORWARD", "drive 3 0 FORWARD"), station.sent);
        assertEquals(station.sentAt.get(0), station.sentAt.get(2));
        runFor(100 * MS);
        assertEquals("drive 4 0 FORWARD", station.sent.get(3));
        assertTrue(station.sentAt.get(3) - station.sentAt.get(0) >= MS * 1_000 / 75, "Schuld wird abgetragen");
        drive(5, 5, CommandPriority.MANUAL);                   // kein Halt: wartet, bis die Schuld getilgt ist
        runFor(100 * MS);
        assertTrue(station.sentAt.get(4) - station.sentAt.get(0) >= 3 * MS * 1_000 / 75, "Fahrt nach getilgter Schuld");
    }

    public void testEmergencyPriorityNeverQueued() {
        setup(SchedulerConfig.defaults());
        assertThrows(IllegalArgumentException.class, () -> drive(3, 0, CommandPriority.EMERGENCY));
    }

    // ================================================================== Sperre beim Senden, Nothalt

    public void testBlockedAtDispatchSendsHaltInstead() {
        setup(TIGHT);
        drive(1, 5, CommandPriority.MANUAL);
        List<String> dropped = new ArrayList<>();
        List<Integer> sentSteps = new ArrayList<>();
        ex.commands().drive(2, 5, SpeedSteps.S128, Direction.FORWARD, CommandPriority.AUTOMATIC, new CommandSink.DriveListener() {
            @Override public void sent(int step, Direction d, long t) { sentSteps.add(step); }
            @Override public void dropped(String reason) { dropped.add(reason); }
        });
        revoked.add(2);                                        // Reservierung entzogen, während der Befehl wartet
        runFor(100 * MS);
        assertEquals(List.of("drive 1 5 FORWARD", "drive 2 0 FORWARD"), station.sent);
        assertEquals(List.of(0), sentSteps);
        assertEquals(List.of("keine Reservierung"), dropped);
        assertEquals(1, journal.byCode("COMMAND_DROPPED").size());
    }

    public void testEmergencyStopIsImmediateDespiteFullQueueAndDropsPendingMovement() {
        setup(TIGHT);
        for (int a = 101; a <= 150; a++) drive(a, 5, CommandPriority.MANUAL);
        assertEquals(1, station.sent.size());
        assertEquals(49, ex.scheduler().backlog());
        ex.emergencyStop("test", "Nothalt-Taste");
        assertEquals(1, station.emergencyStops.get());         // sofort, ohne Durchlauf und ohne Warteschlange
        runFor(2_000 * MS);
        assertEquals(1, station.sent.size());                  // keine wartende Fahrt mehr gesendet
        assertEquals(49L, ex.scheduler().metrics().droppedOnEmergency());
        assertEquals(1, journal.byCode("COMMANDS_DROPPED_ON_EMERGENCY").size());
        assertEquals(OperatingState.EMERGENCY_STOP, ex.snapshot().operatingState());
        // nach dem Nothalt gilt der Zustand als unbestätigt: Halt wird gesendet, obwohl zuletzt nichts anderes kam
        drive(101, 0, CommandPriority.SAFETY);
        runFor(50 * MS);
        assertEquals("drive 101 0 FORWARD", station.sent.get(station.sent.size() - 1));
    }

    public void testEmergencyRequestedButNotYetAppliedBlocksMovement() {
        setup(SchedulerConfig.defaults());
        ex.emergencyStop("anderer Thread", "Nothalt-Taste");   // Executor hat ihn noch nicht übernommen
        drive(4, 10, CommandPriority.MANUAL);                  // Schranke sieht den Nothalt noch nicht
        assertEquals(List.of("drive 4 0 FORWARD"), station.sent);
        assertTrue(journal.byCode("COMMAND_DROPPED").get(0).message().contains("Nothalt angefordert"), "Grund");
    }

    public void testEmergencyDuringSendIsRepeatedAfterTheDriveCommand() {
        setup(SchedulerConfig.defaults());
        AtomicBoolean fired = new AtomicBoolean();
        station.onDrive = () -> {                              // Nothalt kommt genau während des Sendens
            if (fired.compareAndSet(false, true)) ex.emergencyStop("anderer Thread", "Nothalt-Taste");
        };
        drive(3, 10, CommandPriority.MANUAL);
        assertEquals(2, station.emergencyStops.get());         // der Nothalt ist der letzte Befehl
        assertEquals(1, journal.byCode("EMERGENCY_STOP_RESENT").size());
    }

    // ================================================================== Neuverbinden

    public void testReconnectRetransmitsHaltOnceAndNeverSpeed() {
        setup(SchedulerConfig.defaults());
        drive(3, 0, CommandPriority.MANUAL);
        drive(4, 5, CommandPriority.MANUAL);
        drive(3, 0, CommandPriority.MANUAL);                   // unverändert
        assertEquals(List.of("drive 3 0 FORWARD", "drive 4 5 FORWARD"), station.sent);
        int mark = station.sent.size();
        station.disconnected();
        runFor(10 * MS);
        assertEquals(OperatingState.FAULT, ex.snapshot().operatingState());
        station.connected();
        runFor(200 * MS);
        // einmalig Halt; Lok 4 (zuletzt Fahrt) zuerst: Absenkung vor gleichbleibendem Halt
        assertEquals(List.of("drive 4 0 FORWARD", "drive 3 0 FORWARD"), sentSince(mark));
        assertEquals(2L, ex.scheduler().metrics().retransmissions());
        assertEquals(1, journal.byCode("SCHEDULER_RETRANSMIT").size());
        station.connected();                                   // doppelte Meldung: keine weitere Neuübertragung
        runFor(200 * MS);
        drive(3, 0, CommandPriority.SAFETY);                   // jetzt wieder bestätigt: unverändert
        runFor(200 * MS);
        assertEquals(List.of("drive 4 0 FORWARD", "drive 3 0 FORWARD"), sentSince(mark));
        assertTrue(station.sent.stream().filter(s -> s.equals("drive 4 5 FORWARD")).count() == 1, "Fahrstufe nie wiederholt");
    }

    public void testReconnectAfterHeartbeatLossRetransmitsHalt() {
        setup(SchedulerConfig.defaults());
        drive(3, 0, CommandPriority.MANUAL);
        int mark = station.sent.size();
        // 6 s ohne Lebenszeichen → Verbindung verloren
        long end = clock.nanoTime() + 6_000 * MS;
        while (clock.nanoTime() < end) {
            clock.advanceNanos(Math.min(ex.nextDueNanos(), end) - clock.nanoTime());
            ex.pump();
        }
        assertEquals(OperatingState.FAULT, ex.snapshot().operatingState());
        runFor(200 * MS);                                      // Lebenszeichen wieder da
        assertEquals(List.of("drive 3 0 FORWARD"), sentSince(mark));
    }

    // ================================================================== Überlast und Messwerte

    public void testOverloadDetectedReportedSafetyFirstComfortDeferred() {
        setup(SchedulerConfig.defaults());
        AtomicInteger comfortWhileOverloaded = new AtomicInteger();
        station.onFunction = () -> { if (ex.scheduler().overloaded()) comfortWhileOverloaded.incrementAndGet(); };
        for (int a = 201; a <= 260; a++) drive(a, 5, CommandPriority.MANUAL);
        for (int a = 201; a <= 230; a++) ex.commands().function(a, 0, true, CommandPriority.COMFORT);
        runFor(60 * MS);
        assertTrue(ex.scheduler().overloaded(), "Überlast erkannt");
        assertEquals(1, journal.byCode("SCHEDULER_OVERLOAD").size());
        assertTrue(ex.snapshot().scheduler().overloaded(), "Überlast in der Momentaufnahme");
        assertTrue(ex.snapshot().scheduler().overloadReasons().get(0).startsWith("Rückstau"), "Befund");

        int mark = station.sent.size();
        for (int a = 301; a <= 305; a++) drive(a, 3, CommandPriority.SAFETY);
        runFor(5 * 1_000 * MS / 40);                            // gut fünf Sendeplätze
        List<String> next = sentSince(mark).subList(0, 5);
        for (int i = 0; i < 5; i++) assertEquals("drive " + (301 + i) + " 3 FORWARD", next.get(i));

        runFor(10_000 * MS);
        assertEquals(0, comfortWhileOverloaded.get());
        assertEquals(30L, station.sent.stream().filter(s -> s.startsWith("fn ")).count());
        assertEquals(1, journal.byCode("SCHEDULER_OVERLOAD_CLEARED").size());
        assertTrue(journal.byCode("SCHEDULER_OVERLOAD").size() <= 2, "Journal gedrosselt");
        assertTrue(!ex.snapshot().scheduler().overloaded(), "aufgehoben in der Momentaufnahme");
        int lastDrive = -1, firstFn = Integer.MAX_VALUE;
        for (int i = 0; i < station.sent.size(); i++) {
            if (station.sent.get(i).startsWith("drive")) lastDrive = i;
            if (station.sent.get(i).startsWith("fn") && firstFn == Integer.MAX_VALUE) firstFn = i;
        }
        assertTrue(firstFn > lastDrive, "Komfort erst nach allen Fahrbefehlen");
        // Sicherheit wartet nur auf sich selbst (fünf Sendeplätze), nie auf den Rückstau der Hand-Befehle
        double fiveSlotsMs = 5 * 1_000.0 / ex.scheduler().budgetPerSecond();
        assertTrue(ex.scheduler().metrics().waitOf(CommandPriority.SAFETY).maxMs() <= fiveSlotsMs + 0.5,
                "Sicherheit vor dem Rückstau: " + ex.scheduler().metrics().waitOf(CommandPriority.SAFETY).maxMs());
    }

    public void testMetricsAreReportedPeriodically() {
        setup(SchedulerConfig.defaults().withMetricsReportSeconds(1));
        drive(3, 10, CommandPriority.MANUAL);
        drive(3, 10, CommandPriority.MANUAL);
        runFor(1_100 * MS);
        assertEquals(1, journal.byCode("SCHEDULER_METRICS").size());
        SchedulerMetrics r = ex.scheduler().lastReport();
        assertEquals(1L, r.sent());
        assertEquals(1L, r.suppressed());
        assertEquals(1L, r.waitOf(CommandPriority.MANUAL).count());
        assertEquals(r, ex.snapshot().scheduler());
        assertTrue(journal.byCode("SCHEDULER_METRICS").get(0).message().contains("Wartezeit Hand"), "Wartezeit je Klasse");
    }
}
