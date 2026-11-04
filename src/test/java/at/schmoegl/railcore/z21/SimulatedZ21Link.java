package at.schmoegl.railcore.z21;

import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simulierte Z21 für Tests des Betriebstreibers {@link Z21CommandStation} – kein Netzwerk. Antwortet nach der
 * Z21-LAN-Spezifikation (Vorlage: {@code z21.check.SimulatedZ21}) mit einstellbarer Verzögerung auf der gemeinsamen
 * {@link TimeSource.Manual}; die Uhr stellt nur der Test weiter. Die Z21 lässt sich „verstummen“ lassen
 * ({@link #silent}), Broadcasts lassen sich einspielen ({@link #push}).
 */
final class SimulatedZ21Link implements Z21Link {

    private final TimeSource.Manual clock;
    final List<byte[]> sent = new CopyOnWriteArrayList<>();
    /** Firmware als BCD (0x0143 = 1.43); null = Zentrale kennt die Abfrage nicht (LAN_X_UNKNOWN_COMMAND). */
    volatile Integer firmwareBcd = 0x0143;
    /** Zentrale antwortet nicht (Kabel gezogen, Z21 aus). */
    volatile boolean silent;
    volatile long replyDelayMs = 2;
    long broadcastFlags;
    int opened;
    boolean closed;

    private record Scheduled(long at, byte[] packet) {}

    private final List<Scheduled> queue = new ArrayList<>();
    private final Map<Integer, Integer> speedByte = new HashMap<>();
    private final Map<Integer, Integer> turnouts = new HashMap<>();

    SimulatedZ21Link(TimeSource.Manual clock) { this.clock = clock; }

    /** Als Fabrik für den Treiber. */
    Z21Link.Factory factory() {
        return () -> {
            opened++;
            closed = false;
            return this;
        };
    }

    // ---------------------------------------------------------------- Z21Link

    @Override
    public void send(byte[] p) {
        sent.add(p.clone());
        if (!silent) respond(p);
    }

    @Override
    public synchronized byte[] receive(long timeoutMillis) {
        byte[] p = takeDue();
        if (p == null && timeoutMillis > 0) {      // Thread-Betrieb: kurz warten statt heiß zu kreisen
            try { wait(timeoutMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            p = takeDue();
        }
        return p;
    }

    private byte[] takeDue() {
        long now = clock.nanoTime();
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).at() <= now) return queue.remove(i).packet();
        }
        return null;
    }

    @Override
    public void close() { closed = true; }

    // ---------------------------------------------------------------- Testhilfen

    /** Meldung der Zentrale sofort zustellen (Broadcast, z. B. Melder). */
    synchronized void push(byte[] p) {
        if (!silent) queue.add(new Scheduled(clock.nanoTime(), p));
        notifyAll();
    }

    /** Handregler an der Z21 ändert eine Adresse – ohne Meldung an andere Clients (wie an der echten Z21). */
    synchronized void handController(int address, int speedByteValue) { speedByte.put(address, speedByteValue); }

    List<String> sentHex() { return sent.stream().map(Z21Protocol::hex).toList(); }

    long count(String hex) { return sentHex().stream().filter(hex::equals).count(); }

    long countPrefix(String hexPrefix) { return sentHex().stream().filter(h -> h.startsWith(hexPrefix)).count(); }

    void clearSent() { sent.clear(); }

    // ---------------------------------------------------------------- Z21-Verhalten

    private synchronized void reply(byte[] p) {
        if (silent) return;
        queue.add(new Scheduled(clock.nanoTime() + replyDelayMs * 1_000_000L, p));
        notifyAll();
    }

    private void respond(byte[] p) {
        int header = u16(p, 2);
        switch (header) {
            case 0x10 -> reply(packet(0x10, 0x40, 0xE2, 0x01, 0x00));
            case 0x50 -> broadcastFlags = (u16(p, 4) | (long) u16(p, 6) << 16);
            case 0x40 -> respondX(p);
            default -> { }
        }
    }

    private void respondX(byte[] p) {
        int xh = p[4] & 0xFF, db0 = p[5] & 0xFF;
        if (xh == 0x21 && db0 == 0x24) reply(x(0x62, 0x22, 0x00));
        else if (xh == 0x21 && db0 == 0x81) reply(x(0x61, 0x01));
        else if (xh == 0x21 && db0 == 0x80) reply(x(0x61, 0x00));
        else if (xh == 0x80) reply(x(0x81, 0x00));
        else if (xh == 0xF1 && db0 == 0x0A) {
            Integer fw = firmwareBcd;
            reply(fw != null ? x(0xF3, 0x0A, fw >> 8 & 0xFF, fw & 0xFF) : x(0x61, 0x82));
        } else if (xh == 0xE3 && db0 == 0xF0) {
            reply(locoInfo(addr(p[6], p[7])));
        } else if (xh == 0xE4 && db0 == 0x13) {
            int a = addr(p[6], p[7]);
            synchronized (this) { speedByte.put(a, p[8] & 0xFF); }
            reply(locoInfo(a));                    // die Z21 bestätigt eigene Fahrbefehle mit einer Lokmeldung
        } else if (xh == 0x53 && (p[7] & 0x08) != 0) {
            int a = ((p[5] & 0xFF) << 8) | (p[6] & 0xFF);
            synchronized (this) { turnouts.put(a, (p[7] & 1) == 1 ? 2 : 1); }
            reply(x(0x43, p[5] & 0xFF, p[6] & 0xFF, turnouts.get(a)));
        }
    }

    private synchronized byte[] locoInfo(int a) {
        int msb = (a >> 8) & 0x3F | (a >= 128 ? 0xC0 : 0);
        int v = speedByte.getOrDefault(a, 0x80);
        return x(0xEF, msb, a & 0xFF, 0x04, v, 0x00, 0, 0, 0, 0x00);
    }

    private static int addr(byte msb, byte lsb) { return ((msb & 0x3F) << 8) | (lsb & 0xFF); }

    // ---------------------------------------------------------------- Kodierhilfen (wie Spezifikation)

    /** LAN_CAN_DETECTOR Belegtmeldung des 10808: Modul {@code module}, Port 0..7. */
    static byte[] canOccupancy(int module, int port, boolean occupied) {
        int v1 = (occupied ? 0x1000 : 0) | 0x0100;
        return packet(0xC4, 0x01, 0x00, module & 0xFF, module >> 8, port, 0x01, v1 & 0xFF, v1 >> 8, 0, 0);
    }

    /** LAN_CAN_DETECTOR RailCom-Adressen (index 0 = Adressen 1+2). */
    static byte[] canRailCom(int module, int port, int a1, int a2) {
        return packet(0xC4, 0x01, 0x00, module & 0xFF, module >> 8, port, 0x11, a1 & 0xFF, a1 >> 8, a2 & 0xFF, a2 >> 8);
    }

    /** R-Bus-Meldung Gruppe 0 (Module 1..10), Modul 1 Eingang 1 belegt. */
    static byte[] rbusModule1Input1() {
        return packet(0x80, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    static byte[] x(int... b) {
        int[] all = Arrays.copyOf(b, b.length + 1);
        int xor = 0;
        for (int v : b) xor ^= v & 0xFF;
        all[b.length] = xor;
        return packet(0x40, all);
    }

    static byte[] packet(int header, int... data) {
        byte[] p = new byte[4 + data.length];
        p[0] = (byte) p.length;
        p[1] = (byte) (p.length >> 8);
        p[2] = (byte) header;
        p[3] = (byte) (header >> 8);
        for (int i = 0; i < data.length; i++) p[4 + i] = (byte) data[i];
        return p;
    }

    static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
}
