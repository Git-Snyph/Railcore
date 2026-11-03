package at.schmoegl.railcore.core;

/**
 * Zustand einer Lok laut Zentrale.
 *
 * @param address    DCC-Adresse
 * @param speedStep  Fahrstufe 0..steps.maxStep (0 = Halt)
 * @param steps      Fahrstufen-Modus
 * @param direction  Richtung
 * @param functions  Bitmaske F0..F31 (Bit n = Funktion n)
 */
public record LocoState(int address, int speedStep, SpeedSteps steps, Direction direction, long functions) {

    public boolean function(int n) { return (functions >>> n & 1L) != 0; }
}
