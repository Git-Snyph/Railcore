package at.schmoegl.railcore.sim;

import at.schmoegl.railcore.core.AbstractCommandStation;
import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.DistrictPower;
import at.schmoegl.railcore.core.LocoState;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.StationCapabilities;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.core.TurnoutPosition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Simulierte Zentrale: gibt Fahrbefehle mit zufälliger Befehlsverzögerung an die {@link SimLoco}s weiter
 * (Reihenfolge je Lok bleibt erhalten, ein späterer Befehl überholt nie einen früheren), meldet Melderereignisse
 * und sendet regelmäßig Lebenszeichen. Gleisspannung, Weichen und CVs werden nur aufgezeichnet.
 * <p>
 * <b>Störungen</b> (Lastenheft 10, „auf Knopfdruck oder per Skript“; Skript: {@link SimFaultScript}). Alle sind
 * deterministisch (Simulationsuhr, eigener Seed für Paketverlust) und ab Werk ausgeschaltet – ohne Störung verhält
 * sich die Zentrale genau wie bisher:
 * <ul>
 *   <li>{@link #delayReplies}: Verbindung verspätet – die Zentrale meldet eine Zeit lang nichts (keine
 *       Lebenszeichen), danach kommen die zurückgehaltenen Meldungen auf einmal.</li>
 *   <li>{@link #loseLink}: Verbindung verloren – beide Richtungen tot, nichts wird nachgeliefert; danach wieder
 *       Lebenszeichen.</li>
 *   <li>{@link #setPacketLoss}: Paketverlust mit einstellbarem Anteil, beide Richtungen (auch Nothalt).</li>
 *   <li>{@link #districtPower}: Kurzschluss, Überlast, Aus und Ein je Booster-Bezirk (Meldung wie Zentrale/Booster);
 *       Loks im Bezirk halten, Melder im Bezirk verlieren die Versorgung.</li>
 *   <li>{@link #reportTrackPower}: Gleisspannung der Zentrale (aus/Kurzschluss: alle Melder ohne Versorgung).</li>
 *   <li>{@link #flicker}: flackernder Melder.</li>
 *   <li>{@link #cutDetectorSupply}/{@link #restoreDetectorSupply}: Ausfall der 10808-Versorgung für einzelne Melder
 *       oder ganze CAN-Bereiche; bei Rückkehr meldet der Melder seinen Zustand wie nach dem Einschalten.</li>
 *   <li>{@link #foreignDrive}: Lok fährt anders als gesendet (Fremdsteuerung einer echten Adresse).</li>
 * </ul>
 * Optional (ab Werk aus): Fähigkeiten melden ({@link #setCapabilities}), Lokabfragen beantworten
 * ({@link #setAnswerQueries}), Lok-Broadcast bei Fremdsteuerung ({@link #setLocoBroadcast}). Die Melderabfrage
 * ({@link #requestDetectorStatus}) beantwortet die Zentrale für alle versorgten Melder.
 */
public final class SimCommandStation extends AbstractCommandStation {

    /** Ein ausgegebener Fahrbefehl: Ausgabe- und Ankunftszeitpunkt. */
    public record SentDrive(long issuedNanos, long arrivalNanos, int address, int step, Direction direction) {}

    /** Antwortzeit der Zentrale auf Abfragen (fest, damit Störungsläufe nicht die Zufallsfolge der Welt verändern). */
    private static final long REPLY_NANOS = 5_000_000L;

    private record Remembered(int step, Direction direction, SpeedSteps steps) {}

    private final SimWorld world;
    private final Distribution commandDelayMillis;
    private final long heartbeatNanos;
    private final List<SentDrive> drives = new ArrayList<>();
    private final Map<Integer, Long> lastArrival = new HashMap<>();
    private boolean connected;
    private int emergencyStops;
    private int emergencyStopsDelivered;

    // ---- Störungen
    private long repliesHeldUntil = Long.MIN_VALUE;
    private final List<Runnable> heldReplies = new ArrayList<>();
    private long linkDeadUntil = Long.MIN_VALUE;
    private double packetLoss;
    private Random lossRandom = new Random(0);
    private int lostToStation, lostFromStation;
    private final Map<Integer, Remembered> memory = new HashMap<>();
    private final Map<Integer, Boolean> contacts = new TreeMap<>();
    private final Set<Integer> cutContacts = new HashSet<>();
    private final Map<Integer, Set<Integer>> districtLocos = new LinkedHashMap<>();
    private final Map<Integer, Set<Integer>> districtContacts = new LinkedHashMap<>();
    private final Map<Integer, DistrictPower> districts = new TreeMap<>();
    private TrackPower trackPower = TrackPower.ON;
    private StationCapabilities capabilities;
    private boolean answerQueries;
    private boolean locoBroadcast;

    public SimCommandStation(SimWorld world, Distribution commandDelayMillis, long heartbeatMillis) {
        this.world = Objects.requireNonNull(world);
        this.commandDelayMillis = Objects.requireNonNull(commandDelayMillis);
        this.heartbeatNanos = heartbeatMillis * 1_000_000L;
        world.setFeedbackSink(this::feedback);
    }

    public Distribution commandDelayMillis() { return commandDelayMillis; }

    /** Tatsächlich bei den Loks angekommene Fahrbefehle (verlorene fehlen). */
    public List<SentDrive> drives() { return List.copyOf(drives); }

    /** Aufrufe des Nothalts durch die Steuerung (auch solche, die unterwegs verloren gingen). */
    public int emergencyStops() { return emergencyStops; }

    /** Nothalte, die die Zentrale tatsächlich erreicht haben. */
    public int emergencyStopsDelivered() { return emergencyStopsDelivered; }

    /** Pakete der Steuerung, die durch Paketverlust oder tote Verbindung verloren gingen. */
    public int lostToStation() { return lostToStation; }

    /** Meldungen der Zentrale, die verloren gingen. */
    public int lostFromStation() { return lostFromStation; }

    @Override public String name() { return "Simulator"; }

    @Override
    public void connect() {
        connected = true;
        fire(l -> l.onConnectionChanged(true, name()));
        if (capabilities != null) { StationCapabilities c = capabilities; fire(l -> l.onCapabilities(c)); }
        heartbeat();
    }

    @Override
    public void disconnect() {
        if (!connected) return;
        connected = false;
        fire(l -> l.onConnectionChanged(false, name()));
    }

    @Override public boolean isConnected() { return connected; }

    @Override public void setTrackPower(boolean on) { }

    @Override
    public void emergencyStop() {
        emergencyStops++;
        if (!reachesStation()) return;
        emergencyStopsDelivered++;
        memory.replaceAll((a, r) -> new Remembered(0, r.direction(), r.steps()));
        for (SimLoco l : world.locos()) deliver(l.address(), 0, Direction.FORWARD, false);
    }

    @Override
    public void setLocoDrive(int address, int speedStep, SpeedSteps steps, Direction direction) {
        if (!reachesStation()) return;
        memory.put(address, new Remembered(speedStep, direction, steps));
        deliver(address, speedStep, direction, true);
    }

    @Override
    public void emergencyStopLoco(int address, SpeedSteps steps, Direction direction) {
        if (!reachesStation()) return;
        memory.put(address, new Remembered(0, direction, steps));
        deliver(address, 0, direction, true);
    }

    @Override public void setLocoFunction(int address, int function, boolean on) { }

    /** Beantwortet die Abfrage nur, wenn {@link #setAnswerQueries} eingeschaltet ist (Speicher der Zentrale). */
    @Override
    public void requestLocoState(int address) {
        if (!answerQueries || !reachesStation()) return;
        world.after(REPLY_NANOS, () -> reportLoco(address));
    }

    @Override public void setTurnout(int address, TurnoutPosition position) { }

    /** Melderabfrage: alle versorgten Melder melden ihren Zustand, auch unverändert. */
    @Override
    public void requestDetectorStatus() {
        if (!reachesStation()) return;
        world.after(REPLY_NANOS, () -> {
            for (var e : contacts.entrySet()) {
                int c = e.getKey();
                boolean occupied = e.getValue();
                if (powered(c)) toController(() -> { long ts = world.now(); fire(l -> l.onFeedback(c, occupied, ts)); });
            }
        });
    }

    /**
     * Zustände neu einlesen: wie beim Verbinden meldet die Zentrale Fähigkeiten (falls eingestellt), Gleisspannung
     * und den Zustand aller versorgten Melder.
     */
    @Override
    public void refreshState() {
        if (!reachesStation()) return;
        world.after(REPLY_NANOS, () -> {
            if (capabilities != null) { StationCapabilities c = capabilities; toController(() -> fire(l -> l.onCapabilities(c))); }
            TrackPower p = trackPower;
            toController(() -> fire(l -> l.onTrackPower(p)));
            for (var e : contacts.entrySet()) {
                int c = e.getKey();
                boolean occupied = e.getValue();
                if (powered(c)) toController(() -> { long ts = world.now(); fire(l -> l.onFeedback(c, occupied, ts)); });
            }
        });
    }

    @Override public StationCapabilities capabilities() { return capabilities == null ? StationCapabilities.UNKNOWN : capabilities; }

    /** Melderereignis aus der Welt an die Listener (Empfangszeitpunkt = jetzt). */
    public void feedback(int contact, boolean occupied) {
        contacts.put(contact, occupied);
        if (!powered(contact)) return;                       // 10808 ohne Versorgung meldet nichts
        toController(() -> { long ts = world.now(); fire(l -> l.onFeedback(contact, occupied, ts)); });
    }

    // ================================================================== Einstellungen

    /** Fähigkeiten, die die Zentrale beim Verbinden meldet (ab Werk keine Meldung). */
    public void setCapabilities(StationCapabilities c) {
        capabilities = Objects.requireNonNull(c);
        if (connected) fire(l -> l.onCapabilities(c));
    }

    /** Lokabfragen beantworten (mit dem Speicher der Zentrale). */
    public void setAnswerQueries(boolean on) { answerQueries = on; }

    /** Lokmeldung an die Steuerung bei Fremdsteuerung (wie eine Zentrale mit Lok-Broadcast; die Z21 tut das nicht). */
    public void setLocoBroadcast(boolean on) { locoBroadcast = on; }

    /** Melder mit Anfangszustand bekannt machen (für Melderabfrage und Rückkehr der Versorgung). */
    public void defineContacts(Collection<Integer> list, boolean occupied) {
        for (int c : list) contacts.put(c, occupied);
    }

    /** Wahrer Rohzustand eines Melders (null = unbekannt). */
    public Boolean contactState(int contact) { return contacts.get(contact); }

    /** Loks und Melder einem Booster-Bezirk zuordnen (Bezirk startet eingeschaltet). */
    public void assignDistrict(int district, Collection<Integer> locoAddresses, Collection<Integer> contactList) {
        districtLocos.computeIfAbsent(district, k -> new TreeSet<>()).addAll(locoAddresses);
        districtContacts.computeIfAbsent(district, k -> new TreeSet<>()).addAll(contactList);
        districts.putIfAbsent(district, DistrictPower.ON);
    }

    // ================================================================== Störungen

    /** Verbindung verspätet: {@code millis} lang keine Meldung der Zentrale, danach alles Zurückgehaltene auf einmal. */
    public void delayReplies(long millis) {
        long until = world.now() + millis * 1_000_000L;
        repliesHeldUntil = Math.max(repliesHeldUntil, until);
        world.at(until, this::releaseHeld);
    }

    /** Verbindung verloren: {@code millis} lang gehen Pakete in beide Richtungen verloren (nichts wird nachgeliefert). */
    public void loseLink(long millis) {
        linkDeadUntil = Math.max(linkDeadUntil, world.now() + millis * 1_000_000L);
    }

    /** Paketverlust in beide Richtungen; {@code fraction} 0 = aus, 1 = alles. */
    public void setPacketLoss(double fraction, long seed) {
        if (!(fraction >= 0 && fraction <= 1)) throw new IllegalArgumentException("Anteil Paketverlust außerhalb 0–1: " + fraction);
        packetLoss = fraction;
        lossRandom = SimWorld.randomForRun(seed);
    }

    /**
     * Zustand eines Booster-Bezirks setzen und melden. Ohne Versorgung halten die Loks im Bezirk (Fahrstrom weg) und
     * die Melder im Bezirk melden nichts mehr; bei Rückkehr melden sie ihren Zustand wie nach dem Einschalten.
     */
    public void districtPower(int district, DistrictPower state) {
        Set<Integer> before = poweredContacts();
        districts.put(district, Objects.requireNonNull(state));
        if (!state.powered()) {
            for (int a : districtLocos.getOrDefault(district, Set.of())) {
                SimLoco l = world.loco(a);
                if (l != null) l.drive(world.now(), 0, Direction.FORWARD);
            }
        }
        toController(() -> { long ts = world.now(); fire(l -> l.onPowerDistrict(district, state, ts)); });
        reportReturned(before);
    }

    /** Gleisspannung der Zentrale melden; aus, Kurzschluss und Programmiergleis nehmen allen Meldern die Versorgung. */
    public void reportTrackPower(TrackPower p) {
        Set<Integer> before = poweredContacts();
        trackPower = Objects.requireNonNull(p);
        if (p == TrackPower.OFF || p == TrackPower.SHORT_CIRCUIT || p == TrackPower.PROGRAMMING)
            for (SimLoco l : world.locos()) l.drive(world.now(), 0, Direction.FORWARD);
        toController(() -> fire(l -> l.onTrackPower(p)));
        reportReturned(before);
    }

    /** Melder wechselt {@code toggles}-mal im Abstand {@code periodMillis} und endet im Ausgangszustand (gerade Zahl). */
    public void flicker(int contact, int toggles, long periodMillis) {
        boolean start = Boolean.TRUE.equals(contacts.get(contact));
        for (int i = 1; i <= toggles; i++) {
            boolean v = (i % 2 == 1) != start;
            world.after(i * periodMillis * 1_000_000L, () -> feedback(contact, v));
        }
    }

    /** Ausfall der 10808-Versorgung für einzelne Melder bzw. einen ganzen CAN-Bereich: sie melden nichts mehr. */
    public void cutDetectorSupply(Collection<Integer> list) { cutContacts.addAll(list); }

    /** Versorgung zurück: die Melder melden ihren aktuellen Zustand wie nach dem Einschalten. */
    public void restoreDetectorSupply(Collection<Integer> list) {
        Set<Integer> before = poweredContacts();
        cutContacts.removeAll(list);
        reportReturned(before);
    }

    /**
     * Fremdsteuerung einer echten Adresse: Die Zentrale führt einen Fahrbefehl aus, der nicht von RailCore kommt (z. B.
     * Handregler direkt auf der Lokadresse). Mit {@link #setLocoBroadcast} erfährt die Steuerung das sofort, sonst erst
     * bei der nächsten Lokabfrage.
     */
    public void foreignDrive(int address, int step, Direction direction) {
        Remembered r = memory.get(address);
        memory.put(address, new Remembered(step, direction, r == null ? SpeedSteps.S128 : r.steps()));
        SimLoco l = world.loco(address);
        if (l != null) l.drive(world.now(), step, direction);
        if (locoBroadcast) reportLoco(address);
    }

    // ================================================================== intern

    private void reportLoco(int address) {
        Remembered r = memory.get(address);
        if (r == null) return;
        LocoState s = new LocoState(address, r.step(), r.steps(), r.direction(), 0L);
        toController(() -> fire(l -> l.onLocoState(s)));
    }

    private boolean powered(int contact) {
        if (cutContacts.contains(contact)) return false;
        if (trackPower == TrackPower.OFF || trackPower == TrackPower.SHORT_CIRCUIT || trackPower == TrackPower.PROGRAMMING)
            return false;
        for (var e : districtContacts.entrySet())
            if (e.getValue().contains(contact) && !districts.getOrDefault(e.getKey(), DistrictPower.ON).powered()) return false;
        return true;
    }

    private Set<Integer> poweredContacts() {
        Set<Integer> s = new TreeSet<>();
        for (int c : contacts.keySet()) if (powered(c)) s.add(c);
        return s;
    }

    /** Melder, die wieder versorgt sind, melden ihren Zustand (Einschaltmeldung). */
    private void reportReturned(Set<Integer> poweredBefore) {
        for (var e : contacts.entrySet()) {
            int c = e.getKey();
            boolean occupied = e.getValue();
            if (!poweredBefore.contains(c) && powered(c))
                toController(() -> { long ts = world.now(); fire(l -> l.onFeedback(c, occupied, ts)); });
        }
    }

    /** Erreicht ein Paket der Steuerung die Zentrale? */
    private boolean reachesStation() {
        if (world.now() < linkDeadUntil || lose()) { lostToStation++; return false; }
        return true;
    }

    /** Meldung der Zentrale an die Steuerung: verloren, zurückgehalten oder sofort. */
    private void toController(Runnable event) {
        if (world.now() < linkDeadUntil || lose()) { lostFromStation++; return; }
        if (world.now() < repliesHeldUntil) { heldReplies.add(event); return; }
        event.run();
    }

    private boolean lose() { return packetLoss > 0 && lossRandom.nextDouble() < packetLoss; }

    private void releaseHeld() {
        if (world.now() < repliesHeldUntil) return;          // inzwischen verlängert
        List<Runnable> held = List.copyOf(heldReplies);
        heldReplies.clear();
        held.forEach(Runnable::run);
    }

    private void deliver(int address, int step, Direction dir, boolean record) {
        long now = world.now();
        long arrival = now + Math.round(commandDelayMillis.sample(world.random()) * 1e6);
        arrival = Math.max(arrival, lastArrival.getOrDefault(address, Long.MIN_VALUE));
        lastArrival.put(address, arrival);
        if (record) drives.add(new SentDrive(now, arrival, address, step, dir));
        SimLoco loco = world.loco(address);
        if (loco != null) world.at(arrival, () -> loco.drive(world.now(), step, dir));
    }

    private void heartbeat() {
        if (!connected) return;
        toController(() -> { long ts = world.now(); fire(l -> l.onHeartbeat(ts)); });
        world.after(heartbeatNanos, this::heartbeat);
    }
}
