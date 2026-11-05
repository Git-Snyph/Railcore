package at.schmoegl.railcore.layout.graph;

import java.util.List;

/**
 * Booster-Bezirk (Entscheidung 40/E7): Stromkreis eines Boosters bzw. Ausgangs der Zentrale. Zugeordnet wird er je
 * Meldeabschnitt ({@link DetectionSection#district()}); Gleis ohne Melder bekommt ihn über {@code extraRanges}.
 *
 * <p>Woher die Zustandsmeldung eines Bezirks kommt (Zentrale, Booster oder Belegtmelder 10808) und wie die Kennung
 * {@code id} dort heißt, ist <b>an Hardware zu prüfen</b>; {@code id} ist bis dahin nur die Nummer, unter der die
 * Steuerung den Bezirk führt ({@link at.schmoegl.railcore.core.DistrictPower}).
 *
 * @param reversingLoop Kehrschleifenbezirk: Polarität wird im Bezirk selbsttätig umgeschaltet (Kehrschleifenmodul);
 *                      nur solche Bezirke dürfen eine Kehrschleife enthalten
 */
public record BoosterDistrict(int id, String name, boolean reversingLoop, List<TrackRange> extraRanges) {
    public BoosterDistrict {
        if (id < 0) throw new IllegalArgumentException("Bezirk: Nummer darf nicht negativ sein: " + id);
        name = name == null ? "" : name;
        extraRanges = extraRanges == null ? List.of() : List.copyOf(extraRanges);
    }

    public static BoosterDistrict of(int id, String name, TrackRange... extraRanges) {
        return new BoosterDistrict(id, name, false, List.of(extraRanges));
    }

    public static BoosterDistrict reversingLoop(int id, String name, TrackRange... extraRanges) {
        return new BoosterDistrict(id, name, true, List.of(extraRanges));
    }
}
