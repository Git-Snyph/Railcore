package at.schmoegl.railcore.z21.check;

/**
 * Einstufung ausgehender Z21-Pakete für das Prüfprogramm.
 * <ul>
 *   <li>{@link #PASSIVE}: nur lesen bzw. abonnieren – in Stufe 1 als einzige erlaubt (Positivliste).</li>
 *   <li>{@link #SAFETY}: macht den Zustand sicherer (Nothalt, Fahrstufe 0, Weichenausgang abschalten) –
 *       jederzeit erlaubt.</li>
 *   <li>{@link #ACTIVE}: alles andere (Gleisspannung, Fahren, Funktionen, Weiche stellen, CV, POM) – nur in
 *       Stufe 2 und nur nach ausdrücklicher Bestätigung des Bedieners.</li>
 * </ul>
 * Unbekannte Pakete gelten als aktiv (im Zweifel nicht senden).
 */
public enum CommandClass {
    PASSIVE, SAFETY, ACTIVE;

    public static CommandClass of(byte[] p) {
        if (p == null || p.length < 4) return ACTIVE;
        int header = (p[2] & 0xFF) | (p[3] & 0xFF) << 8;
        return switch (header) {
            // Seriennummer, Code, HW-Info, Abmelden, Broadcast-Flags setzen/lesen, R-Bus lesen, Systemzustand, CAN-Melder
            case 0x10, 0x18, 0x1A, 0x30, 0x50, 0x51, 0x81, 0x85, 0xC4 -> PASSIVE;
            case 0x40 -> ofX(p);
            default -> ACTIVE;
        };
    }

    private static CommandClass ofX(byte[] p) {
        if (p.length < 5) return ACTIVE;
        int xh = p[4] & 0xFF;
        int db0 = p.length > 5 ? p[5] & 0xFF : -1;
        if (xh == 0x21 && (db0 == 0x21 || db0 == 0x24)) return PASSIVE;      // Version, Status
        if (xh == 0xF1 && db0 == 0x0A) return PASSIVE;                        // Firmware-Version
        if (xh == 0xE3 && db0 == 0xF0) return PASSIVE;                        // Lokinfo abfragen
        if (xh == 0x43) return PASSIVE;                                       // Weicheninfo abfragen
        if (xh == 0x80) return SAFETY;                                        // Nothalt alle Loks
        if (xh == 0xE4 && (db0 == 0x10 || db0 == 0x12 || db0 == 0x13) && p.length >= 9) {
            int v = p[8] & 0x7F;
            // Halt (0) oder Nothalt (1); nur bei 28 Fahrstufen zusätzlich die Zwischenbit-Varianten 0x10/0x11
            // (bei 14/128 Fahrstufen wären 0x10/0x11 echte Fahrstufen)
            boolean halt = v == 0 || v == 1 || (db0 == 0x12 && (v == 0x10 || v == 0x11));
            return halt ? SAFETY : ACTIVE;
        }
        if (xh == 0x53 && p.length >= 8 && (p[7] & 0x08) == 0) return SAFETY; // Weichenausgang abschalten
        return ACTIVE;
    }
}
