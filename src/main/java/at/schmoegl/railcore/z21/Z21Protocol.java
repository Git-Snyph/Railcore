package at.schmoegl.railcore.z21;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.LocoState;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.core.TurnoutPosition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Kodierung und Dekodierung des Z21-LAN-Protokolls (öffentliche Spezifikation von Roco/Fleischmann).
 * Jedes Paket: DataLen (2 Byte LE) | Header (2 Byte LE) | Daten. Ein UDP-Paket kann mehrere Datensätze enthalten.
 * Reine Funktionen ohne Netzwerk, damit sie einzeln getestet werden können.
 */
public final class Z21Protocol {
    public static final int PORT = 21105;

    static final int H_SERIAL = 0x10, H_GET_CODE = 0x18, H_HWINFO = 0x1A, H_LOGOFF = 0x30, H_X = 0x40,
            H_BROADCASTFLAGS = 0x50, H_GET_BROADCASTFLAGS = 0x51,
            H_RMBUS_DATACHANGED = 0x80, H_RMBUS_GETDATA = 0x81,
            H_SYSTEMSTATE_DATACHANGED = 0x84, H_SYSTEMSTATE_GETDATA = 0x85,
            H_RAILCOM_DATACHANGED = 0x88, H_CAN_DETECTOR = 0xC4;

    /** Broadcast-Flags: Fahr- und Schaltinfos + R-Bus-Rückmelder. */
    public static final int BC_DRIVING_SWITCHING = 0x00000001, BC_RBUS = 0x00000002, BC_CAN_DETECTOR = 0x00080000;
    /**
     * Weitere Broadcast-Flags laut Z21-LAN-Spezifikation: RailCom der abonnierten Loks, Systemzustand,
     * Lokinfos aller Loks (ab Firmware 1.20, hohe Netzlast), RailCom aller Loks (ab Firmware 1.29).
     * Ob die Zentrale ein Flag tatsächlich übernimmt, wird über LAN_GET_BROADCASTFLAGS erkannt, nie vorausgesetzt.
     */
    public static final int BC_RAILCOM = 0x00000004, BC_SYSTEM_STATE = 0x00000100,
            BC_ALL_LOCOS = 0x00010000, BC_RAILCOM_ALL = 0x00040000;

    /** NId für "alle CAN-Belegtmelder". */
    public static final int CAN_ALL = 0xD000;

    private Z21Protocol() {}

    // ---------------------------------------------------------------- Befehle

    public static byte[] getSerialNumber() { return packet(H_SERIAL); }

    /** LAN_GET_CODE: Freischaltcode (z21 start gesperrt/freigeschaltet). */
    public static byte[] getCode() { return packet(H_GET_CODE); }

    /** LAN_GET_HWINFO: Hardware-Typ und Firmware-Version. */
    public static byte[] getHwInfo() { return packet(H_HWINFO); }

    /** LAN_GET_BROADCASTFLAGS: tatsächlich gesetzte Broadcast-Flags dieses Clients. */
    public static byte[] getBroadcastFlags() { return packet(H_GET_BROADCASTFLAGS); }

    /** LAN_SYSTEMSTATE_GETDATA: Ströme, Spannungen, Temperatur, Zentralenzustand, Fähigkeiten. */
    public static byte[] systemStateGetData() { return packet(H_SYSTEMSTATE_GETDATA); }

    /** LAN_X_GET_FIRMWARE_VERSION. */
    public static byte[] getFirmwareVersion() { return x(0xF1, 0x0A); }

    public static byte[] logoff() { return packet(H_LOGOFF); }

    public static byte[] setBroadcastFlags(int flags) {
        return packet(H_BROADCASTFLAGS, (byte) flags, (byte) (flags >> 8), (byte) (flags >> 16), (byte) (flags >> 24));
    }

    public static byte[] getVersion() { return x(0x21, 0x21); }

    public static byte[] getStatus() { return x(0x21, 0x24); }

    public static byte[] trackPowerOn() { return x(0x21, 0x81); }

    public static byte[] trackPowerOff() { return x(0x21, 0x80); }

    /** LAN_X_SET_STOP: Nothalt für alle Loks, Gleisspannung bleibt an. */
    public static byte[] stopAll() { return x(0x80); }

    public static byte[] getLocoInfo(int address) {
        return x(0xE3, 0xF0, locoMsb(address), address & 0xFF);
    }

    /**
     * LAN_X_SET_LOCO_DRIVE. speedStep 0 = Halt, 1..maxStep. Bei 128 Fahrstufen ist auf dem Draht 1 = Nothalt,
     * deshalb wird Fahrstufe n als n+1 übertragen. Bei 28 Fahrstufen liegt das Zwischenbit an Position 4.
     */
    public static byte[] setLocoDrive(int address, int speedStep, SpeedSteps steps, Direction dir) {
        if (speedStep < 0 || speedStep > steps.maxStep) throw new IllegalArgumentException("Fahrstufe außerhalb: " + speedStep);
        return x(0xE4, stepsCode(steps), locoMsb(address), address & 0xFF, speedByte(speedStep, steps, dir, false));
    }

    public static byte[] emergencyStopLoco(int address, SpeedSteps steps, Direction dir) {
        return x(0xE4, stepsCode(steps), locoMsb(address), address & 0xFF, speedByte(0, steps, dir, true));
    }

    /** LAN_X_SET_LOCO_FUNCTION: Funktion 0..31 ein/aus. */
    public static byte[] setLocoFunction(int address, int function, boolean on) {
        if (function < 0 || function > 31) throw new IllegalArgumentException("Funktion 0..31: " + function);
        return x(0xE4, 0xF8, locoMsb(address), address & 0xFF, (on ? 0x40 : 0x00) | function);
    }

    /**
     * LAN_X_SET_TURNOUT. address ab 1 (wie auf dem Handregler); auf dem Draht 0-basiert.
     * activate=true schaltet den Ausgang ein, danach muss er mit activate=false wieder abgeschaltet werden.
     */
    public static byte[] setTurnout(int address, TurnoutPosition pos, boolean activate) {
        if (pos == TurnoutPosition.UNKNOWN) throw new IllegalArgumentException("Weichenlage UNKNOWN nicht schaltbar");
        int a = address - 1;
        int db2 = 0x80 | (activate ? 0x08 : 0) | (pos == TurnoutPosition.DIVERGING ? 1 : 0);
        return x(0x53, (a >> 8) & 0xFF, a & 0xFF, db2);
    }

    public static byte[] getTurnoutInfo(int address) {
        int a = address - 1;
        return x(0x43, (a >> 8) & 0xFF, a & 0xFF);
    }

    // ---------------------------------------------------------------- CV-Programmierung (cv ab 1, auf dem Draht 0-basiert)

    public static byte[] cvRead(int cv) {
        int a = cvAddr(cv);
        return x(0x23, 0x11, a >> 8, a & 0xFF);
    }

    public static byte[] cvWrite(int cv, int value) {
        int a = cvAddr(cv);
        return x(0x24, 0x12, a >> 8, a & 0xFF, checkByte(value));
    }

    public static byte[] cvPomWrite(int address, int cv, int value) {
        int a = cvAddr(cv);
        return x(0xE6, 0x30, (address >> 8) & 0x3F, address & 0xFF, 0xEC | (a >> 8 & 0x03), a & 0xFF, checkByte(value));
    }

    public static byte[] cvPomRead(int address, int cv) {
        int a = cvAddr(cv);
        return x(0xE6, 0x30, (address >> 8) & 0x3F, address & 0xFF, 0xE4 | (a >> 8 & 0x03), a & 0xFF, 0);
    }

    /**
     * LAN_X_CV_POM_ACCESSORY_WRITE_BYTE / _READ_BYTE: CV eines Zubehördecoders auf dem Hauptgleis.
     * decoderAddress = Decoderadresse (nicht Weichenadresse; 4 Weichen je Decoder: decoder = (weiche-1)/4).
     * output = null programmiert den ganzen Decoder, sonst Ausgang 0..7.
     * Hinweis: Bitbelegung nach Z21-LAN-Spezifikation, an echter Hardware noch zu prüfen.
     */
    public static byte[] cvPomAccessory(int decoderAddress, Integer output, int cv, boolean write, int value) {
        if (decoderAddress < 0 || decoderAddress > 0x7FF) throw new IllegalArgumentException("Decoderadresse 0..2047");
        int a = cvAddr(cv);
        int db1 = (decoderAddress >> 4) & 0xFF;
        int db2 = ((decoderAddress & 0x0F) << 4) | (output == null ? 0 : 0x08 | (output & 0x07));
        return x(0xE6, 0x31, db1, db2, (write ? 0xEC : 0xE4) | (a >> 8 & 0x03), a & 0xFF, write ? checkByte(value) : 0);
    }

    private static int cvAddr(int cv) {
        if (cv < 1 || cv > 1024) throw new IllegalArgumentException("CV 1..1024: " + cv);
        return cv - 1;
    }

    private static int checkByte(int v) {
        if (v < 0 || v > 255) throw new IllegalArgumentException("CV-Wert 0..255: " + v);
        return v;
    }

    /** LAN_CAN_DETECTOR-Abfrage: aktuellen Zustand der CAN-Belegtmelder (z. B. Z21 Detector 10808) anfordern. */
    public static byte[] canDetectorRequest(int networkId) {
        return packet(H_CAN_DETECTOR, (byte) 0x00, (byte) networkId, (byte) (networkId >> 8));
    }

    public static byte[] rmBusGetData(int group) { return packet(H_RMBUS_GETDATA, (byte) group); }

    // ---------------------------------------------------------------- Dekodierung

    /** Zerlegt ein empfangenes UDP-Paket in einzelne Nachrichten. Defekte Datensätze werden übersprungen. */
    public static List<Z21Message> decode(byte[] buf, int len) {
        List<Z21Message> out = new ArrayList<>();
        int pos = 0;
        while (pos + 4 <= len) {
            int dataLen = u16(buf, pos);
            if (dataLen < 4 || pos + dataLen > len) break;
            int header = u16(buf, pos + 2);
            byte[] data = Arrays.copyOfRange(buf, pos + 4, pos + dataLen);
            Z21Message m = decodeOne(header, data);
            if (m != null) out.add(m);
            pos += dataLen;
        }
        return out;
    }

    static Z21Message decodeOne(int header, byte[] d) {
        switch (header) {
            case H_SERIAL:
                if (d.length >= 4) return new Z21Message.SerialNumber(u32(d, 0));
                break;
            case H_GET_CODE:
                if (d.length >= 1) return new Z21Message.Code(d[0] & 0xFF);
                break;
            case H_HWINFO:
                if (d.length >= 8) return new Z21Message.HwInfo(u32(d, 0), u32(d, 4));
                break;
            case H_GET_BROADCASTFLAGS:
                if (d.length >= 4) return new Z21Message.BroadcastFlags(u32(d, 0));
                break;
            case H_SYSTEMSTATE_DATACHANGED:
                if (d.length >= 16) return new Z21Message.SystemState(s16(d, 0), s16(d, 2), s16(d, 4), s16(d, 6),
                        u16(d, 8), u16(d, 10), d[12] & 0xFF, d[13] & 0xFF, d[15] & 0xFF);
                break;
            case H_RAILCOM_DATACHANGED:
                // LocoAddress(2) ReceiveCounter(4) ErrorCounter(2) Reserved(1) Options(1) Speed(1) QoS(1) Reserved(1)
                if (d.length >= 13) return new Z21Message.RailComData(u16(d, 0), u32(d, 2), u16(d, 6),
                        d[9] & 0xFF, d[10] & 0xFF, d[11] & 0xFF);
                break;
            case H_RMBUS_DATACHANGED:
                if (d.length >= 11) return new Z21Message.RBusFeedback(d[0] & 0xFF, Arrays.copyOfRange(d, 1, 11));
                break;
            case H_CAN_DETECTOR:
                if (d.length >= 10) return decodeCanDetector(d);
                break;
            case H_X:
                if (d.length < 2 || !xorOk(d)) return new Z21Message.Unknown(header, d);
                return decodeX(d);
            default:
                break;
        }
        return new Z21Message.Unknown(header, d);
    }

    private static Z21Message decodeX(byte[] d) {
        int xh = d[0] & 0xFF;
        int db0 = d.length > 1 ? d[1] & 0xFF : -1;
        if (xh == 0x61) {
            return switch (db0) {
                case 0x00 -> new Z21Message.PowerState(TrackPower.OFF);
                case 0x01 -> new Z21Message.PowerState(TrackPower.ON);
                case 0x02 -> new Z21Message.PowerState(TrackPower.PROGRAMMING);
                case 0x08 -> new Z21Message.PowerState(TrackPower.SHORT_CIRCUIT);
                case 0x12 -> new Z21Message.CvFailed("Kurzschluss auf dem Programmiergleis");
                case 0x13 -> new Z21Message.CvFailed("Keine Antwort vom Decoder");
                case 0x82 -> new Z21Message.UnknownCommand();
                default -> new Z21Message.Unknown(H_X, d);
            };
        }
        if (xh == 0x81 && db0 == 0x00) return new Z21Message.PowerState(TrackPower.EMERGENCY_STOP);
        if (xh == 0x62 && db0 == 0x22 && d.length >= 4) {
            int s = d[2] & 0xFF;
            TrackPower p = (s & 0x04) != 0 ? TrackPower.SHORT_CIRCUIT
                    : (s & 0x20) != 0 ? TrackPower.PROGRAMMING
                    : (s & 0x01) != 0 ? TrackPower.EMERGENCY_STOP
                    : (s & 0x02) != 0 ? TrackPower.OFF : TrackPower.ON;
            return new Z21Message.PowerState(p);
        }
        if (xh == 0x64 && db0 == 0x14 && d.length >= 6) {
            int cv = (((d[2] & 0xFF) << 8) | (d[3] & 0xFF)) + 1;
            return new Z21Message.CvResult(cv, d[4] & 0xFF);
        }
        if (xh == 0x63 && db0 == 0x21 && d.length >= 4) return new Z21Message.FirmwareVersion(d[2] & 0xFF, d[3] & 0xFF);
        if (xh == 0xF3 && db0 == 0x0A && d.length >= 5) return new Z21Message.XFirmware(((d[2] & 0xFF) << 8) | (d[3] & 0xFF));
        if (xh == 0x43 && d.length >= 5) {
            int addr = (((d[1] & 0xFF) << 8) | (d[2] & 0xFF)) + 1;
            TurnoutPosition p = switch (d[3] & 0x03) {
                case 1 -> TurnoutPosition.STRAIGHT;
                case 2 -> TurnoutPosition.DIVERGING;
                default -> TurnoutPosition.UNKNOWN;
            };
            return new Z21Message.Turnout(addr, p);
        }
        if (xh == 0xEF && d.length >= 8) return decodeLocoInfo(d);
        return new Z21Message.Unknown(H_X, d);
    }

    private static Z21Message decodeLocoInfo(byte[] d) {
        int address = ((d[1] & 0x3F) << 8) | (d[2] & 0xFF);
        int db2 = d[3] & 0xFF;
        boolean busy = (db2 & 0x08) != 0;
        SpeedSteps steps = switch (db2 & 0x07) {
            case 0 -> SpeedSteps.S14;
            case 2 -> SpeedSteps.S28;
            default -> SpeedSteps.S128;
        };
        int db3 = d[4] & 0xFF;
        Direction dir = (db3 & 0x80) != 0 ? Direction.FORWARD : Direction.REVERSE;
        int speed = decodeSpeed(db3 & 0x7F, steps);
        int db4 = d[5] & 0xFF;
        long f = 0;
        if ((db4 & 0x10) != 0) f |= 1;              // F0
        f |= (long) (db4 & 0x0F) << 1;              // F1..F4
        // d[6..] = DB5.. : F5-F12, F13-F20, F21-F28, F29-F31 (falls vorhanden, letztes Byte ist XOR)
        int shift = 5;
        for (int i = 6; i < d.length - 1 && shift <= 29; i++, shift += 8) f |= (long) (d[i] & 0xFF) << shift;
        f &= 0xFFFFFFFFL;
        // DB8 (F29–F31) gibt es nur bei neuerer Firmware: EF, 2 Adressbytes, DB2..DB8, XOR = 11 Byte.
        boolean f29to31 = d.length >= 11;
        return new Z21Message.Loco(new LocoState(address, speed, steps, dir, f), busy, f29to31);
    }

    /**
     * LAN_CAN_DETECTOR: NId(2) Addr(2) Port(1) Type(1) Value1(2) Value2(2).
     * Type 0x01 = Belegtzustand; Type 0x11..0x1F = RailCom-Lokadressen im Abschnitt (je zwei pro Meldung).
     * Hinweis: Richtungsbits der RailCom-Adressen (Bit 14/15) an echter Hardware zu prüfen.
     */
    private static Z21Message decodeCanDetector(byte[] d) {
        int nid = u16(d, 0), addr = u16(d, 2), port = d[4] & 0xFF, type = d[5] & 0xFF;
        int v1 = u16(d, 6), v2 = u16(d, 8);
        if (type == 0x01) {
            boolean occupied = (v1 & 0x1000) != 0;
            boolean voltage = (v1 & 0x0100) != 0;
            int overload = (v1 & 0x1200) == 0x1200 ? v1 & 0x0F : 0;
            return new Z21Message.CanOccupancy(nid, addr, port, occupied, voltage, overload, v1);
        }
        if (type >= 0x11 && type <= 0x1F) {
            return new Z21Message.CanRailCom(nid, addr, port, type - 0x11, v1 & 0x3FFF, v1 >> 14, v2 & 0x3FFF, v2 >> 14);
        }
        return new Z21Message.Unknown(H_CAN_DETECTOR, d);
    }

    // ---------------------------------------------------------------- Hilfsfunktionen

    static int speedByte(int step, SpeedSteps steps, Direction dir, boolean eStop) {
        int v;
        if (eStop) v = 1;
        else if (step == 0) v = 0;
        else v = switch (steps) {
            case S14, S128 -> step + 1;
            // 28 Fahrstufen: Wert w = step+3 (4..31), übertragen als 000 w0 w4 w3 w2 w1 -> Bit 4 = w&1, Bits 0..3 = w>>1
            case S28 -> { int w = step + 3; yield ((w & 1) << 4) | (w >> 1); }
        };
        return (dir == Direction.FORWARD ? 0x80 : 0) | v;
    }

    static int decodeSpeed(int v, SpeedSteps steps) {
        return switch (steps) {
            case S14, S128 -> v <= 1 ? 0 : v - 1;
            case S28 -> { int w = ((v & 0x0F) << 1) | ((v >> 4) & 1); yield w <= 3 ? 0 : w - 3; }
        };
    }

    private static int stepsCode(SpeedSteps s) {
        return switch (s) { case S14 -> 0x10; case S28 -> 0x12; case S128 -> 0x13; };
    }

    private static int locoMsb(int address) {
        if (address < 1 || address > 9999) throw new IllegalArgumentException("Lokadresse 1..9999: " + address);
        int msb = (address >> 8) & 0x3F;
        return address >= 128 ? msb | 0xC0 : msb;
    }

    /** X-Bus-Paket mit angehängter XOR-Prüfsumme. */
    static byte[] x(int... xbytes) {
        byte[] b = new byte[xbytes.length + 1];
        int xor = 0;
        for (int i = 0; i < xbytes.length; i++) { b[i] = (byte) xbytes[i]; xor ^= xbytes[i] & 0xFF; }
        b[xbytes.length] = (byte) xor;
        return packet(H_X, b);
    }

    static byte[] packet(int header, byte... data) {
        int len = 4 + data.length;
        byte[] p = new byte[len];
        p[0] = (byte) len; p[1] = (byte) (len >> 8);
        p[2] = (byte) header; p[3] = (byte) (header >> 8);
        System.arraycopy(data, 0, p, 4, data.length);
        return p;
    }

    private static boolean xorOk(byte[] d) {
        int x = 0;
        for (int i = 0; i < d.length - 1; i++) x ^= d[i] & 0xFF;
        return x == (d[d.length - 1] & 0xFF);
    }

    static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }

    static int s16(byte[] b, int o) { return (short) u16(b, o); }

    /** BCD-kodierte Firmware-Version, z. B. 0x0142 → "1.42". */
    public static String formatBcdVersion(long bcd) {
        return String.format("%x.%02x", (bcd >> 8) & 0xFF, bcd & 0xFF);
    }

    static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }
}
