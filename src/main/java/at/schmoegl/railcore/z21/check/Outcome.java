package at.schmoegl.railcore.z21.check;

/** Ergebnis einer Einzelprüfung. Ohne Rückmeldung nie {@link #CONFIRMED}. */
public enum Outcome {
    CONFIRMED("bestätigt"),
    NEGATIVE("abweichend / nicht vorhanden"),
    UNKNOWN("unbekannt – keine Rückmeldung"),
    NOT_TESTED("nicht geprüft"),
    ABORTED("abgebrochen");

    public final String label;

    Outcome(String label) { this.label = label; }
}
