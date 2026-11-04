package at.schmoegl.railcore.z21.check;

import java.io.IOException;

/**
 * Rohe Paketverbindung zur Z21 für das Prüfprogramm. Im Betrieb UDP ({@link UdpZ21Transport}),
 * in Tests eine simulierte Gegenstelle. Das Prüfprogramm braucht rohe Antworten und Zeitüberschreitungen,
 * deshalb nicht der Betriebstreiber {@code Z21CommandStation} (siehe Entscheidung 20).
 */
public interface Z21Transport extends AutoCloseable {

    /** Sendet ein vollständiges Z21-Paket. Eine Ausnahme gilt als Verbindungsverlust. */
    void send(byte[] packet) throws IOException;

    /**
     * Wartet höchstens {@code timeoutMillis} auf ein Paket.
     *
     * @return Paketinhalt oder {@code null}, wenn in dieser Zeit nichts kam
     */
    byte[] receive(long timeoutMillis) throws IOException;

    @Override
    void close();
}
