package at.schmoegl.bahnsteuerung.layout;

import java.util.List;

/**
 * Drehscheibe. Die Bühne ist selbst ein Block (bridgeBlockId); jeder Abgang ist mit einem Block verbunden.
 * Ansteuerung je nach Decoder (z. B. Märklin 7686 / DSD über Zubehöradressen) folgt in einem späteren Meilenstein.
 */
public record Turntable(String id, String name, String bridgeBlockId, int baseAddress, List<Exit> exits) {

    public record Exit(int position, BlockSide connectsTo) {}

    public Turntable { exits = List.copyOf(exits); }
}
