package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.StationCapabilities;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.OperatingState;

/**
 * Sperren der Befehlsausgabe, gemeinsam genutzt von der Sicherheitsschranke ({@link GatedCommandSink}, beim
 * Annehmen) und vom {@link CommandScheduler} (erneut beim Senden, weil ein Befehl in der Warteschlange warten kann,
 * während sich der Anlagenzustand ändert). Nur Steuerungs-Thread.
 */
final class CommandRules {

    private final PlantState plant;
    private final MovementAuthority authority;

    CommandRules(PlantState plant, MovementAuthority authority) {
        this.plant = plant;
        this.authority = authority;
    }

    /** @return Grund, warum Lok {@code address} nicht fahren darf (Fahrstufe &gt; 0), oder null */
    String movementBlocked(int address) {
        String why = blocked(true);
        if (why == null && !authority.mayMove(address)) why = "keine Reservierung";
        return why;
    }

    /**
     * Kennzeichnung nach Kurzschluss bzw. Spannungsausfall ({@link TrainCaution#blocksIncrease}): Anfahren,
     * Beschleunigen und Richtungswechsel sind gesperrt, Bremsen bleibt erlaubt, damit die Automatik geordnet hält.
     *
     * @param sentStep      zuletzt an die Zentrale gesendete Fahrstufe (0, wenn nie gesendet)
     * @param sentDirection zuletzt gesendete Richtung ({@code null}, wenn nie gesendet)
     * @return Grund oder null
     */
    String increaseBlocked(int address, int step, Direction direction, int sentStep, Direction sentDirection) {
        if (step == 0 || !plant.increaseBlocked(address)) return null;
        if (sentDirection != null && direction == sentDirection && step <= sentStep) return null;   // Bremsen/Halten
        return "Zug gekennzeichnet (" + plant.increaseBlockingCautions(address) + ") – nur Bremsen und Halten erlaubt";
    }

    /** @return Grund, warum Gleisspannung ein bzw. Weichen stellen gesperrt ist, oder null */
    String accessoryBlocked() { return blocked(false); }

    /**
     * Fähigkeiten werden nicht vorausgesetzt (Lastenheft 8): F29–F31 nur, wenn die Zentrale sie erkennbar kann.
     *
     * @return Grund, warum die Funktion nicht gesendet werden kann, oder null
     */
    String functionUnsupported(int function) {
        if (function < 0 || function > 31) return "Funktion außerhalb F0–F31";
        if (function >= 29 && !plant.capabilities().has(StationCapabilities.Feature.FUNCTIONS_F29_F31))
            return "Zentrale unterstützt F29–F31 nicht erkennbar (" + plant.capabilities().describe() + ")";
        return null;
    }

    /**
     * Abfragen brauchen eine antwortende Zentrale; ohne Verbindung wären sie sinnlos und würden beim Adapter
     * scheitern. @return Grund oder null
     */
    String queryBlocked() {
        if (plant.operatingState() == OperatingState.OFFLINE) return "keine Verbindung zur Zentrale";
        if (plant.communication() == CommunicationWatchdog.Status.LOST) return "Verbindung zur Zentrale verloren";
        return null;
    }

    /** Fähigkeiten nie voraussetzen: Melderabfrage nur, wenn die Zentrale sie erkennbar kann. @return Grund oder null */
    String detectorQueryUnsupported() {
        if (!plant.capabilities().has(StationCapabilities.Feature.DETECTOR_STATUS_QUERY))
            return "Zentrale unterstützt die Melderabfrage nicht erkennbar (" + plant.capabilities().describe() + ")";
        return null;
    }

    private String blocked(boolean movement) {
        if (plant.emergencyLatched()) return "Nothalt nicht quittiert";
        if (plant.communication() != CommunicationWatchdog.Status.OK) return "Verbindung zur Zentrale nicht in Ordnung";
        OperatingState s = plant.operatingState();
        if (movement && !s.manualMayMove()) return "Betriebszustand " + s.german();
        if (!movement && (s == OperatingState.OFFLINE || s == OperatingState.EMERGENCY_STOP || s == OperatingState.FAULT))
            return "Betriebszustand " + s.german();
        return null;
    }
}
