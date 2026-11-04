package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.core.TurnoutPosition;

import java.util.HashMap;
import java.util.Map;

/**
 * Aktuell geltende Weichenlagen, wie der Aufrufer sie dem Graph übergibt (z. B. eine Momentaufnahme des
 * Steuerungs-Threads). Der Graph selbst kennt keinen Zustand.
 *
 * <p>Fehlt eine Weiche, ist ihre Lage {@link TurnoutPosition#UNKNOWN}; über eine unbekannte Lage führt kein Weg
 * (im Zweifel halt). Aus fehlenden Rückmeldungen wird nie eine Lage abgeleitet.
 */
@FunctionalInterface
public interface TurnoutStates {

    /** Logische Lage der Weiche; nie {@code null}, unbekannt ist {@link TurnoutPosition#UNKNOWN}. */
    TurnoutPosition position(String turnoutId);

    /** Alle Lagen unbekannt (Zustand nach Neustart). */
    static TurnoutStates allUnknown() { return id -> TurnoutPosition.UNKNOWN; }

    /** Feste Lagen aus einer Tabelle; nicht enthaltene Weichen sind unbekannt. */
    static TurnoutStates of(Map<String, TurnoutPosition> positions) {
        Map<String, TurnoutPosition> copy = new HashMap<>();
        positions.forEach((id, p) -> { if (id != null && p != null) copy.put(id, p); });
        Map<String, TurnoutPosition> fixed = Map.copyOf(copy);
        return id -> fixed.getOrDefault(id, TurnoutPosition.UNKNOWN);
    }
}
