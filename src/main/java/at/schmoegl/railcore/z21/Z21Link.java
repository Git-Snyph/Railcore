package at.schmoegl.railcore.z21;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

/**
 * Rohe Paketverbindung des Betriebstreibers {@link Z21CommandStation} zur Z21. Im Betrieb UDP ({@link #udp}), in
 * Tests eine simulierte Z21 ohne Netzwerk. Getrennt von {@code z21.check.Z21Transport}, damit der Betriebstreiber
 * nicht vom Prüfprogramm abhängt (Entscheidung 20).
 */
public interface Z21Link extends AutoCloseable {

    /** Öffnet eine Verbindung (z. B. einen UDP-Socket). */
    @FunctionalInterface
    interface Factory {
        Z21Link open() throws IOException;
    }

    /** Sendet ein vollständiges Z21-Paket. */
    void send(byte[] packet) throws IOException;

    /**
     * Wartet höchstens {@code timeoutMillis} auf ein Paket (0 = nur nachsehen, nicht warten).
     *
     * @return Paketinhalt oder {@code null}, wenn in dieser Zeit nichts kam
     */
    byte[] receive(long timeoutMillis) throws IOException;

    @Override
    void close();

    /** UDP-Verbindung zur Z21; Pakete fremder Absender werden verworfen. */
    static Factory udp(String host, int port) {
        return () -> new Udp(host, port);
    }

    /** UDP-Umsetzung. */
    final class Udp implements Z21Link {
        private final DatagramSocket socket;
        private final InetSocketAddress target;
        private final byte[] buffer = new byte[1500];

        Udp(String host, int port) throws IOException {
            this.target = new InetSocketAddress(host, port);
            if (target.isUnresolved()) throw new IOException("Adresse nicht auflösbar: " + host);
            this.socket = new DatagramSocket();
        }

        @Override
        public void send(byte[] packet) throws IOException {
            socket.send(new DatagramPacket(packet, packet.length, target));
        }

        @Override
        public synchronized byte[] receive(long timeoutMillis) throws IOException {
            // 0 hieße bei DatagramSocket „unbegrenzt warten“: kürzeste Wartezeit ist 1 ms
            socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, timeoutMillis)));
            DatagramPacket p = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                return null;
            }
            if (!target.getAddress().equals(p.getAddress())) return null;
            return Arrays.copyOf(p.getData(), p.getLength());
        }

        @Override
        public void close() { socket.close(); }
    }
}
