package at.schmoegl.railcore.train;

import at.schmoegl.railcore.layout.Block;
import at.schmoegl.railcore.layout.BlockEnd;
import at.schmoegl.railcore.layout.BlockSide;
import at.schmoegl.railcore.layout.Layout;
import at.schmoegl.railcore.layout.Route;
import at.schmoegl.railcore.layout.Sensor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Wo ein Zug auf der Anlage steht: Folge der befahrenen Abschnitte (Blöcke und Fahrstraßen) von hinten nach vorne,
 * plus Position der Zugspitze im vordersten Abschnitt. Daraus ergibt sich, welche Blöcke und Rückmelder der Zug
 * in seiner ganzen Länge belegt.
 */
public final class TrackPath {

    /** Ein befahrener Abschnitt. Bei Blöcken gibt towardB die Fahrtrichtung im Block an. */
    public record Segment(Kind kind, String id, double length, boolean towardB) {
        public enum Kind { BLOCK, ROUTE }
    }

    /** Liefert die Fahrstraße, über die ein Zug ein Blockende verlässt (oder leer, wenn dort Schluss ist). */
    @FunctionalInterface
    public interface RouteResolver {
        Optional<Route> next(BlockSide exit);
    }

    /** Belegter Bereich in einem Block (Blockkoordinaten ab Ende A). */
    public record Occupancy(String blockId, double fromMm, double toMm) {}

    private final Layout layout;
    private final Deque<Segment> segments = new ArrayDeque<>();
    private double headProgress;   // Strecke der Zugspitze im letzten Segment ab dessen Eintritt
    private double trainLength;
    private boolean blocked;
    private String blockedReason;

    /** Zug steht im Block; headPos = Blockkoordinate der Zugspitze, Fahrtrichtung towardB. */
    public TrackPath(Layout layout, String blockId, double headPos, boolean towardB, double trainLength) {
        this.layout = layout;
        this.trainLength = trainLength;
        Block b = layout.block(blockId);
        segments.add(new Segment(Segment.Kind.BLOCK, blockId, b.lengthMm(), towardB));
        headProgress = towardB ? headPos : b.lengthMm() - headPos;
    }

    public void setTrainLength(double l) { trainLength = l; }

    public double trainLength() { return trainLength; }

    public Segment head() { return segments.getLast(); }

    /** Blockkoordinate der Zugspitze (nur wenn die Spitze in einem Block ist). */
    public double headBlockPos() {
        Segment s = head();
        return s.towardB() ? headProgress : s.length() - headProgress;
    }

    public double headProgress() { return headProgress; }

    /** Strecke bis zum Ende des Abschnitts, in dem die Zugspitze ist. */
    public double remainingInHead() { return Math.max(0, head().length() - headProgress); }

    public boolean isBlocked() { return blocked; }

    public String blockedReason() { return blockedReason; }

    public String headBlockId() {
        Iterator<Segment> it = segments.descendingIterator();
        while (it.hasNext()) {
            Segment s = it.next();
            if (s.kind() == Segment.Kind.BLOCK) return s.id();
        }
        return null;
    }

    /** Blockende, auf das der Zug in seinem vordersten Block zufährt. */
    public BlockSide headExit() {
        Segment s = head();
        if (s.kind() != Segment.Kind.BLOCK) return null;
        return new BlockSide(s.id(), s.towardB() ? BlockEnd.B : BlockEnd.A);
    }

    /**
     * Zugspitze um distance mm vorrücken. Am Blockende wird über den Resolver die nächste Fahrstraße gesucht.
     * Gibt es keine, bleibt der Zug am Blockende stehen (blocked = true) – im Simulator heißt das Prellbock.
     *
     * @return tatsächlich zurückgelegte Strecke
     */
    public double advance(double distance, RouteResolver resolver) {
        double moved = 0;
        while (distance > 1e-9) {
            double room = head().length() - headProgress;
            if (distance <= room) {
                headProgress += distance;
                moved += distance;
                break;
            }
            headProgress = head().length();
            moved += room;
            distance -= room;
            if (!enterNext(resolver)) break;
        }
        trimTail();
        return moved;
    }

    /** Korrektur der Position (Abgleich durch Rückmelder): Zugspitze auf Blockkoordinate setzen. */
    public void correctHeadInBlock(String blockId, double blockPos) {
        Segment s = head();
        if (s.kind() == Segment.Kind.BLOCK && s.id().equals(blockId)) {
            headProgress = Math.max(0, Math.min(s.length(), s.towardB() ? blockPos : s.length() - blockPos));
        }
    }

    private boolean enterNext(RouteResolver resolver) {
        Segment h = head();
        if (h.kind() == Segment.Kind.BLOCK) {
            BlockSide exit = new BlockSide(h.id(), h.towardB() ? BlockEnd.B : BlockEnd.A);
            Optional<Route> r = resolver.next(exit);
            if (r.isEmpty()) {
                blocked = true;
                blockedReason = "Kein Weg ab " + exit;
                return false;
            }
            segments.add(new Segment(Segment.Kind.ROUTE, r.get().id(), r.get().lengthMm(), r.get().from().equals(exit)));
        } else {
            Route r = layout.route(h.id()).orElseThrow();
            BlockSide entry = h.towardB() ? r.to() : r.from();
            Block b = layout.block(entry.blockId());
            segments.add(new Segment(Segment.Kind.BLOCK, b.id(), b.lengthMm(), entry.end() == BlockEnd.A));
        }
        headProgress = 0;
        blocked = false;
        blockedReason = null;
        return true;
    }

    /** Hintere Abschnitte entfernen, die der Zug ganz verlassen hat. */
    private void trimTail() {
        double behind = headProgress;
        Iterator<Segment> it = segments.descendingIterator();
        it.next();
        int keep = 1;
        while (it.hasNext() && behind < trainLength) {
            behind += it.next().length();
            keep++;
        }
        while (segments.size() > keep) segments.removeFirst();
    }

    /** Alle von der Zuglänge belegten Bereiche in Blöcken. */
    public List<Occupancy> occupancy() {
        List<Occupancy> out = new ArrayList<>();
        double front = headProgress;   // Abstand der Zugspitze vom Segmentanfang
        double rest = trainLength;
        Iterator<Segment> it = segments.descendingIterator();
        boolean first = true;
        while (it.hasNext() && rest > 1e-9) {
            Segment s = it.next();
            double hi = first ? front : s.length();
            first = false;
            double lo = Math.max(0, hi - rest);
            rest -= (hi - lo);
            if (s.kind() == Segment.Kind.BLOCK) {
                double a = s.towardB() ? lo : s.length() - hi;
                double b = s.towardB() ? hi : s.length() - lo;
                out.add(new Occupancy(s.id(), a, b));
            }
        }
        return out;
    }

    /** Welche Fahrstraßen der Zug gerade (teilweise) belegt. */
    public List<String> occupiedRoutes() {
        List<String> out = new ArrayList<>();
        double rest = trainLength;
        Iterator<Segment> it = segments.descendingIterator();
        boolean first = true;
        while (it.hasNext() && rest > 1e-9) {
            Segment s = it.next();
            double len = first ? headProgress : s.length();
            first = false;
            if (s.kind() == Segment.Kind.ROUTE && len > 0) out.add(s.id());
            rest -= len;
        }
        return out;
    }

    /** Ist der Rückmelder vom Zug belegt? */
    public boolean covers(String blockId, Sensor sensor) {
        for (Occupancy o : occupancy())
            if (o.blockId().equals(blockId) && o.fromMm() <= sensor.toMm() && o.toMm() >= sensor.fromMm()) return true;
        return false;
    }

    /** Kopie für Vorausberechnungen. */
    public List<Segment> segments() { return List.copyOf(segments); }
}
