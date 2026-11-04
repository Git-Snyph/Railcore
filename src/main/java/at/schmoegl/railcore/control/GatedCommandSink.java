package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.OperatingState;

/**
 * Sicherheitsschranke vor der Befehlsausgabe; nur vom Steuerungs-Executor nutzbar. Bewegung, Einschalten der
 * Gleisspannung und Weichenstellen werden abgewiesen, solange der Anlagenzustand es nicht erlaubt (Nothalt,
 * Störung, Verbindung nicht in Ordnung). Halten (Fahrstufe 0) und Abschalten sind immer erlaubt.
 * <p>
 * Bewegung braucht zusätzlich eine Reservierung (Grundsatz 2). Solange es keine Reservierungsverwaltung gibt,
 * verweigert {@link MovementAuthority#NONE} jede Bewegung.
 */
final class GatedCommandSink implements CommandSink {

    private final ThreadGuard guard;
    private final PlantState plant;
    private final Journal journal;
    private final CommandSink target;
    private final MovementAuthority authority;

    GatedCommandSink(ThreadGuard guard, PlantState plant, Journal journal, CommandSink target, MovementAuthority authority) {
        this.guard = guard;
        this.plant = plant;
        this.journal = journal;
        this.target = target;
        this.authority = authority;
    }

    @Override
    public boolean drive(int address, int speedStep, SpeedSteps steps, Direction direction) {
        guard.check("Fahrbefehl");
        if (speedStep > 0) {
            String why = blocked(true);
            if (why == null && !authority.mayMove(address)) why = "keine Reservierung";
            if (why != null) return reject("Fahrbefehl Lok " + address + " Stufe " + speedStep, why);
        }
        return target.drive(address, speedStep, steps, direction);
    }

    @Override
    public boolean trackPower(boolean on) {
        guard.check("Gleisspannung");
        if (on) {
            String why = blocked(false);
            if (why != null) return reject("Gleisspannung ein", why);
        }
        return target.trackPower(on);
    }

    @Override
    public boolean turnout(int address, TurnoutPosition position) {
        guard.check("Weiche");
        String why = blocked(false);
        if (why != null) return reject("Weiche " + address + " → " + position, why);
        return target.turnout(address, position);
    }

    /** @return Grund der Sperre oder null */
    private String blocked(boolean movement) {
        if (plant.emergencyLatched()) return "Nothalt nicht quittiert";
        if (plant.communication() != CommunicationWatchdog.Status.OK) return "Verbindung zur Zentrale nicht in Ordnung";
        OperatingState s = plant.operatingState();
        if (movement && !s.manualMayMove()) return "Betriebszustand " + s.german();
        if (!movement && (s == OperatingState.OFFLINE || s == OperatingState.EMERGENCY_STOP || s == OperatingState.FAULT))
            return "Betriebszustand " + s.german();
        return null;
    }

    private boolean reject(String what, String why) {
        journal.warn("sicherheit", "COMMAND_REJECTED", what + " abgewiesen: " + why);
        return false;
    }
}
