package at.schmoegl.railcore.train;

/**
 * Physikalisches Fahrmodell: Die tatsächliche Geschwindigkeit folgt der Sollgeschwindigkeit mit begrenzter
 * Beschleunigung bzw. Verzögerung (so wie der Decoder mit CV3/CV4 nachregelt).
 */
public final class MotionModel {
    private double speed;       // aktuelle Geschwindigkeit mm/s (>= 0)
    private double target;      // Sollgeschwindigkeit mm/s
    private double accel;
    private double decel;

    public MotionModel(double accel, double decel) {
        this.accel = accel;
        this.decel = decel;
    }

    public void setTarget(double mmPerSec) { this.target = Math.max(0, mmPerSec); }

    /** Sofortiger Halt (Nothalt). */
    public void stopImmediately() { speed = 0; target = 0; }

    public double speed() { return speed; }

    public double target() { return target; }

    public void setRates(double accel, double decel) { this.accel = accel; this.decel = decel; }

    /** Zustand um dt Sekunden fortschreiben; liefert die zurückgelegte Strecke in mm. */
    public double advance(double dt) {
        double v0 = speed;
        double rate = target > speed ? accel : decel;
        double dv = target - speed;
        double tReach = Math.abs(dv) / rate;
        if (tReach >= dt) {
            double v1 = speed + Math.signum(dv) * rate * dt;
            speed = v1;
            return (v0 + v1) / 2 * dt;
        }
        // Soll wird innerhalb von dt erreicht
        double d1 = (v0 + target) / 2 * tReach;
        speed = target;
        return d1 + target * (dt - tReach);
    }

    /** Bremsweg von der aktuellen Geschwindigkeit. */
    public double brakingDistance() { return speed * speed / (2 * decel); }
}
