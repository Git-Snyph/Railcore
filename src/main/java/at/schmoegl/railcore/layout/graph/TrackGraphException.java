package at.schmoegl.railcore.layout.graph;

import java.util.List;

/** Gleisgraph ist ungültig; {@link #problems()} nennt alle gefundenen Fehler (Grundlage für den Prüfbericht ab B3). */
public final class TrackGraphException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    public TrackGraphException(List<String> problems) {
        super("Gleisgraph ungültig: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() { return problems; }
}
