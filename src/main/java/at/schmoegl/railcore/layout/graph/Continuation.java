package at.schmoegl.railcore.layout.graph;

import java.util.List;

/** Ergebnis von {@link TrackGraph#next}: wie es am Ende einer befahrenen Kante weitergeht. */
public sealed interface Continuation permits Continuation.Next, Continuation.EndOfTrack, Continuation.Blocked {

    /** Weiter auf {@code edge}; {@code requires} sind die dabei genutzten Weichenlagen. */
    record Next(DirectedEdge edge, List<TurnoutRequirement> requires) implements Continuation {
        public Next {
            requires = List.copyOf(requires);
        }
    }

    /** Gleisende oder Prellbock am Knoten. */
    record EndOfTrack(String nodeId) implements Continuation {}

    /** Kein Durchkommen am Knoten bei den übergebenen Weichenlagen (im Zweifel halt). */
    record Blocked(String nodeId, Reason reason) implements Continuation {}

    enum Reason {
        /** mindestens eine beteiligte Weiche hat eine unbekannte Lage */
        POSITION_UNKNOWN,
        /** alle Lagen bekannt, aber keine passt (z. B. gegen die Spitze bei falscher Lage) */
        POSITION_MISMATCH,
        /** mehr als eine Fortsetzung passt – darf bei gültigem Graph nicht vorkommen */
        AMBIGUOUS
    }
}
