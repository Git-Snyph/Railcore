package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.train.AccuracyNotice;
import at.schmoegl.railcore.train.BrakeCurve;
import at.schmoegl.railcore.train.DriveOrder;
import at.schmoegl.railcore.train.MeasurementPoint;
import at.schmoegl.railcore.train.PositionEstimator;
import at.schmoegl.railcore.train.PositionUncertainty;
import at.schmoegl.railcore.train.SpeedProfile;
import at.schmoegl.railcore.train.StopAccuracy;
import at.schmoegl.railcore.train.StopPlanner;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Fährt einen Zug nach einem {@link DriveOrder} bis zum Haltepunkt (Entscheidung 24). Läuft ausschließlich auf dem
 * Steuerungs-Thread: als {@link ControlExecutor.TickTask} (Bewegungsrechnung mit tatsächlich vergangener Zeit), als
 * {@link ControlExecutor.FeedbackObserver} (Positionsabgleich sofort beim Meldepunkt) und über eingeplante Aufgaben
 * (Brems- und Haltebefehle zum vorausberechneten Zeitpunkt). Alle Befehle gehen durch die Befehlsschranke des
 * Executors (Reservierung, Nothalt, Betriebszustand) und den {@link CommandScheduler}: Anfahren als Automatik,
 * Bremsstufen und Halt als Sicherheit. Ein Fahrbefehl wirkt in der Positionsschätzung erst ab seinem tatsächlichen
 * Sendezeitpunkt; die Vorhersage rechnet die voraussichtliche Wartezeit im Befehlsplaner mit ein.
 * <p>
 * Ablauf Bahnsteig: Bremskurve auf das Annäherungstempo (ggf. mit begrenztem Überfahrtempo am Meldepunkt), bei
 * Annäherungstempo wird der Haltebefehl auf {@code t = jetzt + (Ziel − Auslaufweg − Position) / v − mittlere
 * Befehlsverzögerung} eingeplant und bei jedem Abgleich neu geplant. Halt auf der Strecke: eine Bremskurve bis zum
 * Stillstand mit Sicherheitsabstand. Immer überwacht: das Ende der Freigabe mit Positionsunsicherheit
 * (Vielfaches von σ), oberer Befehlsverzögerung, Auslauf und einem Takt Reaktionszeit.
 * <p>
 * Im Zweifel halt: Nothalt gesetzt, Fahrbefehl abgewiesen, Abweichung beim Abgleich zu groß, Meldepunkt nicht
 * angesprochen oder Überwachung des Freigabeendes greift → Halt auf kürzestem Weg (ungeplant, von der
 * Genauigkeitsvorgabe ausgenommen). Ein Zug wird während der Halteplanung nie wieder beschleunigt.
 */
public final class TrainDriver {

    public enum State { DRIVING, STOP_SCHEDULED, STOPPING, STOPPED, ABORTED }

    private final ControlExecutor ex;
    private final Journal journal;
    private final DriveOrder order;
    private final StopPlanner.Settings settings;
    private final StopPlanner.StopPlan plan;
    private final SpeedProfile profile;
    private final Direction dir;
    private final PositionUncertainty uncertainty;
    private final PositionEstimator est;
    private final long commandDelayNanos;
    private final double tickSeconds;
    private final boolean[] pointDone;
    private final ControlExecutor.TickTask tickTask = (now, elapsed) -> onTick(now);
    private final long phaseNanos;
    private final ControlExecutor.FeedbackObserver observer = this::onFeedback;
    /**
     * Rückmeldung des Befehlsplaners: die Positionsschätzung übernimmt einen Fahrbefehl erst zum tatsächlichen
     * Sendezeitpunkt (Wartezeit im Befehlsplaner verlängert die Befehlsverzögerung); bis dahin rechnet sie mit der
     * bisherigen Geschwindigkeit weiter, die die Lok ja auch noch fährt.
     */
    private final CommandSink.DriveListener listener = new CommandSink.DriveListener() {
        @Override public void sent(int speedStep, Direction direction, long sentNanos) {
            est.command(sentNanos, profile.speed(dir, speedStep), commandDelayNanos);
            if (speedStep == 0) stopCommandNanos = sentNanos;
        }

        @Override public void dropped(String reason) { onDropped(reason); }
    };

    private State state = State.DRIVING;
    private int lastStep;
    private boolean unplanned;
    private String haltReason;
    private ScheduledAction stopAction;
    private ScheduledAction updateAction;
    private long plannedStopCommandNanos = Long.MIN_VALUE;
    private long stopCommandNanos = Long.MIN_VALUE;
    private double lastDeviationMm = Double.NaN;
    private int fixCount;
    private int interventions;
    private ScheduledAction phaseAction;

    private TrainDriver(ControlExecutor ex, DriveOrder order, StopPlanner.Settings settings) {
        this.ex = ex;
        this.journal = ex.journal();
        this.order = order;
        this.settings = settings;
        this.profile = order.profile();
        this.dir = order.direction();
        this.uncertainty = profile.uncertainty();
        this.tickSeconds = ex.config().tickMillis() / 1_000.0;
        this.phaseNanos = phaseOffsetNanos(order.address(), ex.config().tickMillis() * 1_000_000L);
        this.plan = StopPlanner.plan(order, settings, tickSeconds);
        this.commandDelayNanos = order.commandDelay().meanNanos();
        this.pointDone = new boolean[order.points().size()];
        for (int i = 0; i < pointDone.length; i++)
            pointDone[i] = order.points().get(i).positionMm() <= order.startPositionMm();
        this.lastStep = profile.stepFor(dir, order.lineSpeedMmS());
        long now = ex.clock().nanoTime();
        this.est = new PositionEstimator(now, order.startPositionMm(), profile.speed(dir, lastStep), order.startSigmaMm(),
                plan.runoutTauSeconds());
    }

    /**
     * Startet den Fahrauftrag (nur Steuerungs-Thread). Annahme: der Zug fährt bereits mit der Fahrstufe der
     * Streckengeschwindigkeit an der Startposition (Anfahren folgt mit Fahrplan und Handbetrieb); die Fahrstufe wird
     * zur Sicherheit erneut gesendet und muss die Befehlsschranke passieren.
     */
    public static TrainDriver start(ControlExecutor ex, DriveOrder order, StopPlanner.Settings settings) {
        Objects.requireNonNull(ex);
        TrainDriver d = new TrainDriver(ex, Objects.requireNonNull(order), Objects.requireNonNull(settings));
        d.begin();
        return d;
    }

    // ------------------------------------------------------------------ Abfragen (Steuerungs-Thread)

    public State state() { return state; }

    public boolean finished() { return state == State.STOPPED || state == State.ABORTED; }

    public StopPlanner.StopPlan plan() { return plan; }

    public Optional<StopAccuracy> accuracy() { return Optional.ofNullable(plan.accuracy()); }

    public Optional<AccuracyNotice> notice() { return Optional.ofNullable(plan.notice()); }

    public PositionEstimator estimator() { return est; }

    public int lastStep() { return lastStep; }

    /** Halt ungeplant (Nothalt, Überwachung, Abweichung) – von der Genauigkeitsvorgabe ausgenommen. */
    public boolean unplannedHalt() { return unplanned; }

    public String haltReason() { return haltReason; }

    /** Zeitpunkt, für den der Haltebefehl zuletzt eingeplant war. */
    public long plannedStopCommandNanos() { return plannedStopCommandNanos; }

    /** Zeitpunkt, zu dem der Haltebefehl tatsächlich ausgegeben wurde. */
    public long stopCommandNanos() { return stopCommandNanos; }

    /** Abweichung beim letzten Abgleich (Messung − Schätzung, mm). */
    public double lastDeviationMm() { return lastDeviationMm; }

    public int fixCount() { return fixCount; }

    /** Eingriffe der Überwachung des Freigabeendes (als Sicherheit gesendete Absenkungen und Halte). */
    public int supervisionInterventions() { return interventions; }

    /** Fester Versatz dieses Zuges im Takt (ns). */
    public long phaseNanos() { return phaseNanos; }

    /**
     * Fester, deterministischer Versatz im Takt aus der Lokadresse (Entscheidung 28): Bruchteil von Adresse · φ⁻¹,
     * damit fortlaufende Adressen gleichmäßig über den Takt verteilt sind und gleichzeitig bremsende Züge ihre
     * Befehle nicht im selben Moment abgeben. Der Takt selbst bleibt unverändert (Entscheidung 24).
     */
    static long phaseOffsetNanos(int address, long tickNanos) {
        double f = (address * 0.6180339887498949) % 1.0;
        return (long) (Math.abs(f) * tickNanos);
    }

    /** Takt: Bewegungsrechnung um den eigenen Versatz verschoben (ein Durchlauf je Takt bleibt). */
    private void onTick(long now) {
        if (finished()) return;
        if (phaseNanos == 0) { update(now); return; }
        if (phaseAction != null && phaseAction.isPending()) phaseAction.cancel();
        phaseAction = ex.schedule(now + phaseNanos, "Takt " + order.trainId(), () -> update(ex.clock().nanoTime()));
    }

    // ------------------------------------------------------------------ Ablauf

    private void begin() {
        long now = ex.clock().nanoTime();
        journal.info("fahrt", "DRIVE_STARTED", "Fahrauftrag " + order.trainId() + " (Lok " + order.address() + ") bis "
                + plan.stop().id() + " bei " + fmt(plan.targetMm()) + " mm, Ende der Freigabe " + fmt(order.endOfAuthorityMm()) + " mm");
        if (plan.accuracy() != null) journal.info("fahrt", "STOP_ACCURACY", plan.accuracy().format()
                + (Double.isInfinite(plan.passSpeedCapMmS()) ? "" : "; Überfahrtempo begrenzt auf " + fmt(plan.passSpeedCapMmS()) + " mm/s"));
        if (plan.notice() != null)
            journal.warn("fahrt", "STOP_ACCURACY_NOT_REACHABLE", "Haltepunkt " + plan.stop().id() + ": " + plan.notice().text());
        if (plan.targetPulledBack())
            journal.warn("fahrt", "STOP_TARGET_PULLED_BACK", "Haltepunkt " + plan.stop().id() + " zu nah am Ende der Freigabe – Ziel auf "
                    + fmt(plan.targetMm()) + " mm zurückgenommen");
        if (lastStep <= 0) { haltNow(now, "Streckengeschwindigkeit ergibt keine Fahrstufe", true); return; }
        if (!ex.commands().drive(order.address(), lastStep, order.steps(), dir, CommandPriority.AUTOMATIC, listener)) {
            lastStep = 0;
            unplanned = true;
            haltReason = "Fahrbefehl abgewiesen";
            ex.commands().drive(order.address(), 0, order.steps(), dir, CommandPriority.SAFETY, listener);
            finish(State.ABORTED, "Fahrauftrag " + order.trainId() + " abgebrochen: Fahrbefehl abgewiesen");
            return;
        }
        ex.addTickTask(tickTask);
        ex.addFeedbackObserver(observer);
        update(now);
    }

    /** Hauptrechnung: bei jedem Takt, jeder eingeplanten Neuberechnung und jedem Abgleich. */
    private void update(long now) {
        if (finished()) return;
        est.advanceTo(now);
        if (ex.plant().emergencyLatched()) {
            abort(now, "Nothalt gesetzt");
            return;
        }
        if (state == State.STOPPING) {
            if (est.stopped()) finishStopped();
            return;
        }
        double sigma = est.sigmaMm(uncertainty);
        if (checkMissingPoints(now, sigma)) return;

        // Befehlsverzögerung = eingemessene Verzögerung + voraussichtliche Wartezeit im Befehlsplaner, je nach Klasse:
        // planmäßige Bremsstufen laufen als Automatik, Eingriffe der Überwachung als Sicherheit (Entscheidung 28)
        long delay = commandDelayNanos;
        double sEff = est.predictPositionMm(now + delay + ex.scheduler().expectedWaitNanos(CommandPriority.SAFETY));
        double sPlan = est.predictPositionMm(now + delay + ex.scheduler().expectedWaitNanos(CommandPriority.AUTOMATIC));
        double vSup = supervision(sEff, sigma);
        double va = plan.approachSpeedMmS();
        int planStep = profile.stepFor(dir, planned(sPlan, sigma));
        if (plan.platform() && planStep < plan.approachStep()) planStep = plan.approachStep();
        int step = Math.min(planStep, profile.stepFor(dir, vSup));
        if (plan.platform() && step < plan.approachStep() && vSup >= va) step = plan.approachStep();
        step = Math.min(step, lastStep);   // nie wieder beschleunigen
        boolean intervention = step < Math.min(planStep, lastStep);   // die Überwachung verlangt weniger als der Plan

        if (step == 0) {
            if (intervention) { interventions++; haltNow(now, "Überwachung des Freigabeendes", true); }
            else haltNow(now, "Halt vor " + plan.stop().id(), false);
            return;
        }
        if (step != lastStep && !send(now, step, intervention)) return;

        if (plan.platform() && lastStep == plan.approachStep() && est.settled()
                && Math.abs(est.speedMmS() - va) < 1e-9) {
            planStop(now);
            if (finished() || state == State.STOPPING) return;
        }
        planNextUpdate(now, sEff, sigma);
    }

    /** Höchste zulässige Geschwindigkeit an Stelle x (Bremskurven, Überfahrtempo, Überwachung). */
    private double allowed(double x, double sigma) { return Math.min(planned(x, sigma), supervision(x, sigma)); }

    /** Planmäßige Bremskurven an Stelle x (Bremskurven, Überfahrtempo), ohne Überwachung des Freigabeendes. */
    private double planned(double x, double sigma) {
        double b = plan.decelMmS2();
        double v = order.lineSpeedMmS();
        if (plan.platform()) {
            v = Math.min(v, BrakeCurve.towards(x, plan.brakePointMm(), plan.approachSpeedMmS(), b));
            if (!Double.isInfinite(plan.passSpeedCapMmS()))
                v = Math.min(v, BrakeCurve.towards(x, plan.capPointMm(), plan.passSpeedCapMmS(), b));
        } else {
            double upper = x + settings.safetySigmas() * sigma;
            v = Math.min(v, BrakeCurve.supervision(plan.targetMm() - upper, b, plan.reactionSeconds()));
        }
        return v;
    }

    /** Überwachung des Freigabeendes mit oberer Positionsschranke. */
    private double supervision(double x, double sigma) {
        double upper = x + settings.safetySigmas() * sigma;
        return BrakeCurve.supervision(order.endOfAuthorityMm() - upper, plan.decelMmS2(), plan.reactionSeconds());
    }

    /** Muss bei Position x eine niedrigere Fahrstufe gelten als die aktuelle? */
    private boolean needsLowerStep(double x, double sigma) {
        double va = plan.approachSpeedMmS();
        if (plan.platform() && lastStep == plan.approachStep()) return supervision(x, sigma) < va;
        return allowed(x, sigma) < profile.speed(dir, lastStep) - 1e-9;
    }

    /** Nächste Herabstufung exakt einplanen, wenn sie vor dem nächsten Takt fällig wird. */
    private void planNextUpdate(long now, double sEff, double sigma) {
        double vc = est.targetSpeedMmS();
        if (lastStep == 0 || vc <= 0) return;
        double hi = order.endOfAuthorityMm();
        if (needsLowerStep(sEff, sigma) || !needsLowerStep(hi, sigma)) return;
        double lo = sEff;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (needsLowerStep(mid, sigma)) hi = mid; else lo = mid;
        }
        long due = now + (long) Math.ceil((hi - sEff) / vc * 1e9);
        if (due - now >= ex.config().tickMillis() * 1_000_000L) return;   // der Takt kommt vorher
        if (updateAction != null && updateAction.isPending()) {
            if (updateAction.dueNanos() == due) return;
            updateAction.cancel();
        }
        updateAction = ex.schedule(due, "Bremsstufe " + order.trainId(), () -> update(ex.clock().nanoTime()));
    }

    /** Haltebefehl auf den berechneten Zeitpunkt einplanen bzw. neu planen. */
    private void planStop(long now) {
        double rest = plan.targetMm() - profile.stopRunoutMm() - est.positionMm();
        long due = now + (long) (rest / plan.approachSpeedMmS() * 1e9) - commandDelayNanos;
        plannedStopCommandNanos = due;
        if (due <= now) {
            haltNow(now, "Halt am Bahnsteig " + plan.stop().id(), false);
            return;
        }
        if (stopAction != null && stopAction.isPending()) {
            if (Math.abs(stopAction.dueNanos() - due) < 1_000) return;
            stopAction.cancel();
        }
        stopAction = ex.schedule(due, "Halt " + order.trainId() + " am " + plan.stop().id(), this::onStopDue);
        if (state == State.DRIVING) {
            state = State.STOP_SCHEDULED;
            journal.info("fahrt", "STOP_SCHEDULED", "Halt " + order.trainId() + " am " + plan.stop().id() + " eingeplant in "
                    + fmt((due - now) / 1e6) + " ms (Position " + fmt(est.positionMm()) + " mm)");
        }
    }

    private void onStopDue() {
        long now = ex.clock().nanoTime();
        est.advanceTo(now);
        if (ex.plant().emergencyLatched()) { abort(now, "Nothalt gesetzt"); return; }
        if (state == State.STOPPING || finished()) return;
        haltNow(now, "Halt am Bahnsteig " + plan.stop().id(), false);
    }

    /** Ein angenommener Fahrbefehl wurde vor dem Senden verworfen (statt seiner ging Halt hinaus): im Zweifel halt. */
    private void onDropped(String reason) {
        if (finished()) return;
        long now = ex.clock().nanoTime();
        est.advanceTo(now);
        if (ex.plant().emergencyLatched()) { abort(now, "Nothalt gesetzt"); return; }
        if (state != State.STOPPING) haltNow(now, "Fahrbefehl beim Senden verworfen: " + reason, true);
    }

    /**
     * Fahrstufe senden. Planmäßige Bremsstufen als Automatik (innerhalb der Klasse Absenkung vor Anfahren), Eingriffe
     * der Überwachung als Sicherheit (Entscheidung 28); ein Sicherheitsbefehl ersetzt eine wartende Bremsstufe.
     */
    private boolean send(long now, int step, boolean intervention) {
        if (intervention) {
            if (interventions++ == 0)
                journal.warn("fahrt", "SUPERVISION_INTERVENTION", "Überwachung des Freigabeendes greift bei " + order.trainId()
                        + ": Stufe " + step + " statt " + lastStep + " (Position " + fmt(est.positionMm()) + " mm; weitere Eingriffe gezählt)");
        }
        CommandPriority p = intervention ? CommandPriority.SAFETY : CommandPriority.AUTOMATIC;
        boolean ok = ex.commands().drive(order.address(), step, order.steps(), dir, p, listener);
        if (!ok) {
            haltNow(now, "Fahrbefehl Stufe " + step + " abgewiesen", true);
            return false;
        }
        lastStep = step;
        return true;
    }

    /** Fahrstufe 0 sofort. {@code unplannedHalt}: auf kürzestem Weg, von der Genauigkeit ausgenommen. */
    private void haltNow(long now, String reason, boolean unplannedHalt) {
        cancelActions();
        ex.commands().drive(order.address(), 0, order.steps(), dir, CommandPriority.SAFETY, listener);   // Halten ist immer erlaubt
        lastStep = 0;
        stopCommandNanos = now;
        haltReason = reason;
        state = State.STOPPING;
        if (unplannedHalt) {
            unplanned = true;
            journal.warn("fahrt", "UNPLANNED_HALT", "Ungeplanter Halt " + order.trainId() + ": " + reason
                    + " (Position " + fmt(est.positionMm()) + " mm)");
        } else {
            journal.info("fahrt", "STOP_COMMAND", "Haltebefehl " + order.trainId() + ": " + reason + " (Position "
                    + fmt(est.positionMm()) + " mm, Abweichung vom Plan " + fmt((now - plannedStopCommandNanos) / 1e6) + " ms)");
        }
    }

    private void abort(long now, String reason) {
        cancelActions();
        if (lastStep > 0) {
            ex.commands().drive(order.address(), 0, order.steps(), dir, CommandPriority.SAFETY, listener);
            lastStep = 0;
        }
        unplanned = true;
        haltReason = reason;
        finish(State.ABORTED, "Fahrauftrag " + order.trainId() + " abgebrochen: " + reason);
    }

    private void finishStopped() {
        finish(State.STOPPED, "Zug " + order.trainId() + " steht bei " + fmt(est.positionMm()) + " mm (Ziel "
                + fmt(plan.targetMm()) + " mm" + (unplanned ? ", ungeplanter Halt: " + haltReason : "") + ")");
    }

    private void finish(State s, String msg) {
        cancelActions();
        state = s;
        ex.removeTickTask(tickTask);
        ex.removeFeedbackObserver(observer);
        if (s == State.ABORTED) journal.warn("fahrt", "DRIVE_ABORTED", msg);
        else journal.info("fahrt", "STOP_REACHED", msg);
    }

    private void cancelActions() {
        if (stopAction != null) stopAction.cancel();
        if (updateAction != null) updateAction.cancel();
        if (phaseAction != null) phaseAction.cancel();
    }

    // ------------------------------------------------------------------ Meldepunkte

    private void onFeedback(int contact, boolean occupied, long timestampNanos) {
        if (!occupied || finished()) return;
        List<MeasurementPoint> pts = order.points();
        int idx = -1;
        for (int i = 0; i < pts.size(); i++)
            if (!pointDone[i] && pts.get(i).contact() == contact) { idx = i; break; }
        if (idx < 0) return;   // nicht unser Meldepunkt (oder schon verarbeitet)
        pointDone[idx] = true;
        MeasurementPoint m = pts.get(idx);
        long now = ex.clock().nanoTime();
        est.advanceTo(now);
        double sigmaBefore = est.sigmaMm(uncertainty);
        long crossing = timestampNanos - m.delay().meanNanos();
        double vCross = est.speedMmS();
        double fixSigma = Math.hypot(uncertainty.sensorSpreadMm(), vCross * m.delay().sigmaSeconds());
        double delta = est.correct(m.positionMm(), crossing, fixSigma);
        lastDeviationMm = delta;
        fixCount++;
        double warn = settings.deviationWarnSigmas() * sigmaBefore + settings.deviationBaseMm();
        double halt = settings.deviationHaltSigmas() * sigmaBefore + settings.deviationBaseMm();
        String msg = "Abgleich " + order.trainId() + " am Meldepunkt " + contact + " (" + fmt(m.positionMm()) + " mm): Abweichung "
                + fmt(delta) + " mm bei σ " + fmt(sigmaBefore) + " mm";
        if (Math.abs(delta) > halt) {
            journal.error("fahrt", "POSITION_UNCERTAIN", msg + " – Position unsicher, Halt");
            haltNow(now, "Position unsicher", true);
            return;
        }
        if (Math.abs(delta) > warn) journal.warn("fahrt", "POSITION_DEVIATION", msg + " – größer als erwartet (Schlupf oder Profil?)");
        else journal.info("fahrt", "POSITION_FIX", msg);
        update(now);
    }

    /** Ein Meldepunkt, den der Zug sicher überfahren haben müsste, hat nicht angesprochen → im Zweifel halt. */
    private boolean checkMissingPoints(long now, double sigma) {
        List<MeasurementPoint> pts = order.points();
        for (int i = 0; i < pts.size(); i++) {
            if (pointDone[i]) continue;
            MeasurementPoint m = pts.get(i);
            double lower = est.positionMm() - settings.safetySigmas() * sigma - settings.deviationBaseMm();
            double travelDuringDelay = est.speedMmS() * m.delay().upperSeconds(settings.safetySigmas()) + est.speedMmS() * tickSeconds;
            if (lower > m.positionMm() + travelDuringDelay) {
                pointDone[i] = true;
                journal.error("fahrt", "MEASUREMENT_MISSING", "Meldepunkt " + m.contact() + " (" + fmt(m.positionMm())
                        + " mm) hat nicht angesprochen, Zug " + order.trainId() + " geschätzt bei " + fmt(est.positionMm()) + " mm");
                haltNow(now, "Meldepunkt " + m.contact() + " nicht angesprochen", true);
                return true;
            }
        }
        return false;
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }
}
