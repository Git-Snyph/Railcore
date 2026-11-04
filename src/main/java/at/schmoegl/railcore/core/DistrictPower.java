package at.schmoegl.railcore.core;

/**
 * Zustand eines Booster-Bezirks (Stromkreis eines Boosters bzw. Ausgangs der Zentrale), wie ihn Zentrale, Booster
 * oder Belegtmelder melden (Lastenheft 7, „Kurzschluss / Überlast“). Nur {@link #ON} gilt als versorgt; alles andere
 * bedeutet: Gleis im Bezirk ohne Fahrstrom, Melder dort ohne Versorgung.
 */
public enum DistrictPower {
    ON, OFF, SHORT_CIRCUIT, OVERLOAD, UNKNOWN;

    /** Ist der Bezirk sicher versorgt? */
    public boolean powered() { return this == ON; }

    /** Kurzschluss oder Überlast (Störung, nicht bloß abgeschaltet). */
    public boolean fault() { return this == SHORT_CIRCUIT || this == OVERLOAD; }

    public String german() {
        return switch (this) {
            case ON -> "eingeschaltet";
            case OFF -> "ausgeschaltet";
            case SHORT_CIRCUIT -> "Kurzschluss";
            case OVERLOAD -> "Überlast";
            case UNKNOWN -> "unbekannt";
        };
    }
}
