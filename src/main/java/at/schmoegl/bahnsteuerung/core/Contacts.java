package at.schmoegl.bahnsteuerung.core;

/**
 * Umrechnung zwischen Modul/Eingang (wie auf Rückmeldemodulen beschriftet) und
 * fortlaufender Kontaktnummer. Modul 1 Eingang 1 = Kontakt 1, Modul 2 Eingang 1 = Kontakt 9.
 */
public final class Contacts {
    private Contacts() {}

    public static int of(int module, int input) {
        if (module < 1 || input < 1 || input > 8) throw new IllegalArgumentException("Modul/Eingang ungültig: " + module + ":" + input);
        return (module - 1) * 8 + input;
    }

    public static int module(int contact) { return (contact - 1) / 8 + 1; }

    public static int input(int contact) { return (contact - 1) % 8 + 1; }

    public static String format(int contact) { return module(contact) + ":" + input(contact); }

    /** Liest "12" (Kontaktnummer) oder "2:4" (Modul:Eingang). */
    public static int parse(String s) {
        s = s.trim();
        int i = s.indexOf(':');
        return i < 0 ? Integer.parseInt(s) : of(Integer.parseInt(s.substring(0, i)), Integer.parseInt(s.substring(i + 1)));
    }
}
