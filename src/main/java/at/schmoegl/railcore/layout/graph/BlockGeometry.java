package at.schmoegl.railcore.layout.graph;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aus den Meldeabschnitten berechnete Lage eines Blocks im Graph (nur für Blöcke ohne Geometriefehler).
 *
 * @param lengthMm   Länge des Blocks (Summe der abgedeckten Kantenstücke)
 * @param endsA      Enden auf Seite A, Richtung zeigt aus dem Block hinaus (B → A); enthält {@link Block#endA()} umgekehrt
 * @param endsB      Enden auf Seite B, Richtung zeigt aus dem Block hinaus (A → B); bei einer Weiche im Block mehrere
 * @param aToBForward je Kante des Blocks: ob „A → B“ der Bezugsrichtung der Kante entspricht
 */
public record BlockGeometry(String blockId, double lengthMm, List<DirectedLocation> endsA,
                            List<DirectedLocation> endsB, Map<String, Boolean> aToBForward) {

    public BlockGeometry {
        endsA = List.copyOf(endsA);
        endsB = List.copyOf(endsB);
        aToBForward = Map.copyOf(aToBForward);
    }

    public List<DirectedLocation> ends(Block.End end) { return end == Block.End.A ? endsA : endsB; }

    /** Fahrtrichtung im Block, wenn man die Kante {@code edgeId} in Richtung {@code forward} befährt. */
    public Optional<Block.Direction> direction(String edgeId, boolean forward) {
        Boolean f = aToBForward.get(edgeId);
        if (f == null) return Optional.empty();
        return Optional.of(f == forward ? Block.Direction.A_TO_B : Block.Direction.B_TO_A);
    }
}
