package at.schmoegl.bahnsteuerung.layout;

import java.util.List;

/**
 * Schattenbahnhof. Jedes Gleis besteht aus mehreren Blöcken hintereinander (blockIds vom Ausfahrt-Ende nach hinten).
 * Fährt der vorderste Zug aus, rücken die dahinter stehenden Züge automatisch auf.
 */
public record StagingYard(String id, String name, List<Track> tracks) {

    public record Track(String id, List<String> blockIds, BlockEnd exitEnd) {
        public Track { blockIds = List.copyOf(blockIds); }
    }

    public StagingYard { tracks = List.copyOf(tracks); }
}
