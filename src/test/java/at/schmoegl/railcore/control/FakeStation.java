package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.AbstractCommandStation;
import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.util.TimeSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Zentrale für Tests: zeichnet Aufrufe nur auf, keine Hardware, kein Netzwerk. */
final class FakeStation extends AbstractCommandStation {

    final AtomicInteger emergencyStops = new AtomicInteger();
    final List<String> sent = new CopyOnWriteArrayList<>();
    /** Sendezeitpunkte parallel zu {@link #sent} (nur mit Uhr). */
    final List<Long> sentAt = new CopyOnWriteArrayList<>();
    volatile boolean failEmergencyStop;
    /** Wird bei jedem Fahrbefehl nach dem Aufzeichnen ausgeführt (Test-Eingriff). */
    volatile Runnable onDrive;
    /** Wird bei jedem Funktionsbefehl nach dem Aufzeichnen ausgeführt (Test-Eingriff). */
    volatile Runnable onFunction;
    private final TimeSource clock;

    FakeStation() { this(null); }

    FakeStation(TimeSource clock) { this.clock = clock; }

    @Override public String name() { return "Testzentrale"; }
    @Override public void connect() { }
    @Override public void disconnect() { }
    @Override public boolean isConnected() { return true; }
    @Override public void setTrackPower(boolean on) { record("power " + on); }

    @Override public void emergencyStop() {
        if (failEmergencyStop) throw new IllegalStateException("Senden fehlgeschlagen (Test)");
        emergencyStops.incrementAndGet();
    }

    @Override public void setLocoDrive(int address, int speedStep, SpeedSteps steps, Direction direction) {
        record("drive " + address + " " + speedStep + " " + direction);
        Runnable r = onDrive;
        if (r != null) r.run();
    }
    @Override public void emergencyStopLoco(int address, SpeedSteps steps, Direction direction) { record("estop " + address); }
    @Override public void setLocoFunction(int address, int function, boolean on) {
        record("fn " + address);
        Runnable r = onFunction;
        if (r != null) r.run();
    }
    @Override public void requestLocoState(int address) { }
    @Override public void setTurnout(int address, TurnoutPosition position) { record("turnout " + address + " " + position); }

    private void record(String s) {
        sent.add(s);
        if (clock != null) sentAt.add(clock.nanoTime());
    }

    // Ereignisse wie aus dem Empfangs-Thread der Zentrale
    void connected() { fire(l -> l.onConnectionChanged(true, name())); }
    void disconnected() { fire(l -> l.onConnectionChanged(false, name())); }
    void heartbeat(long ts) { fire(l -> l.onHeartbeat(ts)); }
    void feedback(int contact, boolean occupied, long ts) { fire(l -> l.onFeedback(contact, occupied, ts)); }
    void trackPower(TrackPower p) { fire(l -> l.onTrackPower(p)); }
}
