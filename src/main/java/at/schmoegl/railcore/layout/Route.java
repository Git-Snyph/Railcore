package at.schmoegl.railcore.layout;

import java.util.List;

/**
 * Fahrstraße: Verbindung von einem Blockende zum nächsten über eine bestimmte Weichenstellung.
 *
 * @param lengthMm       Gleislänge zwischen den Blöcken (Weichenbereich)
 * @param bidirectional  darf auch von "to" nach "from" befahren werden
 */
public record Route(String id, BlockSide from, BlockSide to, double lengthMm, List<TurnoutSetting> turnouts,
                    double maxSpeedKmh, boolean bidirectional) {

    public Route {
        turnouts = List.copyOf(turnouts);
    }

    /** Liefert die Gegenseite, wenn diese Fahrstraße am angegebenen Blockende beginnt, sonst null. */
    public BlockSide otherSide(BlockSide start) {
        if (from.equals(start)) return to;
        if (bidirectional && to.equals(start)) return from;
        return null;
    }
}
