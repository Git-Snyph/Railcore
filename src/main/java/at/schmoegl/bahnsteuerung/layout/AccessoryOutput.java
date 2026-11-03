package at.schmoegl.bahnsteuerung.layout;

import at.schmoegl.bahnsteuerung.core.TurnoutPosition;

/** Ein einzelner Schaltbefehl an eine Zubehöradresse (z. B. für Signalbilder oder Licht). */
public record AccessoryOutput(int address, TurnoutPosition output) {}
