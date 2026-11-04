package at.schmoegl.railcore.core;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Erkannte Fähigkeiten einer Zentrale (Lastenheft 8). Eine Fähigkeit gilt nur, wenn der Adapter sie an der Zentrale
 * <b>erkannt</b> hat (z. B. aus der Firmware-Version); was nicht erkannt ist, fehlt – nichts wird vorausgesetzt, nur
 * weil es in der Schnittstelle steht. Vor der Erkennung und nach einem Verbindungsverlust gilt {@link #UNKNOWN}.
 *
 * @param firmware  Firmware-Version als Text, z. B. "1.43"; {@code null} = nicht ermittelt
 * @param features  erkannte Fähigkeiten
 */
public record StationCapabilities(String firmware, Set<Feature> features) {

    /** Fähigkeiten, die die Steuerung nutzt, sobald sie erkannt sind. */
    public enum Feature {
        /** CAN-Belegtmelder (Z21 Detector 10808). */
        CAN_DETECTORS("CAN-Melder"),
        /** RailCom-Adressen je Meldeabschnitt. */
        RAILCOM("RailCom"),
        /** Funktionen F29–F31. */
        FUNCTIONS_F29_F31("Funktionen F29–F31"),
        /** Lokmeldungen auch bei Fahrbefehlen anderer Clients (an der Z21 1.43 nicht gegeben). */
        LOCO_BROADCAST("Lok-Broadcast bei Fremdsteuerung"),
        /** Zubehör meldet die tatsächliche Endlage (nicht nur den Speicher der Zentrale). */
        ACCESSORY_END_POSITION("Endlagenmeldung Zubehör"),
        /**
         * Abfrage aller Melderzustände, die auch unveränderte Zustände meldet ({@link CommandStation#requestDetectorStatus}):
         * Grundlage für die Erkennung stummer Melder und für das erneute Einlesen nach einem Versorgungsausfall.
         */
        DETECTOR_STATUS_QUERY("Melderabfrage");

        private final String german;

        Feature(String german) { this.german = german; }

        public String german() { return german; }
    }

    /** Nichts erkannt: keine Fähigkeit wird genutzt. */
    public static final StationCapabilities UNKNOWN = new StationCapabilities(null, Set.of());

    public StationCapabilities {
        features = features.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(Objects.requireNonNull(features)));
    }

    public boolean has(Feature f) { return features.contains(f); }

    /** Ist die Firmware ermittelt? */
    public boolean detected() { return firmware != null; }

    /** Text für Journal und Oberfläche. */
    public String describe() {
        if (!detected()) return "Firmware unbekannt, keine Fähigkeit erkannt";
        StringBuilder sb = new StringBuilder("Firmware ").append(firmware).append(": ");
        if (features.isEmpty()) return sb.append("keine Zusatzfähigkeit erkannt").toString();
        boolean first = true;
        for (Feature f : Feature.values()) {
            if (!features.contains(f)) continue;
            if (!first) sb.append(", ");
            sb.append(f.german());
            first = false;
        }
        return sb.toString();
    }
}
