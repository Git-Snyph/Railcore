package at.schmoegl.railcore.z21;

import at.schmoegl.railcore.core.LocoState;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.core.TurnoutPosition;

/** Dekodierte Nachrichten von der Z21. */
public sealed interface Z21Message {

    /**
     * Meldungen, die nur der Fähigkeitserkennung und dem Prüfprogramm dienen und den Betriebszustand nicht ändern.
     * Der Betriebstreiber behandelt sie gesammelt mit einem einzigen Fall.
     */
    sealed interface Diagnostic extends Z21Message {}

    record SerialNumber(long serial) implements Z21Message {}

    /** Antwort auf LAN_X_GET_VERSION: X-Bus-Version (0x30 = 3.0) und Zentralen-Kennung (0x12 = Z21). */
    record FirmwareVersion(int xBusVersion, int commandStationId) implements Z21Message {}

    /** Antwort auf LAN_X_GET_FIRMWARE_VERSION, BCD-kodiert (0x0142 = 1.42). */
    record XFirmware(int bcdVersion) implements Diagnostic {}

    /** Antwort auf LAN_GET_HWINFO: Hardware-Typ und BCD-kodierte Firmware-Version. */
    record HwInfo(long hardwareType, long bcdFirmware) implements Diagnostic {}

    /** Antwort auf LAN_GET_CODE: 0 = keine Sperre, 1 = z21 start gesperrt, 2 = z21 start freigeschaltet. */
    record Code(int code) implements Diagnostic {}

    /** Antwort auf LAN_GET_BROADCASTFLAGS. */
    record BroadcastFlags(long flags) implements Diagnostic {}

    /**
     * LAN_SYSTEMSTATE_DATACHANGED. Ströme in mA, Temperatur in °C, Spannungen in mV.
     * capabilities erst ab Firmware 1.42 belegt (0 = nicht gemeldet).
     */
    record SystemState(int mainCurrentMa, int progCurrentMa, int filteredMainCurrentMa, int temperatureC,
                       int supplyVoltageMv, int vccVoltageMv, int centralState, int centralStateEx, int capabilities)
            implements Diagnostic {}

    /** LAN_RAILCOM_DATACHANGED: RailCom-Daten einer Lok (Zähler, Optionen, Geschwindigkeit, Empfangsgüte). */
    record RailComData(int locoAddress, long receiveCounter, int errorCounter, int options, int speed, int qos)
            implements Diagnostic {}

    /**
     * LAN_CAN_BOOSTER_SYSTEMSTATE_CHGD (Header 0xCA, ab Firmware 1.41): Zustand eines Ausgangs eines CAN-Boosters,
     * laut Spezifikation etwa einmal je Sekunde je Booster und Ausgang. networkId 0xC101..0xC1FF, outputPort 1 oder 2
     * (2 nur 10807), state = Booster_State-Bits ({@code Z21Protocol.BS_*}), Spannung in mV, Strom in mA.
     * Nur Diagnose: Deutung und Zuordnung zu Booster-Bezirken sind an Hardware zu prüfen, der Betriebstreiber
     * wertet die Meldung nicht aus. Temperatur liefert diese CAN-Meldung nicht (nur die zLink-Meldung 0xBA direkt
     * vom Booster).
     */
    record CanBoosterState(int networkId, int outputPort, int state, int vccVoltageMv, int currentMa)
            implements Diagnostic {}

    /** LAN_X_UNKNOWN_COMMAND: die Zentrale kennt den Befehl nicht. */
    record UnknownCommand() implements Diagnostic {}

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
