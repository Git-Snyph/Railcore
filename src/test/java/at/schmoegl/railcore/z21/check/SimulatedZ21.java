package at.schmoegl.railcore.z21.check;

import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Simulierte Z21 für Tests des Prüfprogramms. Antwortet nach der Z21-LAN-Spezifikation mit einstellbarer
 * Verzögerung; einzelne Antworten lassen sich unterdrücken, die Verbindung lässt sich „abreißen“.
 * Zeit läuft über {@link TimeSource.Manual}: {@link #receive} stellt die Uhr weiter, wenn nichts ansteht.
 */
final class SimulatedZ21 implements Z21Transport {

    final TimeSource.Manual clock = new TimeSource.Manual();
    final List<byte[]> sent = new ArrayList<>();
    /** Unterdrückte Antworten: serial, hwinfo, code, flags, sysstate, rbus, can, version, status, fw, loco, turnout, pom, power */
    final Set<String> silent = new HashSet<>();
    boolean dead;
    boolean dieAfterDrive;
    boolean echoLoco = true;
    boolean echoTurnout = true;
    boolean xFirmwareSupported = true;
    boolean f29 = true;
    Integer pomValue = 161;
    TrackPower power = TrackPower.OFF;
    long supportedFlags = 0xFFFFFFFFL;
    long flags;
    long replyDelayMs = 5;
    final List<byte[]> canReplies = new ArrayList<>();

    private record Scheduled(long at, byte[] packet) {}

    private final List<Scheduled> queue = new ArrayList<>();
    private final Map<Integer, Integer> locoSpeedByte = new HashMap<>();
    private final Map<Integer, Integer> turnouts = new HashMap<>();

    // ---------------------------------------------------------------- Transport

    @Override
    public void send(byte[] p) {
        sent.add(p.clone());
        if (!dead) respond(p);
    }

    @Override
    public byte[] receive(long timeoutMillis) {
        byte[] due = takeDue();
        if (due != null) return due;
        long now = clock.nanoTime();
        long limit = now + timeoutMillis * 1_000_000L;
        long next = queue.stream().mapToLong(Scheduled::at).min().orElse(Long.MAX_VALUE);
        long target = Math.min(limit, Math.max(next, now + 1_000_000L));
        clock.advanceMillis((target - now) / 1_000_000.0);
        return takeDue();
    }

    @Override
    public void close() { }

    private byte[] takeDue() {
        long now = clock.nanoTime();
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).at() <= now) return queue.remove(i).packet();
        }
        return null;
    }

    /** Meldung zu einem späteren Zeitpunkt (relativ zu jetzt) zustellen, z. B. Broadcasts während der Beobachtung. */
    void broadcastAt(long ms, byte[] p) { queue.add(new Scheduled(clock.nanoTime() + ms * 1_000_000L, p)); }

    private record HandChange(int atQuery, int address, int speedByte) {}

    private final List<HandChange> handChanges = new ArrayList<>();
    private int locoQueries;

    /**
     * Ein Handregler an der Z21 ändert den Fahrzustand einer Adresse, bevor die {@code atQuery}-te Lokinfo-Abfrage
     * beantwortet wird (1 = erste Abfrage) – wie an der echten Z21 ohne Meldung an andere Clients.
     */
    void handControllerBeforeQuery(int atQuery, int address, int speedByte) {
        handChanges.add(new HandChange(atQuery, address, speedByte));
    }

    private void applyHandChanges() {
        locoQueries++;
        handChanges.removeIf(h -> {
            if (h.atQuery() > locoQueries) return false;
            locoSpeedByte.put(h.address(), h.speedByte());
            return true;
        });
    }

    private void reply(String kind, byte[] p) {
        if (dead || silent.contains(kind)) return;
        queue.add(new Scheduled(clock.nanoTime() + replyDelayMs * 1_000_000L, p));
    }

    // ---------------------------------------------------------------- Abfragen der Tests

    List<byte[]> sentMatching(String hexPrefix) {
        return sent.stream().filter(b -> hex(b).startsWith(hexPrefix)).toList();
    }

    boolean anySent(CommandClass c) { return sent.stream().anyMatch(b -> CommandClass.of(b) == c); }

    int indexOf(String hex) {
        for (int i = 0; i < sent.size(); i++) if (hex(sent.get(i)).equals(hex)) return i;
        return -1;
    }

    int lastIndexOf(String hex) {
        for (int i = sent.size() - 1; i >= 0; i--) if (hex(sent.get(i)).equals(hex)) return i;
        return -1;
    }

    // ---------------------------------------------------------------- Z21-Verhalten

    private void respond(byte[] p) {
        int header = u16(p, 2);
        switch (header) {
            case 0x10 -> reply("serial", packet(0x10, le32(123456)));
            case 0x18 -> reply("code", packet(0x18, 0));
            case 0x1A -> reply("hwinfo", packet(0x1A, concat(le32(0x201), le32(0x0143))));
            case 0x50 -> flags = (u16(p, 4) | (long) u16(p, 6) << 16) & supportedFlags;
            case 0x51 -> reply("flags", packet(0x51, le32(flags)));
            case 0x85 -> reply("sysstate", packet(0x84, 0x64, 0, 0, 0, 0x60, 0, 0x1E, 0, 0x20, 0x4E, 0x50, 0x46,
                    stateByte(), 0, 0, 0x7B));
            case 0x81 -> reply("rbus", packet(0x80, p[4] & 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
            case 0xC4 -> canReplies.forEach(c -> reply("can", c));
            case 0x40 -> respondX(p);
            default -> { }
        }
    }

    private void respondX(byte[] p) {
        int xh = p[4] & 0xFF, db0 = p[5] & 0xFF;
        if (xh == 0x21 && db0 == 0x21) reply("version", x(0x63, 0x21, 0x30, 0x12));
        else if (xh == 0x21 && db0 == 0x24) reply("status", x(0x62, 0x22, stateByte()));
        else if (xh == 0x21 && db0 == 0x81) { if (!silent.contains("power")) power = TrackPower.ON; reply("power", x(0x61, 0x01)); }
        else if (xh == 0x21 && db0 == 0x80) { if (!silent.contains("power")) power = TrackPower.OFF; reply("power", x(0x61, 0x00)); }
        else if (xh == 0xF1 && db0 == 0x0A) reply("fw", xFirmwareSupported ? x(0xF3, 0x0A, 0x01, 0x43) : x(0x61, 0x82));
        else if (xh == 0xE3 && db0 == 0xF0) {
            applyHandChanges();
            reply("loco", locoInfo(addr(p[6], p[7])));
        }
        else if (xh == 0xE4 && db0 == 0x13) {
            int a = addr(p[6], p[7]);
            int v = p[8] & 0xFF;
            locoSpeedByte.put(a, v);
            if (echoLoco) reply("loco", locoInfo(a));
            if (dieAfterDrive && (v & 0x7F) > 1) dead = true;
        } else if (xh == 0x43) reply("turnout", turnoutInfo(p[5] & 0xFF, p[6] & 0xFF));
        else if (xh == 0x53) {
            if ((p[7] & 0x08) != 0) {
                turnouts.put(((p[5] & 0xFF) << 8) | (p[6] & 0xFF), (p[7] & 1) == 1 ? 2 : 1);
                if (echoTurnout) reply("turnout", turnoutInfo(p[5] & 0xFF, p[6] & 0xFF));
            }
        } else if (xh == 0xE6 && db0 == 0x31) {
            if (pomValue != null) reply("pom", x(0x64, 0x14, p[8] & 0x03, p[9] & 0xFF, pomValue));
        } else if (xh == 0x80) reply("power", x(0x81, 0x00));
    }

    private int stateByte() {
        return switch (power) {
            case ON -> 0x00;
            case EMERGENCY_STOP -> 0x01;
            case SHORT_CIRCUIT -> 0x04;
            case PROGRAMMING -> 0x20;
            default -> 0x02;
        };
    }

    private byte[] turnoutInfo(int msb, int lsb) {
        return x(0x43, msb, lsb, turnouts.getOrDefault((msb << 8) | lsb, 0));
    }

    byte[] locoInfo(int a) {
        int msb = (a >> 8) & 0x3F | (a >= 128 ? 0xC0 : 0);
        int v = locoSpeedByte.getOrDefault(a, 0x80);
        return f29 ? x(0xEF, msb, a & 0xFF, 0x04, v, 0x00, 0, 0, 0, 0x00)
                : x(0xEF, msb, a & 0xFF, 0x04, v, 0x00, 0, 0, 0);
    }

    private static int addr(byte msb, byte lsb) { return ((msb & 0x3F) << 8) | (lsb & 0xFF); }

    // ---------------------------------------------------------------- Kodierhilfen (wie Spezifikation)

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

    static int[] le32(long v) { return new int[]{(int) v & 0xFF, (int) (v >> 8) & 0xFF, (int) (v >> 16) & 0xFF, (int) (v >> 24) & 0xFF}; }

    static int[] concat(int[] a, int[] b) {
        int[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02X ", v));
        return sb.toString().trim();
    }
}
