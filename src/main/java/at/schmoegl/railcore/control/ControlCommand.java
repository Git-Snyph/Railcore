package at.schmoegl.railcore.control;

import at.schmoegl.railcore.train.Train;

import java.util.Objects;

/**
 * Befehle an den Steuerungs-Executor (Bedienhandlungen aus Oberfläche, später API). Sie werden in die Warteschlange
 * gelegt und vom Executor nacheinander geprüft und ausgeführt. Der Nothalt ist bewusst kein Befehl, sondern
 * {@link ControlExecutor#emergencyStop}: er darf nie hinter der Warteschlange warten.
 */
public sealed interface ControlCommand {

    /** Wer den Befehl ausgelöst hat (für das Journal). */
    String operator();

    /**
     * Nothalt bzw. Störung quittieren (ausdrückliche Bedienhandlung). Führt nur über die erlaubten Übergänge nach
     * „Wiederanlauf“, nie direkt in den Betrieb; abgelehnt, solange die Verbindung zur Zentrale verloren ist.
     */
    record AcknowledgeEmergency(String operator) implements ControlCommand {
        public AcknowledgeEmergency { Objects.requireNonNull(operator); }
    }

    /** Freigabe „Bereit“ aus „Verbinde“ oder „Wiederanlauf“ (ausdrückliche Bedienhandlung). */
    record RequestReady(String operator) implements ControlCommand {
        public RequestReady { Objects.requireNonNull(operator); }
    }

    /** Zug anmelden; er startet „unbestätigt“. */
    record RegisterTrain(Train train, String operator) implements ControlCommand {
        public RegisterTrain { Objects.requireNonNull(train); Objects.requireNonNull(operator); }
    }
}
