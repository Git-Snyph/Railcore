package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.layout.graph.Coverage.Interval;
import at.schmoegl.railcore.layout.graph.LayoutFinding.Code;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Prüfung des Gleisplans und Aufbau der Indizes für {@link TrackLayout} (Lastenheft 4 und 7, „Gleisplan prüfen“).
 * Läuft einmal im Konstruktor; danach werden die Felder nur noch gelesen.
 *
 * <p>Grundsatz: Eine Angabe, die sich nicht eindeutig prüfen lässt, ist ein Fehler bzw. fehlt im Index – nie wird
 * eine fehlerhafte Angabe stillschweigend als gültig übernommen.
 */
final class LayoutChecker {

    private static final double TOL = Coverage.TOL;
    /** Toleranz beim Zuordnen einer eingetragenen Abschnittsgrenze zu einer abgeleiteten (Meldepunkt überschreiben). */
    private static final double MATCH_TOL = 0.5;
    private static final int LIST_LIMIT = 8;

    private final TrackLayout.Spec spec;
    private final TrackGraph graph;
    private final Map<String, TrackEdge> edges = new LinkedHashMap<>();

    final List<LayoutFinding> findings = new ArrayList<>();
    final Map<String, DetectionSection> sections = new LinkedHashMap<>();
    final Map<Integer, DetectionSection> byContact = new LinkedHashMap<>();
    final Map<String, Block> blocks = new LinkedHashMap<>();
    final Map<Integer, BoosterDistrict> districts = new LinkedHashMap<>();
    final Map<String, BlockSignal> signals = new LinkedHashMap<>();
    final Map<String, HoldingArea> holdingAreas = new LinkedHashMap<>();
    final Map<String, StopPoint> stopPoints = new LinkedHashMap<>();
    final Map<String, SpeedChangePoint> speedPoints = new LinkedHashMap<>();
    final Map<String, TurnoutAddress> turnoutAddresses = new LinkedHashMap<>();
    final Map<String, String> blockOfSection = new HashMap<>();
    final Map<String, BlockGeometry> geometries = new LinkedHashMap<>();
    final Map<String, List<ReportPoint>> reportPoints = new LinkedHashMap<>();
    final Coverage sectionCoverage;
    final Coverage districtCoverage;
    final Coverage blockCoverage;

    /** Gültige Bereiche je Meldeabschnitt. */
    private final Map<String, List<TrackRange>> validRanges = new LinkedHashMap<>();

    LayoutChecker(TrackLayout.Spec spec) {
        this.spec = spec;
        this.graph = spec.graph();
        for (TrackEdge e : graph.edges()) edges.put(e.id(), e);

        collectObjects();
        sectionCoverage = buildSectionCoverage();
        districtCoverage = buildDistrictCoverage();
        checkOverlaps(sectionCoverage, Code.OVERLAPPING_SECTIONS, "Meldeabschnitte", true);
        checkOverlaps(districtCoverage, Code.OVERLAPPING_DISTRICTS, "Bezirke", false);
        blockCoverage = checkBlocks();
        deriveReportPoints();
        checkSignalsAndAddresses();
        checkHoldingAreas();
        checkPoints();
        checkUnverifiedLengths();
        checkTrackCoverage();
        checkTurnoutSections();
        checkComponents();
        checkBlockExits();
        checkReversingLoops();
        checkDoubleSlipLogic();
    }

    private void add(Code code, String subject, String message) { findings.add(LayoutFinding.of(code, subject, message)); }

    // --- Objekte, Kennungen, Kontakte ---------------------------------------------------------------------------

    private void collectObjects() {
        for (DetectionSection s : spec.sections()) {
            if (sections.putIfAbsent(s.id(), s) != null) {
                add(Code.DUPLICATE_ID, s.id(), "Meldeabschnitt " + s.id() + " mehrfach angegeben");
                continue;
            }
            DetectionSection other = byContact.putIfAbsent(s.contact(), s);
            if (other != null)
                add(Code.DUPLICATE_CONTACT, s.id(), "Kontakt " + s.contact() + " gehört zu den Meldeabschnitten "
                        + other.id() + " und " + s.id());
        }
        for (BoosterDistrict d : spec.districts())
            if (districts.putIfAbsent(d.id(), d) != null)
                add(Code.DUPLICATE_ID, "Bezirk " + d.id(), "Bezirk " + d.id() + " mehrfach angegeben");
        for (Block b : spec.blocks())
            if (blocks.putIfAbsent(b.id(), b) != null) add(Code.DUPLICATE_ID, b.id(), "Block " + b.id() + " mehrfach angegeben");
        for (BlockSignal s : spec.signals())
            if (signals.putIfAbsent(s.id(), s) != null) add(Code.DUPLICATE_ID, s.id(), "Signal " + s.id() + " mehrfach angegeben");
        for (HoldingArea h : spec.holdingAreas())
            if (holdingAreas.putIfAbsent(h.id(), h) != null)
                add(Code.DUPLICATE_ID, h.id(), "Haltebereich " + h.id() + " mehrfach angegeben");
        for (StopPoint p : spec.stopPoints())
            if (stopPoints.putIfAbsent(p.id(), p) != null)
                add(Code.DUPLICATE_ID, p.id(), "Haltepunkt " + p.id() + " mehrfach angegeben");
        for (SpeedChangePoint p : spec.speedChangePoints())
            if (speedPoints.putIfAbsent(p.id(), p) != null)
                add(Code.DUPLICATE_ID, p.id(), "Geschwindigkeitswechselpunkt " + p.id() + " mehrfach angegeben");
        for (TurnoutAddress a : spec.turnoutAddresses())
            if (turnoutAddresses.putIfAbsent(a.turnoutId(), a) != null)
                add(Code.DUPLICATE_ID, a.turnoutId(), "Adresse der Weiche " + a.turnoutId() + " mehrfach angegeben");
    }

    /** Bereich gültig (Kante bekannt, innerhalb der Kante, mit Länge)? Sonst Befund. */
    private boolean validRange(TrackRange r, String subject, String what) {
        TrackEdge e = edges.get(r.edgeId());
        if (e == null) {
            add(Code.UNKNOWN_REFERENCE, subject, what + ": unbekannte Kante " + r.edgeId());
            return false;
        }
        if (r.toMm() > e.lengthMm() + TOL) {
            add(Code.INVALID_LOCATION, subject, what + ": Bereich " + r.fromMm() + "–" + r.toMm() + " mm liegt hinter dem Ende"
                    + " der Kante " + e.id() + " (" + e.lengthMm() + " mm)");
            return false;
        }
        if (r.lengthMm() <= TOL) {
            add(Code.INVALID_LOCATION, subject, what + ": Bereich auf " + e.id() + " ohne Länge");
            return false;
        }
        return true;
    }

    /** Ort gültig? Sonst Befund. */
    private boolean validLocation(TrackLocation l, String subject, String what) {
        TrackEdge e = edges.get(l.edgeId());
        if (e == null) {
            add(Code.UNKNOWN_REFERENCE, subject, what + ": unbekannte Kante " + l.edgeId());
            return false;
        }
        if (l.offsetMm() > e.lengthMm() + TOL) {
            add(Code.INVALID_LOCATION, subject, what + ": " + l.offsetMm() + " mm liegt hinter dem Ende der Kante " + e.id()
                    + " (" + e.lengthMm() + " mm)");
            return false;
        }
        return true;
    }

    private Coverage buildSectionCoverage() {
        Coverage.Builder b = Coverage.builder();
        for (DetectionSection s : sections.values()) {
            List<TrackRange> ok = new ArrayList<>();
            for (TrackRange r : s.ranges())
                if (validRange(r, s.id(), "Meldeabschnitt " + s.id())) {
                    ok.add(r);
                    b.add(r, s.id());
                }
            validRanges.put(s.id(), List.copyOf(ok));
        }
        return b.build();
    }

    private Coverage buildDistrictCoverage() {
        Coverage.Builder b = Coverage.builder();
        for (DetectionSection s : sections.values()) {
            if (s.district().isEmpty()) continue;
            int d = s.district().getAsInt();
            if (!districts.containsKey(d)) {
                add(Code.UNKNOWN_REFERENCE, s.id(), "Meldeabschnitt " + s.id() + ": unbekannter Bezirk " + d);
                continue;
            }
            for (TrackRange r : validRanges.get(s.id())) b.add(r, Integer.toString(d));
        }
        for (BoosterDistrict d : districts.values())
            for (TrackRange r : d.extraRanges())
                if (validRange(r, "Bezirk " + d.id(), "Bezirk " + d.id())) b.add(r, Integer.toString(d.id()));
        return b.build();
    }

    /** Überlappungen mit positiver Länge; {@code sameOwnerCounts}: auch Bereiche desselben Eigentümers. */
    private void checkOverlaps(Coverage c, Code code, String what, boolean sameOwnerCounts) {
        Set<String> reported = new HashSet<>();
        for (String edge : c.edges()) {
            List<Interval> raw = c.raw(edge);
            for (int i = 0; i < raw.size(); i++)
                for (int j = i + 1; j < raw.size() && raw.get(j).from() < raw.get(i).to() - TOL; j++) {
                    String a = raw.get(i).owner();
                    String b = raw.get(j).owner();
                    if (a.equals(b) && !sameOwnerCounts) continue;
                    String key = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
                    if (!reported.add(key)) continue;
                    String subject = a.equals(b) ? a : key.replace("|", ", ");
                    add(code, subject, what + " " + (a.equals(b) ? a + " (mit sich selbst)" : a + " und " + b)
                            + " überlappen sich auf Kante " + edge + " ab " + raw.get(j).from() + " mm");
                }
        }
    }

    // --- Blöcke -------------------------------------------------------------------------------------------------

    private Coverage checkBlocks() {
        Coverage.Builder all = Coverage.builder();
        Map<String, Coverage> extents = new LinkedHashMap<>();
        for (Block b : blocks.values()) {
            if (b.sectionIds().isEmpty()) {
                add(Code.BLOCK_WITHOUT_SENSOR, b.id(), "Block " + b.id() + " hat keinen Meldeabschnitt");
                continue;
            }
            Coverage.Builder ext = Coverage.builder();
            Set<String> seen = new HashSet<>();
            int known = 0;
            boolean usable = true;
            for (String sid : b.sectionIds()) {
                if (!sections.containsKey(sid)) {
                    add(Code.UNKNOWN_REFERENCE, b.id(), "Block " + b.id() + ": unbekannter Meldeabschnitt " + sid);
                    usable = false;
                    continue;
                }
                if (!seen.add(sid)) {
                    add(Code.BLOCK_GEOMETRY, b.id(), "Block " + b.id() + ": Meldeabschnitt " + sid + " mehrfach angegeben");
                    usable = false;
                    continue;
                }
                known++;
                String other = blockOfSection.putIfAbsent(sid, b.id());
                if (other != null) {
                    add(Code.SECTION_IN_SEVERAL_BLOCKS, sid, "Meldeabschnitt " + sid + " gehört zu den Blöcken " + other
                            + " und " + b.id());
                    usable = false;
                }
                for (TrackRange r : validRanges.get(sid)) {
                    ext.add(r, b.id());
                    all.add(r, b.id());
                }
                if (validRanges.get(sid).size() != sections.get(sid).ranges().size()) usable = false;
            }
            if (known > 0 && known < spec.recommendedSectionsPerBlock())
                add(Code.FEW_SENSORS_IN_BLOCK, b.id(), "Block " + b.id() + " hat " + known + " Meldeabschnitt(e), empfohlen "
                        + spec.recommendedSectionsPerBlock());
            if (!validLocation(b.endA().location(), b.id(), "Block " + b.id() + ", Ende A")) usable = false;
            if (usable && known > 0) extents.put(b.id(), ext.build());
        }
        Coverage blockCov = all.build();
        for (Map.Entry<String, Coverage> e : extents.entrySet()) {
            BlockGeometry g = geometry(blocks.get(e.getKey()), e.getValue());
            if (g != null) geometries.put(e.getKey(), g);
        }
        return blockCov;
    }

    private record Visit(DirectedEdge edge, int interval) {}

    /** Lage des Blocks aus seinen Bereichen, ausgehend von Ende A; {@code null} und Befund bei Fehler. */
    private BlockGeometry geometry(Block b, Coverage ext) {
        DirectedLocation a = b.endA();
        DirectedEdge d0 = new DirectedEdge(edges.get(a.edgeId()), a.forward());
        double along0 = d0.toEdgeOffset(a.offsetMm());
        int idx0 = -1;
        List<Interval> m0 = ext.merged(d0.edgeId());
        for (int i = 0; i < m0.size(); i++)
            if (Math.abs(Coverage.along(d0, m0.get(i))[0] - along0) <= MATCH_TOL) idx0 = i;
        boolean atRim = idx0 >= 0;
        if (atRim && Coverage.along(d0, m0.get(idx0))[0] <= TOL)
            for (DirectedEdge p : predecessors(d0)) if (intervalAtEnd(ext, p) >= 0) atRim = false;
        if (!atRim) {
            add(Code.BLOCK_GEOMETRY, b.id(), "Block " + b.id() + ": Ende A " + a + " liegt nicht am Rand des Blocks"
                    + " oder zeigt nicht in den Block");
            return null;
        }

        Map<String, Boolean> orientation = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<Visit> queue = new ArrayDeque<>();
        List<DirectedLocation> endsA = new ArrayList<>();
        List<DirectedLocation> endsB = new ArrayList<>();
        boolean[] conflict = {false};
        enqueue(new Visit(d0, idx0), orientation, visited, queue, conflict);
        double length = 0;
        while (!queue.isEmpty()) {
            Visit v = queue.poll();
            DirectedEdge de = v.edge();
            Interval iv = ext.merged(de.edgeId()).get(v.interval());
            length += iv.length();
            double[] st = Coverage.along(de, iv);
            double len = de.lengthMm();
            if (st[1] < len - TOL) {
                addEnd(endsB, new DirectedLocation(de.locationAt(st[1]), de.forward()));
            } else {
                List<DirectedEdge> next = successors(de);
                boolean open = next.isEmpty();
                for (DirectedEdge n : next) {
                    int j = intervalAtStart(ext, n);
                    if (j >= 0) enqueue(new Visit(n, j), orientation, visited, queue, conflict);
                    else open = true;
                }
                if (open) addEnd(endsB, new DirectedLocation(de.locationAt(len), de.forward()));
            }
            if (st[0] > TOL) {
                addEnd(endsA, new DirectedLocation(de.locationAt(st[0]), !de.forward()));
            } else {
                List<DirectedEdge> prev = predecessors(de);
                boolean open = prev.isEmpty();
                for (DirectedEdge p : prev) {
                    int j = intervalAtEnd(ext, p);
                    if (j >= 0) enqueue(new Visit(p, j), orientation, visited, queue, conflict);
                    else open = true;
                }
                if (open) addEnd(endsA, new DirectedLocation(de.locationAt(0), !de.forward()));
            }
        }
        if (conflict[0]) {
            add(Code.BLOCK_GEOMETRY, b.id(), "Block " + b.id() + ": Richtung A → B nicht eindeutig (Block führt in sich"
                    + " zurück, z. B. über eine Kehrschleife)");
            return null;
        }
        int total = 0;
        for (String e : ext.edges()) total += ext.merged(e).size();
        if (visited.size() < total) {
            add(Code.BLOCK_GEOMETRY, b.id(), "Block " + b.id() + " ist nicht zusammenhängend");
            return null;
        }
        return new BlockGeometry(b.id(), length, endsA, endsB, orientation);
    }

    private static void enqueue(Visit v, Map<String, Boolean> orientation, Set<String> visited, Deque<Visit> queue,
                                boolean[] conflict) {
        Boolean known = orientation.putIfAbsent(v.edge().edgeId(), v.edge().forward());
        if (known != null && known != v.edge().forward()) {
            conflict[0] = true;
            return;
        }
        if (visited.add(v.edge().edgeId() + "#" + v.interval())) queue.add(v);
    }

    private static void addEnd(List<DirectedLocation> ends, DirectedLocation x) {
        for (DirectedLocation e : ends) if (e.sameAs(x, TOL)) return;
        ends.add(x);
    }

    /** Index des vereinigten Bereichs auf {@code d}, der am Anfang (in Fahrrichtung) beginnt, sonst −1. */
    private static int intervalAtStart(Coverage c, DirectedEdge d) {
        List<Interval> m = c.merged(d.edgeId());
        for (int i = 0; i < m.size(); i++) {
            double[] st = Coverage.along(d, m.get(i));
            if (st[0] <= TOL && st[1] > TOL) return i;
        }
        return -1;
    }

    /** Index des vereinigten Bereichs auf {@code d}, der am Ende (in Fahrrichtung) endet, sonst −1. */
    private static int intervalAtEnd(Coverage c, DirectedEdge d) {
        List<Interval> m = c.merged(d.edgeId());
        for (int i = 0; i < m.size(); i++) {
            double[] st = Coverage.along(d, m.get(i));
            if (st[1] >= d.lengthMm() - TOL && st[0] < d.lengthMm() - TOL) return i;
        }
        return -1;
    }

    /** Fortsetzungen am Ende von {@code d} (alle Weichenlagen), Verbindungskanten ohne Länge übersprungen. */
    private List<DirectedEdge> successors(DirectedEdge d) {
        List<DirectedEdge> out = new ArrayList<>();
        collectSuccessors(d, out, new HashSet<>());
        return out;
    }

    private void collectSuccessors(DirectedEdge d, List<DirectedEdge> out, Set<String> seenConnectors) {
        for (TrackGraph.Step s : graph.steps(d)) {
            DirectedEdge n = s.next();
            if (n.edge().role() == TrackEdge.Role.CONNECTOR) {
                if (seenConnectors.add(n.edgeId())) collectSuccessors(n, out, seenConnectors);
            } else if (!out.contains(n)) {
                out.add(n);
            }
        }
    }

    /** Kanten, über die man (in derselben Fahrrichtung) am Anfang von {@code d} ankommt. */
    private List<DirectedEdge> predecessors(DirectedEdge d) {
        List<DirectedEdge> out = new ArrayList<>();
        for (DirectedEdge p : successors(d.reversed())) out.add(p.reversed());
        return out;
    }

    // --- Meldepunkte --------------------------------------------------------------------------------------------

    private void deriveReportPoints() {
        Map<String, List<ReportPoint>> derived = new LinkedHashMap<>();
        Map<String, Coverage> own = new HashMap<>();
        for (DetectionSection s : sections.values()) {
            Coverage.Builder cb = Coverage.builder();
            for (TrackRange r : validRanges.get(s.id())) cb.add(r, s.id());
            Coverage c = cb.build();
            own.put(s.id(), c);
            List<ReportPoint> points = new ArrayList<>();
            for (String edgeId : c.edges())
                for (Interval iv : c.merged(edgeId))
                    for (boolean forward : new boolean[] {true, false}) {
                        DirectedEdge de = new DirectedEdge(edges.get(edgeId), forward);
                        double start = Coverage.along(de, iv)[0];
                        if (start <= TOL) {
                            List<DirectedEdge> prev = predecessors(de);
                            if (prev.isEmpty()) continue; // vom Gleisende her fährt niemand ein
                            boolean inside = false;
                            for (DirectedEdge p : prev) if (intervalAtEnd(c, p) >= 0) inside = true;
                            if (inside) continue;
                            start = 0;
                        }
                        DirectedLocation at = new DirectedLocation(de.locationAt(start), forward);
                        points.add(new ReportPoint(s.id(), at, at, false));
                    }
            derived.put(s.id(), points);
        }
        for (ReportPointOverride o : spec.reportPointOverrides()) {
            List<ReportPoint> points = derived.get(o.sectionId());
            if (points == null) {
                add(Code.UNKNOWN_REFERENCE, o.sectionId(), "Meldepunkt: unbekannter Meldeabschnitt " + o.sectionId());
                continue;
            }
            int idx = -1;
            for (int i = 0; i < points.size(); i++)
                if (!points.get(i).overridden() && points.get(i).boundary().sameAs(o.boundary(), MATCH_TOL)) idx = i;
            if (idx < 0) {
                add(Code.UNKNOWN_REFERENCE, o.sectionId(), "Meldepunkt " + o.sectionId() + ": an " + o.boundary()
                        + " gibt es keine Einfahrt in den Abschnitt");
                continue;
            }
            if (!validLocation(o.at().location(), o.sectionId(), "Meldepunkt " + o.sectionId())) continue;
            if (!own.get(o.sectionId()).coversPoint(o.at().edgeId(), o.at().offsetMm())) {
                add(Code.INVALID_LOCATION, o.sectionId(), "Meldepunkt " + o.sectionId() + ": " + o.at()
                        + " liegt nicht im Meldeabschnitt");
                continue;
            }
            points.set(idx, new ReportPoint(o.sectionId(), points.get(idx).boundary(), o.at(), true));
        }
        for (Map.Entry<String, List<ReportPoint>> e : derived.entrySet()) reportPoints.put(e.getKey(), List.copyOf(e.getValue()));
    }

    // --- Signale, Adressen, Haltebereiche, Punkte, Längen -------------------------------------------------------

    private void checkSignalsAndAddresses() {
        Map<Integer, String> owners = new LinkedHashMap<>();
        Set<String> turnoutIds = new HashSet<>();
        for (TrackNode n : graph.nodes()) {
            if (n instanceof TrackNode.TurnoutNode t) turnoutIds.add(t.turnoutId());
            if (n instanceof TrackNode.DoubleSlipNode d) {
                turnoutIds.add(d.turnoutIdA());
                turnoutIds.add(d.turnoutIdB());
            }
        }
        for (TurnoutAddress a : turnoutAddresses.values()) {
            if (!turnoutIds.contains(a.turnoutId()))
                add(Code.UNKNOWN_REFERENCE, a.turnoutId(), "Adresse für unbekannte Weiche " + a.turnoutId());
            claim(owners, a.address(), "Weiche " + a.turnoutId());
        }
        for (BlockSignal s : signals.values()) {
            if (!blocks.containsKey(s.blockId()))
                add(Code.UNKNOWN_REFERENCE, s.id(), "Signal " + s.id() + ": unbekannter Block " + s.blockId());
            for (int a : s.addresses()) claim(owners, a, "Signal " + s.id());
        }
    }

    private void claim(Map<Integer, String> owners, int address, String owner) {
        String other = owners.putIfAbsent(address, owner);
        if (other != null)
            add(Code.DUPLICATE_ADDRESS, owner, "Adresse " + address + " gehört zu " + other + " und " + owner);
    }

    private void checkHoldingAreas() {
        for (HoldingArea h : holdingAreas.values()) {
            boolean ok = true;
            for (String b : h.blockIds())
                if (!blocks.containsKey(b)) {
                    add(Code.UNKNOWN_REFERENCE, h.id(), "Haltebereich " + h.id() + ": unbekannter Block " + b);
                    ok = false;
                }
            if (!ok) continue;
            for (int i = 0; i + 1 < h.blockIds().size(); i++) {
                String b1 = h.blockIds().get(i);
                String b2 = h.blockIds().get(i + 1);
                BlockGeometry g1 = geometries.get(b1);
                BlockGeometry g2 = geometries.get(b2);
                if (g1 == null || g2 == null) continue; // Geometriefehler ist schon gemeldet
                if (b1.equals(b2) || !adjacent(g1, g2))
                    add(Code.HOLDING_AREA_NOT_CONTIGUOUS, h.id(), "Haltebereich " + h.id() + ": Blöcke " + b1 + " und " + b2
                            + " stoßen nicht aneinander");
            }
        }
    }

    /** Stößt ein Ende von {@code g1} unmittelbar an ein Ende von {@code g2}? */
    private boolean adjacent(BlockGeometry g1, BlockGeometry g2) {
        List<DirectedLocation> ends1 = new ArrayList<>(g1.endsA());
        ends1.addAll(g1.endsB());
        List<DirectedLocation> ends2 = new ArrayList<>(g2.endsA());
        ends2.addAll(g2.endsB());
        for (DirectedLocation x : ends1)
            for (DirectedLocation y : ends2) {
                if (x.reversed().sameAs(y, TOL)) return true; // gleiche Stelle auf derselben Kante, Gegenrichtung
                // am Knoten: x zeigt am Kantenende hinaus, y kommt aus einer Folgekante zurück
                DirectedEdge dx = new DirectedEdge(edges.get(x.edgeId()), x.forward());
                if (Math.abs(dx.toEdgeOffset(x.offsetMm()) - dx.lengthMm()) > TOL) continue;
                DirectedEdge dy = new DirectedEdge(edges.get(y.edgeId()), y.forward());
                if (Math.abs(dy.toEdgeOffset(y.offsetMm()) - dy.lengthMm()) > TOL) continue;
                if (successors(dx).contains(dy.reversed())) return true;
            }
        return false;
    }

    private void checkPoints() {
        for (StopPoint p : stopPoints.values()) validLocation(p.at().location(), p.id(), "Haltepunkt " + p.id());
        for (SpeedChangePoint p : speedPoints.values())
            validLocation(p.at().location(), p.id(), "Geschwindigkeitswechselpunkt " + p.id());
    }

    private void checkUnverifiedLengths() {
        Set<String> pieces = new HashSet<>();
        for (TrackEdge e : edges.values()) if (e.pieceInstanceId() != null) pieces.add(e.pieceInstanceId());
        for (String id : new TreeSet<>(spec.unverifiedLengthPieces())) {
            if (!pieces.contains(id)) add(Code.UNKNOWN_REFERENCE, id, "Länge unbekannt: unbekanntes Gleisstück " + id);
            else add(Code.UNKNOWN_LENGTH, id, "Gleisstück " + id + ": Länge unbekannt bzw. geschätzt");
        }
    }

    // --- Abdeckung ----------------------------------------------------------------------------------------------

    private static boolean isTurnoutEdge(TrackEdge e) {
        return e.role() == TrackEdge.Role.TURNOUT_STRAIGHT || e.role() == TrackEdge.Role.TURNOUT_DIVERGING
                || e.role() == TrackEdge.Role.DOUBLE_SLIP_ROUTE;
    }

    private void checkTrackCoverage() {
        for (TrackEdge e : edges.values()) {
            if (e.role() == TrackEdge.Role.CONNECTOR) continue;
            if (!isTurnoutEdge(e)) {
                List<Interval> gaps = sectionCoverage.gaps(e.id(), e.lengthMm());
                if (!gaps.isEmpty())
                    add(Code.TRACK_WITHOUT_SENSOR, e.id(), "Kante " + e.id() + " ohne Meldeabschnitt: " + describe(gaps));
            }
            List<Interval> gaps = districtCoverage.gaps(e.id(), e.lengthMm());
            if (!gaps.isEmpty()) add(Code.TRACK_WITHOUT_DISTRICT, e.id(), "Kante " + e.id() + " ohne Bezirk: " + describe(gaps));
        }
    }

    private static String describe(List<Interval> gaps) {
        List<String> parts = new ArrayList<>();
        for (Interval g : gaps) parts.add(fmt(g.from()) + "–" + fmt(g.to()) + " mm");
        return String.join(", ", parts);
    }

    private static String fmt(double mm) { return String.format(java.util.Locale.ROOT, "%.1f", mm); }

    private void checkTurnoutSections() {
        for (TrackNode n : graph.nodes()) {
            List<TrackEdge> branches = new ArrayList<>();
            String name;
            if (n instanceof TrackNode.TurnoutNode t) {
                branches.add(graph.leaving(t.id(), Port.STRAIGHT).edge());
                branches.add(graph.leaving(t.id(), Port.DIVERGING).edge());
                name = "Weiche " + t.id();
            } else if (n instanceof TrackNode.DoubleSlipNode d && d.sideA()) {
                for (Port r : List.of(Port.A1_B1, Port.A1_B2, Port.A2_B1, Port.A2_B2)) branches.add(graph.leaving(d.id(), r).edge());
                name = "Doppelkreuzungsweiche " + branches.getFirst().pieceInstanceId();
            } else {
                continue;
            }
            for (TrackEdge e : branches)
                if (!sectionCoverage.coversAll(e.id(), 0, e.lengthMm())) {
                    add(Code.TURNOUT_WITHOUT_SECTION, n.id(), name + " nicht vollständig von einem Meldeabschnitt abgedeckt"
                            + " (Kante " + e.id() + ")");
                    break;
                }
        }
    }

    // --- Zusammenhang, Sackgassen, Ausfahrten -------------------------------------------------------------------

    private void checkComponents() {
        Map<String, String> parent = new HashMap<>();
        for (TrackNode n : graph.nodes()) parent.put(n.id(), n.id());
        for (TrackEdge e : edges.values()) union(parent, e.fromNode(), e.toNode());
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (TrackNode n : graph.nodes()) members.computeIfAbsent(find(parent, n.id()), k -> new ArrayList<>()).add(n.id());
        Set<String> withBlock = new HashSet<>();
        for (TrackEdge e : edges.values()) if (!blockCoverage.raw(e.id()).isEmpty()) withBlock.add(find(parent, e.fromNode()));
        for (Map.Entry<String, List<String>> c : members.entrySet()) {
            List<String> nodes = c.getValue();
            if (!withBlock.contains(c.getKey())) {
                add(Code.UNREACHABLE_NODE, nodes.getFirst(), "Gleisteil ohne Block, Knoten nicht erreichbar: " + list(nodes));
                continue;
            }
            boolean bufferStop = false;
            boolean branching = false;
            for (String id : nodes) {
                NodeKind k = graph.node(id).kind();
                if (k == NodeKind.BUFFER_STOP) bufferStop = true;
                if (k == NodeKind.TURNOUT || k == NodeKind.DOUBLE_SLIP) branching = true;
            }
            if (bufferStop && !branching)
                add(Code.DEAD_END_WITHOUT_EXIT, nodes.getFirst(), "Gleisteil mit Gleisende und ohne Weiche, Züge können nur"
                        + " pendeln: " + list(nodes));
        }
    }

    private static String find(Map<String, String> parent, String x) {
        String root = x;
        while (!parent.get(root).equals(root)) root = parent.get(root);
        while (!parent.get(x).equals(root)) {
            String next = parent.get(x);
            parent.put(x, root);
            x = next;
        }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    private static String list(List<String> ids) {
        if (ids.size() <= LIST_LIMIT) return String.join(", ", ids);
        return String.join(", ", ids.subList(0, LIST_LIMIT)) + " … (" + ids.size() + ")";
    }

    private void checkBlockExits() {
        for (BlockGeometry g : geometries.values()) {
            Block b = blocks.get(g.blockId());
            boolean exit = false;
            for (Block.Direction d : Block.Direction.values())
                if (b.allows(d)) for (DirectedLocation x : g.ends(d.exitEnd())) if (reachesBlock(b.id(), x)) exit = true;
            if (!exit)
                add(Code.BLOCK_WITHOUT_EXIT, b.id(), "Block " + b.id() + ": in keiner erlaubten Richtung führt ein Weg zu"
                        + " einem Block");
        }
    }

    /**
     * Erreicht ein Zug, der am Blockende {@code x} hinausfährt, das Gleis eines Blocks (alle Weichenlagen)? Auch der
     * eigene Block zählt, wenn er über anderes Gleis wieder erreicht wird (z. B. Oval mit nur einem Block); nicht aber
     * die Fortsetzungen am Ende, die unmittelbar im eigenen Block bleiben (Weiche im Block).
     */
    private boolean reachesBlock(String blockId, DirectedLocation x) {
        DirectedEdge de = new DirectedEdge(edges.get(x.edgeId()), x.forward());
        double along = de.toEdgeOffset(x.offsetMm());
        for (Interval iv : blockCoverage.raw(de.edgeId())) {
            double[] st = Coverage.along(de, iv);
            if (st[0] >= along - TOL && st[1] > along + TOL) return true;
        }
        Set<DirectedEdge> seen = new HashSet<>();
        Deque<DirectedEdge> todo = new ArrayDeque<>();
        if (along >= de.lengthMm() - TOL)
            for (DirectedEdge n : successors(de)) {
                boolean ownStart = false;
                for (Interval iv : blockCoverage.raw(n.edgeId()))
                    if (iv.owner().equals(blockId) && Coverage.along(n, iv)[0] <= TOL) ownStart = true;
                if (!ownStart) todo.add(n);
            }
        else
            todo.addAll(successors(de));
        while (!todo.isEmpty()) {
            DirectedEdge n = todo.poll();
            if (!seen.add(n)) continue;
            if (!blockCoverage.raw(n.edgeId()).isEmpty()) return true;
            todo.addAll(successors(n));
        }
        return false;
    }

    // --- Kehrschleifen ------------------------------------------------------------------------------------------

    /**
     * Polaritätsprüfung: Jede Kante bekommt eine Schienenlage (welche Schiene in Bezugsrichtung links liegt); über
     * jeden Übergang muss die linke Schiene in Fahrrichtung durchgehen. Lässt sich das nicht widerspruchsfrei
     * erfüllen, enthält der Graph eine Kehrschleife (auch Gleisdreieck). Kanten, die vollständig in einem
     * Kehrschleifenbezirk liegen, sind frei (dort schaltet das Kehrschleifenmodul um) und werden ausgelassen.
     *
     * <p>Trennstellen werden bewusst nicht als Auflösung gewertet: Ob eine Trennstelle eine oder beide Schienen
     * trennt, steht nicht im Modell, und auch beidseitig getrennt braucht die Kehrschleife eine Umpolung.
     */
    private void checkReversingLoops() {
        Coverage.Builder rb = Coverage.builder();
        for (String edge : districtCoverage.edges())
            for (Interval iv : districtCoverage.raw(edge)) {
                BoosterDistrict d = districts.get(Integer.valueOf(iv.owner()));
                if (d != null && d.reversingLoop()) rb.add(new TrackRange(edge, iv.from(), iv.to()), iv.owner());
            }
        Coverage reversing = rb.build();
        List<TrackEdge> list = new ArrayList<>(edges.values());
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < list.size(); i++) index.put(list.get(i).id(), i);
        boolean[] free = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) {
            TrackEdge e = list.get(i);
            free[i] = e.lengthMm() > 0 && reversing.coversAll(e.id(), 0, e.lengthMm());
        }
        int[] parent = new int[list.size()];
        boolean[] parity = new boolean[list.size()];
        boolean[] conflict = new boolean[list.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (TrackEdge e : list)
            for (DirectedEdge d : List.of(e.forward(), e.backward())) {
                int a = index.get(e.id());
                if (free[a]) continue;
                for (TrackGraph.Step s : graph.steps(d)) {
                    int b = index.get(s.next().edgeId());
                    if (free[b]) continue;
                    unionParity(parent, parity, conflict, a, b, d.forward() != s.next().forward());
                }
            }
        Map<Integer, List<String>> conflicted = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            if (free[i]) continue;
            int r = findParity(parent, parity, i)[0];
            if (conflict[r]) conflicted.computeIfAbsent(r, k -> new ArrayList<>()).add(list.get(i).id());
        }
        for (List<String> edgeIds : conflicted.values())
            add(Code.REVERSING_LOOP_WITHOUT_DISTRICT, edgeIds.getFirst(), "Kehrschleife ohne eigenen Kehrschleifenbezirk"
                    + " im Gleisteil mit den Kanten " + list(edgeIds));
    }

    /** Wurzel und Parität zur Wurzel (iterativ, mit Pfadverkürzung). */
    private static int[] findParity(int[] parent, boolean[] parity, int x) {
        List<Integer> path = new ArrayList<>();
        int r = x;
        while (parent[r] != r) {
            path.add(r);
            r = parent[r];
        }
        // von der Wurzel her Paritäten aufsummieren
        for (int i = path.size() - 1; i >= 0; i--) {
            int n = path.get(i);
            int p = parent[n];
            if (p != r) parity[n] ^= parity[p];
            parent[n] = r;
        }
        return new int[] {r, parity[x] ? 1 : 0};
    }

    private static void unionParity(int[] parent, boolean[] parity, boolean[] conflict, int a, int b, boolean differ) {
        int[] fa = findParity(parent, parity, a);
        int[] fb = findParity(parent, parity, b);
        boolean pa = a != fa[0] && fa[1] == 1;
        boolean pb = b != fb[0] && fb[1] == 1;
        if (fa[0] == fb[0]) {
            if ((pa ^ pb) != differ) conflict[fa[0]] = true;
            return;
        }
        parent[fa[0]] = fb[0];
        parity[fa[0]] = pa ^ pb ^ differ;
        conflict[fb[0]] |= conflict[fa[0]];
    }

    // --- Doppelkreuzungsweiche ----------------------------------------------------------------------------------

    private void checkDoubleSlipLogic() {
        for (TrackNode n : graph.nodes())
            if (n instanceof TrackNode.DoubleSlipNode d && d.sideA() && !d.logic().hardwareVerified())
                add(Code.DOUBLE_SLIP_LOGIC_UNVERIFIED, graph.leaving(d.id(), Port.A1_B1).edge().pieceInstanceId(),
                        "Doppelkreuzungsweiche " + d.id() + ": Stelllogik nicht an Hardware geprüft (vorläufige Annahme,"
                                + " Entscheidung 41)");
    }
}
