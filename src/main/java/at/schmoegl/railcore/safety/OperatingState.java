package at.schmoegl.railcore.safety;

import java.util.EnumSet;
import java.util.Set;

/**
 * Betriebszustand der gesamten Anlage. Übergänge sind fest vorgegeben; alles andere wird abgelehnt.
 */
public enum OperatingState {
    /** Keine Verbindung zur Zentrale. */
    OFFLINE,
    /** Verbindung wird aufgebaut, Zustände werden eingelesen. */
    CONNECTING,
    /** Verbunden, Zustände geprüft; Handbetrieb erlaubt, Automatik nicht aktiv. */
    READY,
    /** Automatikbetrieb läuft. */
    RUNNING,
    /** Automatik fährt Züge geordnet zum nächsten Halt. */
    STOPPING,
    /** Nothalt ausgelöst; nur Quittieren oder Trennen möglich. */
    EMERGENCY_STOP,
    /** Störung (z. B. Kommunikationsausfall); Bediener muss quittieren. */
    FAULT,
    /** Nach Störung: Zustände werden neu eingelesen und mit dem gespeicherten Stand abgeglichen. */
    RECOVERY;

    public Set<OperatingState> allowedNext() {
        return switch (this) {
            case OFFLINE -> EnumSet.of(CONNECTING);
            case CONNECTING -> EnumSet.of(READY, FAULT, OFFLINE);
            case READY -> EnumSet.of(RUNNING, EMERGENCY_STOP, FAULT, OFFLINE);
            case RUNNING -> EnumSet.of(STOPPING, EMERGENCY_STOP, FAULT, OFFLINE);
            case STOPPING -> EnumSet.of(READY, EMERGENCY_STOP, FAULT, OFFLINE);
            case EMERGENCY_STOP -> EnumSet.of(RECOVERY, FAULT, OFFLINE);
            case FAULT -> EnumSet.of(RECOVERY, OFFLINE, EMERGENCY_STOP);
            case RECOVERY -> EnumSet.of(READY, FAULT, EMERGENCY_STOP, OFFLINE);
        };
    }

    /** Dürfen Züge durch die Automatik bewegt werden? */
    public boolean automationMayMove() { return this == RUNNING || this == STOPPING; }

    /** Dürfen Züge von Hand bewegt werden (Oberfläche, Maus)? */
    public boolean manualMayMove() { return this == READY || this == RUNNING || this == STOPPING; }

    public String german() {
        return switch (this) {
            case OFFLINE -> "Offline";
            case CONNECTING -> "Verbinde";
            case READY -> "Bereit";
            case RUNNING -> "Automatik";
            case STOPPING -> "Automatik endet";
            case EMERGENCY_STOP -> "NOTHALT";
            case FAULT -> "STÖRUNG";
            case RECOVERY -> "Wiederanlauf";
        };
    }
}
