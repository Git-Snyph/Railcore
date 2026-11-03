package at.schmoegl.bahnsteuerung.layout;

import java.util.List;

/**
 * Ein Block: Gleisabschnitt, in dem höchstens ein Zug steht. Pflicht: mindestens ein Rückmelder; empfohlen drei
 * (Einfahrt, Mitte, Halt), weil jeder Melder ein Abgleichpunkt für die Positionsberechnung ist.
 *
 * @param stopMarginMm   Abstand der Zugspitze zum Blockende beim Halt
 * @param directions     erlaubte Fahrtrichtungen durch den Block
 * @param maxSpeedKmh    Höchstgeschwindigkeit (Vorbild-km/h), 0 = keine Begrenzung
 */
public record Block(String id, String name, Type type, double lengthMm, List<Sensor> sensors,
                    double stopMarginMm, Directions directions, double maxSpeedKmh) {

    public enum Type { NORMAL, STATION, STAGING, TURNTABLE_TRACK, TURNTABLE_BRIDGE }

    public enum Directions { BOTH, A_TO_B, B_TO_A }

    public Block {
        sensors = List.copyOf(sensors);
    }

    public boolean allowsExitAt(BlockEnd end) {
        return switch (directions) {
            case BOTH -> true;
            case A_TO_B -> end == BlockEnd.B;
            case B_TO_A -> end == BlockEnd.A;
        };
    }

    public String displayName() { return name == null || name.isBlank() ? id : name; }
}
