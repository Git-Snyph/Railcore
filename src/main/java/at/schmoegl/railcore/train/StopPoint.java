package at.schmoegl.railcore.train;

import java.util.Objects;

/**
 * Haltepunkt eines Fahrauftrags (Entscheidung 24).
 * <ul>
 *   <li>{@link Kind#PLATFORM} Bahnsteig-Haltepunkt: zweistufig – Bremskurve auf das Annäherungstempo, die letzte
 *       Strecke {@code approachDistanceMm} im Annäherungstempo, dann Halt zum berechneten Zeitpunkt. Ziel ±10 mm.</li>
 *   <li>{@link Kind#LINE} Halt auf der Strecke (Signal, Ende der Freigabe): eine Bremskurve bis zum Stillstand,
 *       Zielpunkt {@code safetyDistanceMm} vor {@code positionMm}; der Durchrutschweg liegt dahinter in der Freigabe.</li>
 * </ul>
 */
public record StopPoint(String id, Kind kind, double positionMm, double approachDistanceMm, double safetyDistanceMm) {

    public enum Kind { PLATFORM, LINE }

    /** Standard-Annäherungsstrecke am Bahnsteig (Lastenheft: 50–100 mm), je Haltepunkt einstellbar. */
    public static final double DEFAULT_APPROACH_MM = 75.0;

    public StopPoint {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        if (!Double.isFinite(positionMm)) throw new IllegalArgumentException("Position des Haltepunkts fehlt");
        if (!(approachDistanceMm >= 0 && approachDistanceMm <= 1_000))
            throw new IllegalArgumentException("Annäherungsstrecke unplausibel: " + approachDistanceMm);
        if (!(safetyDistanceMm >= 0 && safetyDistanceMm <= 5_000))
            throw new IllegalArgumentException("Sicherheitsabstand unplausibel: " + safetyDistanceMm);
        if (kind == Kind.PLATFORM && approachDistanceMm < 10)
            throw new IllegalArgumentException("Annäherungsstrecke am Bahnsteig muss mindestens 10 mm betragen");
    }

    public static StopPoint platform(String id, double positionMm) {
        return new StopPoint(id, Kind.PLATFORM, positionMm, DEFAULT_APPROACH_MM, 0);
    }

    public static StopPoint platform(String id, double positionMm, double approachDistanceMm) {
        return new StopPoint(id, Kind.PLATFORM, positionMm, approachDistanceMm, 0);
    }

    public static StopPoint line(String id, double signalPositionMm, double safetyDistanceMm) {
        return new StopPoint(id, Kind.LINE, signalPositionMm, 0, safetyDistanceMm);
    }

    /** Wo die Zugspitze stehen soll. */
    public double targetMm() { return kind == Kind.PLATFORM ? positionMm : positionMm - safetyDistanceMm; }
}
