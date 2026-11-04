package at.schmoegl.railcore.sim;

import at.schmoegl.railcore.core.AbstractCommandStation;
import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TurnoutPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Simulierte Zentrale: gibt Fahrbefehle mit zufälliger Befehlsverzögerung an die {@link SimLoco}s weiter
 * (Reihenfolge je Lok bleibt erhalten, ein späterer Befehl überholt nie einen früheren), meldet Melderereignisse
 * und sendet regelmäßig Lebenszeichen. Gleisspannung, Weichen und CVs werden nur aufgezeichnet.
 */
public final class SimCommandStation extends AbstractCommandStation {

    /** Ein ausgegebener Fahrbefehl: Ausgabe- und Ankunftszeitpunkt. */
    public record SentDrive(long issuedNanos, long arrivalNanos, int address, int step, Direction direction) {}

    private final SimWorld world;
    private final Distribution commandDelayMillis;
    private final long heartbeatNanos;
    private final List<SentDrive> drives = new ArrayList<>();
    private final Map<Integer, Long> lastArrival = new HashMap<>();
    private boolean connected;
    private int emergencyStops;

    public SimCommandStation(SimWorld world, Distribution commandDelayMillis, long heartbeatMillis) {
        this.world = Objects.requireNonNull(world);
        this.commandDelayMillis = Objects.requireNonNull(commandDelayMillis);
        this.heartbeatNanos = heartbeatMillis * 1_000_000L;
        world.setFeedbackSink(this::feedback);
    }

    public Distribution commandDelayMillis() { return commandDelayMillis; }

    public List<SentDrive> drives() { return List.copyOf(drives); }

    public int emergencyStops() { return emergencyStops; }

    @Override public String name() { return "Simulator"; }

    @Override
    public void connect() {
        connected = true;
        fire(l -> l.onConnectionChanged(true, name()));
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
        for (SimLoco l : world.locos()) deliver(l.address(), 0, Direction.FORWARD, false);
    }

    @Override
    public void setLocoDrive(int address, int speedStep, SpeedSteps steps, Direction direction) {
        deliver(address, speedStep, direction, true);
    }

    @Override
    public void emergencyStopLoco(int address, SpeedSteps steps, Direction direction) { deliver(address, 0, direction, true); }

    @Override public void setLocoFunction(int address, int function, boolean on) { }

    @Override public void requestLocoState(int address) { }

    @Override public void setTurnout(int address, TurnoutPosition position) { }

    /** Melderereignis aus der Welt an die Listener (Empfangszeitpunkt = jetzt). */
    public void feedback(int contact, boolean occupied) {
        long ts = world.now();
        fire(l -> l.onFeedback(contact, occupied, ts));
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
        long ts = world.now();
        fire(l -> l.onHeartbeat(ts));
        world.after(heartbeatNanos, this::heartbeat);
    }
}
