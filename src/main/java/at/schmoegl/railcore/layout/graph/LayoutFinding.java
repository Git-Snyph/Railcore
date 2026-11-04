package at.schmoegl.railcore.layout.graph;

/**
 * Befund der Gleisplan-Prüfung (Lastenheft Kapitel 4 und 7, „Gleisplan prüfen“). Fehler verhindern den
 * Automatikbetrieb, Hinweise werden nur angezeigt.
 *
 * @param subject Kennung des betroffenen Objekts (Block, Abschnitt, Kante, Knoten …)
 */
public record LayoutFinding(Severity severity, Code code, String subject, String message) {

    public enum Severity {
        /** verhindert den Automatikbetrieb */
        ERROR,
        /** wird nur angezeigt */
        HINT
    }

    /** Art des Befunds; der Schweregrad hängt allein an der Art. */
    public enum Code {
        // --- Fehler ---
        /** Block ohne Meldeabschnitt */
        BLOCK_WITHOUT_SENSOR(Severity.ERROR),
        /** Meldeabschnitte (oder Bereiche eines Abschnitts) überlappen sich */
        OVERLAPPING_SECTIONS(Severity.ERROR),
        /** Kontakt-Kennung mehrfach vergeben */
        DUPLICATE_CONTACT(Severity.ERROR),
        /** Zubehöradresse mehrfach vergeben (Weichen, Signale) */
        DUPLICATE_ADDRESS(Severity.ERROR),
        /** Kennung eines Objekts mehrfach vergeben */
        DUPLICATE_ID(Severity.ERROR),
        /** Verweis auf ein unbekanntes Objekt (Kante, Abschnitt, Block, Bezirk, Weiche, Gleisstück, Meldepunkt) */
        UNKNOWN_REFERENCE(Severity.ERROR),
        /** Lage außerhalb der Kante, Bereich ohne Länge oder Meldepunkt außerhalb seines Abschnitts */
        INVALID_LOCATION(Severity.ERROR),
        /** Kehrschleife, die nicht durch einen Kehrschleifenbezirk aufgelöst ist */
        REVERSING_LOOP_WITHOUT_DISTRICT(Severity.ERROR),
        /** Meldeabschnitt gehört zu mehr als einem Block */
        SECTION_IN_SEVERAL_BLOCKS(Severity.ERROR),
        /** Block nicht zusammenhängend, Ende A liegt nicht am Blockrand oder Richtung nicht eindeutig */
        BLOCK_GEOMETRY(Severity.ERROR),
        /** Blöcke eines Haltebereichs stoßen nicht aneinander */
        HOLDING_AREA_NOT_CONTIGUOUS(Severity.ERROR),
        /** Gleis in zwei Bezirken */
        OVERLAPPING_DISTRICTS(Severity.ERROR),
        // --- Hinweise ---
        /** weniger Meldeabschnitte im Block als empfohlen (Standard 3) */
        FEW_SENSORS_IN_BLOCK(Severity.HINT),
        /** Länge eines Gleisstücks als unbekannt bzw. geschätzt markiert */
        UNKNOWN_LENGTH(Severity.HINT),
        /** Gleis ohne Meldeabschnitt (ohne Weichen, die meldet {@link #TURNOUT_WITHOUT_SECTION}) */
        TRACK_WITHOUT_SENSOR(Severity.HINT),
        /** Gleis ohne Booster-Bezirk */
        TRACK_WITHOUT_DISTRICT(Severity.HINT),
        /** Weiche bzw. Doppelkreuzungsweiche nicht vollständig von einem Meldeabschnitt abgedeckt */
        TURNOUT_WITHOUT_SECTION(Severity.HINT),
        /** Gleisteil mit Block und Gleisende, aber ohne Weiche: Züge können nur hin und her pendeln */
        DEAD_END_WITHOUT_EXIT(Severity.HINT),
        /** Knoten in einem Gleisteil ohne Block – kein Zug kann ihn erreichen */
        UNREACHABLE_NODE(Severity.HINT),
        /** Block, den ein Zug in keiner erlaubten Richtung zu einem anderen Block verlassen kann */
        BLOCK_WITHOUT_EXIT(Severity.HINT),
        /** Doppelkreuzungsweiche mit an der Hardware ungeprüfter Stelllogik (Entscheidung 41) */
        DOUBLE_SLIP_LOGIC_UNVERIFIED(Severity.HINT);

        private final Severity severity;

        Code(Severity severity) { this.severity = severity; }

        public Severity severity() { return severity; }
    }

    public LayoutFinding {
        if (severity == null || code == null || subject == null || message == null)
            throw new IllegalArgumentException("Befund unvollständig");
        if (severity != code.severity())
            throw new IllegalArgumentException("Schweregrad " + severity + " passt nicht zu " + code);
    }

    static LayoutFinding of(Code code, String subject, String message) {
        return new LayoutFinding(code.severity(), code, subject, message);
    }

    public boolean isError() { return severity == Severity.ERROR; }

    @Override
    public String toString() { return (isError() ? "Fehler" : "Hinweis") + " " + code + " [" + subject + "]: " + message; }
}
