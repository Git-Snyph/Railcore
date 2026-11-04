package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.core.TurnoutPosition;

/**
 * Für einen Übergang im Graph verlangte Weichenlage. {@code turnoutId} ist die logische Weiche (Zubehör), nicht
 * der Knoten; mehrere Knoten dürfen dieselbe Weiche verwenden (z. B. Weichenverbindung an einem Antrieb).
 * Die Lage ist logisch gemeint, eine vertauscht angeschlossene Weiche gleicht das Zubehör aus.
 */
public record TurnoutRequirement(String turnoutId, TurnoutPosition position) {
    public TurnoutRequirement {
        if (turnoutId == null || turnoutId.isBlank()) throw new IllegalArgumentException("Weiche ohne Kennung");
        TurnoutBranches.requireBranch(position);
    }

    public boolean satisfiedBy(TurnoutStates states) {
        return states.position(turnoutId) == position;
    }
}
