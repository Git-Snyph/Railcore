package at.schmoegl.bahnsteuerung.train;

import at.schmoegl.bahnsteuerung.core.Direction;
import at.schmoegl.bahnsteuerung.core.SpeedSteps;

import java.util.Arrays;

/**
 * Eingemessenes Fahrverhalten einer Lok bzw. eines Zuges.
 * <ul>
 *   <li>Geschwindigkeit je Fahrstufe in Modell-mm/s, getrennt für vorwärts und rückwärts.
 *       Nicht gemessene Stufen (NaN) werden linear zwischen gemessenen Stufen interpoliert.</li>
 *   <li>Beschleunigung und Verzögerung in mm/s² (ergibt sich aus Decoder-CV3/CV4 und Fahrzeugmasse).</li>
 * </ul>
 */
public final class SpeedProfile {

    private final SpeedSteps steps;
    private final double[] forward;
    private final double[] reverse;
    private double accelMmS2;
    private double decelMmS2;
    private boolean calibrated;

    public SpeedProfile(SpeedSteps steps) {
        this.steps = steps;
        this.forward = new double[steps.maxStep + 1];
        this.reverse = new double[steps.maxStep + 1];
        Arrays.fill(forward, Double.NaN);
        Arrays.fill(reverse, Double.NaN);
        forward[0] = reverse[0] = 0;
        this.accelMmS2 = 150;
        this.decelMmS2 = 150;
    }

    /**
     * Vorläufiges lineares Profil aus der Vorbild-Höchstgeschwindigkeit, bis die Lok eingemessen ist.
     * Bewusst vorsichtig: Annahme, dass die Lok bei Höchststufe etwas schneller fährt als Vmax.
     */
    public static SpeedProfile linear(SpeedSteps steps, double maxMmPerSec, double accel, double decel) {
        SpeedProfile p = new SpeedProfile(steps);
        p.set(Direction.FORWARD, steps.maxStep, maxMmPerSec);
        p.set(Direction.REVERSE, steps.maxStep, maxMmPerSec);
        p.accelMmS2 = accel;
        p.decelMmS2 = decel;
        return p;
    }

    public SpeedSteps steps() { return steps; }

    public double accel() { return accelMmS2; }

    public double decel() { return decelMmS2; }

    public void setAccel(double mmS2) { this.accelMmS2 = mmS2; }

    public void setDecel(double mmS2) { this.decelMmS2 = mmS2; }

    public boolean isCalibrated() { return calibrated; }

    public void setCalibrated(boolean c) { this.calibrated = c; }

    public void set(Direction dir, int step, double mmPerSec) { table(dir)[step] = mmPerSec; }

    public double measured(Direction dir, int step) { return table(dir)[step]; }

    /** Geschwindigkeit bei dieser Fahrstufe (interpoliert). */
    public double speed(Direction dir, int step) {
        if (step <= 0) return 0;
        double[] t = table(dir);
        if (!Double.isNaN(t[step])) return t[step];
        int lo = step - 1;
        while (lo > 0 && Double.isNaN(t[lo])) lo--;
        int hi = step + 1;
        while (hi < t.length && Double.isNaN(t[hi])) hi++;
        if (hi >= t.length) {
            // über der höchsten gemessenen Stufe: Steigung der letzten beiden Messpunkte fortsetzen
            if (lo == 0) return 0;
            int lo2 = lo - 1;
            while (lo2 > 0 && Double.isNaN(t[lo2])) lo2--;
            double slope = (t[lo] - t[lo2]) / (lo - lo2);
            return t[lo] + slope * (step - lo);
        }
        return t[lo] + (t[hi] - t[lo]) * (step - lo) / (double) (hi - lo);
    }

    /** Höchste Fahrstufe, deren Geschwindigkeit die Vorgabe nicht überschreitet. */
    public int stepFor(Direction dir, double mmPerSec) {
        if (mmPerSec <= 0) return 0;
        int best = 0;
        for (int s = 1; s <= steps.maxStep; s++) {
            if (speed(dir, s) <= mmPerSec + 1e-9) best = s; else break;
        }
        return best;
    }

    /** Bremsweg aus Geschwindigkeit v bis zum Stillstand. */
    public double brakingDistance(double v) { return v * v / (2 * decelMmS2); }

    public double maxSpeed(Direction dir) { return speed(dir, steps.maxStep); }

    public SpeedProfile copy() {
        SpeedProfile p = new SpeedProfile(steps);
        System.arraycopy(forward, 0, p.forward, 0, forward.length);
        System.arraycopy(reverse, 0, p.reverse, 0, reverse.length);
        p.accelMmS2 = accelMmS2;
        p.decelMmS2 = decelMmS2;
        p.calibrated = calibrated;
        return p;
    }

    private double[] table(Direction d) { return d == Direction.FORWARD ? forward : reverse; }
}
