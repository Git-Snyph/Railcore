package at.schmoegl.railcore.z21;

import at.schmoegl.railcore.core.AbstractCommandStation;
import at.schmoegl.railcore.core.Contacts;
import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.StationCapabilities;
import at.schmoegl.railcore.core.StationCapabilities.Feature;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.util.TimeSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Treiber für Roco/Fleischmann Z21 und z21 über UDP (Port 21105, Lastenheft 8).
 * <ul>
 *   <li><b>Verbunden</b> meldet der Treiber erst, wenn die Z21 antwortet – UDP allein beweist nichts. Jede empfangene
 *       Nachricht ist ein Lebenszeichen; ob die Verbindung verloren ist, entscheidet der Kommunikations-Watchdog des
 *       Steuerungs-Executors. Antwortet die Z21 nach einer Pause von mehr als {@code relinkAfterMs} wieder, meldet der
 *       Treiber erneut „verbunden“ und erneuert die Anmeldung (die Z21 vergisst Clients nach 60 s ohne Verkehr).</li>
 *   <li><b>Fähigkeiten</b> werden erkannt, nicht vorausgesetzt: Firmware-Abfrage beim Verbinden; CAN-Melder und
 *       RailCom erst ab 1.30, F29–F31 erst ab 1.42. CAN-Melder werden erst nach Erkennung abonniert.
 *       Lok-Broadcast bei Fremdsteuerung gibt es an der Z21 nicht (Z21-Prüfprogramm), Endlagen meldet sie nicht.</li>
 *   <li><b>R-Bus wird nicht genutzt</b> (weder abonniert noch abgefragt): die Z21 spiegelt CAN-Belegung um ein Modul
 *       versetzt in die R-Bus-Daten, das ergäbe falsche Kontaktnummern (Lastenheft 8, „vor Nutzung zu klären“).</li>
 *   <li>Statusabfrage alle {@code pollMs} als Lebenszeichen und Anmeldungserhalt.</li>
 *   <li>Weichenausgänge werden nach {@code turnoutPulseMs} wieder abgeschaltet; beim Trennen sofort.</li>
 *   <li>Zeit ausschließlich über die {@link TimeSource}; Empfangszeitpunkte der Ereignisse stammen von ihr.</li>
 *   <li>Ein Sendefehler wird gemeldet <i>und</i> als Ausnahme weitergegeben: ein nicht gesendeter Befehl
 *       (auch ein Nothalt) darf nie als gesendet gelten.</li>
 * </ul>
 * Empfang und Zeitgeber laufen in einem eigenen Thread ({@code z21-empfang}); er legt Ereignisse nur über die
 * Listener ab (beim Steuerungs-Executor: in dessen Warteschlange). Für deterministische Tests lässt sich der Treiber
 * ohne Thread betreiben und mit {@link #service(long)} schrittweise bedienen.
 */
public class Z21CommandStation extends AbstractCommandStation {

    /** Pause, nach der eine neue Antwort als Wiederverbindung gilt (Standard). */
    public static final int DEFAULT_RELINK_AFTER_MS = 3_000;
    /** Längste Wartezeit des Empfangs-Threads, damit Zeitgeber pünktlich laufen. */
    private static final long SERVICE_SLICE_MS = 10;
    /** Kommt die erste Antwort später, wird die Anmeldung wiederholt (Pakete könnten verloren sein). */
    private static final long LOGIN_REPLY_WINDOW_NANOS = 500_000_000L;
    private static final int MAX_FIRMWARE_RETRIES = 3;

    private record PulseOff(long dueNanos, int address, TurnoutPosition position) {}

    private final String name;
    private final Z21Link.Factory linkFactory;
    private final TimeSource clock;
    private final int turnoutPulseMs;
    private final long pollNanos;
    private final long relinkNanos;
    private final boolean background;

    private volatile Z21Link link;
    private volatile boolean open;
    private volatile boolean reachable;
    private volatile long lastReceiveNanos;
    private volatile StationCapabilities capabilities = StationCapabilities.UNKNOWN;
    private long nextKeepAliveNanos;              // nur Empfangs-Thread bzw. service()
    private volatile long loginNanos;
    private int firmwareRetries;                  // nur Empfangs-Thread bzw. service()
    private final List<PulseOff> pulseOffs = new ArrayList<>();   // geschützt durch sich selbst
    private Thread receiver;

    private final Map<Integer, Boolean> canState = new ConcurrentHashMap<>();
    private final Map<Integer, TreeMap<Integer, int[]>> railcom = new ConcurrentHashMap<>();

    /** @param pollMs Abstand der Statusabfragen (Lebenszeichen für den Watchdog), z. B. 1000 */
    public Z21CommandStation(String host, int port, int turnoutPulseMs, int pollMs) {
        this("Z21 (" + host + ")", Z21Link.udp(host, port), TimeSource.SYSTEM, turnoutPulseMs, pollMs,
                DEFAULT_RELINK_AFTER_MS, true);
    }

    /**
     * @param background true = eigener Empfangs-Thread (Betrieb); false = Bedienung über {@link #service(long)} (Tests)
     */
    Z21CommandStation(String name, Z21Link.Factory linkFactory, TimeSource clock, int turnoutPulseMs, int pollMs,
                      int relinkAfterMs, boolean background) {
        if (turnoutPulseMs < 20 || turnoutPulseMs > 5_000) throw new IllegalArgumentException("Schaltimpuls 20–5000 ms: " + turnoutPulseMs);
        if (pollMs < 50 || pollMs > 20_000) throw new IllegalArgumentException("Statusabfrage 50–20000 ms: " + pollMs);
        if (relinkAfterMs < pollMs) throw new IllegalArgumentException("Wiederverbindungsgrenze muss mindestens eine Statusabfrage betragen");
        this.name = Objects.requireNonNull(name);
        this.linkFactory = Objects.requireNonNull(linkFactory);
        this.clock = Objects.requireNonNull(clock);
        this.turnoutPulseMs = turnoutPulseMs;
        this.pollNanos = pollMs * 1_000_000L;
        this.relinkNanos = relinkAfterMs * 1_000_000L;
        this.background = background;
    }

    @Override
    public String name() { return name; }

    @Override
    public StationCapabilities capabilities() { return capabilities; }

    /** Antwortet die Z21 (mindestens eine Nachricht seit dem Öffnen)? */
    public boolean isReachable() { return reachable; }

    @Override
    public synchronized void connect() throws IOException {
        if (open) return;
        link = linkFactory.open();
        open = true;
        reachable = false;
        capabilities = StationCapabilities.UNKNOWN;
        nextKeepAliveNanos = clock.nanoTime() + pollNanos;
        sendLogin();
        if (background) {
            receiver = new Thread(this::receiveLoop, "z21-empfang");
            receiver.setDaemon(true);
            receiver.start();
        }
    }

    /** Anmeldung: Identität, Firmware (Fähigkeiten), Fahr-/Schaltmeldungen, Status. CAN erst nach Erkennung. */
    private void sendLogin() {
        canState.clear();
        railcom.clear();
        loginNanos = clock.nanoTime();
        firmwareRetries = 0;
        send(Z21Protocol.getSerialNumber());
        send(Z21Protocol.getFirmwareVersion());
        send(Z21Protocol.setBroadcastFlags(Z21Protocol.BC_DRIVING_SWITCHING));
        send(Z21Protocol.getStatus());
    }

    @Override
    public void disconnect() {
        Thread t;
        boolean wasReachable;
        synchronized (this) {
            if (!open) return;
            flushPulseOffs();
            try { send(Z21Protocol.logoff()); } catch (RuntimeException ignored) { }
            open = false;
            wasReachable = reachable;
            reachable = false;
            capabilities = StationCapabilities.UNKNOWN;
            Z21Link l = link;
            if (l != null) l.close();
            t = receiver;
            receiver = null;
        }
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (wasReachable) fire(l -> l.onConnectionChanged(false, name()));
    }

    /** Beim Trennen: alle noch eingeschalteten Weichenausgänge sofort abschalten. */
    private void flushPulseOffs() {
        List<PulseOff> all;
        synchronized (pulseOffs) {
            all = List.copyOf(pulseOffs);
            pulseOffs.clear();
        }
        for (PulseOff p : all) {
            try { send(Z21Protocol.setTurnout(p.address(), p.position(), false)); }
            catch (RuntimeException e) { fireError("Weichenausgang " + p.address() + " konnte nicht abgeschaltet werden", e); }
        }
    }

    @Override
    public boolean isConnected() { return open && reachable; }

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

    /**
     * Zustände neu einlesen nach einer Wiederverbindung ohne Neuverbinden (Entscheidung 37): dieselben lesenden
     * Abfragen wie bei der Anmeldung – Firmware (daraus Fähigkeiten; mit CAN-Meldern folgt wie beim Anmelden die
     * CAN-Abfrage aller Melder) und Status. Der Vergleichsstand der Melder wird verworfen, damit die Antworten als
     * Meldung weitergereicht werden. Schaltet nichts.
     * TODO (Entscheidung 37, Punkt 2): LAN_CAN_DETECTOR als regelmäßige Melderabfrage (Fähigkeit
     * DETECTOR_STATUS_QUERY) erst nach dem nächsten aktiven Hardware-Lauf anbinden – vorher im Prüfprogramm klären,
     * ob nicht versorgte Module schweigen und ob alle Ports antworten.
     */
    @Override
    public void refreshState() {
        canState.clear();
        railcom.clear();
        send(Z21Protocol.getFirmwareVersion());
        send(Z21Protocol.getStatus());
    }

    @Override
    public void setTurnout(int address, TurnoutPosition position) {
        send(Z21Protocol.setTurnout(address, position, true));
        synchronized (pulseOffs) {
            pulseOffs.add(new PulseOff(clock.nanoTime() + turnoutPulseMs * 1_000_000L, address, position));
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

    /**
     * Sendet ein Paket. Nicht geöffnet → {@link IllegalStateException}; Sendefehler → gemeldet und als
     * {@link UncheckedIOException} weitergegeben (der Aufrufer darf den Befehl nicht als gesendet ansehen).
     */
    protected void send(byte[] data) {
        Z21Link l = link;
        if (!open || l == null) throw new IllegalStateException("Z21 nicht verbunden");
        try {
            l.send(data);
        } catch (IOException e) {
            fireError("Senden an Z21 fehlgeschlagen", e);
            throw new UncheckedIOException("Senden an Z21 fehlgeschlagen", e);
        }
    }

    // ---------------------------------------------------------------- Empfang und Zeitgeber

    private void receiveLoop() {
        while (open) {
            try {
                service(SERVICE_SLICE_MS);
            } catch (RuntimeException e) {
                if (open) fireError("Fehler im Z21-Empfang", e);
            }
        }
    }

    /**
     * Ein Durchgang: höchstens ein Paket empfangen (Wartezeit höchstens {@code timeoutMillis}, nie über den nächsten
     * Zeitgeber hinaus), danach fällige Zeitgeber (Statusabfrage, Abschalten der Weichenausgänge).
     * Im Betrieb ruft der Empfangs-Thread das auf; Tests ohne Thread rufen es direkt.
     *
     * @return true, wenn ein Paket verarbeitet wurde
     */
    boolean service(long timeoutMillis) {
        Z21Link l = link;
        if (!open || l == null) return false;
        long now = clock.nanoTime();
        long untilTimer = Math.max(0, (nextDueNanos() - now) / 1_000_000L);
        byte[] p = null;
        try {
            p = l.receive(Math.min(timeoutMillis, untilTimer));
        } catch (IOException e) {
            if (open) fireError("Empfang von der Z21 fehlgeschlagen", e);
        }
        if (p != null) onPacket(p);
        runTimers();
        return p != null;
    }

    private long nextDueNanos() {
        long next = nextKeepAliveNanos;
        synchronized (pulseOffs) {
            for (PulseOff p : pulseOffs) next = Math.min(next, p.dueNanos());
        }
        return next;
    }

    private void runTimers() {
        long now = clock.nanoTime();
        if (now >= nextKeepAliveNanos) {
            nextKeepAliveNanos += pollNanos;
            if (nextKeepAliveNanos <= now) nextKeepAliveNanos = now + pollNanos;
            try {
                send(Z21Protocol.getStatus());
                // Firmware-Antwort verloren (UDP)? Begrenzt wiederholen; bis dahin gilt keine Fähigkeit.
                if (reachable && !capabilities.detected() && firmwareRetries < MAX_FIRMWARE_RETRIES) {
                    firmwareRetries++;
                    send(Z21Protocol.getFirmwareVersion());
                }
            } catch (RuntimeException e) {
                fireError("Statusabfrage fehlgeschlagen", e);
            }
        }
        List<PulseOff> due = new ArrayList<>();
        synchronized (pulseOffs) {
            pulseOffs.removeIf(p -> {
                if (p.dueNanos() > now) return false;
                due.add(p);
                return true;
            });
        }
        for (PulseOff p : due) {
            try { send(Z21Protocol.setTurnout(p.address(), p.position(), false)); }
            catch (RuntimeException e) { fireError("Weichenausgang " + p.address() + " konnte nicht abgeschaltet werden", e); }
        }
    }

    private void onPacket(byte[] packet) {
        long now = clock.nanoTime();
        boolean first = !reachable;
        boolean afterSilence = !first && now - lastReceiveNanos > relinkNanos;
        lastReceiveNanos = now;
        fire(l -> l.onHeartbeat(now));
        if (first) {
            reachable = true;
            // Erste Antwort lange nach der Anmeldung: die Anmeldepakete sind womöglich verloren – wiederholen
            if (now - loginNanos > LOGIN_REPLY_WINDOW_NANOS) {
                try { sendLogin(); } catch (RuntimeException e) { fireError("Neuanmeldung an der Z21 fehlgeschlagen", e); }
            }
            fire(l -> l.onConnectionChanged(true, name()));
        } else if (afterSilence) {
            // Die Z21 kann uns abgemeldet haben: Anmeldung erneuern, Melder und Fähigkeiten neu erkennen
            capabilities = StationCapabilities.UNKNOWN;
            try { sendLogin(); } catch (RuntimeException e) { fireError("Neuanmeldung an der Z21 fehlgeschlagen", e); }
            fire(l -> l.onConnectionChanged(true, name() + " antwortet wieder"));
        }
        for (Z21Message m : Z21Protocol.decode(packet, packet.length)) handle(m, now);
    }

    private void handle(Z21Message m, long now) {
        switch (m) {
            case Z21Message.XFirmware f -> onFirmware(f.bcdVersion());
            case Z21Message.Diagnostic _ -> { } // nur für das Prüfprogramm
            case Z21Message.PowerState p -> fire(l -> l.onTrackPower(p.state()));
            case Z21Message.Loco lo -> fire(l -> l.onLocoState(lo.state()));
            case Z21Message.Turnout t -> fire(l -> l.onTurnoutPosition(t.address(), t.position()));
            case Z21Message.RBusFeedback _ -> { } // R-Bus wird nicht genutzt (siehe Klassenbeschreibung)
            case Z21Message.CanOccupancy co -> handleCanOccupancy(co, now);
            case Z21Message.CanRailCom rc -> handleCanRailCom(rc, now);
            case Z21Message.CvResult cv -> fire(l -> l.onCvResult(cv.cv(), cv.value()));
            case Z21Message.CvFailed cf -> fire(l -> l.onCvFailed(cf.reason()));
            case Z21Message.SerialNumber _ -> { }
            case Z21Message.FirmwareVersion _ -> { }
            case Z21Message.Unknown _ -> { }
        }
    }

    /** Fähigkeiten aus der Firmware-Version (BCD, 0x0143 = 1.43) nach Lastenheft 8. */
    static StationCapabilities capabilitiesFor(int bcdVersion) {
        EnumSet<Feature> f = EnumSet.noneOf(Feature.class);
        if (bcdVersion >= 0x0130) { f.add(Feature.CAN_DETECTORS); f.add(Feature.RAILCOM); }
        if (bcdVersion >= 0x0142) f.add(Feature.FUNCTIONS_F29_F31);
        return new StationCapabilities(Z21Protocol.formatBcdVersion(bcdVersion), f);
    }

    private void onFirmware(int bcd) {
        StationCapabilities caps = capabilitiesFor(bcd);
        capabilities = caps;
        if (caps.has(Feature.CAN_DETECTORS)) {
            try {
                send(Z21Protocol.setBroadcastFlags(Z21Protocol.BC_DRIVING_SWITCHING | Z21Protocol.BC_CAN_DETECTOR));
                send(Z21Protocol.canDetectorRequest(Z21Protocol.CAN_ALL));
            } catch (RuntimeException e) {
                fireError("CAN-Melder konnten nicht abonniert werden", e);
            }
        }
        fire(l -> l.onCapabilities(caps));
    }

    /** Kontaktnummer eines CAN-Melders: Modul wie am 10808 eingestellt, Port 0..7 = Eingang 1..8. */
    static int canContact(int module, int port) { return Contacts.of(module, port + 1); }

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
        var byIndex = railcom.computeIfAbsent(contact, k -> new TreeMap<>());
        List<Integer> all = new ArrayList<>();
        synchronized (byIndex) {
            byIndex.put(rc.index(), new int[]{rc.address1(), rc.address2()});
            for (int[] a : byIndex.values()) for (int x : a) if (x != 0) all.add(x);
        }
        List<Integer> addresses = List.copyOf(all);
        fire(l -> l.onRailComAddresses(contact, addresses, now));
    }
}
