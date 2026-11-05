package at.schmoegl.railcore.layout.graph;

import java.util.Set;

/**
 * Haltepunkt (Lastenheft Kapitel 4): Stelle, an der die Zugspitze hält, je Fahrtrichtung ({@code at}) und Zugart bzw.
 * Zuglänge (z. B. Bahnsteigmitte für kurze Züge).
 *
 * @param trainTypes       Zugarten, für die der Haltepunkt gilt; leer = alle
 * @param maxTrainLengthMm gilt für Züge bis zu dieser Länge ({@link Double#POSITIVE_INFINITY} = alle)
 * @param platformSide     Bahnsteigseite in Fahrtrichtung des Haltepunkts (Entscheidung 46, Option A);
 *                         {@link PlatformSide#UNSPECIFIED} = keine eigene Angabe
 */
public record StopPoint(String id, DirectedLocation at, Set<String> trainTypes, double maxTrainLengthMm,
                        PlatformSide platformSide) {
    public StopPoint {
        LayoutIds.require(id, "Haltepunkt");
        if (at == null) throw new IllegalArgumentException("Haltepunkt " + id + ": Lage fehlt");
        trainTypes = trainTypes == null ? Set.of() : Set.copyOf(trainTypes);
        LayoutIds.requireLength(maxTrainLengthMm, "Haltepunkt " + id + ": Höchstzuglänge");
        if (maxTrainLengthMm == 0) throw new IllegalArgumentException("Haltepunkt " + id + ": Höchstzuglänge 0");
        if (platformSide == null) platformSide = PlatformSide.UNSPECIFIED;
    }

    /** Haltepunkt ohne eigene Angabe zur Bahnsteigseite. */
    public StopPoint(String id, DirectedLocation at, Set<String> trainTypes, double maxTrainLengthMm) {
        this(id, at, trainTypes, maxTrainLengthMm, PlatformSide.UNSPECIFIED);
    }

    /** Haltepunkt für alle Zugarten und -längen. */
    public static StopPoint of(String id, DirectedLocation at) {
        return new StopPoint(id, at, Set.of(), Double.POSITIVE_INFINITY);
    }
}
