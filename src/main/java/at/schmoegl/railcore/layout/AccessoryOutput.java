package at.schmoegl.railcore.layout;

import at.schmoegl.railcore.core.TurnoutPosition;

/** Ein einzelner Schaltbefehl an eine Zubehöradresse (z. B. für Signalbilder oder Licht). */
public record AccessoryOutput(int address, TurnoutPosition output) {}
