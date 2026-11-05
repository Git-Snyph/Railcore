package at.schmoegl.railcore.train;

import at.schmoegl.railcore.layout.graph.DetectionSection;
import at.schmoegl.railcore.layout.graph.DirectedEdge;
import at.schmoegl.railcore.layout.graph.DirectedLocation;
import at.schmoegl.railcore.layout.graph.GraphPath;
import at.schmoegl.railcore.layout.graph.ReportPoint;
import at.schmoegl.railcore.layout.graph.TrackEdge;
import at.schmoegl.railcore.layout.graph.TrackGraph;
import at.schmoegl.railcore.layout.graph.TrackLayout;
import at.schmoegl.railcore.layout.graph.TrackLocation;
import at.schmoegl.railcore.layout.graph.TrackRange;
import at.schmoegl.railcore.layout.graph.TurnoutRequirement;
import at.schmoegl.railcore.layout.graph.TurnoutStates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Erzeugt einen {@link DriveOrder} aus einem Fahrweg im Gleisgraph (Schritt C1). Die Wegkoordinate des Auftrags ist
 * die Wegkoordinate des Fahrwegs: 0 = Zugspitze beim Start; {@link Result#locate} rechnet zurück auf den Graph.
 * {@link DriveOrder}, {@link StopPlanner} und der Fahrer bleiben unverändert.
 *
 * <p><b>Meldepunkte</b> stammen aus den Abschnittsgrenzen des Gleisplans je Fahrtrichtung ({@link TrackLayout#reportPoints},
 * auch überschriebene). Ein Belegtmelder spricht an, wenn die erste stromabnehmende Achse die Grenze erreicht; die
 * Zugspitze ist dann um deren Abstand weiter. Bis Fahrzeugdaten aus der Datenbank vorliegen (MobaDB, Lastenheft 9),
 * gilt ein vorläufiger Standardwert ({@link Settings#leadingPickupOffsetMm}). Weggelassen werden Meldepunkte, die
 * hinter der Spitze oder hinter dem Ende der Freigabe liegen, und solche, deren Abschnitt in diesem Moment noch vom
 * eigenen Zug belegt ist (er spräche nicht erneut an; der Fahrer hielte sonst wegen eines „fehlenden“ Meldepunkts).
 *
 * <p><b>Ende der Freigabe:</b> höchstens das Wegende. Verlangt der Weg eine Weichenlage, die laut übergebenen Lagen
 * nicht anliegt (auch unbekannt), endet die Freigabe vor dem ersten Gleisstück dieser Weiche (im Zweifel halt).
 * Liegt das Ziel dahinter, wird der Halt auf die Strecke vor die Weiche zurückgenommen ({@link Result#stopReplaced()}).
 * Die Reservierung selbst (Meilenstein 4, Entscheidung 40/E1) ist nicht Teil dieser Klasse.
 *
 * <p><b>Abgewiesen</b> (Ausnahme, kein Auftrag) werden: Gleisplan mit Fehlern, unbestätigte Position (Grundsatz 5),
 * nicht eindeutige oder unplausible Ausdehnung des Zuges, Fahrweg nicht an der Zugspitze, Ziel nicht auf dem Weg.
 */
public final class DriveOrderFactory {

    /**
     * Einstellungen, alle vorläufig bis zu echten Messwerten.
     *
     * @param leadingPickupOffsetMm  Abstand Zugspitze → erste stromabnehmende Achse, wenn der Auftrag keinen nennt
     *                               (vorläufig; maßgeblich werden die MobaDB-Offsets des führenden Fahrzeugs)
     * @param sensorDelaySigmaMs     Streuung der Melderverzögerung, solange je Abschnitt nicht eingemessen
     *                               (Mittelwert = Ansprechverzögerung des Abschnitts)
     * @param fallbackSafetyDistanceMm Sicherheitsabstand, wenn ein Bahnsteighalt auf die Strecke zurückgenommen wird
     */
    public record Settings(double leadingPickupOffsetMm, double sensorDelaySigmaMs, double fallbackSafetyDistanceMm) {

        /** Vorläufiger Abstand der ersten stromabnehmenden Achse hinter der Zugspitze (H0, Annahme). */
        public static final double DEFAULT_LEADING_PICKUP_OFFSET_MM = 20.0;
        /** Vorläufige Streuung der Melderverzögerung. */
        public static final double DEFAULT_SENSOR_DELAY_SIGMA_MS = 10.0;
        /** Vorläufiger Sicherheitsabstand für einen zurückgenommenen Halt. */
        public static final double DEFAULT_FALLBACK_SAFETY_DISTANCE_MM = 20.0;

        public static Settings defaults() {
            return new Settings(DEFAULT_LEADING_PICKUP_OFFSET_MM, DEFAULT_SENSOR_DELAY_SIGMA_MS,
                    DEFAULT_FALLBACK_SAFETY_DISTANCE_MM);
        }

        public Settings {
            if (!(leadingPickupOffsetMm >= 0 && leadingPickupOffsetMm <= 1_000))
                throw new IllegalArgumentException("Abstand der ersten stromabnehmenden Achse unplausibel: " + leadingPickupOffsetMm);
            if (!(sensorDelaySigmaMs >= 0 && sensorDelaySigmaMs <= 10_000))
                throw new IllegalArgumentException("Streuung der Melderverzögerung unplausibel: " + sensorDelaySigmaMs);
            if (!(fallbackSafetyDistanceMm >= 0 && fallbackSafetyDistanceMm <= 5_000))
                throw new IllegalArgumentException("Sicherheitsabstand unplausibel: " + fallbackSafetyDistanceMm);
        }
    }

    /** Ziel des Fahrauftrags. */
    public sealed interface Target permits AtStopPoint, AtSignal, AtPathEnd {}

    /** Bahnsteighalt an einem Haltepunkt des Gleisplans (zweistufig, Ziel ±10 mm). */
    public record AtStopPoint(String stopPointId, double approachDistanceMm) implements Target {
        public AtStopPoint {
            Objects.requireNonNull(stopPointId);
        }

        public static AtStopPoint of(String stopPointId) { return new AtStopPoint(stopPointId, StopPoint.DEFAULT_APPROACH_MM); }
    }

    /** Halt auf der Strecke vor einem Signal des Gleisplans (Blockende). */
    public record AtSignal(String signalId, double safetyDistanceMm) implements Target {
        public AtSignal {
            Objects.requireNonNull(signalId);
        }
    }

    /** Halt auf der Strecke am Ende der Freigabe. */
    public record AtPathEnd(double safetyDistanceMm) implements Target {}

    /**
     * Auftrag an die Fabrik.
     *
     * @param trainType             Zugart für Haltepunkte mit Zugart-Einschränkung ({@code null} = unbekannt)
     * @param path                  Fahrweg ab der Zugspitze (erste Kante = Kante der Spitze, Anfang = Spitze)
     * @param turnouts              aktuell bekannte Weichenlagen
     * @param endOfAuthorityMm      Ende der Freigabe auf dem Weg; leer = Wegende
     * @param leadingPickupOffsetMm Abstand der ersten stromabnehmenden Achse; leer = {@link Settings}
     */
    public record Request(Train train, String trainType, TrainPosition position, GraphPath path, TurnoutStates turnouts,
                          Target target, double lineSpeedMmS, ModelScale scale, DelayStats commandDelay,
                          OptionalDouble endOfAuthorityMm, OptionalDouble leadingPickupOffsetMm) {
        public Request {
            Objects.requireNonNull(train, "Zug fehlt");
            Objects.requireNonNull(position, "Position fehlt");
            Objects.requireNonNull(path, "Fahrweg fehlt");
            Objects.requireNonNull(turnouts, "Weichenlagen fehlen");
            Objects.requireNonNull(target, "Ziel fehlt");
            Objects.requireNonNull(scale, "Maßstab fehlt");
            Objects.requireNonNull(commandDelay, "Befehlsverzögerung fehlt");
            if (endOfAuthorityMm == null) endOfAuthorityMm = OptionalDouble.empty();
            if (leadingPickupOffsetMm == null) leadingPickupOffsetMm = OptionalDouble.empty();
        }

        /** Freigabe bis zum Wegende, Achsabstand aus den Einstellungen. */
        public static Request of(Train train, TrainPosition position, GraphPath path, TurnoutStates turnouts,
                                 Target target, double lineSpeedMmS, ModelScale scale, DelayStats commandDelay) {
            return new Request(train, null, position, path, turnouts, target, lineSpeedMmS, scale, commandDelay,
                    OptionalDouble.empty(), OptionalDouble.empty());
        }

        public Request withEndOfAuthority(double mm) {
            return new Request(train, trainType, position, path, turnouts, target, lineSpeedMmS, scale, commandDelay,
                    OptionalDouble.of(mm), leadingPickupOffsetMm);
        }

        public Request withLeadingPickupOffset(double mm) {
            return new Request(train, trainType, position, path, turnouts, target, lineSpeedMmS, scale, commandDelay,
                    endOfAuthorityMm, OptionalDouble.of(mm));
        }

        public Request withTrainType(String type) {
            return new Request(train, type, position, path, turnouts, target, lineSpeedMmS, scale, commandDelay,
                    endOfAuthorityMm, leadingPickupOffsetMm);
        }
    }

    /**
     * Meldepunkt im Auftrag mit Herkunft.
     *
     * @param boundaryMm Wegkoordinate der Abschnittsgrenze (Meldepunkt des Gleisplans)
     */
    public record PlannedPoint(MeasurementPoint point, String sectionId, double boundaryMm, ReportPoint source) {}

    /** Weggelassener Meldepunkt mit Grund (für das Journal). */
    public record SkippedPoint(String sectionId, double boundaryMm, String reason) {}

    /**
     * Ergebnis.
     *
     * @param limitedBy     Weichenlage, vor der die Freigabe endet (leer = nicht begrenzt)
     * @param stopReplaced  das gewünschte Ziel lag hinter dem Ende der Freigabe und wurde durch einen Halt auf der
     *                      Strecke davor ersetzt
     */
    public record Result(DriveOrder order, GraphPath path, TrainExtent startExtent, List<PlannedPoint> points,
                         List<SkippedPoint> skippedPoints, Optional<TurnoutRequirement> limitedBy, boolean stopReplaced,
                         List<String> notes) {
        public Result {
            points = List.copyOf(points);
            skippedPoints = List.copyOf(skippedPoints);
            notes = List.copyOf(notes);
        }

        /** Ort der Zugspitze bei Wegkoordinate {@code s} des Auftrags. */
        public TrackLocation locate(double s) { return path.locate(s); }

        /** Zugspitze mit Fahrtrichtung bei Wegkoordinate {@code s} des Auftrags. */
        public DirectedLocation headAt(double s) { return TrainPosition.headAt(path, s); }
    }

    private static final double EPS = 1e-6;
    /** Überschneidung, ab der ein Abschnitt als vom eigenen Zug belegt gilt (wie {@link TrackLayout}). */
    private static final double OVERLAP_TOL = 0.01;

    private final TrackLayout layout;
    private final TrackGraph graph;
    private final TrainExtent.Resolver resolver;
    private final Settings settings;
    private final Map<String, List<ReportPoint>> reportPointsByEdge;

    public DriveOrderFactory(TrainExtent.Resolver resolver, Settings settings) {
        this.resolver = Objects.requireNonNull(resolver, "Resolver fehlt");
        this.settings = Objects.requireNonNull(settings, "Einstellungen fehlen");
        this.layout = resolver.layout();
        this.graph = layout.graph();
        Map<String, List<ReportPoint>> byEdge = new LinkedHashMap<>();
        for (DetectionSection s : layout.sections())
            for (ReportPoint p : layout.reportPoints(s.id()))
                byEdge.computeIfAbsent(p.at().edgeId(), k -> new ArrayList<>()).add(p);
        Map<String, List<ReportPoint>> copy = new LinkedHashMap<>();
        byEdge.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.reportPointsByEdge = Map.copyOf(copy);
    }

    public DriveOrderFactory(TrackLayout layout) { this(new TrainExtent.Resolver(layout), Settings.defaults()); }

    public Result create(Request r) {
        if (!layout.permitsAutomatic())
            throw new IllegalStateException("Gleisplan hat Fehler – kein Fahrauftrag (" + layout.errors().size() + " Fehler)");
        Train train = r.train();
        TrainPosition pos = r.position();
        if (!pos.isConfirmed())
            throw new IllegalStateException("Position von " + train.id() + " ist unbestätigt – kein Fahrauftrag");
        GraphPath path = r.path();
        for (DirectedEdge e : path.edges())
            if (!graph.containsEdge(e.edge()))
                throw new IllegalArgumentException("Kante " + e.edgeId() + " gehört nicht zu diesem Gleisplan");
        DirectedEdge first = path.edges().getFirst();
        if (!first.equals(pos.headEdge(graph)) || Math.abs(path.startOffsetMm() - pos.headAlongMm(graph)) > EPS)
            throw new IllegalArgumentException("Fahrweg beginnt nicht an der Zugspitze " + pos.head() + ": " + path);

        TrainExtent extent = resolver.extent(pos, train.lengthMm(), r.turnouts());
        if (!extent.certain())
            throw new IllegalStateException("Lage von " + train.id() + " nicht eindeutig – kein Fahrauftrag: " + extent.notes());
        GraphPath body = extent.body().orElseThrow();
        GraphPath combined = combine(body, path);

        List<String> notes = new ArrayList<>(extent.notes());
        double[] cum = cumulativeStarts(path);

        // Ende der Freigabe: Wegende bzw. Vorgabe, vor der ersten nicht anliegenden Weichenlage
        double eoa = path.lengthMm();
        if (r.endOfAuthorityMm().isPresent()) {
            double req = r.endOfAuthorityMm().getAsDouble();
            if (!(req > 0 && req <= path.lengthMm() + EPS))
                throw new IllegalArgumentException("Ende der Freigabe außerhalb des Fahrwegs: " + req);
            eoa = Math.min(req, path.lengthMm());
        }
        Optional<TurnoutRequirement> limitedBy = Optional.empty();
        List<DirectedEdge> edges = path.edges();
        outer:
        for (int i = 0; i + 1 < edges.size(); i++) {
            DirectedEdge from = edges.get(i);
            DirectedEdge to = edges.get(i + 1);
            for (TrackGraph.Step step : graph.steps(from)) {
                if (!step.next().equals(to)) continue;
                for (TurnoutRequirement req : step.requires()) {
                    if (req.satisfiedBy(r.turnouts())) continue;
                    double limit = (isTurnoutEdge(from) ? cum[i] : cum[i + 1]) - path.startOffsetMm();
                    if (limit < eoa) {
                        if (!(limit > EPS))
                            throw new IllegalStateException("Weiche " + req.turnoutId() + " liegt nicht " + req.position()
                                    + " und die Zugspitze von " + train.id() + " steht schon an ihr – kein Fahrauftrag");
                        eoa = limit;
                        limitedBy = Optional.of(req);
                        notes.add("Freigabe endet bei " + fmt(limit) + " mm vor Weiche " + req.turnoutId() + " (verlangt "
                                + req.position() + ", Lage " + r.turnouts().position(req.turnoutId()) + ")");
                    }
                    break outer;
                }
                break;
            }
        }

        // Ziel
        StopPoint stop = stopFor(r, path, eoa);
        boolean replaced = false;
        if (stop.positionMm() > eoa + EPS) {
            replaced = true;
            double safety = stop.kind() == StopPoint.Kind.LINE ? stop.safetyDistanceMm() : settings.fallbackSafetyDistanceMm();
            notes.add("Ziel " + stop.id() + " bei " + fmt(stop.positionMm()) + " mm liegt hinter dem Ende der Freigabe "
                    + fmt(eoa) + " mm – Halt auf der Strecke davor");
            stop = StopPoint.line(stop.id(), eoa, safety);
        }

        // Meldepunkte
        double pickup = r.leadingPickupOffsetMm().orElse(settings.leadingPickupOffsetMm());
        if (!(pickup >= 0 && pickup <= 1_000)) throw new IllegalArgumentException("Achsabstand unplausibel: " + pickup);
        List<PlannedPoint> planned = new ArrayList<>();
        List<SkippedPoint> skipped = new ArrayList<>();
        double length = train.lengthMm();
        double bodyLen = body.lengthMm();
        for (int i = 0; i < edges.size(); i++) {
            DirectedEdge e = edges.get(i);
            for (ReportPoint rp : reportPointsByEdge.getOrDefault(e.edgeId(), List.of())) {
                if (rp.at().forward() != e.forward()) continue;
                double sb = cum[i] + e.toEdgeOffset(rp.at().offsetMm()) - path.startOffsetMm();
                if (sb < -pickup - EPS || sb > path.lengthMm() + EPS) continue;   // nicht auf diesem Wegstück
                double sp = sb + pickup;
                if (sp <= EPS) continue;
                if (sp > eoa + EPS) {
                    skipped.add(new SkippedPoint(rp.sectionId(), sb, "hinter dem Ende der Freigabe"));
                    continue;
                }
                DetectionSection section = layout.section(rp.sectionId()).orElseThrow();
                double hi = sb + bodyLen;
                double lo = Math.max(0, hi + pickup - length);
                if (hi - lo > OVERLAP_TOL && overlaps(section, combined.ranges(lo, hi))) {
                    skipped.add(new SkippedPoint(rp.sectionId(), sb, "Abschnitt dann noch vom eigenen Zug belegt"));
                    notes.add("Meldepunkt " + rp.sectionId() + " bei " + fmt(sb) + " mm weggelassen: Abschnitt dann noch"
                            + " vom eigenen Zug belegt");
                    continue;
                }
                DelayStats delay = new DelayStats(section.responseDelayMs(), settings.sensorDelaySigmaMs());
                planned.add(new PlannedPoint(new MeasurementPoint(section.contact(), sp, delay), section.id(), sb, rp));
            }
        }
        planned.sort((a, b) -> Double.compare(a.point().positionMm(), b.point().positionMm()));
        List<MeasurementPoint> points = planned.stream().map(PlannedPoint::point).toList();

        DriveOrder order = new DriveOrder(train.id(), train.address(), train.steps(), pos.towardHead(), train.profile(),
                r.scale(), r.lineSpeedMmS(), 0, pos.sigmaMm(train.profile().uncertainty()), points, stop, eoa,
                r.commandDelay());
        return new Result(order, path, extent, planned, skipped, limitedBy, replaced, notes);
    }

    // ------------------------------------------------------------------------------------------------------------

    private StopPoint stopFor(Request r, GraphPath path, double eoa) {
        return switch (r.target()) {
            case AtStopPoint t -> {
                at.schmoegl.railcore.layout.graph.StopPoint sp = layout.stopPoints().stream()
                        .filter(p -> p.id().equals(t.stopPointId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unbekannter Haltepunkt " + t.stopPointId()));
                if (sp.maxTrainLengthMm() < r.train().lengthMm())
                    throw new IllegalArgumentException("Haltepunkt " + sp.id() + " gilt nur für Züge bis "
                            + fmt(sp.maxTrainLengthMm()) + " mm");
                if (!sp.trainTypes().isEmpty() && (r.trainType() == null || !sp.trainTypes().contains(r.trainType())))
                    throw new IllegalArgumentException("Haltepunkt " + sp.id() + " gilt nicht für Zugart " + r.trainType());
                double s = firstAhead(path, List.of(sp.at()))
                        .orElseThrow(() -> new IllegalArgumentException("Haltepunkt " + sp.id()
                                + " liegt nicht in Fahrtrichtung auf dem Fahrweg"));
                yield StopPoint.platform(sp.id(), s, t.approachDistanceMm());
            }
            case AtSignal t -> {
                List<DirectedLocation> at = layout.signalLocations(t.signalId());
                if (at.isEmpty()) throw new IllegalArgumentException("Unbekanntes oder fehlerhaftes Signal " + t.signalId());
                double s = firstAhead(path, at).orElseThrow(() -> new IllegalArgumentException("Signal " + t.signalId()
                        + " liegt nicht in Fahrtrichtung auf dem Fahrweg"));
                yield StopPoint.line(t.signalId(), s, t.safetyDistanceMm());
            }
            case AtPathEnd t -> StopPoint.line("Ende der Freigabe", eoa, t.safetyDistanceMm());
        };
    }

    /** Kleinste Wegkoordinate vor der Spitze, an der der Weg einen der Orte in dessen Richtung berührt. */
    private static OptionalDouble firstAhead(GraphPath path, List<DirectedLocation> locations) {
        double best = Double.POSITIVE_INFINITY;
        for (DirectedLocation l : locations)
            for (double s : TrainPosition.coordinatesOf(path, l))
                if (s > EPS && s < best) best = s;
        return Double.isInfinite(best) ? OptionalDouble.empty() : OptionalDouble.of(best);
    }

    /** Zugkörper (Schluss → Spitze) und Fahrweg (ab Spitze) als ein Weg. */
    private GraphPath combine(GraphPath body, GraphPath path) {
        List<DirectedEdge> edges = new ArrayList<>(body.edges());
        edges.addAll(path.edges().subList(1, path.edges().size()));
        return GraphPath.of(graph, edges, body.startOffsetMm(), path.endOffsetMm());
    }

    private static double[] cumulativeStarts(GraphPath path) {
        double[] cum = new double[path.edges().size()];
        double c = 0;
        for (int i = 0; i < cum.length; i++) {
            cum[i] = c;
            c += path.edges().get(i).lengthMm();
        }
        return cum;
    }

    private static boolean isTurnoutEdge(DirectedEdge e) {
        TrackEdge.Role role = e.edge().role();
        return role == TrackEdge.Role.TURNOUT_STRAIGHT || role == TrackEdge.Role.TURNOUT_DIVERGING
                || role == TrackEdge.Role.DOUBLE_SLIP_ROUTE;
    }

    private static boolean overlaps(DetectionSection section, List<TrackRange> occupied) {
        for (TrackRange o : occupied)
            for (TrackRange s : section.ranges())
                if (s.edgeId().equals(o.edgeId()) && Math.min(s.toMm(), o.toMm()) - Math.max(s.fromMm(), o.fromMm()) > OVERLAP_TOL)
                    return true;
        return false;
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }
}
