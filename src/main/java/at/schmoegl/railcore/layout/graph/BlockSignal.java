package at.schmoegl.railcore.layout.graph;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Signal an einem Blockende (Lastenheft Kapitel 4): gilt für Züge, die den Block über dieses Ende verlassen
 * (Ende B: Richtung A → B, Ende A: Richtung B → A). Die Lage im Graph ergibt sich aus dem Blockende.
 *
 * <p>Die Signalbilder als Folge von Zubehörbefehlen kommen mit den Fahrstraßen (B4); hier stehen nur die
 * belegten Zubehöradressen, damit die Prüfung doppelte Adressen erkennt. Leer = Signal ohne Decoder.
 */
public record BlockSignal(String id, String blockId, Block.End end, List<Integer> addresses) {
    public BlockSignal {
        LayoutIds.require(id, "Signal");
        LayoutIds.require(blockId, "Signal " + id + ": Block");
        if (end == null) throw new IllegalArgumentException("Signal " + id + ": Blockende fehlt");
        addresses = List.copyOf(new LinkedHashSet<>(addresses));
        for (int a : addresses)
            if (a < 1) throw new IllegalArgumentException("Signal " + id + ": Adresse muss größer 0 sein: " + a);
    }

    /** Fahrtrichtung, für die das Signal gilt. */
    public Block.Direction direction() { return end == Block.End.B ? Block.Direction.A_TO_B : Block.Direction.B_TO_A; }
}
