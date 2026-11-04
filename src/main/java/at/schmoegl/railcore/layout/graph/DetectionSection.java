package at.schmoegl.railcore.layout.graph;

import java.util.List;
import java.util.OptionalInt;

/**
 * Meldeabschnitt (Lastenheft Kapitel 4): physisch isolierter Abschnitt an einem Melder-Eingang, Lage im Graph als
 * Bereiche auf Kanten – auch über eine Weiche hinweg (z. B. Spitze, gerader Strang und Abzweig).
 *
 * @param contact        Kontakt-Kennung des Melder-Eingangs (fortlaufende Nummer wie {@link at.schmoegl.railcore.core.Contacts})
 * @param ranges         Lage im Graph, mindestens ein Bereich mit Länge
 * @param responseDelayMs Ansprechverzögerung in ms (belegt erst, wenn so lange durchgehend belegt gemeldet)
 * @param releaseDelayMs Freimeldeverzögerung in ms (frei erst, wenn so lange durchgehend frei gemeldet)
 * @param district       Booster-Bezirk (Entscheidung 40/E7); leer = nicht zugeordnet (Hinweis „Gleis ohne Bezirk“)
 */
public record DetectionSection(String id, int contact, List<TrackRange> ranges, int responseDelayMs,
                               int releaseDelayMs, OptionalInt district) {

    public DetectionSection {
        LayoutIds.require(id, "Meldeabschnitt");
        if (contact < 1) throw new IllegalArgumentException("Meldeabschnitt " + id + ": Kontakt muss größer 0 sein: " + contact);
        if (ranges == null || ranges.isEmpty()) throw new IllegalArgumentException("Meldeabschnitt " + id + ": Lage fehlt");
        ranges = List.copyOf(ranges);
        if (responseDelayMs < 0 || releaseDelayMs < 0)
            throw new IllegalArgumentException("Meldeabschnitt " + id + ": Verzögerungen dürfen nicht negativ sein");
        if (district == null) district = OptionalInt.empty();
    }

    /** Meldeabschnitt im Bezirk {@code district}. */
    public static DetectionSection of(String id, int contact, int district, int responseDelayMs, int releaseDelayMs,
                                      TrackRange... ranges) {
        return new DetectionSection(id, contact, List.of(ranges), responseDelayMs, releaseDelayMs, OptionalInt.of(district));
    }

    public double lengthMm() {
        double sum = 0;
        for (TrackRange r : ranges) sum += r.lengthMm();
        return sum;
    }
}
