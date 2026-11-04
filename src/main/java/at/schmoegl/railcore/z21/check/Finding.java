package at.schmoegl.railcore.z21.check;

/**
 * Einzelergebnis des Prüfprogramms.
 *
 * @param topic            Prüfpunkt, z. B. "Seriennummer"
 * @param outcome          Ergebnis
 * @param detail           Klartext (Werte, Rohdaten, Begründung)
 * @param verifyOnHardware true = Deutung ist nicht gesichert und an Hardware zu prüfen
 */
public record Finding(String topic, Outcome outcome, String detail, boolean verifyOnHardware) {

    @Override
    public String toString() {
        return "[" + outcome.label + "] " + topic + ": " + detail + (verifyOnHardware ? " (an Hardware zu prüfen)" : "");
    }
}
