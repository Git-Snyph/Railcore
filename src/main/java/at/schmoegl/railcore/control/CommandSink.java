package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TurnoutPosition;

/**
 * Schmale Ausgabeschnittstelle für Befehle an die Zentrale; nur vom Steuerungs-Thread nutzbar. Hinter der
 * Sicherheitsschranke ({@link GatedCommandSink}) liegt der {@link CommandScheduler} (Prioritäten, DCC-Budget,
 * Zusammenfassen, unveränderte Fahrstufen nicht senden, Entscheidung 27). Der Nothalt läuft nie hierüber, sondern
 * direkt (Grundsatz 7).
 * <p>
 * Rückgabe jeweils true = Befehl angenommen (gesendet, eingereiht oder als unverändert nicht nötig), false =
 * abgewiesen (Grund steht im Journal). Ein angenommener Befehl kann noch warten; wann er tatsächlich gesendet wurde,
 * meldet der {@link DriveListener}.
 */
public interface CommandSink {

    /** Rückmeldung zu einem Fahrbefehl; läuft auf dem Steuerungs-Thread. */
    interface DriveListener {
        /** Der Befehl (bzw. sein neuester zusammengefasster Wert) wurde zum Zeitpunkt {@code sentNanos} gesendet. */
        void sent(int speedStep, Direction direction, long sentNanos);

        /**
         * Der Befehl wurde vor dem Senden verworfen (z. B. Nothalt, Sperre beim Senden). Ein verworfener Fahrbefehl
         * mit Fahrstufe &gt; 0 wird durch Halt ersetzt.
         */
        void dropped(String reason);

        /** Die wartende Bremsstufe wurde wegen langer Wartezeit zur Sicherheit aufgestuft (Entscheidung 30). */
        default void escalated() { }

        DriveListener NONE = new DriveListener() {
            @Override public void sent(int speedStep, Direction direction, long sentNanos) { }
            @Override public void dropped(String reason) { }
        };
    }

    /** Fahrbefehl: Fahrstufe und Richtung in einem Aufruf, mit Vorrangklasse und Rückmeldung. */
    boolean drive(int address, int speedStep, SpeedSteps steps, Direction direction, CommandPriority priority,
                  DriveListener listener);

    /** Fahrbefehl ohne Rückmeldung. */
    default boolean drive(int address, int speedStep, SpeedSteps steps, Direction direction, CommandPriority priority) {
        return drive(address, speedStep, steps, direction, priority, DriveListener.NONE);
    }

    /** Lokfunktion schalten (Licht, Sound …), üblicherweise {@link CommandPriority#COMFORT}. */
    boolean function(int address, int function, boolean on, CommandPriority priority);

    /** Gleisspannung; kein DCC-Paket, läuft ohne Warteschlange. */
    boolean trackPower(boolean on);

    /** Weiche/Signal stellen; der Schaltimpuls bleibt Sache des Adapters, zählt aber zum DCC-Budget. */
    boolean turnout(int address, TurnoutPosition position, CommandPriority priority);
}
