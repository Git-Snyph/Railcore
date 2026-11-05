package at.schmoegl.railcore.layout.graph;

import java.util.List;

/**
 * Haltebereich (Entscheidung 40/E6): ein oder mehrere unmittelbar aufeinanderfolgende Blöcke, in denen ein
 * geplantes Ziel für Züge länger als ein Block liegen darf. Die Blöcke stehen in Reihenfolge; dass sie wirklich
 * aneinanderstoßen, prüft {@link TrackLayout}.
 */
public record HoldingArea(String id, List<String> blockIds) {
    public HoldingArea {
        LayoutIds.require(id, "Haltebereich");
        if (blockIds == null || blockIds.isEmpty())
            throw new IllegalArgumentException("Haltebereich " + id + ": mindestens ein Block nötig");
        blockIds = List.copyOf(blockIds);
    }

    public static HoldingArea of(String id, String... blockIds) { return new HoldingArea(id, List.of(blockIds)); }
}
