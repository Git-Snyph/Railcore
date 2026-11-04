package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.core.TurnoutPosition;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Welche Lage der beiden gekoppelten Weichenhälften (Seite A, Seite B) welchen Fahrweg einer
 * Doppelkreuzungsweiche freigibt. Die Zuordnung hängt von Bauart und Verdrahtung ab und ist deshalb Teil des
 * Gleisstücks, nicht fest einprogrammiert.
 *
 * <p>Je Außenanschluss müssen seine beiden Fahrwege verschiedene Lagen verlangen, sonst wäre die Fortsetzung
 * mehrdeutig. Eine Lagekombination ohne Fahrweg bedeutet „kein Durchkommen“ (im Zweifel halt).
 *
 * <p>{@code hardwareVerified} sagt, ob die Zuordnung für diese Bauart an der echten Hardware geprüft wurde
 * (Entscheidung 41). Ungeprüfte Zuordnungen meldet {@link TrackGraph#notices()} als Hinweis; sie gelten nicht als
 * gesichert.
 */
public record DoubleSlipLogic(Map<Port, Setting> settings, boolean hardwareVerified) {

    /** Verlangte Lage beider Hälften für einen Fahrweg. */
    public record Setting(TurnoutPosition sideA, TurnoutPosition sideB) {
        public Setting {
            TurnoutBranches.requireBranch(sideA);
            TurnoutBranches.requireBranch(sideB);
        }
    }

    private static final List<Port> ROUTES = List.of(Port.A1_B1, Port.A1_B2, Port.A2_B1, Port.A2_B2);

    public DoubleSlipLogic {
        Map<Port, Setting> copy = new EnumMap<>(Port.class);
        copy.putAll(settings);
        for (Port r : ROUTES)
            if (!copy.containsKey(r)) throw new IllegalArgumentException("Lage für Fahrweg " + r + " fehlt");
        if (copy.size() != ROUTES.size())
            throw new IllegalArgumentException("Nur Fahrwege A1_B1, A1_B2, A2_B1, A2_B2 erlaubt: " + copy.keySet());
        requireDistinct(copy, Port.A1_B1, Port.A1_B2, "A1");
        requireDistinct(copy, Port.A2_B1, Port.A2_B2, "A2");
        requireDistinct(copy, Port.A1_B1, Port.A2_B1, "B1");
        requireDistinct(copy, Port.A1_B2, Port.A2_B2, "B2");
        settings = Map.copyOf(copy);
    }

    /**
     * VORLÄUFIGE ANNAHME – AN HARDWARE ZU PRÜFEN (Entscheidung 41), deshalb {@code hardwareVerified = false}:
     * durchgehende Stränge A1–B1 und A2–B2 bei beiden Hälften „gerade“, Bogenfahrwege A1–B2 und A2–B1 bei beiden
     * Hälften „abzweigend“; gemischte Lagen geben keinen Fahrweg frei. Ob eine reale Bauart so schaltet, ist nicht
     * belegt; die Zuordnung je Bauart wird an der Hardware geprüft und dann mit {@code hardwareVerified = true}
     * eingetragen.
     */
    public static DoubleSlipLogic provisionalAssumption() {
        Setting straight = new Setting(TurnoutPosition.STRAIGHT, TurnoutPosition.STRAIGHT);
        Setting diverging = new Setting(TurnoutPosition.DIVERGING, TurnoutPosition.DIVERGING);
        return new DoubleSlipLogic(Map.of(Port.A1_B1, straight, Port.A2_B2, straight,
                Port.A1_B2, diverging, Port.A2_B1, diverging), false);
    }

    public Setting setting(Port route) {
        Setting s = settings.get(route);
        if (s == null) throw new IllegalArgumentException("Kein Fahrweg der Doppelkreuzungsweiche: " + route);
        return s;
    }

    private static void requireDistinct(Map<Port, Setting> m, Port r1, Port r2, String port) {
        if (m.get(r1).equals(m.get(r2)))
            throw new IllegalArgumentException("Fahrwege " + r1 + " und " + r2 + " ab " + port
                    + " verlangen dieselbe Lage – Fortsetzung wäre mehrdeutig");
    }
}
