package at.schmoegl.bahnsteuerung.core;

/** Fahrstufen-Modus eines DCC-Decoders. maxStep = höchste Fahrstufe (ohne Halt/Nothalt). */
public enum SpeedSteps {
    S14(14), S28(28), S128(126);

    public final int maxStep;

    SpeedSteps(int maxStep) { this.maxStep = maxStep; }
}
