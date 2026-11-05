package at.schmoegl.railcore.layout.graph;

import java.util.ArrayList;
import java.util.List;

/** Gleisplan-Datei nicht lesbar; {@link #problems()} nennt alle gefundenen Ladefehler, nicht nur den ersten. */
public final class TrackPlanException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final List<TrackPlanReport.Problem> problems;

    public TrackPlanException(List<TrackPlanReport.Problem> problems) {
        super(message(problems));
        if (problems.isEmpty()) throw new IllegalArgumentException("Ausnahme ohne Ladefehler");
        this.problems = List.copyOf(problems);
    }

    public List<TrackPlanReport.Problem> problems() { return problems; }

    private static String message(List<TrackPlanReport.Problem> problems) {
        List<String> parts = new ArrayList<>();
        for (TrackPlanReport.Problem p : problems) parts.add(p.toString());
        return "Gleisplan-Datei ungültig: " + String.join("; ", parts);
    }
}
