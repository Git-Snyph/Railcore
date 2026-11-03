package at.schmoegl.bahnsteuerung.z21;

import at.schmoegl.bahnsteuerung.core.AbstractCommandStation;
import at.schmoegl.bahnsteuerung.core.Contacts;
import at.schmoegl.bahnsteuerung.core.Direction;
import at.schmoegl.bahnsteuerung.core.SpeedSteps;
import at.schmoegl.bahnsteuerung.core.TurnoutPosition;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Treiber für Roco/Fleischmann Z21 und z21 über UDP (Port 21105).
 * <ul>
 *   <li>Abonniert Fahr-/Schaltinfos und R-Bus-Rückmelder.</li>
 *   <li>Sendet alle 20 s eine Statusabfrage, weil die Z21 Clients nach 60 s ohne Verkehr abmeldet.</li>
 *   <li>Weichenausgänge werden nach {@code turnoutPulseMs} automatisch wieder abgeschaltet.</li>
 *   <li>Rückmelder werden als Änderungen (pro Kontakt) gemeldet, nicht als ganze Modulbytes.</li>
 * </ul>
 */
public class Z21CommandStation extends AbstractCommandStation {

    private final InetSocketAddress target;
    private final int turnoutPulseMs;
    private final int pollMs;
    private volatile DatagramSocket socket;
    private volatile boolean connected;
    private volatile long lastReceiveNanos;
    private ScheduledExecutorService scheduler;
    private Thread receiver;
    /** Letzter bekannter Zustand der 20 R-Bus-Module, um Einzeländerungen zu erkennen. */
    private final int[] rbus = new int[20];
    private final boolean[] rbusKnown = new boolean[2];

    /** @param pollMs Abstand der Statusabfragen (Lebenszeichen für den Watchdog), z. B. 1000 */
    public Z21CommandStation(String host, int port, int turnoutPulseMs, int pollMs) {
        this.target = new InetSocketAddress(host, port);
        this.turnoutPulseMs = turnoutPulseMs;
        this.pollMs = pollMs;
    }

    @Override
    public String name() { return "Z21 (" + target.getHostString() + ")"; }

    @Override
    public synchronized void connect() throws IOException {
        if (connected) return;
        socket = new DatagramSocket();
        socket.setSoTimeout(1000);
        connected = true;
        lastReceiveNanos = System.nanoTime();
        receiver = new Thread(this::receiveLoop, "z21-empfang");
        receiver.setDaemon(true);
        receiver.start();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "z21-takt");
            t.setDaemon(true);
            return t;
        });
        send(Z21Protocol.getSerialNumber());
        send(Z21Protocol.setBroadcastFlags(Z21Protocol.BC_DRIVING_SWITCHING | Z21Protocol.BC_RBUS | Z21Protocol.BC_CAN_DETECTOR));
        send(Z21Protocol.getVersion());
        send(Z21Protocol.getStatus());
        send(Z21Protocol.rmBusGetData(0));
        send(Z21Protocol.rmBusGetData(1));
        send(Z21Protocol.canDetectorRequest(Z21Protocol.CAN_ALL));
        scheduler.scheduleAtFixedRate(this::keepAlive, pollMs, pollMs, TimeUnit.MILLISECONDS);
        fire(l -> l.onConnectionChanged(true, name()));
    }

    /** Statusabfrage: hält die Anmeldung an der Z21 aktiv und erzeugt Lebenszeichen für den Watchdog. */
    private void keepAlive() {
        try { send(Z21Protocol.getStatus()); } catch (RuntimeException e) { fireError("Statusabfrage fehlgeschlagen", e); }
    }

    @Override
    public synchronized void disconnect() {
        if (!connected) return;
        try { send(Z21Protocol.logoff()); } catch (RuntimeException ignored) { }
        connected = false;
        if (scheduler != null) scheduler.shutdownNow();
        if (socket != null) socket.close();
        if (receiver != null) receiver.interrupt();
        fire(l -> l.onConnectionChanged(false, name()));
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public void setTrackPower(boolean on) { send(on ? Z21Protocol.trackPowerOn() : Z21Protocol.trackPowerOff()); }

    @Override
    public void emergencyStop() { send(Z21Protocol.stopAll()); }

    @Override
    public void setLocoDrive(int address, int speedStep, SpeedSteps steps, Direction direction) {
        send(Z21Protocol.setLocoDrive(address, speedStep, steps, direction));
    }

    @Override
    public void emergencyStopLoco(int address, SpeedSteps steps, Direction direction) {
        send(Z21Protocol.emergencyStopLoco(address, steps, direction));
    }

    @Override
    public void setLocoFunction(int address, int function, boolean on) {
        send(Z21Protocol.setLocoFunction(address, function, on));
    }

    @Override
    public void requestLocoState(int address) { send(Z21Protocol.getLocoInfo(address)); }

    @Override
    public void setTurnout(int address, TurnoutPosition position) {
        send(Z21Protocol.setTurnout(address, position, true));
        ScheduledExecutorService s = scheduler;
        if (s != null && !s.isShutdown()) {
            s.schedule(() -> send(Z21Protocol.setTurnout(address, position, false)), turnoutPulseMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void readCv(int cv) { send(Z21Protocol.cvRead(cv)); }

    @Override
    public void writeCv(int cv, int value) { send(Z21Protocol.cvWrite(cv, value)); }

    @Override
    public void readCvOnMain(int address, int cv) { send(Z21Protocol.cvPomRead(address, cv)); }

    @Override
    public void writeCvOnMain(int address, int cv, int value) { send(Z21Protocol.cvPomWrite(address, cv, value)); }

    @Override
    public void readAccessoryCvOnMain(int decoderAddress, Integer output, int cv) {
        send(Z21Protocol.cvPomAccessory(decoderAddress, output, cv, false, 0));
    }

    @Override
    public void writeAccessoryCvOnMain(int decoderAddress, Integer output, int cv, int value) {
        send(Z21Protocol.cvPomAccessory(decoderAddress, output, cv, true, value));
    }

    protected void send(byte[] data) {
        DatagramSocket s = socket;
        if (!connected || s == null) throw new IllegalStateException("Z21 nicht verbunden");
        try {
            s.send(new DatagramPacket(data, data.length, target));
        } catch (IOException e) {
            fireError("Senden an Z21 fehlgeschlagen", e);
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[1500];
        while (connected) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                long now = System.nanoTime();
                lastReceiveNanos = now;
                fire(l -> l.onHeartbeat(now));
                for (Z21Message m : Z21Protocol.decode(p.getData(), p.getLength())) handle(m, now);
            } catch (SocketTimeoutException ignored) {
                // normal, Schleife prüft "connected"
            } catch (SocketException e) {
                if (connected) fireError("Z21-Verbindung unterbrochen", e);
            } catch (IOException | RuntimeException e) {
                fireError("Fehler beim Empfang von der Z21", e);
            }
        }
    }

    private void handle(Z21Message m, long now) {
        switch (m) {
            case Z21Message.PowerState p -> fire(l -> l.onTrackPower(p.state()));
            case Z21Message.Loco lo -> fire(l -> l.onLocoState(lo.state()));
            case Z21Message.Turnout t -> fire(l -> l.onTurnoutPosition(t.address(), t.position()));
            case Z21Message.RBusFeedback fb -> handleRBus(fb, now);
            case Z21Message.CanOccupancy co -> handleCanOccupancy(co, now);
            case Z21Message.CanRailCom rc -> handleCanRailCom(rc, now);
            case Z21Message.CvResult cv -> fire(l -> l.onCvResult(cv.cv(), cv.value()));
            case Z21Message.CvFailed cf -> fire(l -> l.onCvFailed(cf.reason()));
            case Z21Message.SerialNumber s -> { }
            case Z21Message.FirmwareVersion v -> { }
            case Z21Message.Unknown u -> { }
        }
    }

    /** Kontaktnummer eines CAN-Melders: Modul wie am 10808 eingestellt, Port 0..7 = Eingang 1..8. */
    static int canContact(int module, int port) { return Contacts.of(module, port + 1); }

    private final java.util.Map<Integer, Boolean> canState = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, java.util.TreeMap<Integer, int[]>> railcom = new java.util.concurrent.ConcurrentHashMap<>();

    private void handleCanOccupancy(Z21Message.CanOccupancy co, long now) {
        if (co.module() < 1 || co.port() > 7) return;
        int contact = canContact(co.module(), co.port());
        Boolean prev = canState.put(contact, co.occupied());
        if (prev == null || prev != co.occupied()) fire(l -> l.onFeedback(contact, co.occupied(), now));
        if (co.overload() > 0) fireError("Überlast an Melder " + Contacts.format(contact) + " (Stufe " + co.overload() + ")", null);
    }

    private void handleCanRailCom(Z21Message.CanRailCom rc, long now) {
        if (rc.module() < 1 || rc.port() > 7) return;
        int contact = canContact(rc.module(), rc.port());
        var byIndex = railcom.computeIfAbsent(contact, k -> new java.util.TreeMap<>());
        java.util.List<Integer> all = new java.util.ArrayList<>();
        synchronized (byIndex) {
            byIndex.put(rc.index(), new int[]{rc.address1(), rc.address2()});
            for (int[] a : byIndex.values()) for (int x : a) if (x != 0) all.add(x);
        }
        fire(l -> l.onRailComAddresses(contact, all, now));
    }

    private synchronized void handleRBus(Z21Message.RBusFeedback fb, long now) {
        if (fb.group() > 1) return;
        boolean first = !rbusKnown[fb.group()];
        rbusKnown[fb.group()] = true;
        for (int i = 0; i < 10; i++) {
            int module = fb.group() * 10 + i + 1;
            int newBits = fb.modules()[i] & 0xFF;
            int changed = first ? newBits : newBits ^ rbus[module - 1];
            rbus[module - 1] = newBits;
            for (int bit = 0; bit < 8; bit++) {
                if ((changed >> bit & 1) != 0) {
                    int contact = Contacts.of(module, bit + 1);
                    boolean occ = (newBits >> bit & 1) != 0;
                    fire(l -> l.onFeedback(contact, occ, now));
                }
            }
        }
    }
}
