package at.schmoegl.railcore.z21;

import at.schmoegl.railcore.core.LocoState;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.core.TurnoutPosition;

/** Dekodierte Nachrichten von der Z21. */
public sealed interface Z21Message {

    record SerialNumber(long serial) implements Z21Message {}

    record FirmwareVersion(int xBusVersion, int commandStationId) implements Z21Message {}

    record PowerState(TrackPower state) implements Z21Message {}

    record Loco(LocoState state, boolean busy) implements Z21Message {}

    record Turnout(int address, TurnoutPosition position) implements Z21Message {}

    /** R-Bus-Rückmelder: Gruppe 0 = Module 1..10, Gruppe 1 = Module 11..20; ein Byte je Modul, Bit 0 = Eingang 1. */
    record RBusFeedback(int group, byte[] modules) implements Z21Message {}

    record CvResult(int cv, int value) implements Z21Message {}

    record CvFailed(String reason) implements Z21Message {}

    /** CAN-Belegtmelder (Z21 Detector 10808). port 0..7. overload 0 = keine Überlast. */
    record CanOccupancy(int networkId, int module, int port, boolean occupied, boolean trackVoltage, int overload) implements Z21Message {}

    /** RailCom-Lokadressen in einem CAN-Abschnitt. index = 0 für Adressen 1+2, 1 für 3+4 ... Adresse 0 = keine. */
    record CanRailCom(int networkId, int module, int port, int index, int address1, int dirBits1, int address2, int dirBits2) implements Z21Message {}

    record Unknown(int header, byte[] data) implements Z21Message {}
}
