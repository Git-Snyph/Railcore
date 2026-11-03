package at.schmoegl.railcore.layout;

import java.util.List;
import java.util.Map;

/**
 * Dokumentation der Anlagen-Hardware: Geräte und Verkabelung. Wird im Gleisplan als eigene Ebene dargestellt
 * und für Plausibilitätsprüfungen genutzt (doppelte Adressen, Melder ohne Modul usw.).
 */
public record Hardware(List<Device> devices, List<Cable> cables) {

    public enum DeviceType { COMMAND_STATION, BOOSTER, FEEDBACK_MODULE, ACCESSORY_DECODER, LOCO_DECODER, POWER_SUPPLY, OTHER }

    /**
     * @param bus          z. B. "R-Bus", "B-Bus", "LocoNet", "CAN"
     * @param firstAddress erste Adresse (Rückmeldemodul: Modulnummer; Zubehördecoder: erste Weichenadresse)
     * @param addressCount Anzahl Adressen/Eingänge
     * @param properties   freie Angaben (Hersteller, Typ, Seriennummer, Firmware ...)
     */
    public record Device(String id, String name, DeviceType type, String bus, int firstAddress, int addressCount,
                         Map<String, String> properties) {
        public Device { properties = Map.copyOf(properties); }
    }

    /**
     * Leitung zwischen zwei Endpunkten. Endpunkt-Format: "geraet/anschluss" oder "block:ID", "weiche:ID".
     *
     * @param kind  z. B. "Gleis", "Bus", "Melder", "Versorgung", "Licht"
     */
    public record Cable(String id, String from, String to, String kind, String color, String note) {}

    public Hardware {
        devices = List.copyOf(devices);
        cables = List.copyOf(cables);
    }

    public static Hardware empty() { return new Hardware(List.of(), List.of()); }
}
