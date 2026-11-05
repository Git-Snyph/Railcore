package at.schmoegl.railcore.train;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.layout.graph.Continuation;
import at.schmoegl.railcore.layout.graph.DirectedEdge;
import at.schmoegl.railcore.layout.graph.DirectedLocation;
import at.schmoegl.railcore.layout.graph.GraphPath;
import at.schmoegl.railcore.layout.graph.TrackGraph;
import at.schmoegl.railcore.layout.graph.TurnoutStates;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Position eines Zuges im Gleisgraph (Lastenheft Kapitel 6, Schritt C1): Zugspitze als Ort mit Fahrtrichtung, dazu
 * die Decoder-Richtung, die den Zug zur Spitze hin bewegt, und der Stand der Positionsunsicherheit. Unveränderlich;
 * jede Änderung liefert eine neue Position. Belegung und Zugschluss berechnet {@link TrainExtent}.
 *
 * <p><b>Zugspitze:</b> {@code head.forward()} ist die Richtung, in die die Spitze zeigt, bezogen auf die Kante; der
 * Zug liegt dahinter. Fährt der Zug in die Gegenrichtung (Richtungswechsel), wird sein Schluss zur neuen Spitze
 * ({@link #reversedAt}).
 *
 * <p><b>Ausrichtung der Lok:</b> {@code towardHead} ist die Decoder-Richtung, in der der Zug zur Spitze fährt
 * (vorwärts, wenn die Lok mit dem Führerstand 1 voraus fährt; rückwärts z. B. beim Schieben mit Steuerwagen voraus).
 *
 * <p><b>Unsicherheit</b> wie bei {@link PositionEstimator}: Unsicherheit des letzten Abgleichs ({@code fixSigmaMm})
 * und Strecke seit dem Abgleich; die Parameter ({@link PositionUncertainty}) gehören zum Zug und werden beim Rechnen
 * übergeben ({@link #sigmaMm}).
 *
 * <p><b>Status:</b> Nach einem Neustart gilt jede Position als unbestätigt (Grundsatz 5); bestätigt wird sie erst
 * durch eine Rückmeldung bzw. den Bediener ({@link #confirmed()}). Aus einer unbestätigten Position wird kein
 * Fahrauftrag erzeugt ({@link DriveOrderFactory}).
 *
 * @param head               Zugspitze mit Fahrtrichtung
 * @param towardHead         Decoder-Richtung, die den Zug zur Spitze hin bewegt
 * @param fixSigmaMm         Unsicherheit des letzten Abgleichs (Standardabweichung)
 * @param distanceSinceFixMm seit dem letzten Abgleich gefahrene Strecke
 * @param status             bestätigt oder unbestätigt
 */
public record TrainPosition(DirectedLocation head, Direction towardHead, double fixSigmaMm, double distanceSinceFixMm,
                            Status status) {

    /** Ob die Position durch Rückmeldung oder Bediener bestätigt ist. */
    public enum Status { UNCONFIRMED, CONFIRMED }

    /** Ergebnis von {@link #advance}: neue Position, tatsächlich gefahrene Strecke und gegebenenfalls der Grund für den Halt. */
    public record Movement(TrainPosition position, double movedMm, Optional<Continuation> stop) {
        public boolean complete() { return stop.isEmpty(); }
    }

    private static final double EPS = 1e-6;

    public TrainPosition {
        Objects.requireNonNull(head, "Zugspitze fehlt");
        Objects.requireNonNull(towardHead, "Richtung zur Zugspitze fehlt");
        Objects.requireNonNull(status, "Status fehlt");
        if (!(fixSigmaMm >= 0) || !Double.isFinite(fixSigmaMm))
            throw new IllegalArgumentException("Unsicherheit des Abgleichs ungültig: " + fixSigmaMm);
        if (!(distanceSinceFixMm >= 0) || !Double.isFinite(distanceSinceFixMm))
            throw new IllegalArgumentException("Strecke seit dem Abgleich ungültig: " + distanceSinceFixMm);
    }

    /** Neu aufgegleister bzw. nach Neustart angenommener Zug: unbestätigt (Grundsatz 5). */
    public static TrainPosition unconfirmed(DirectedLocation head, Direction towardHead, double sigmaMm) {
        return new TrainPosition(head, towardHead, sigmaMm, 0, Status.UNCONFIRMED);
    }

    /** Bestätigte Position (z. B. aus einem Abgleich am Meldepunkt, Zug per RailCom erkannt). */
    public static TrainPosition confirmed(DirectedLocation head, Direction towardHead, double sigmaMm) {
        return new TrainPosition(head, towardHead, sigmaMm, 0, Status.CONFIRMED);
    }

    public boolean isConfirmed() { return status == Status.CONFIRMED; }

    /** Dieselbe Position, als bestätigt markiert. */
    public TrainPosition confirmed() { return new TrainPosition(head, towardHead, fixSigmaMm, distanceSinceFixMm, Status.CONFIRMED); }

    /** Dieselbe Position, als unbestätigt markiert (z. B. bei Verbindungsverlust oder unerklärter Abweichung). */
    public TrainPosition unconfirmed() { return new TrainPosition(head, towardHead, fixSigmaMm, distanceSinceFixMm, Status.UNCONFIRMED); }

    /** Positionsunsicherheit (Standardabweichung) wie {@link PositionEstimator#sigmaMm}: Abgleich und Strecke seither. */
    public double sigmaMm(PositionUncertainty u) {
        Objects.requireNonNull(u);
        return Math.hypot(fixSigmaMm, u.odometrySigmaMm(distanceSinceFixMm));
    }

    /** Befahrene Kante der Zugspitze mit Fahrtrichtung. */
    public DirectedEdge headEdge(TrackGraph graph) { return new DirectedEdge(graph.edge(head.edgeId()), head.forward()); }

    /** mm der Zugspitze ab dem Anfang ihrer Kante in Fahrtrichtung. */
    public double headAlongMm(TrackGraph graph) { return headEdge(graph).toEdgeOffset(head.offsetMm()); }

    /**
     * Zugspitze um {@code distanceMm} in Fahrtrichtung vorrücken (Schätzung, nicht Bewegung des Zuges). Endet das
     * Gleis vorher oder liegt eine Weiche unbekannt oder falsch, bleibt die Spitze am letzten befahrbaren Kantenende
     * und {@link Movement#stop()} nennt den Grund – über eine unbekannte Lage wird nie hinweggerechnet; der Aufrufer
     * muss dann halten (im Zweifel halt). Die Strecke zählt zur Strecke seit dem Abgleich.
     */
    public Movement advance(TrackGraph graph, double distanceMm, TurnoutStates states) {
        if (!(distanceMm >= 0) || !Double.isFinite(distanceMm))
            throw new IllegalArgumentException("Strecke muss endlich und nicht negativ sein: " + distanceMm);
        graph.requireLocation(head.location());
        TrackGraph.FollowResult r = graph.follow(headEdge(graph), headAlongMm(graph), distanceMm, states);
        GraphPath p = r.path();
        DirectedLocation newHead = new DirectedLocation(p.end(), p.edges().getLast().forward());
        TrainPosition moved = new TrainPosition(newHead, towardHead, fixSigmaMm, distanceSinceFixMm + p.lengthMm(), status);
        return new Movement(moved, p.lengthMm(), r.stop());
    }

    /** Abgleich: Zugspitze auf {@code measuredHead} setzen, Unsicherheit des Abgleichs {@code newFixSigmaMm}, Strecke seither 0. */
    public TrainPosition corrected(DirectedLocation measuredHead, double newFixSigmaMm) {
        return new TrainPosition(measuredHead, towardHead, newFixSigmaMm, 0, status);
    }

    /**
     * Richtungswechsel: der bisherige Zugschluss {@code tail} (Ort mit der bisherigen Fahrtrichtung, wie ihn
     * {@link TrainExtent#tail()} liefert) wird zur neuen Spitze, die Decoder-Richtung kehrt sich um. Die
     * Unsicherheit bleibt, sie wächst mit der weiteren Fahrt.
     */
    public TrainPosition reversedAt(DirectedLocation tail) {
        return new TrainPosition(tail.reversed(), towardHead.opposite(), fixSigmaMm, distanceSinceFixMm, status);
    }

    /**
     * Wegkoordinaten auf {@code path}, an denen die Zugspitze in passender Fahrtrichtung liegt, aufsteigend (leer =
     * Spitze liegt nicht auf dem Weg oder der Weg führt in die Gegenrichtung). Ein Weg kann dieselbe Stelle mehrmals
     * enthalten (Oval).
     */
    public List<Double> coordinatesOn(GraphPath path) { return coordinatesOf(path, head); }

    /** Zugspitze an Wegkoordinate {@code s}, Fahrtrichtung wie der Weg dort (Rückrechnung zu {@link #coordinatesOn}). */
    public static DirectedLocation headAt(GraphPath path, double s) {
        return new DirectedLocation(path.locate(s), path.directedEdgeAt(s).forward());
    }

    /** Wegkoordinaten, an denen {@code path} den Ort in dessen Richtung berührt, aufsteigend. */
    static List<Double> coordinatesOf(GraphPath path, DirectedLocation at) {
        List<Double> out = new ArrayList<>();
        double cumulative = 0;
        List<DirectedEdge> edges = path.edges();
        for (DirectedEdge e : edges) {
            if (e.edgeId().equals(at.edgeId()) && e.forward() == at.forward() && at.offsetMm() <= e.lengthMm() + EPS) {
                double s = cumulative + e.toEdgeOffset(at.offsetMm()) - path.startOffsetMm();
                if (s >= -EPS && s <= path.lengthMm() + EPS) {
                    double clamped = Math.min(Math.max(s, 0), path.lengthMm());
                    if (out.isEmpty() || Math.abs(out.getLast() - clamped) > EPS) out.add(clamped);
                }
            }
            cumulative += e.lengthMm();
        }
        return out;
    }
}
