package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.AbstractCommandStation;
import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.core.TurnoutPosition;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Zentrale für Tests: zeichnet Aufrufe nur auf, keine Hardware, kein Netzwerk. */
final class FakeStation extends AbstractCommandStation {

    final AtomicInteger emergencyStops = new AtomicInteger();
    final List<String> sent = new CopyOnWriteArrayList<>();
    volatile boolean failEmergencyStop;

    @Override public String name() { return "Testzentrale"; }
    @Override public void connect() { }
    @Override public void disconnect() { }
    @Override public boolean isConnected() { return true; }
    @Override public void setTrackPower(boolean on) { sent.add("power " + on); }

    @Override public void emergencyStop() {
        if (failEmergencyStop) throw new IllegalStateException("Senden fehlgeschlagen (Test)");
        emergencyStops.incrementAndGet();
    }

    @Override public void setLocoDrive(int address, int speedStep, SpeedSteps steps, Direction direction) {
        sent.add("drive " + address + " " + speedStep + " " + direction);
    }
    @Override public void emergencyStopLoco(int address, SpeedSteps steps, Direction direction) { sent.add("estop " + address); }
    @Override public void setLocoFunction(int address, int function, boolean on) { sent.add("fn " + address); }
    @Override public void requestLocoState(int address) { }
    @Override public void setTurnout(int address, TurnoutPosition position) { sent.add("turnout " + address + " " + position); }

    // Ereignisse wie aus dem Empfangs-Thread der Zentrale
    void connected() { fire(l -> l.onConnectionChanged(true, name())); }
    void disconnected() { fire(l -> l.onConnectionChanged(false, name())); }
    void heartbeat(long ts) { fire(l -> l.onHeartbeat(ts)); }
    void feedback(int contact, boolean occupied, long ts) { fire(l -> l.onFeedback(contact, occupied, ts)); }
    void trackPower(TrackPower p) { fire(l -> l.onTrackPower(p)); }
}
