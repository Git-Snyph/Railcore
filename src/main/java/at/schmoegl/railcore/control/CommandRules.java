package at.schmoegl.railcore.control;

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

    /** @return Grund, warum Gleisspannung ein bzw. Weichen stellen gesperrt ist, oder null */
    String accessoryBlocked() { return blocked(false); }

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
