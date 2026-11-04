package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.core.TurnoutPosition;

/**
 * Eigene Zweiglängen und Grenzzeichen einer Weiche (Entscheidung 40/E3), alles in mm ab der Weichenspitze.
 *
 * <p>Das Grenzzeichen eines Schenkels ist die Stelle, ab der ein Fahrzeug auf diesem Schenkel den anderen nicht
 * mehr behindert. Es ist Pflichtangabe und darf hinter dem Weichenende liegen (bei Modellweichen üblich); dann
 * liegt es auf dem anschließenden Gleis. Ein zu früh angegebenes Grenzzeichen ist unsicher, deshalb gibt es keinen
 * Standardwert.
 */
public record TurnoutBranches(double straightLengthMm, double divergingLengthMm,
                              double straightClearanceMm, double divergingClearanceMm) {

    public TurnoutBranches {
        requirePositive(straightLengthMm, "Länge gerader Strang");
        requirePositive(divergingLengthMm, "Länge Abzweig");
        requirePositive(straightClearanceMm, "Grenzzeichen gerader Strang");
        requirePositive(divergingClearanceMm, "Grenzzeichen Abzweig");
    }

    public double lengthMm(TurnoutPosition branch) {
        return switch (requireBranch(branch)) {
            case STRAIGHT -> straightLengthMm;
            default -> divergingLengthMm;
        };
    }

    public double clearanceMm(TurnoutPosition branch) {
        return switch (requireBranch(branch)) {
            case STRAIGHT -> straightClearanceMm;
            default -> divergingClearanceMm;
        };
    }

    static TurnoutPosition requireBranch(TurnoutPosition branch) {
        if (branch != TurnoutPosition.STRAIGHT && branch != TurnoutPosition.DIVERGING)
            throw new IllegalArgumentException("Weichenschenkel muss gerade oder abzweigend sein: " + branch);
        return branch;
    }

    private static void requirePositive(double value, String what) {
        if (!(value > 0) || !Double.isFinite(value))
            throw new IllegalArgumentException(what + " muss eine endliche Zahl größer 0 sein: " + value);
    }
}
