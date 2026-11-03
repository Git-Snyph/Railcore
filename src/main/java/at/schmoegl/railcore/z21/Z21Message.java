package at.schmoegl.railcore.z21;

import at.schmoegl.railcore.core.LocoState;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.core.TurnoutPosition;

/** Dekodierte Nachrichten von der Z21. */
public sealed interface Z21Message {

    record SerialNumber(long serial) implements Z21Message {}

    /** Antwort auf LAN_X_GET_VERSION: X-Bus-Version (0x30 = 3.0) und Zentralen-Kennung (0x12 = Z21). */
    record FirmwareVersion(int xBusVersion, int commandStationId) implements Z21Message {}

    /** Antwort auf LAN_X_GET_FIRMWARE_VERSION, BCD-kodiert (0x0142 = 1.42). */
    record XFirmware(int bcdVersion) implements Z21Message {}

    /** Antwort auf LAN_GET_HWINFO: Hardware-Typ und BCD-kodierte Firmware-Version. */
    record HwInfo(long hardwareType, long bcdFirmware) implements Z21Message {}

    /** Antwort auf LAN_GET_CODE: 0 = keine Sperre, 1 = z21 start gesperrt, 2 = z21 start freigeschaltet. */
    record Code(int code) implements Z21Message {}

    /** Antwort auf LAN_GET_BROADCASTFLAGS. */
    record BroadcastFlags(long flags) implements Z21Message {}

    /**
     * LAN_SYSTEMSTATE_DATACHANGED. Ströme in mA, Temperatur in °C, Spannungen in mV.
     * capabilities erst ab Firmware 1.42 belegt (0 = nicht gemeldet).
     */
    record SystemState(int mainCurrentMa, int progCurrentMa, int filteredMainCurrentMa, int temperatureC,
                       int supplyVoltageMv, int vccVoltageMv, int centralState, int centralStateEx, int capabilities)
            implements Z21Message {}

    /** LAN_RAILCOM_DATACHANGED: RailCom-Daten einer Lok (Zähler, Optionen, Geschwindigkeit, Empfangsgüte). */
    record RailComData(int locoAddress, long receiveCounter, int errorCounter, int options, int speed, int qos)
            implements Z21Message {}

    /** LAN_X_UNKNOWN_COMMAND: die Zentrale kennt den Befehl nicht. */
    record UnknownCommand() implements Z21Message {}

    record PowerState(TrackPower state) implements Z21Message {}

    /** f29to31Present = die Meldung enthält das Byte für F29–F31 (neuere Firmware). */
    record Loco(LocoState state, boolean busy, boolean f29to31Present) implements Z21Message {}

    record Turnout(int address, TurnoutPosition position) implements Z21Message {}

    /** R-Bus-Rückmelder: Gruppe 0 = Module 1..10, Gruppe 1 = Module 11..20; ein Byte je Modul, Bit 0 = Eingang 1. */
    record RBusFeedback(int group, byte[] modules) implements Z21Message {}

    record CvResult(int cv, int value) implements Z21Message {}

    record CvFailed(String reason) implements Z21Message {}

    /** CAN-Belegtmelder (Z21 Detector 10808). port 0..7. overload 0 = keine Überlast. rawValue = Value1 unverändert. */
    record CanOccupancy(int networkId, int module, int port, boolean occupied, boolean trackVoltage, int overload,
                        int rawValue) implements Z21Message {}

    /** RailCom-Lokadressen in einem CAN-Abschnitt. index = 0 für Adressen 1+2, 1 für 3+4 ... Adresse 0 = keine. */
    record CanRailCom(int networkId, int module, int port, int index, int address1, int dirBits1, int address2, int dirBits2) implements Z21Message {}

    record Unknown(int header, byte[] data) implements Z21Message {}
}
