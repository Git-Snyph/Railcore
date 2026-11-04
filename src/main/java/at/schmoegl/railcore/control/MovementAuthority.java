package at.schmoegl.railcore.control;

/**
 * Prüft, ob eine Lok eine gültige Reservierung zur Bewegung besitzt (Grundsatz 2). Wird beim Anlegen des
 * Steuerungs-Executors übergeben und von der Befehlsschranke vor jedem Fahrbefehl mit Fahrstufe &gt; 0 gefragt.
 * Solange es keine Reservierungsverwaltung gibt, gilt im Betrieb {@link #NONE}; Simulatortests übergeben eine
 * eigene Freigabe als Platzhalter für die spätere Reservierung.
 */
@FunctionalInterface
public interface MovementAuthority {

    boolean mayMove(int address);

    /** Keine Reservierungsverwaltung vorhanden: im Zweifel halt. */
    MovementAuthority NONE = address -> false;
}
