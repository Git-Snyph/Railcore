package at.schmoegl.railcore.layout.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Gemeinsamer Bericht beim Laden eines Gleisplans (Schritt B3): Ladefehler (Datei, Format, Verweise, Graph) und,
 * wenn der Gleisplan gebaut werden konnte, die Befunde der Gleisplan-Prüfung ({@link TrackLayout#findings()}).
 *
 * <p>Im Zweifel halt: Automatikbetrieb nur, wenn es keinen Ladefehler gibt, der Gleisplan gebaut ist und die
 * Prüfung keinen Fehler meldet ({@link #permitsAutomatic()}).
 *
 * @param plan    gelesenes Dateimodell; leer, wenn die Datei nicht gelesen werden konnte
 * @param layout  gebauter und geprüfter Gleisplan; leer bei Ladefehlern
 */
public record TrackPlanReport(Optional<TrackPlan> plan, List<Problem> loadErrors, Optional<TrackLayout> layout) {

    /** Stufe, in der ein Ladefehler erkannt wurde. */
    public enum Phase {
        /** Datei nicht lesbar */
        FILE,
        /** kein gültiges JSON */
        SYNTAX,
        /** Pflichtfeld fehlt, unbekanntes Feld, falscher Typ oder ungültiger Wert */
        FORMAT,
        /** Verweis auf unbekannten Typ oder unbekanntes Stück bzw. unbekannten Anschluss */
        REFERENCE,
        /** Gleisgraph nicht baubar (offener oder doppelt verbundener Anschluss, Grenzzeichen hinter Gleisende …) */
        GRAPH
    }

    /**
     * Ladefehler.
     *
     * @param where Stelle in der Datei, z. B. {@code pieces[3] (w1).branches}; leer, wenn nicht zuzuordnen
     */
    public record Problem(Phase phase, String where, String message) {
        public Problem {
            if (phase == null || message == null) throw new IllegalArgumentException("Ladefehler unvollständig");
            where = where == null ? "" : where;
        }

        static Problem format(String where, String message) { return new Problem(Phase.FORMAT, where, message); }

        static Problem reference(String where, String message) { return new Problem(Phase.REFERENCE, where, message); }

        @Override
        public String toString() { return "Ladefehler " + phase + (where.isEmpty() ? "" : " [" + where + "]") + ": " + message; }
    }

    public TrackPlanReport {
        loadErrors = List.copyOf(loadErrors);
        if (plan == null) plan = Optional.empty();
        if (layout == null) layout = Optional.empty();
        if (!loadErrors.isEmpty() && layout.isPresent())
            throw new IllegalArgumentException("Bei Ladefehlern gibt es keinen Gleisplan");
        if (loadErrors.isEmpty() && layout.isEmpty())
            throw new IllegalArgumentException("Ohne Ladefehler muss ein Gleisplan vorliegen");
    }

    /** Befunde der Gleisplan-Prüfung; leer, wenn wegen Ladefehlern nicht geprüft wurde. */
    public List<LayoutFinding> findings() { return layout.map(TrackLayout::findings).orElse(List.of()); }

    public List<LayoutFinding> errors() { return layout.map(TrackLayout::errors).orElse(List.of()); }

    public List<LayoutFinding> hints() { return layout.map(TrackLayout::hints).orElse(List.of()); }

    /** Datei geladen und Gleisplan gebaut (Prüf-Fehler sind möglich, siehe {@link #permitsAutomatic()}). */
    public boolean loaded() { return layout.isPresent(); }

    /** Keine Ladefehler und keine Prüf-Fehler; Hinweise verhindern den Automatikbetrieb nicht. */
    public boolean permitsAutomatic() { return layout.map(TrackLayout::permitsAutomatic).orElse(false); }

    /** Lesbarer Bericht, eine Zeile je Ladefehler bzw. Befund. */
    public String text() {
        List<String> lines = new ArrayList<>();
        String name = plan.map(TrackPlan::name).filter(n -> !n.isEmpty()).map(n -> "„" + n + "“").orElse("(ohne Namen)");
        lines.add("Gleisplan " + name + ": " + loadErrors.size() + " Ladefehler, " + errors().size() + " Fehler, "
                + hints().size() + " Hinweise – Automatik " + (permitsAutomatic() ? "erlaubt" : "gesperrt"));
        for (Problem p : loadErrors) lines.add(p.toString());
        for (LayoutFinding f : findings()) lines.add(f.toString());
        return String.join("\n", lines);
    }

    @Override
    public String toString() { return text(); }
}
