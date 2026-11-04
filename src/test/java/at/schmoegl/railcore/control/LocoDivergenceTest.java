package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.LocoState;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.OperatingState;
import at.schmoegl.railcore.train.Train;
import at.schmoegl.railcore.util.TimeSource;

import java.util.List;
import java.util.Set;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;

/** Nachkorrektur bei abweichender Lokmeldung (Lastenheft 7): Echo-Spielraum, Stellvertreter, Nothalt. */
public class LocoDivergenceTest {

    private final TimeSource.Manual clock = new TimeSource.Manual();
    private final Journal journal = Journal.inMemory();
    private final FakeStation station = new FakeStation(clock);
    private final ControlExecutor ex = ControlExecutor.create(ControlConfig.defaults(), SchedulerConfig.defaults(),
            SupervisionConfig.defaults(), clock, journal, station, List.of(1), address -> true);

    private void step(double ms) {
        clock.advanceMillis(ms);
        station.heartbeat(clock.nanoTime());
        ex.step();
    }

    private void ready() {
        ex.submit(new ControlCommand.RegisterTrain(new Train("A", "Zug A", 3, SpeedSteps.S128, 1000, 120, 1, null), "Test"));
        station.connected();
        ex.step();
        step(10);
        ex.submit(new ControlCommand.RequestReady("Test"));
        step(10);
        assertEquals(OperatingState.READY, ex.snapshot().operatingState());
    }

    private static LocoState loco(int address, int step) {
        return new LocoState(address, step, SpeedSteps.S128, Direction.FORWARD, 0L);
    }

    private String lastSent() { return station.sent.get(station.sent.size() - 1); }

    public void testLateEchoOfPreviousValueIsNoDivergenceButLaterIs() {
        ready();
        ex.commands().drive(3, 40, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL);
        step(100);
        ex.commands().drive(3, 60, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL);
        step(100);
        assertEquals("drive 3 60 FORWARD", lastSent());
        int sent = station.sent.size();
        station.locoState(loco(3, 40));                                     // verspätetes Echo des vorigen Werts
        step(10);
        assertEquals(0, journal.byCode("LOCO_DIVERGED").size());
        step(600);                                                          // Echo-Spielraum 500 ms vorbei
        station.locoState(loco(3, 40));
        step(10);
        assertEquals(1, journal.byCode("LOCO_DIVERGED").size());
        assertEquals(sent + 1, station.sent.size());                        // nicht als unverändert unterdrückt
        assertEquals("drive 3 60 FORWARD", lastSent());
        assertEquals(Set.of(TrainCaution.FOREIGN_CONTROL), ex.snapshot().trains().get("A").cautions());
    }

    /** Entscheidung 37 (6): Handregler und RailCore im Wechsel – nach 3 Abweichungen in 10 s Halt, Bediener entscheidet. */
    public void testAlternatingControlLeadsToHaltAndOperatorDecision() {
        ready();
        ex.commands().drive(3, 40, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL);
        step(1_000);
        station.locoState(loco(3, 90));                                     // 1. Abweichung: erneut 40
        step(1_000);
        assertEquals("drive 3 40 FORWARD", lastSent());
        station.locoState(loco(3, 90));                                     // 2. Abweichung: erneut 40
        step(1_000);
        assertEquals("drive 3 40 FORWARD", lastSent());
        assertEquals(0, journal.byCode("LOCO_CONTROL_CONFLICT").size());
        station.locoState(loco(3, 90));                                     // 3. Abweichung in 10 s: Halt
        step(100);
        assertEquals("drive 3 0 FORWARD", lastSent());
        assertEquals(1, journal.byCode("LOCO_CONTROL_CONFLICT").size());
        assertEquals(Set.of(TrainCaution.FOREIGN_CONTROL, TrainCaution.CONFLICTING_CONTROL),
                ex.snapshot().trains().get("A").cautions());
        assertEquals(false, ex.commands().drive(3, 40, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));

        ex.submit(new ControlCommand.ReleaseTrainCaution("A", "Bediener"));   // Bediener entscheidet
        step(10);
        assertEquals(Set.of(), ex.snapshot().trains().get("A").cautions());
        assertEquals(true, ex.commands().drive(3, 40, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL));
    }

    public void testDivergencesOutsideWindowDoNotHalt() {
        ready();
        ex.commands().drive(3, 40, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL);
        step(1_000);
        for (int i = 0; i < 3; i++) {
            station.locoState(loco(3, 90));
            step(6_000);                                                    // Abstand größer als das halbe Fenster
            assertEquals("drive 3 40 FORWARD", lastSent());
        }
        assertEquals(3, journal.byCode("LOCO_DIVERGED").size());
        assertEquals(0, journal.byCode("LOCO_CONTROL_CONFLICT").size());
    }

    public void testProxyAddressesAndUnknownLocosAreNotChecked() {
        ready();
        station.locoState(loco(9000, 50));                                  // Handregler
        station.locoState(loco(77, 50));                                    // nie angesprochen
        step(10);
        assertEquals(0, journal.byCode("LOCO_DIVERGED").size());
    }

    public void testStandingLocoAfterEmergencyStopIsExpected() {
        ready();
        ex.commands().drive(3, 40, SpeedSteps.S128, Direction.FORWARD, CommandPriority.MANUAL);
        step(100);
        ex.emergencyStop("Test", "Nothalt-Taste");
        step(10);
        station.locoState(loco(3, 0));
        step(600);
        assertEquals(0, journal.byCode("LOCO_DIVERGED").size());
    }

    public void testReleaseRequiresKnownTrainAndConfigIsValidated() {
        ready();
        ex.submit(new ControlCommand.ReleaseTrainCaution("X", "Bediener"));
        step(10);
        assertEquals(true, journal.byCode("COMMAND_REJECTED").stream().anyMatch(e -> e.message().contains("nicht angemeldet")));
        assertThrows(IllegalArgumentException.class, () -> SupervisionConfig.defaults().withDetectorQuery(1_000, 1_500));
        assertThrows(IllegalArgumentException.class, () -> SupervisionConfig.defaults().withLocoEchoGraceMillis(-1));
        assertEquals(0, SupervisionConfig.defaults().withDetectorQuery(0, 0).detectorQueryMillis());
        // Entscheidung 37: Melderabfrage ab Werk an (2 s, stumm nach 5 s), Halt nach 3 Abweichungen in 10 s – alles einstellbar
        SupervisionConfig d = SupervisionConfig.defaults();
        assertEquals(List.of(2_000, 5_000, 3, 10_000), List.of(d.detectorQueryMillis(), d.detectorSilenceMillis(),
                d.conflictDivergences(), d.conflictWindowMillis()));
        assertEquals(4_000, d.withDetectorQuery(1_500, 4_000).detectorSilenceMillis());
        assertEquals(5, d.withConflict(5, 20_000).conflictDivergences());
        assertThrows(IllegalArgumentException.class, () -> d.withConflict(0, 10_000));
        assertThrows(IllegalArgumentException.class, () -> d.withConflict(3, 500));
    }
}
