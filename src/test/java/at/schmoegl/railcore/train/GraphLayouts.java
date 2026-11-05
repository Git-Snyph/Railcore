package at.schmoegl.railcore.train;

import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.layout.graph.Block;
import at.schmoegl.railcore.layout.graph.BoosterDistrict;
import at.schmoegl.railcore.layout.graph.DetectionSection;
import at.schmoegl.railcore.layout.graph.DirectedLocation;
import at.schmoegl.railcore.layout.graph.HoldingArea;
import at.schmoegl.railcore.layout.graph.Port;
import at.schmoegl.railcore.layout.graph.StopPoint;
import at.schmoegl.railcore.layout.graph.TrackGeometry;
import at.schmoegl.railcore.layout.graph.TrackGraph;
import at.schmoegl.railcore.layout.graph.TrackLayout;
import at.schmoegl.railcore.layout.graph.TrackPiece;
import at.schmoegl.railcore.layout.graph.TrackRange;
import at.schmoegl.railcore.layout.graph.TurnoutBranches;
import at.schmoegl.railcore.layout.graph.TurnoutStates;

import java.util.List;
import java.util.Map;

/**
 * Synthetische Testanlagen für Zugposition, Ausdehnung und Fahrauftrag (Schritt C1), im Test aufgebaut wie in B1/B2.
 * Alle Maße sind synthetische Testwerte, keine Herstellerangaben. Ansprech- und Freimeldeverzögerung aller
 * Abschnitte nach dem Roco-Standard des Z21 Detector 10808 (Entscheidung 45): Einschaltverzögerung 160 ms,
 * Ausschaltverzögerung 1160 ms (Bedienungsanleitung 10808, Kapitel „Eigenschaften“).
 */
final class GraphLayouts {

    private GraphLayouts() {}

    static final TrackPiece G1000 = TrackPiece.plain("TEST-G1000", TrackGeometry.straight(1000));
    static final TrackPiece G500 = TrackPiece.plain("TEST-G500", TrackGeometry.straight(500));
    static final TrackPiece G400 = TrackPiece.plain("TEST-G400", TrackGeometry.straight(400));
    static final double W_STRAIGHT = 200;
    static final double W_DIV = 204.7;
    /** Grenzzeichen Abzweig 250 mm ab der Spitze: 45,3 mm hinter dem Weichenende. */
    static final TurnoutBranches W_BR = new TurnoutBranches(W_STRAIGHT, W_DIV, 150, 250);
    static final TrackPiece.TurnoutPiece W = new TrackPiece.TurnoutPiece("TEST-W", TrackPiece.Hand.LEFT, W_BR);
    /** Roco-Standard Z21 Detector 10808: Einschaltverzögerung (Ansprechverzögerung). */
    static final int RESPONSE_MS = 160;
    /** Roco-Standard Z21 Detector 10808: Ausschaltverzögerung (Freimeldeverzögerung). */
    static final int RELEASE_MS = 1160;

    static final TurnoutStates W_STRAIGHT_SET = TurnoutStates.of(Map.of("w", TurnoutPosition.STRAIGHT));
    static final TurnoutStates W_DIVERGING_SET = TurnoutStates.of(Map.of("w", TurnoutPosition.DIVERGING));

    static TrackRange full(TrackGraph g, String edge) { return new TrackRange(edge, 0, g.edge(edge).lengthMm()); }

    static DetectionSection section(String id, int contact, int district, TrackRange... ranges) {
        return DetectionSection.of(id, contact, district, RESPONSE_MS, RELEASE_MS, ranges);
    }

    static TrackLayout requireClean(TrackLayout l) {
        if (!l.permitsAutomatic()) throw new AssertionError("Testanlage fehlerhaft: " + l.errors());
        return l;
    }

    /** Oval aus vier geraden Kanten e1 → e2 → e3 → e4 → e1 zu je 1000 mm, je Kante ein Abschnitt, Blöcke O1 (e1, e2) und O2 (e3, e4). */
    static TrackLayout oval() {
        TrackGraph.Builder b = TrackGraph.builder();
        List<String> ring = List.of("e1", "e2", "e3", "e4");
        for (String id : ring) b.place(id, G1000);
        for (int i = 0; i < ring.size(); i++) b.connect(ring.get(i), Port.B, ring.get((i + 1) % ring.size()), Port.A);
        TrackGraph g = b.build();
        TrackLayout.Builder l = TrackLayout.builder(g).district(BoosterDistrict.of(1, "Oval"));
        for (int i = 0; i < ring.size(); i++) l.section(section("s" + ring.get(i), i + 1, 1, full(g, ring.get(i))));
        l.block(Block.of("O1", DirectedLocation.of("e1", 0, true), "se1", "se2"));
        l.block(Block.of("O2", DirectedLocation.of("e3", 0, true), "se3", "se4"));
        return requireClean(l.build());
    }

    /**
     * Bahnhofskopf: Prellbock – a1 – a2 – Weiche w; gerader Strang → s1 – s2 – Prellbock; Abzweig → d1 – d2 –
     * Prellbock. Je Kante ein Abschnitt, Weichenabschnitt st über beide Zweige. Blöcke A (a1, a2), S (s1, s2),
     * D (d1, d2). Haltepunkt hS1 auf s1 bei 500 mm in Richtung Prellbock, Signal sigA am Ende B von A.
     */
    static TrackLayout station() {
        TrackGraph g = TrackGraph.builder()
                .place("a1", G1000).place("a2", G1000).placeTurnout("w", W, "w", W_BR)
                .place("s1", G1000).place("s2", G1000).place("d1", G1000).place("d2", G1000)
                .bufferStop("a1", Port.A).connect("a1", Port.B, "a2", Port.A).connect("a2", Port.B, "w", Port.TIP)
                .connect("w", Port.STRAIGHT, "s1", Port.A).connect("s1", Port.B, "s2", Port.A).bufferStop("s2", Port.B)
                .connect("w", Port.DIVERGING, "d1", Port.A).connect("d1", Port.B, "d2", Port.A).bufferStop("d2", Port.B)
                .build();
        TrackLayout.Builder l = TrackLayout.builder(g).district(BoosterDistrict.of(1, "Bahnhof"));
        int c = 1;
        for (String e : List.of("a1", "a2", "s1", "s2", "d1", "d2")) l.section(section("s" + e, c++, 1, full(g, e)));
        l.section(section("st", c, 1, full(g, "w.STRAIGHT"), full(g, "w.DIVERGING")));
        l.block(Block.of("A", DirectedLocation.of("a1", 0, true), "sa1", "sa2"));
        l.block(Block.of("S", DirectedLocation.of("s1", 0, true), "ss1", "ss2"));
        l.block(Block.of("D", DirectedLocation.of("d1", 0, true), "sd1", "sd2"));
        l.stopPoint(StopPoint.of("hS1", DirectedLocation.of("s1", 500, true)));
        l.signal(new at.schmoegl.railcore.layout.graph.BlockSignal("sigA", "A", Block.End.B, List.of()));
        return requireClean(l.build());
    }

    /**
     * Kehrschleife: Prellbock – g0 (1000) – Weiche w; gerader Strang → l1 (1000), c1–c4 (je 400), l2 (1000) → zurück an
     * den Abzweig von w. Block G auf g0 (drei Abschnitte), Weichenabschnitt st, Schleifenblock L (sl1, slc, sl2) im
     * eigenen Kehrschleifenbezirk 2.
     */
    static TrackLayout reversingLoop() {
        TrackGraph.Builder b = TrackGraph.builder()
                .place("g0", G1000).placeTurnout("w", W, "w", W_BR).place("l1", G1000).place("l2", G1000)
                .bufferStop("g0", Port.A).connect("g0", Port.B, "w", Port.TIP)
                .connect("w", Port.STRAIGHT, "l1", Port.A);
        String prev = "l1";
        for (String c : List.of("c1", "c2", "c3", "c4")) {
            b.place(c, G400).connect(prev, Port.B, c, Port.A);
            prev = c;
        }
        TrackGraph g = b.connect(prev, Port.B, "l2", Port.A).connect("l2", Port.B, "w", Port.DIVERGING).build();
        TrackLayout.Builder l = TrackLayout.builder(g)
                .district(BoosterDistrict.of(1, "Hauptbezirk")).district(BoosterDistrict.reversingLoop(2, "Kehrschleife"));
        l.section(section("sg1", 1, 1, new TrackRange("g0", 0, 300)));
        l.section(section("sg2", 2, 1, new TrackRange("g0", 300, 600)));
        l.section(section("sg3", 3, 1, new TrackRange("g0", 600, 1000)));
        l.section(section("st", 4, 1, full(g, "w.STRAIGHT"), full(g, "w.DIVERGING")));
        l.section(section("sl1", 21, 2, full(g, "l1")));
        l.section(section("slc", 22, 2, full(g, "c1"), full(g, "c2"), full(g, "c3"), full(g, "c4")));
        l.section(section("sl2", 23, 2, full(g, "l2")));
        l.block(Block.of("G", DirectedLocation.of("g0", 0, true), "sg1", "sg2", "sg3"));
        l.block(Block.of("L", DirectedLocation.of("l1", 0, true), "sl1", "slc", "sl2"));
        return requireClean(l.build());
    }

    /**
     * Schattenbahnhofsgleis aus drei kurzen Blöcken: Prellbock – t1 … t6 (je 500 mm) – Prellbock, je Kante ein
     * Abschnitt, Blöcke K1 (t1, t2), K2 (t3, t4), K3 (t5, t6), Haltebereich H über alle drei (Entscheidung 40/E6).
     */
    static TrackLayout longTrack() {
        TrackGraph.Builder b = TrackGraph.builder();
        for (int i = 1; i <= 6; i++) b.place("t" + i, G500);
        for (int i = 1; i < 6; i++) b.connect("t" + i, Port.B, "t" + (i + 1), Port.A);
        TrackGraph g = b.bufferStop("t1", Port.A).bufferStop("t6", Port.B).build();
        TrackLayout.Builder l = TrackLayout.builder(g).district(BoosterDistrict.of(1, "Schattenbahnhof"));
        for (int i = 1; i <= 6; i++) l.section(section("st" + i, i, 1, full(g, "t" + i)));
        l.block(Block.of("K1", DirectedLocation.of("t1", 0, true), "st1", "st2"));
        l.block(Block.of("K2", DirectedLocation.of("t3", 0, true), "st3", "st4"));
        l.block(Block.of("K3", DirectedLocation.of("t5", 0, true), "st5", "st6"));
        l.holdingArea(HoldingArea.of("H", "K1", "K2", "K3"));
        return requireClean(l.build());
    }
}
