package at.schmoegl.railcore.train;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Fahrauftrag bis zu einem Haltepunkt auf einer eindimensionalen Wegkoordinate (mm entlang des reservierten
 * Fahrwegs; die Abbildung auf Blöcke und Fahrstraßen folgt mit der Reservierungsverwaltung).
 *
 * @param lineSpeedMmS       zulässige Geschwindigkeit auf dem Weg (Modell-mm/s)
 * @param startPositionMm    Position der Zugspitze beim Start (aus dem letzten Abgleich)
 * @param startSigmaMm       Unsicherheit der Startposition (Standardabweichung)
 * @param points             Meldepunkte auf dem Weg (beliebige Anzahl und Lage)
 * @param endOfAuthorityMm   Ende der Freigabe (Reservierung inkl. Durchrutschweg) – darf nie überfahren werden
 * @param commandDelay       eingemessene Befehlsverzögerung bis zur Wirkung am Decoder
 */
public record DriveOrder(String trainId, int address, SpeedSteps steps, Direction direction, SpeedProfile profile,
                         ModelScale scale, double lineSpeedMmS, double startPositionMm, double startSigmaMm,
                         List<MeasurementPoint> points, StopPoint stop, double endOfAuthorityMm,
                         DelayStats commandDelay) {

    public DriveOrder {
        Objects.requireNonNull(trainId);
        Objects.requireNonNull(steps);
        Objects.requireNonNull(direction);
        Objects.requireNonNull(profile);
        Objects.requireNonNull(scale);
        Objects.requireNonNull(stop);
        Objects.requireNonNull(commandDelay);
        points = points.stream().sorted(Comparator.comparingDouble(MeasurementPoint::positionMm)).toList();
        if (!(lineSpeedMmS > 0)) throw new IllegalArgumentException("Streckengeschwindigkeit muss positiv sein");
        if (!(startSigmaMm >= 0)) throw new IllegalArgumentException("Startunsicherheit darf nicht negativ sein");
        if (!(stop.targetMm() > startPositionMm))
            throw new IllegalArgumentException("Haltepunkt " + stop.id() + " liegt nicht vor der Zugspitze");
        if (!(endOfAuthorityMm >= stop.positionMm()))
            throw new IllegalArgumentException("Ende der Freigabe liegt vor dem Haltepunkt " + stop.id());
        if (steps != profile.steps()) throw new IllegalArgumentException("Fahrstufenmodus passt nicht zum Profil");
    }
}
