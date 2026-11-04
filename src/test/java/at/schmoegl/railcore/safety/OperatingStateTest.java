package at.schmoegl.railcore.safety;

import at.schmoegl.railcore.diag.Journal;

import java.util.EnumSet;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/** Zustandsmodell der Anlage (Lastenheft 3): festgelegte Übergänge. */
public class OperatingStateTest {

    public void testConnectingMayGoToEmergencyStopOnlyAsNewTransition() {
        // Entscheidung 34: einzig neuer Übergang „Verbinde → Nothalt“
        assertEquals(EnumSet.of(OperatingState.READY, OperatingState.FAULT, OperatingState.OFFLINE,
                OperatingState.EMERGENCY_STOP), OperatingState.CONNECTING.allowedNext());
        // übrige Übergänge unverändert
        assertEquals(EnumSet.of(OperatingState.CONNECTING), OperatingState.OFFLINE.allowedNext());
        assertEquals(EnumSet.of(OperatingState.RECOVERY, OperatingState.FAULT, OperatingState.OFFLINE),
                OperatingState.EMERGENCY_STOP.allowedNext());
        assertEquals(EnumSet.of(OperatingState.RECOVERY, OperatingState.OFFLINE, OperatingState.EMERGENCY_STOP),
                OperatingState.FAULT.allowedNext());
        assertEquals(EnumSet.of(OperatingState.READY, OperatingState.FAULT, OperatingState.EMERGENCY_STOP, OperatingState.OFFLINE),
                OperatingState.RECOVERY.allowedNext());
        assertEquals(EnumSet.of(OperatingState.RUNNING, OperatingState.EMERGENCY_STOP, OperatingState.FAULT, OperatingState.OFFLINE),
                OperatingState.READY.allowedNext());
        assertEquals(false, OperatingState.EMERGENCY_STOP.allowedNext().contains(OperatingState.READY));   // nur über Wiederanlauf
    }

    public void testStateMachineAcceptsConnectingToEmergencyStop() {
        StateMachine sm = new StateMachine(Journal.inMemory());
        assertEquals(true, sm.transition(OperatingState.CONNECTING, "Test"));
        assertEquals(true, sm.transition(OperatingState.EMERGENCY_STOP, "Programmstart: Test"));
        assertEquals(OperatingState.EMERGENCY_STOP, sm.state());
        assertEquals("Programmstart: Test", sm.reason());
        assertEquals(false, sm.transition(OperatingState.READY, "direkt in Bereit"));       // nie ohne Quittieren
        assertEquals(true, sm.transition(OperatingState.RECOVERY, "Quittiert"));
    }
}
