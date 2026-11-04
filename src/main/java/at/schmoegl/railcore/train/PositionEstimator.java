package at.schmoegl.railcore.train;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Wegbasierte Positionsschätzung eines Zuges auf einer Wegkoordinate (mm). Gerechnet wird ausschließlich mit der
 * tatsächlich vergangenen Zeit (TimeSource-Nanosekunden) und der Sollgeschwindigkeit laut eingemessenem Profil
 * (Software Motion Control: der Decoder folgt der Fahrstufe ohne eigene Rampe).
 * <ul>
 *   <li>Ein Fahrbefehl wirkt nach der mittleren Befehlsverzögerung ({@link #command}).</li>
 *   <li>Fahrstufe 0 wird als Auslauf mit exponentiell abklingender Geschwindigkeit gerechnet (Zeitkonstante aus dem
 *       eingemessenen Auslaufweg: Auslaufweg = v · τ).</li>
 *   <li>Ein Meldepunkt setzt die Position auf den Meldepunkt, zurückgerechnet auf den Zeitpunkt des Überfahrens
 *       (Empfangszeit − mittlere Melderverzögerung) über den Verlauf der Schätzung ({@link #correct}).</li>
 *   <li>Die Unsicherheit wächst mit der Strecke seit dem Abgleich ({@link #sigmaMm}).</li>
 * </ul>
 * Nicht thread-sicher; gehört dem Steuerungs-Thread.
 */
public final class PositionEstimator {

    private static final int HISTORY = 512;
    private static final double STANDSTILL_MM_S = 0.01;

    private record Change(long effectiveNanos, double speed) {}

    private final double runoutTauSeconds;
    private final ArrayDeque<Change> pending = new ArrayDeque<>();
    private final long[] histT = new long[HISTORY];
    private final double[] histPos = new double[HISTORY];
    private int histStart, histSize;

    private long t;
    private double pos;
    private double speed;
    private boolean decaying;
    private long decayStartNanos;
    private double decayStartPos, decayStartSpeed;
    private double fixPos;
    private double fixSigma;

    /**
     * @param nowNanos          Startzeitpunkt
     * @param positionMm        Startposition (aus dem letzten Abgleich)
     * @param speedMmS          Geschwindigkeit, mit der der Zug gerade fährt (laut Profil)
     * @param sigmaMm           Unsicherheit der Startposition (Standardabweichung)
     * @param runoutTauSeconds  Zeitkonstante des Auslaufs nach Fahrstufe 0
     */
    public PositionEstimator(long nowNanos, double positionMm, double speedMmS, double sigmaMm, double runoutTauSeconds) {
        if (!(runoutTauSeconds > 0)) throw new IllegalArgumentException("Auslauf-Zeitkonstante muss positiv sein");
        this.t = nowNanos;
        this.pos = positionMm;
        this.speed = Math.max(0, speedMmS);
        this.fixPos = positionMm;
        this.fixSigma = Math.max(0, sigmaMm);
        this.runoutTauSeconds = runoutTauSeconds;
        remember(t, pos);
    }

    /** Fahrbefehl ausgegeben: neue Sollgeschwindigkeit wirkt nach {@code delayNanos}. */
    public void command(long issuedNanos, double speedMmS, long delayNanos) {
        advanceTo(issuedNanos);
        long eff = issuedNanos + Math.max(0, delayNanos);
        Change last = pending.peekLast();
        if (last != null && last.effectiveNanos() > eff) eff = last.effectiveNanos();
        pending.addLast(new Change(eff, Math.max(0, speedMmS)));
    }

    /** Schätzung bis {@code nowNanos} fortschreiben (tatsächlich vergangene Zeit). */
    public void advanceTo(long nowNanos) {
        if (nowNanos <= t) return;
        while (!pending.isEmpty() && pending.peekFirst().effectiveNanos() <= nowNanos) {
            Change c = pending.pollFirst();
            integrateTo(c.effectiveNanos());
            apply(c.speed());
            remember(t, pos);
        }
        integrateTo(nowNanos);
        remember(t, pos);
    }

    public long timeNanos() { return t; }

    public double positionMm() { return pos; }

    /** Aktuell wirksame Geschwindigkeit laut Schätzung. */
    public double speedMmS() { return speed; }

    /** Keine Befehle mehr unterwegs und kein Auslauf im Gang. */
    public boolean settled() { return pending.isEmpty() && !decaying; }

    public boolean stopped() { return pending.isEmpty() && !decaying && speed == 0; }

    /** Geschwindigkeit nach Wirkung aller ausstehenden Befehle. */
    public double targetSpeedMmS() {
        Change last = pending.peekLast();
        return last == null ? (decaying ? 0 : speed) : last.speed();
    }

    /**
     * Vorhergesagte Position zu einem künftigen Zeitpunkt unter Berücksichtigung ausstehender Befehle; der Auslauf
     * nach Fahrstufe 0 wird dabei mit seinem vollen Weg (v · τ) angesetzt.
     */
    public double predictPositionMm(long futureNanos) {
        if (futureNanos <= t) return positionAt(futureNanos);
        double p = pos;
        long tt = t;
        double v = decaying ? 0 : speed;
        if (decaying) p = decayStartPos + decayStartSpeed * runoutTauSeconds;
        for (Change c : pending) {
            if (c.effectiveNanos() >= futureNanos) break;
            p += v * (c.effectiveNanos() - tt) / 1e9;
            tt = c.effectiveNanos();
            if (c.speed() == 0 && v > 0) { p += v * runoutTauSeconds; v = 0; }
            else v = c.speed();
        }
        return p + v * (futureNanos - tt) / 1e9;
    }

    /** Geschätzte Position zu einem vergangenen Zeitpunkt (lineare Interpolation im Verlauf). */
    public double positionAt(long whenNanos) {
        if (whenNanos >= t) return pos + speed * (whenNanos - t) / 1e9;
        double prevP = Double.NaN;
        long prevT = Long.MIN_VALUE;
        for (int i = histSize - 1; i >= 0; i--) {
            int k = (histStart + i) % HISTORY;
            if (histT[k] <= whenNanos) {
                if (Double.isNaN(prevP) || prevT == histT[k]) return histPos[k];
                double f = (double) (whenNanos - histT[k]) / (prevT - histT[k]);
                return histPos[k] + f * (prevP - histPos[k]);
            }
            prevP = histPos[k];
            prevT = histT[k];
        }
        return histSize == 0 ? pos : histPos[histStart];   // älter als der Verlauf: ältester Wert
    }

    /**
     * Abgleich am Meldepunkt.
     *
     * @param measuredPositionMm  Position des Meldepunkts
     * @param crossingNanos       geschätzter Zeitpunkt des Überfahrens (Empfang − mittlere Melderverzögerung)
     * @param newFixSigmaMm       Unsicherheit dieses Abgleichs (Standardabweichung)
     * @return Abweichung Messung − Schätzung zum Überfahrzeitpunkt (positiv = Zug war weiter als geschätzt)
     */
    public double correct(double measuredPositionMm, long crossingNanos, double newFixSigmaMm) {
        double delta = measuredPositionMm - positionAt(crossingNanos);
        pos += delta;
        decayStartPos += delta;
        for (int i = 0; i < histSize; i++) histPos[(histStart + i) % HISTORY] += delta;
        fixPos = measuredPositionMm;
        fixSigma = Math.max(0, newFixSigmaMm);
        return delta;
    }

    /** Strecke seit dem letzten Abgleich. */
    public double distanceSinceFixMm() { return Math.max(0, pos - fixPos); }

    /** Positionsunsicherheit (Standardabweichung) aus Abgleich und Strecke seit dem Abgleich. */
    public double sigmaMm(PositionUncertainty u) {
        Objects.requireNonNull(u);
        double odo = u.odometrySigmaMm(distanceSinceFixMm());
        return Math.sqrt(fixSigma * fixSigma + odo * odo);
    }

    // ------------------------------------------------------------------ intern

    private void apply(double newSpeed) {
        if (newSpeed == 0 && speed > 0) {
            decaying = true;
            decayStartNanos = t;
            decayStartPos = pos;
            decayStartSpeed = speed;
        } else if (newSpeed > 0) {
            decaying = false;
            speed = newSpeed;
        }
    }

    private void integrateTo(long to) {
        if (to <= t) return;
        if (decaying) {
            double dt = (to - decayStartNanos) / 1e9;
            double e = Math.exp(-dt / runoutTauSeconds);
            pos = decayStartPos + decayStartSpeed * runoutTauSeconds * (1 - e);
            speed = decayStartSpeed * e;
            if (speed < STANDSTILL_MM_S) {
                speed = 0;
                decaying = false;
            }
        } else {
            pos += speed * (to - t) / 1e9;
        }
        t = to;
    }

    private void remember(long when, double p) {
        if (histSize > 0) {
            int last = (histStart + histSize - 1) % HISTORY;
            if (histT[last] == when) { histPos[last] = p; return; }
        }
        int k;
        if (histSize < HISTORY) {
            k = (histStart + histSize) % HISTORY;
            histSize++;
        } else {
            k = histStart;
            histStart = (histStart + 1) % HISTORY;
        }
        histT[k] = when;
        histPos[k] = p;
    }
}
