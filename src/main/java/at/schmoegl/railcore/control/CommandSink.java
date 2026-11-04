package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.CommandStation;
import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TurnoutPosition;

/**
 * Schmale Ausgabeschnittstelle für Befehle an die Zentrale. Vorläufig gibt {@link #direct} die Befehle unmittelbar
 * an die {@link CommandStation} weiter; später tritt an ihre Stelle der CommandScheduler (Prioritäten, DCC-Budget,
 * unveränderte Fahrstufen nicht senden). Der Nothalt läuft nie hierüber, sondern direkt (Grundsatz 7).
 * Rückgabe jeweils true = Befehl angenommen, false = abgewiesen (Grund steht im Journal).
 */
public interface CommandSink {

    /** Fahrbefehl: Fahrstufe und Richtung in einem Aufruf. */
    boolean drive(int address, int speedStep, SpeedSteps steps, Direction direction);

    boolean trackPower(boolean on);

    boolean turnout(int address, TurnoutPosition position);

    /** Vorläufige Weitergabe ohne Planung, bis der CommandScheduler existiert. */
    static CommandSink direct(CommandStation station) {
        return new CommandSink() {
            @Override public boolean drive(int address, int speedStep, SpeedSteps steps, Direction direction) {
                station.setLocoDrive(address, speedStep, steps, direction);
                return true;
            }
            @Override public boolean trackPower(boolean on) { station.setTrackPower(on); return true; }
            @Override public boolean turnout(int address, TurnoutPosition position) { station.setTurnout(address, position); return true; }
        };
    }
}
