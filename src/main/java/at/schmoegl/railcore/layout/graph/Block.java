package at.schmoegl.railcore.layout.graph;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Block (Lastenheft Kapitel 4): logische Betriebseinheit, in der höchstens ein Zug steht, aus Meldeabschnitten
 * geordnet von Ende A zu Ende B.
 *
 * <p>Weil Kanten keine globale Ausrichtung haben, legt {@code endA} fest, wo Ende A liegt und welche Richtung dort
 * „A → B“ ist (die Richtung zeigt in den Block hinein). Daraus berechnet {@link TrackLayout} die Ausrichtung auf
 * allen Kanten des Blocks und die Lage von Ende B (bei einer Weiche im Block auch mehrere Enden).
 *
 * @param sectionIds      Meldeabschnitte von Ende A zu Ende B (für einen Block mit Automatik mindestens einer,
 *                        empfohlen drei)
 * @param directions      erlaubte Fahrtrichtungen, mindestens eine
 * @param trainTypes      zugelassene Zugarten; leer = alle
 * @param minTrainLengthMm Mindestzuglänge in mm (0 = keine)
 * @param maxTrainLengthMm Höchstzuglänge in mm ({@link Double#POSITIVE_INFINITY} = keine)
 */
public record Block(String id, List<String> sectionIds, DirectedLocation endA, Set<Direction> directions,
                    Set<String> trainTypes, double minTrainLengthMm, double maxTrainLengthMm) {

    /** Fahrtrichtung bezogen auf die Blockenden. */
    public enum Direction {
        A_TO_B, B_TO_A;

        public Direction reversed() { return this == A_TO_B ? B_TO_A : A_TO_B; }

        /** Ende, über das ein Zug in dieser Richtung den Block verlässt. */
        public End exitEnd() { return this == A_TO_B ? End.B : End.A; }
    }

    /** Blockende. */
    public enum End { A, B }

    public Block {
        LayoutIds.require(id, "Block");
        sectionIds = List.copyOf(sectionIds);
        if (endA == null) throw new IllegalArgumentException("Block " + id + ": Ende A fehlt");
        if (directions == null || directions.isEmpty())
            throw new IllegalArgumentException("Block " + id + ": mindestens eine erlaubte Richtung nötig");
        directions = Set.copyOf(directions);
        trainTypes = trainTypes == null ? Set.of() : Set.copyOf(trainTypes);
        LayoutIds.requireLength(minTrainLengthMm, "Block " + id + ": Mindestzuglänge");
        LayoutIds.requireLength(maxTrainLengthMm, "Block " + id + ": Höchstzuglänge");
        if (minTrainLengthMm > maxTrainLengthMm)
            throw new IllegalArgumentException("Block " + id + ": Mindestzuglänge größer als Höchstzuglänge");
    }

    /** Block in beiden Richtungen, ohne Einschränkung nach Zugart und -länge. */
    public static Block of(String id, DirectedLocation endA, String... sectionIds) {
        return new Block(id, List.of(sectionIds), endA, EnumSet.allOf(Direction.class), Set.of(), 0,
                Double.POSITIVE_INFINITY);
    }

    public boolean allows(Direction d) { return directions.contains(d); }

    /** Derselbe Block mit anderen erlaubten Richtungen. */
    public Block withDirections(Set<Direction> allowed) {
        return new Block(id, sectionIds, endA, allowed, trainTypes, minTrainLengthMm, maxTrainLengthMm);
    }

    /** Derselbe Block mit anderen Meldeabschnitten. */
    public Block withSections(String... ids) {
        return new Block(id, List.of(ids), endA, directions, trainTypes, minTrainLengthMm, maxTrainLengthMm);
    }
}
