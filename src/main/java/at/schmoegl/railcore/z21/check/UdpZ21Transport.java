package at.schmoegl.railcore.z21.check;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

/** UDP-Verbindung zur Z21 (Standard-Port 21105). Pakete fremder Absender werden verworfen. */
public final class UdpZ21Transport implements Z21Transport {

    private final DatagramSocket socket;
    private final InetSocketAddress target;
    private final byte[] buffer = new byte[1500];

    public UdpZ21Transport(String host, int port) throws IOException {
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
