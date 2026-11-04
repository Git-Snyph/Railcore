package at.schmoegl.railcore.train;

import at.schmoegl.railcore.core.Direction;

import java.util.Objects;

/**
 * Planung eines Halts (Entscheidung 24), reine Rechnung ohne Zustand und ohne Zeitquelle.
 * <p>
 * <b>Bahnsteig:</b> Bremskurve auf das Annäherungstempo bis {@code Ziel − Annäherungsstrecke}, danach
 * Annäherungstempo bis zum Halt. Die Genauigkeit wird aus dem letzten Meldepunkt vor dem Ziel berechnet
 * ({@link StopAccuracy}). Dominiert die Streuung der Melderverzögerung beim Überfahren, begrenzt der Planer das
 * Überfahrtempo des Meldepunkts so weit, dass die Genauigkeit mit Planungsreserve erreicht wird (oder nahezu so gut
 * wie bei Annäherungstempo ist). Ist das Ziel auch so nicht erreichbar, entsteht ein {@link AccuracyNotice}.
 * Liegt das Ziel plus Unsicherheit und Reaktionsweg hinter dem Ende der Freigabe, wird das Ziel zurückgenommen.
 * <p>
 * <b>Strecke:</b> eine Bremskurve bis zum Stillstand {@code Sicherheitsabstand} vor dem Haltepunkt; keine
 * Genauigkeitsvorgabe.
 */
public final class StopPlanner {

    private StopPlanner() {}

    /**
     * Einstellungen der Halteplanung (alle konfigurierbar).
     *
     * @param targetToleranceMm     Zielgenauigkeit am Bahnsteig (±, 95 %), Standard 10 mm
     * @param planningMarginMm      Reserve unter der Zielgenauigkeit, auf die der Planer das Überfahrtempo auslegt
     * @param nearBestMarginMm      ist die Reserve nicht erreichbar: Überfahrtempo so, dass die Genauigkeit höchstens
     *                              so viel schlechter ist als bei Annäherungstempo
     * @param safetySigmas          Vielfaches der Positionsunsicherheit für Überwachung und Freigabeende
     * @param commandSigmas         obere Befehlsverzögerung = Mittel + so viele Standardabweichungen
     * @param runoutSafetyFactor    Sicherheitsfaktor auf den Auslaufweg in der Überwachung
     * @param deviationWarnSigmas   Abweichung beim Abgleich über so vielen σ (+ Grundwert) → Warnung
     * @param deviationHaltSigmas   Abweichung beim Abgleich über so vielen σ (+ Grundwert) → Halt „Position unsicher“
     * @param deviationBaseMm       Grundwert für beide Abweichungsgrenzen
     */
    public record Settings(double targetToleranceMm, double planningMarginMm, double nearBestMarginMm,
                           double safetySigmas, double commandSigmas, double runoutSafetyFactor,
                           double deviationWarnSigmas, double deviationHaltSigmas, double deviationBaseMm) {

        public static Settings defaults() { return new Settings(10.0, 1.5, 0.25, 3.0, 3.0, 1.5, 3.0, 6.0, 10.0); }

        public Settings {
            if (!(targetToleranceMm > 0)) throw new IllegalArgumentException("Zielgenauigkeit muss positiv sein");
            if (!(planningMarginMm >= 0 && planningMarginMm < targetToleranceMm)) throw new IllegalArgumentException("Planungsreserve unplausibel");
            if (!(nearBestMarginMm >= 0)) throw new IllegalArgumentException("Reserve unplausibel");
            if (!(safetySigmas >= 2)) throw new IllegalArgumentException("Sicherheitsfaktor der Unsicherheit mindestens 2σ");
            if (!(commandSigmas >= 0)) throw new IllegalArgumentException("Befehlsverzögerungs-Faktor unplausibel");
            if (!(runoutSafetyFactor >= 1)) throw new IllegalArgumentException("Auslauf-Sicherheitsfaktor mindestens 1");
            if (!(deviationHaltSigmas > deviationWarnSigmas && deviationWarnSigmas > 0)) throw new IllegalArgumentException("Abweichungsgrenzen unplausibel");
            if (!(deviationBaseMm >= 0)) throw new IllegalArgumentException("Grundwert unplausibel");
        }
    }

    /**
     * Ergebnis der Planung.
     *
     * @param targetMm              wo die Zugspitze stehen soll (ggf. vor das Freigabeende zurückgenommen)
     * @param brakePointMm          Bahnsteig: Beginn der Annäherungsstrecke (Ende der Bremskurve)
     * @param approachStep          Fahrstufe des Annäherungstempos
     * @param approachSpeedMmS      Annäherungstempo laut Profil (Modell-mm/s)
     * @param passSpeedCapMmS       begrenztes Überfahrtempo am Meldepunkt (∞ = keine Begrenzung)
     * @param capPointMm            ab hier gilt die Begrenzung (Meldepunkt − Unsicherheit)
     * @param fix                   maßgeblicher Meldepunkt vor dem Ziel oder null
     * @param accuracy              Bahnsteig: erwartete Genauigkeit, sonst null
     * @param notice                Hinweis, falls die Zielgenauigkeit nicht erreichbar ist, sonst null
     * @param runoutTauSeconds      Zeitkonstante des Auslaufs (Auslaufweg / Annäherungstempo)
     * @param reactionSeconds       Reaktionszeit der Überwachung (Befehl oben, Auslauf, ein Takt)
     */
    public record StopPlan(StopPoint stop, double targetMm, boolean targetPulledBack, double brakePointMm,
                           int approachStep, double approachSpeedMmS, double passSpeedCapMmS, double capPointMm,
                           MeasurementPoint fix, StopAccuracy accuracy, AccuracyNotice notice,
                           double runoutTauSeconds, double reactionSeconds, double decelMmS2) {
        public boolean platform() { return stop.kind() == StopPoint.Kind.PLATFORM; }
    }

    public static StopPlan plan(DriveOrder o, Settings s, double tickSeconds) {
        Objects.requireNonNull(o);
        Objects.requireNonNull(s);
        SpeedProfile p = o.profile();
        Direction dir = o.direction();
        double b = p.decel();
        if (!(b > 0)) throw new IllegalArgumentException("Betriebsbremsung im Profil fehlt");
        double approachWanted = o.scale().mmPerSecond(p.approachSpeedKmh());
        int vaStep = Math.max(1, p.stepFor(dir, approachWanted));
        double va = p.speed(dir, vaStep);
        if (!(va > 0)) throw new IllegalArgumentException("Annäherungstempo im Profil nicht darstellbar");
        double tau = Math.max(1e-3, p.stopRunoutMm() / va);
        double reaction = o.commandDelay().upperSeconds(s.commandSigmas()) + s.runoutSafetyFactor() * tau + tickSeconds;
        PositionUncertainty u = p.uncertainty();
        StopPoint stop = o.stop();
        double target = stop.targetMm();

        if (stop.kind() == StopPoint.Kind.LINE) {
            return new StopPlan(stop, target, false, target, 0, va, Double.POSITIVE_INFINITY, target, null, null, null,
                    tau, reaction, b);
        }

        boolean pulledBack = false;
        for (int pass = 0; pass < 2; pass++) {
            double bp = target - stop.approachDistanceMm();
            MeasurementPoint fix = lastPointBefore(o, target);
            double vNom = fix == null ? 0 : Math.min(o.lineSpeedMmS(), BrakeCurve.towards(fix.positionMm(), bp, va, b));
            double cap = Double.POSITIVE_INFINITY;
            StopAccuracy acc = accuracy(o, s, fix, target, Math.max(vNom, va), vaStep, va);
            if (fix != null && vNom > va && acc.expectedToleranceMm() > s.targetToleranceMm() - s.planningMarginMm()) {
                double aim = s.targetToleranceMm() - s.planningMarginMm();
                double atVa = accuracy(o, s, fix, target, va, vaStep, va).expectedToleranceMm();
                double goal = atVa <= aim ? aim : (atVa <= s.targetToleranceMm() ? atVa + s.nearBestMarginMm() : Double.NaN);
                if (!Double.isNaN(goal)) {
                    double lo = va, hi = vNom;
                    for (int i = 0; i < 50; i++) {
                        double mid = (lo + hi) / 2;
                        if (accuracy(o, s, fix, target, mid, vaStep, va).expectedToleranceMm() <= goal) lo = mid; else hi = mid;
                    }
                    cap = lo;
                    acc = accuracy(o, s, fix, target, cap, vaStep, va);
                }
            }
            double capPoint = target;
            if (fix != null) {
                double d = fix.positionMm() - o.startPositionMm();
                double sigmaAtFix = Math.hypot(o.startSigmaMm(), u.odometrySigmaMm(d));
                double vCap = Double.isInfinite(cap) ? 0 : cap;
                capPoint = fix.positionMm() - s.safetySigmas() * sigmaAtFix - vCap * o.commandDelay().upperSeconds(s.commandSigmas());
            }
            // Ziel + Unsicherheit + Reaktionsweg muss vor dem Ende der Freigabe liegen
            double limit = o.endOfAuthorityMm() - s.safetySigmas() * acc.sigmaMm() - va * reaction;
            if (target > limit && pass == 0) {
                target = limit;
                pulledBack = true;
                if (!(target > o.startPositionMm() + stop.approachDistanceMm()))
                    throw new IllegalArgumentException("Freigabe zu kurz für einen sicheren Halt am Haltepunkt " + stop.id());
                continue;
            }
            return new StopPlan(stop, target, pulledBack, bp, vaStep, va, cap, capPoint, fix, acc,
                    acc.notice().orElse(null), tau, reaction, b);
        }
        throw new IllegalStateException("Halteplanung nicht abgeschlossen");
    }

    /** Erwartete Genauigkeit bei Überfahrtempo {@code vPass} am maßgeblichen Meldepunkt. */
    static StopAccuracy accuracy(DriveOrder o, Settings s, MeasurementPoint fix, double target, double vPass,
                                 int vaStep, double va) {
        SpeedProfile p = o.profile();
        PositionUncertainty u = p.uncertainty();
        double fixPos, sSensor, sDelay;
        int contact;
        if (fix != null) {
            fixPos = fix.positionMm();
            sSensor = u.sensorSpreadMm();
            sDelay = vPass * fix.delay().sigmaSeconds();
            contact = fix.contact();
        } else {
            fixPos = o.startPositionMm();
            sSensor = o.startSigmaMm();
            sDelay = 0;
            contact = StopAccuracy.NO_CONTACT;
            vPass = 0;
        }
        double dist = target - fixPos;
        double sOdo = u.odometrySigmaMm(dist);
        double sumSq = va * va + brakeStepsSumSq(p, o.direction(), Math.max(vPass, va), vaStep);
        double sCmd = o.commandDelay().sigmaSeconds() * Math.sqrt(sumSq);
        return StopAccuracy.of(o.stop().id(), s.targetToleranceMm(), contact, fixPos, dist, vPass, sSensor, sDelay,
                sOdo, sCmd, p.stopRunoutSigmaMm());
    }

    /** Summe der quadrierten Geschwindigkeitssprünge beim Herunterstufen von vHigh auf die Annäherungsstufe. */
    static double brakeStepsSumSq(SpeedProfile p, Direction dir, double vHigh, int vaStep) {
        int top = p.stepFor(dir, vHigh);
        double sum = 0;
        for (int st = top; st > vaStep; st--) {
            double dv = p.speed(dir, st) - p.speed(dir, st - 1);
            sum += dv * dv;
        }
        return sum;
    }

    /** Letzter Meldepunkt vor dem Ziel (und vor der Zugspitze beim Start), oder null. */
    static MeasurementPoint lastPointBefore(DriveOrder o, double target) {
        MeasurementPoint best = null;
        for (MeasurementPoint m : o.points())
            if (m.positionMm() > o.startPositionMm() && m.positionMm() < target) best = m;
        return best;
    }
}
