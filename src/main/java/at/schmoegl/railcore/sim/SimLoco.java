package at.schmoegl.railcore.sim;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.train.SpeedProfile;

import java.util.Objects;

/**
 * Simulierter Lokdecoder mit „wahrem“ Fahrverhalten auf einer Wegkoordinate (mm).
 * <ul>
 *   <li>Software Motion Control (Entscheidung 8): CV3/CV4 = 0, der Decoder springt ohne eigene Rampe auf die
 *       Geschwindigkeit der Fahrstufe; die Trägheit kommt aus den Befehlsfolgen der Steuerung.</li>
 *   <li>Wahre Geschwindigkeit = {@code speedFactor} × Profilgeschwindigkeit; der Faktor weicht absichtlich vom
 *       eingemessenen Profil ab (Streuung des Fahrprofils), die Steuerung kennt ihn nicht.</li>
 *   <li>Fahrstufe 0: Auslauf durch Schwungmasse und Motorbremse, exponentiell mit Zeitkonstante
 *       {@code runoutTauSeconds} (Auslaufweg = v · τ).</li>
 * </ul>
 * Bewegung wird analytisch gerechnet (keine Zeitschritte); nur in Fahrtrichtung steigender Wegkoordinate.
 * Nicht thread-sicher; gehört dem Simulationsablauf.
 */
public final class SimLoco {

    private static final double STANDSTILL_MM_S = 0.01;

    private final int address;
    private final SpeedProfile profile;
    private final double speedFactor;
    private final double runoutTauSeconds;

    private long segStart;
    private double segPos;
    private double segSpeed;
    private boolean decaying;
    private int lastStep;

    public SimLoco(int address, SpeedProfile profile, double speedFactor, double runoutTauSeconds) {
        if (!(speedFactor > 0.5 && speedFactor < 2)) throw new IllegalArgumentException("Geschwindigkeitsfaktor unplausibel");
        if (!(runoutTauSeconds > 0)) throw new IllegalArgumentException("Auslauf-Zeitkonstante muss positiv sein");
        this.address = address;
        this.profile = Objects.requireNonNull(profile);
        this.speedFactor = speedFactor;
        this.runoutTauSeconds = runoutTauSeconds;
    }

    public int address() { return address; }

    public double speedFactor() { return speedFactor; }

    public double runoutTauSeconds() { return runoutTauSeconds; }

    public int lastStep() { return lastStep; }

    /** Lok an eine Stelle setzen, fahrend mit der angegebenen Fahrstufe (Anfangszustand eines Laufs). */
    public void place(long nowNanos, double positionMm, int step, Direction dir) {
        segStart = nowNanos;
        segPos = positionMm;
        segSpeed = speedFactor * profile.speed(dir, step);
        decaying = false;
        lastStep = step;
    }

    /** Fahrbefehl kommt am Decoder an. */
    public void drive(long nowNanos, int step, Direction dir) {
        rebase(nowNanos);
        lastStep = step;
        double v = speedFactor * profile.speed(dir, step);
        if (v > 0) {
            segSpeed = v;
            decaying = false;
        } else if (segSpeed > 0) {
            decaying = true;
        }
    }

    public double position(long t) {
        double dt = Math.max(0, t - segStart) / 1e9;
        if (decaying) return segPos + segSpeed * runoutTauSeconds * (1 - Math.exp(-dt / runoutTauSeconds));
        return segPos + segSpeed * dt;
    }

    public double speed(long t) {
        if (!decaying) return segSpeed;
        double v = segSpeed * Math.exp(-Math.max(0, t - segStart) / 1e9 / runoutTauSeconds);
        return v < STANDSTILL_MM_S ? 0 : v;
    }

    public boolean stopped(long t) { return speed(t) == 0; }

    /** Endposition nach vollständigem Auslauf (bzw. aktuelle Position bei Fahrt). */
    public double restPosition(long t) {
        if (decaying) return segPos + segSpeed * runoutTauSeconds;
        return segSpeed == 0 ? segPos : Double.POSITIVE_INFINITY;
    }

    /** Zeitpunkt (ns, aufgerundet), zu dem die Spitze x erreicht – ohne weitere Befehle; sonst Long.MAX_VALUE. */
    public long timeOfPosition(double x, long fromNanos) {
        if (position(fromNanos) >= x) return fromNanos;
        if (segSpeed <= 0) return Long.MAX_VALUE;
        double dx = x - segPos;
        double dt;
        if (decaying) {
            double reach = segSpeed * runoutTauSeconds;
            if (dx >= reach * (1 - 1e-12)) return Long.MAX_VALUE;
            dt = -runoutTauSeconds * Math.log(1 - dx / reach);
        } else {
            dt = dx / segSpeed;
        }
        long t = segStart + (long) Math.ceil(dt * 1e9);
        if (speed(t) == 0 && position(t) < x) return Long.MAX_VALUE;
        return Math.max(t, fromNanos);
    }

    private void rebase(long now) {
        double p = position(now);
        double v = speed(now);
        segPos = p;
        segSpeed = v;
        segStart = now;
        if (v == 0) decaying = false;
    }
}
