package at.schmoegl.railcore.z21.check;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.z21.Z21Protocol;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/** Positivliste der passiven Befehle; alles Unbekannte gilt als aktiv. */
public class CommandClassTest {

    public void testPassiveCommands() {
        for (byte[] p : new byte[][]{Z21Protocol.getSerialNumber(), Z21Protocol.getCode(), Z21Protocol.getHwInfo(),
                Z21Protocol.logoff(), Z21Protocol.setBroadcastFlags(Z21Check.REQUESTED_FLAGS), Z21Protocol.getBroadcastFlags(),
                Z21Protocol.rmBusGetData(0), Z21Protocol.systemStateGetData(), Z21Protocol.canDetectorRequest(Z21Protocol.CAN_ALL),
                Z21Protocol.getVersion(), Z21Protocol.getStatus(), Z21Protocol.getFirmwareVersion(),
                Z21Protocol.getLocoInfo(9001), Z21Protocol.getTurnoutInfo(1)}) {
            assertEquals(CommandClass.PASSIVE, CommandClass.of(p));
        }
    }

    public void testSafetyCommands() {
        assertEquals(CommandClass.SAFETY, CommandClass.of(Z21Protocol.stopAll()));
        assertEquals(CommandClass.SAFETY, CommandClass.of(Z21Protocol.emergencyStopLoco(9001, SpeedSteps.S128, Direction.FORWARD)));
        assertEquals(CommandClass.SAFETY, CommandClass.of(Z21Protocol.setLocoDrive(9001, 0, SpeedSteps.S128, Direction.REVERSE)));
        assertEquals(CommandClass.SAFETY, CommandClass.of(Z21Protocol.setLocoDrive(3, 0, SpeedSteps.S28, Direction.FORWARD)));
        assertEquals(CommandClass.SAFETY, CommandClass.of(Z21Protocol.setTurnout(1, TurnoutPosition.DIVERGING, false)));
    }

    public void testActiveCommands() {
        assertEquals(CommandClass.ACTIVE, CommandClass.of(Z21Protocol.trackPowerOn()));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(Z21Protocol.trackPowerOff()));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(Z21Protocol.setLocoDrive(9001, 1, SpeedSteps.S128, Direction.FORWARD)));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(Z21Protocol.setTurnout(1, TurnoutPosition.STRAIGHT, true)));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(Z21Protocol.setLocoFunction(9001, 29, true)));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(Z21Protocol.cvPomAccessory(0, null, 8, false, 0)));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(Z21Protocol.cvPomWrite(3, 1, 3)));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(Z21Protocol.cvWrite(1, 3)));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(Z21Protocol.cvRead(1)));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(new byte[]{4, 0, 0x22, 0}));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(new byte[]{1}));
    }

    /**
     * Booster-Schaltbefehle aus Spezifikation V1.13 sind nie passiv: LAN_CAN_BOOSTER_SET_TRACKPOWER (0xCB, über die
     * Z21) und LAN_BOOSTER_SET_POWER (0xB2, direkt am Booster über zLink). Auch die Namens-Befehle (0xC8/0xC9) stehen
     * nicht auf der Positivliste.
     */
    public void testBoosterCommandsAreNeverPassive() {
        assertEquals(CommandClass.ACTIVE, CommandClass.of(new byte[]{0x07, 0, (byte) 0xCB, 0, 0x01, (byte) 0xC1, 0x00}));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(new byte[]{0x07, 0, (byte) 0xCB, 0, 0x01, (byte) 0xC1, (byte) 0xFF}));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(new byte[]{0x06, 0, (byte) 0xB2, 0, 0x02, 0x01}));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(new byte[]{0x06, 0, (byte) 0xC8, 0, 0x01, (byte) 0xC1}));
    }

    /** 0x10/0x11 sind nur bei 28 Fahrstufen Halt; bei 128 Fahrstufen sind es Fahrstufen 15/16. */
    public void testSpeedByteDependsOnStepMode() {
        assertEquals(CommandClass.ACTIVE, CommandClass.of(Z21Protocol.setLocoDrive(3, 15, SpeedSteps.S128, Direction.REVERSE)));
        // 28 Fahrstufen, Draht 0x10 = Halt (Zwischenbit)
        byte[] s28 = Z21Protocol.setLocoDrive(3, 0, SpeedSteps.S28, Direction.REVERSE);
        s28[8] = 0x10;
        assertEquals(CommandClass.SAFETY, CommandClass.of(s28));
        assertEquals(CommandClass.ACTIVE, CommandClass.of(Z21Protocol.setLocoDrive(3, 1, SpeedSteps.S28, Direction.REVERSE)));
    }
}
