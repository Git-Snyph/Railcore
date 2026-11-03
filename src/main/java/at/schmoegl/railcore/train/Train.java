package at.schmoegl.railcore.train;

import at.schmoegl.railcore.core.SpeedSteps;

/**
 * Ein Zug, wie ihn die Steuerung fährt: führende Lok (Decoderadresse), Gesamtlänge und eingemessenes Profil.
 * Zugbildung aus Datenbank-Fahrzeugen (Wagenreihung, Doppeltraktion) folgt mit der Datenbank in Meilenstein 2.
 *
 * @param priority  1 = Fernverkehr … 6 = Rangierfahrt (siehe Betriebsregeln)
 */
public record Train(String id, String name, int address, SpeedSteps steps, double lengthMm, double vmaxKmh,
                    int priority, SpeedProfile profile) {

    public String displayName() { return name == null || name.isBlank() ? id : name; }
}
