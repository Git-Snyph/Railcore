package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.diag.Journal;

/**
 * Sicherheitsschranke vor der Befehlsausgabe; nur vom Steuerungs-Executor nutzbar. Bewegung, Einschalten der
 * Gleisspannung und Weichenstellen werden abgewiesen, solange der Anlagenzustand es nicht erlaubt (Nothalt,
 * Störung, Verbindung nicht in Ordnung). Halten (Fahrstufe 0), Abschalten und Lokfunktionen sind immer erlaubt.
 * Reihenfolge: Schranke → {@link CommandScheduler} → Zentrale; der Scheduler prüft dieselben Sperren beim
 * tatsächlichen Senden erneut ({@link CommandRules}).
 * <p>
 * Bewegung braucht zusätzlich eine Reservierung (Grundsatz 2). Solange es keine Reservierungsverwaltung gibt,
 * verweigert {@link MovementAuthority#NONE} jede Bewegung. Ein nach Kurzschluss gekennzeichneter Zug
 * ({@link TrainCaution#POWER_FAULT}) darf nur noch bremsen und halten.
 */
final class GatedCommandSink implements CommandSink {

    private final ThreadGuard guard;
    private final CommandRules rules;
    private final Journal journal;
    private final CommandScheduler target;

    GatedCommandSink(ThreadGuard guard, CommandRules rules, Journal journal, CommandScheduler target) {
        this.guard = guard;
        this.rules = rules;
        this.journal = journal;
        this.target = target;
    }

    @Override
    public boolean drive(int address, int speedStep, SpeedSteps steps, Direction direction, CommandPriority priority,
                         DriveListener listener) {
        guard.check("Fahrbefehl");
        if (speedStep > 0) {
            String why = rules.movementBlocked(address);
            if (why == null) {
                var sent = target.sentDrive(address);
                why = rules.increaseBlocked(address, speedStep, direction, sent.map(CommandScheduler.SentDrive::step).orElse(0),
                        sent.map(CommandScheduler.SentDrive::direction).orElse(null));
            }
            if (why != null) return reject("Fahrbefehl Lok " + address + " Stufe " + speedStep, why);
        }
        return target.drive(address, speedStep, steps, direction, priority, listener);
    }

    @Override
    public boolean function(int address, int function, boolean on, CommandPriority priority) {
        guard.check("Lokfunktion");
        String why = rules.functionUnsupported(function);
        if (why != null) return reject("Funktion F" + function + " Lok " + address, why);
        return target.function(address, function, on, priority);
    }

    @Override
    public boolean requestLocoState(int address, CommandPriority priority) {
        guard.check("Lokzustand abfragen");
        String why = rules.queryBlocked();
        if (why != null) return reject("Abfrage Lok " + address, why);
        return target.requestLocoState(address, priority);
    }

    @Override
    public boolean requestDetectorStatus(CommandPriority priority) {
        guard.check("Melderabfrage");
        String why = rules.queryBlocked();
        if (why == null) why = rules.detectorQueryUnsupported();
        if (why != null) return reject("Melderabfrage", why);
        return target.requestDetectorStatus(priority);
    }

    @Override
    public boolean refreshStationState(CommandPriority priority) {
        guard.check("Zustände neu einlesen");
        String why = rules.queryBlocked();
        if (why != null) return reject("Zustände neu einlesen", why);
        return target.refreshStationState(priority);
    }

    @Override
    public boolean trackPower(boolean on) {
        guard.check("Gleisspannung");
        if (on) {
            String why = rules.accessoryBlocked();
            if (why != null) return reject("Gleisspannung ein", why);
        }
        return target.trackPower(on);
    }

    @Override
    public boolean turnout(int address, TurnoutPosition position, CommandPriority priority) {
        guard.check("Weiche");
        String why = rules.accessoryBlocked();
        if (why != null) return reject("Weiche " + address + " → " + position, why);
        return target.turnout(address, position, priority);
    }

    private boolean reject(String what, String why) {
        journal.warn("sicherheit", "COMMAND_REJECTED", what + " abgewiesen: " + why);
        return false;
    }
}
