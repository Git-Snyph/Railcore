package at.schmoegl.bahnsteuerung.layout;

import java.util.List;

/** Bahnhof: Gruppe von Blöcken, die im Fahrplan als ein Ziel gelten. */
public record Station(String id, String name, List<String> blockIds) {
    public Station { blockIds = List.copyOf(blockIds); }
}
