package at.schmoegl.railcore.control;

/**
 * Vorrangklassen der Befehlsausgabe (Lastenheft 7, „Befehlsverwaltung“; Entscheidung 27). Kleinere Ordnungszahl =
 * höherer Vorrang. Ein niedriger eingestufter Befehl überschreibt nie einen höher eingestuften, noch nicht gesendeten
 * Befehl für dasselbe Ziel; ein höher eingestufter ersetzt einen niedrigeren.
 * <p>
 * {@link #EMERGENCY} ist nur eine Kennzeichnung: der Nothalt läuft nie über den CommandScheduler, sondern direkt an
 * die Zentrale (Grundsatz 7, {@link ControlExecutor#emergencyStop}).
 */
public enum CommandPriority {
    /** 1 – Nothalt (nur Kennzeichnung, nie in der Warteschlange). */
    EMERGENCY("Nothalt"),
    /** 2 – Sicherheit: Sicherheitsbremsung, Halt, Bremsstufen der Halteplanung. */
    SAFETY("Sicherheit"),
    /** 3 – Automatik. */
    AUTOMATIC("Automatik"),
    /** 4 – Fahrplan. */
    TIMETABLE("Fahrplan"),
    /** 5 – Handbetrieb. */
    MANUAL("Hand"),
    /** 6 – Komfort: Licht, Sound, Funktionen. */
    COMFORT("Komfort");

    private final String german;

    CommandPriority(String german) { this.german = german; }

    /** Bezeichnung für Oberfläche und Journal. */
    public String german() { return german; }

    /** Hat diese Klasse Vorrang vor {@code other}? */
    public boolean outranks(CommandPriority other) { return ordinal() < other.ordinal(); }

    /** Die Klassen, die über die Warteschlange laufen (alle außer Nothalt). */
    public static final java.util.List<CommandPriority> QUEUED = java.util.List.of(SAFETY, AUTOMATIC, TIMETABLE, MANUAL, COMFORT);
}
