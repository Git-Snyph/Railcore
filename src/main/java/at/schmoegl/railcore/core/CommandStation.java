package at.schmoegl.railcore.core;

import java.io.IOException;

/**
 * Gemeinsame Schnittstelle aller Digitalzentralen. Die übrige Steuerung kennt nur diese Schnittstelle,
 * dadurch lassen sich weitere Zentralen (ECoS, CS3, DCC-EX, LocoNet ...) ergänzen, ohne den Rest zu ändern.
 */
public interface CommandStation extends AutoCloseable {

    /** Anzeigename, z. B. "Z21 (192.168.0.111)". */
    String name();

    void connect() throws IOException;

    void disconnect();

    boolean isConnected();

    void setTrackPower(boolean on);

    /** Nothalt: alle Loks sofort anhalten (Gleisspannung bleibt je nach Zentrale an). */
    void emergencyStop();

    /** Fahrbefehl. speedStep 0 = Halt, 1..steps.maxStep. */
    void setLocoDrive(int address, int speedStep, SpeedSteps steps, Direction direction);

    /** Nothalt nur für eine Lok. */
    void emergencyStopLoco(int address, SpeedSteps steps, Direction direction);

    void setLocoFunction(int address, int function, boolean on);

    /** Fordert den aktuellen Lokzustand an; Antwort kommt über {@link CommandStationListener#onLocoState}. */
    void requestLocoState(int address);

    /** Weiche/Signal schalten (DCC-Zubehöradresse ab 1). */
    void setTurnout(int address, TurnoutPosition position);

    // ---------------------------------------------------------------- Decoder-Programmierung
    // Ergebnisse kommen asynchron über CommandStationListener#onCvResult / #onCvFailed.

    /** CV auf dem Programmiergleis lesen (cv ab 1). */
    default void readCv(int cv) { throw new UnsupportedOperationException(name() + ": CV lesen nicht unterstützt"); }

    /** CV auf dem Programmiergleis schreiben. */
    default void writeCv(int cv, int value) { throw new UnsupportedOperationException(name() + ": CV schreiben nicht unterstützt"); }

    /** CV auf dem Hauptgleis lesen (POM, erfordert RailCom). */
    default void readCvOnMain(int address, int cv) { throw new UnsupportedOperationException(name() + ": POM lesen nicht unterstützt"); }

    /** CV auf dem Hauptgleis schreiben (POM). Keine Rückmeldung möglich. */
    default void writeCvOnMain(int address, int cv, int value) { throw new UnsupportedOperationException(name() + ": POM schreiben nicht unterstützt"); }

    /** CV eines Zubehördecoders auf dem Hauptgleis lesen (output null = ganzer Decoder). */
    default void readAccessoryCvOnMain(int decoderAddress, Integer output, int cv) { throw new UnsupportedOperationException(name() + ": POM Zubehör nicht unterstützt"); }

    /** CV eines Zubehördecoders auf dem Hauptgleis schreiben. */
    default void writeAccessoryCvOnMain(int decoderAddress, Integer output, int cv, int value) { throw new UnsupportedOperationException(name() + ": POM Zubehör nicht unterstützt"); }

    void addListener(CommandStationListener listener);

    void removeListener(CommandStationListener listener);

    @Override
    default void close() { disconnect(); }
}
