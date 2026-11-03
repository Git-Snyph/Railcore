package at.schmoegl.railcore.z21;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TurnoutPosition;

import java.util.List;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/** Kodierung und Dekodierung gegen Bytefolgen aus der Z21-LAN-Protokoll-Spezifikation. */
public class Z21ProtocolTest {

    static byte[] bytes(String hex) {
        String[] p = hex.trim().split("\\s+");
        byte[] b = new byte[p.length];
        for (int i = 0; i < p.length; i++) b[i] = (byte) Integer.parseInt(p[i], 16);
        return b;
    }

    static Z21Message one(String hex) {
        byte[] b = bytes(hex);
        List<Z21Message> m = Z21Protocol.decode(b, b.length);
        assertEquals(1, m.size());
        return m.get(0);
    }

    public void testSimpleRequests() {
        assertEquals("04 00 10 00", Z21Protocol.hex(Z21Protocol.getSerialNumber()));
        assertEquals("04 00 18 00", Z21Protocol.hex(Z21Protocol.getCode()));
        assertEquals("04 00 1A 00", Z21Protocol.hex(Z21Protocol.getHwInfo()));
        assertEquals("04 00 30 00", Z21Protocol.hex(Z21Protocol.logoff()));
        assertEquals("04 00 51 00", Z21Protocol.hex(Z21Protocol.getBroadcastFlags()));
        assertEquals("04 00 85 00", Z21Protocol.hex(Z21Protocol.systemStateGetData()));
        assertEquals("08 00 50 00 01 00 01 00", Z21Protocol.hex(Z21Protocol.setBroadcastFlags(0x00010001)));
        assertEquals("05 00 81 00 01", Z21Protocol.hex(Z21Protocol.rmBusGetData(1)));
        assertEquals("07 00 C4 00 00 00 D0", Z21Protocol.hex(Z21Protocol.canDetectorRequest(Z21Protocol.CAN_ALL)));
    }

    public void testXBusRequests() {
        assertEquals("07 00 40 00 21 21 00", Z21Protocol.hex(Z21Protocol.getVersion()));
        assertEquals("07 00 40 00 21 24 05", Z21Protocol.hex(Z21Protocol.getStatus()));
        assertEquals("07 00 40 00 F1 0A FB", Z21Protocol.hex(Z21Protocol.getFirmwareVersion()));
        assertEquals("07 00 40 00 21 81 A0", Z21Protocol.hex(Z21Protocol.trackPowerOn()));
        assertEquals("07 00 40 00 21 80 A1", Z21Protocol.hex(Z21Protocol.trackPowerOff()));
        assertEquals("06 00 40 00 80 80", Z21Protocol.hex(Z21Protocol.stopAll()));
        assertEquals("09 00 40 00 E3 F0 00 03 10", Z21Protocol.hex(Z21Protocol.getLocoInfo(3)));
        // 9001 = 0x2329, lange Adresse: MSB | 0xC0
        assertEquals("09 00 40 00 E3 F0 E3 29 D9", Z21Protocol.hex(Z21Protocol.getLocoInfo(9001)));
        assertEquals("08 00 40 00 43 00 00 43", Z21Protocol.hex(Z21Protocol.getTurnoutInfo(1)));
        assertEquals("09 00 40 00 53 00 00 89 DA", Z21Protocol.hex(Z21Protocol.setTurnout(1, TurnoutPosition.DIVERGING, true)));
        assertEquals("09 00 40 00 53 00 00 81 D2", Z21Protocol.hex(Z21Protocol.setTurnout(1, TurnoutPosition.DIVERGING, false)));
        assertEquals("0A 00 40 00 E4 13 E3 29 81 BC",
                Z21Protocol.hex(Z21Protocol.emergencyStopLoco(9001, SpeedSteps.S128, Direction.FORWARD)));
        // Fahrstufe 1 bei 128 Fahrstufen wird als 2 übertragen (1 = Nothalt)
        assertEquals("0A 00 40 00 E4 13 E3 29 82 BF",
                Z21Protocol.hex(Z21Protocol.setLocoDrive(9001, 1, SpeedSteps.S128, Direction.FORWARD)));
    }

    public void testDecodeHwInfo() {
        // Beispiel aus der Spezifikation: HwType 0x00000200, Firmware 1.20
        var h = (Z21Message.HwInfo) one("0C 00 1A 00 00 02 00 00 20 01 00 00");
        assertEquals(0x200L, h.hardwareType());
        assertEquals("1.20", Z21Protocol.formatBcdVersion(h.bcdFirmware()));
    }

    public void testDecodeSerialCodeFlags() {
        assertEquals(new Z21Message.SerialNumber(123456L), one("08 00 10 00 40 E2 01 00"));
        assertEquals(new Z21Message.Code(0), one("05 00 18 00 00"));
        assertEquals(new Z21Message.BroadcastFlags(0x00080101L), one("08 00 51 00 01 01 08 00"));
    }

    public void testDecodeVersions() {
        assertEquals(new Z21Message.FirmwareVersion(0x30, 0x12), one("09 00 40 00 63 21 30 12 60"));
        assertEquals(new Z21Message.XFirmware(0x0142), one("09 00 40 00 F3 0A 01 42 BA"));
        assertEquals("1.42", Z21Protocol.formatBcdVersion(0x0142));
        assertEquals(new Z21Message.UnknownCommand(), one("07 00 40 00 61 82 E3"));
    }

    public void testBadChecksumIsUnknown() {
        assertEquals(Z21Message.Unknown.class, one("09 00 40 00 63 21 30 12 61").getClass());
    }

    public void testDecodeSystemState() {
        var s = (Z21Message.SystemState) one("14 00 84 00 64 00 00 00 60 00 1E 00 20 4E 50 46 02 00 00 7B");
        assertEquals(100, s.mainCurrentMa());
        assertEquals(96, s.filteredMainCurrentMa());
        assertEquals(30, s.temperatureC());
        assertEquals(20000, s.supplyVoltageMv());
        assertEquals(18000, s.vccVoltageMv());
        assertEquals(0x02, s.centralState());
        assertEquals(0x7B, s.capabilities());
        var neg = (Z21Message.SystemState) one("14 00 84 00 FF FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00");
        assertEquals(-1, neg.mainCurrentMa());
    }

    public void testDecodeLocoInfoWithAndWithoutF29() {
        // 9001, 128 Fahrstufen, vorwärts, Fahrstufe 1 (Draht 2), F0 an, mit DB8
        byte[] withDb8 = Z21Protocol.x(0xEF, 0xE3, 0x29, 0x04, 0x82, 0x10, 0, 0, 0, 0x01);
        var l = (Z21Message.Loco) Z21Protocol.decode(withDb8, withDb8.length).get(0);
        assertEquals(9001, l.state().address());
        assertEquals(1, l.state().speedStep());
        assertEquals(Direction.FORWARD, l.state().direction());
        assertEquals(true, l.state().function(0));
        assertEquals(true, l.state().function(29));
        assertEquals(true, l.f29to31Present());
        byte[] noDb8 = Z21Protocol.x(0xEF, 0xE3, 0x29, 0x04, 0x00, 0x00, 0, 0, 0);
        var l2 = (Z21Message.Loco) Z21Protocol.decode(noDb8, noDb8.length).get(0);
        assertEquals(false, l2.f29to31Present());
        assertEquals(0, l2.state().speedStep());
    }

    public void testDecodeCanDetector() {
        var o = (Z21Message.CanOccupancy) one("0E 00 C4 00 34 12 05 00 02 01 00 11 00 00");
        assertEquals(0x1234, o.networkId());
        assertEquals(5, o.module());
        assertEquals(2, o.port());
        assertEquals(true, o.occupied());
        assertEquals(true, o.trackVoltage());
        assertEquals(0x1100, o.rawValue());
        var r = (Z21Message.CanRailCom) one("0E 00 C4 00 34 12 05 00 02 11 03 80 00 00");
        assertEquals(3, r.address1());
        assertEquals(2, r.dirBits1());
        assertEquals(0, r.address2());
    }

    public void testDecodeRailComData() {
        var r = (Z21Message.RailComData) one("11 00 88 00 03 00 10 00 00 00 02 00 00 05 20 7F 00");
        assertEquals(3, r.locoAddress());
        assertEquals(16L, r.receiveCounter());
        assertEquals(2, r.errorCounter());
        assertEquals(5, r.options());
        assertEquals(0x20, r.speed());
        assertEquals(0x7F, r.qos());
    }

    public void testMultipleRecordsInOnePacket() {
        byte[] b = bytes("05 00 18 00 00 08 00 51 00 01 00 00 00");
        assertEquals(2, Z21Protocol.decode(b, b.length).size());
    }
}
