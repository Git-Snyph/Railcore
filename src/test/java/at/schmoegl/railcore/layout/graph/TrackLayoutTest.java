package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.layout.graph.LayoutFinding.Code;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;
import static at.schmoegl.railcore.layout.graph.TrackGeometryTest.assertNear;
import static at.schmoegl.railcore.layout.graph.TrackGraphTest.G1000;
import static at.schmoegl.railcore.layout.graph.TrackGraphTest.R500;
import static at.schmoegl.railcore.layout.graph.TrackGraphTest.R500_LEN;
import static at.schmoegl.railcore.layout.graph.TrackGraphTest.W;
import static at.schmoegl.railcore.layout.graph.TrackGraphTest.W_BR;

/**
 * Auflagen auf dem Gleisgraph und Gleisplan-Prüfung (Schritt B2) auf synthetischen, im Test aufgebauten Anlagen.
 * Jeder Prüffall verändert eine fehlerfreie Anlage so, dass genau eine Art von Befund entsteht.
 * Alle Maße sind synthetische Testwerte.
 */
public class TrackLayoutTest {

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    /** Veränderliche Sammlung der Auflagen, damit jeder Test genau eine Angabe ändern kann. */
    static final class Fixture {
        final TrackGraph graph;
        final Map<String, DetectionSection> sections = new LinkedHashMap<>();
        final Map<String, Block> blocks = new LinkedHashMap<>();
        final List<BoosterDistrict> districts = new ArrayList<>(List.of(BoosterDistrict.of(1, "Hauptbezirk")));
        final List<BlockSignal> signals = new ArrayList<>();
        final List<HoldingArea> holdingAreas = new ArrayList<>();
        final List<StopPoint> stopPoints = new ArrayList<>();
        final List<SpeedChangePoint> speedPoints = new ArrayList<>();
        final List<ReportPointOverride> overrides = new ArrayList<>();
        final List<TurnoutAddress> turnoutAddresses = new ArrayList<>();
        final List<String> unverified = new ArrayList<>();
        int recommended = 3;
        private int nextContact = 1;

        Fixture(TrackGraph graph) { this.graph = graph; }

        TrackRange full(String edge) { return new TrackRange(edge, 0, graph.edge(edge).lengthMm()); }

        /** Meldeabschnitt im Bezirk 1 mit fortlaufendem Kontakt. */
        void section(String id, TrackRange... ranges) {
            sections.put(id, DetectionSection.of(id, nextContact++, 1, 50, 500, ranges));
        }

        /** Je Kante ein Meldeabschnitt gleichen Namens mit Präfix {@code s}. */
        void sectionsPerEdge(String... edgeIds) {
            for (String e : edgeIds) section("s" + e, full(e));
        }

        void block(String id, String endAEdge, String... sectionIds) {
            blocks.put(id, Block.of(id, DirectedLocation.of(endAEdge, 0, true), sectionIds));
        }

        TrackLayout build() {
            TrackLayout.Builder b = TrackLayout.builder(graph).recommendedSectionsPerBlock(recommended);
            sections.values().forEach(b::section);
            blocks.values().forEach(b::block);
            districts.forEach(b::district);
            signals.forEach(b::signal);
            holdingAreas.forEach(b::holdingArea);
            stopPoints.forEach(b::stopPoint);
            speedPoints.forEach(b::speedChangePoint);
            overrides.forEach(b::reportPointOverride);
            turnoutAddresses.forEach(b::turnoutAddress);
            unverified.forEach(b::unverifiedLength);
            return b.build();
        }
    }

    /**
     * Bahnhof: Oval a1–a3 (Block A, nur A → B), Bogen c1–c4 und b1 (Block B), Weiche w, Bogen c5–c8 (Block C);
     * am Abzweig von w das Abstellgleis x1–x3 mit Prellbock (Block S). Weichenabschnitt st über beide Zweige.
     * Signal am Ende B von A, Haltebereich A+B, Halte- und Geschwindigkeitswechselpunkt, überschriebener Meldepunkt.
     */
    static Fixture station(Consumer<TrackGraph.Builder> extra) {
        TrackGraph.Builder g = TrackGraph.builder();
        List<String> first = List.of("a1", "a2", "a3", "c1", "c2", "c3", "c4", "b1");
        List<String> second = List.of("c5", "c6", "c7", "c8");
        for (String id : first) g.place(id, id.startsWith("c") ? R500 : G1000);
        for (String id : second) g.place(id, R500);
        for (int i = 0; i + 1 < first.size(); i++) g.connect(first.get(i), Port.B, first.get(i + 1), Port.A);
        g.placeTurnout("w", W, "w", W_BR).connect("b1", Port.B, "w", Port.TIP).connect("w", Port.STRAIGHT, "c5", Port.A);
        for (int i = 0; i + 1 < second.size(); i++) g.connect(second.get(i), Port.B, second.get(i + 1), Port.A);
        g.connect("c8", Port.B, "a1", Port.A);
        g.place("x1", G1000).place("x2", G1000).place("x3", G1000)
                .connect("w", Port.DIVERGING, "x1", Port.A).connect("x1", Port.B, "x2", Port.A)
                .connect("x2", Port.B, "x3", Port.A).bufferStop("x3", Port.B);
        extra.accept(g);
        Fixture f = new Fixture(g.build());
        f.sectionsPerEdge("a1", "a2", "a3");
        f.section("sc12", f.full("c1"), f.full("c2"));
        f.section("sc34", f.full("c3"), f.full("c4"));
        f.sectionsPerEdge("b1");
        f.section("st", f.full("w.STRAIGHT"), f.full("w.DIVERGING"));
        f.section("sc56", f.full("c5"), f.full("c6"));
        f.sectionsPerEdge("c7", "c8", "x1", "x2", "x3");
        f.block("A", "a1", "sa1", "sa2", "sa3");
        f.blocks.put("A", f.blocks.get("A").withDirections(EnumSet.of(Block.Direction.A_TO_B)));
        f.block("B", "c1", "sc12", "sc34", "sb1");
        f.block("C", "c5", "sc56", "sc7", "sc8");
        f.block("S", "x1", "sx1", "sx2", "sx3");
        f.signals.add(new BlockSignal("sigA", "A", Block.End.B, List.of(10, 11)));
        f.turnoutAddresses.add(new TurnoutAddress("w", 1));
        f.holdingAreas.add(HoldingArea.of("H", "A", "B"));
        f.stopPoints.add(new StopPoint("hS", DirectedLocation.of("x2", 500, true), Set.of("Güterzug"), 1500));
        f.speedPoints.add(new SpeedChangePoint("vA", DirectedLocation.of("a2", 100, true), 40, SpeedChangePoint.Reference.HEAD));
        f.overrides.add(new ReportPointOverride("sx1", DirectedLocation.of("x1", 0, true), DirectedLocation.of("x1", 30, true)));
        return f;
    }

    static Fixture station() { return station(g -> {}); }

    /** Nur Befunde dieser Art, mindestens einer. */
    private static List<LayoutFinding> assertOnly(TrackLayout layout, Code code) {
        List<LayoutFinding> f = layout.findings();
        assertTrue(!f.isEmpty(), "Befund " + code + " erwartet, keiner gefunden");
        for (LayoutFinding x : f) assertTrue(x.code() == code, "nur " + code + " erwartet, gefunden: " + f);
        assertEquals(code.severity() == LayoutFinding.Severity.HINT, layout.permitsAutomatic());
        return f;
    }

    private static void assertClean(TrackLayout layout) {
        assertEquals(List.of(), layout.findings());
        assertTrue(layout.permitsAutomatic(), "Automatik erlaubt");
    }

    private static void assertSame(DirectedLocation expected, DirectedLocation actual) {
        assertTrue(expected.sameAs(actual, 1e-6), "erwartet " + expected + ", erhalten " + actual);
    }

    // --- fehlerfreie Anlage und Indizes -------------------------------------------------------------------------

    public void testCorrectStationHasNoFindings() {
        TrackLayout l = station().build();
        assertClean(l);
        assertEquals(13, l.sections().size());
        assertEquals("S", l.blockOfSection("sx2").orElseThrow().id());
        assertTrue(l.blockOfSection("st").isEmpty(), "Weichenabschnitt gehört zu keinem Block");
        assertEquals(List.of("sa1", "sa2", "sa3"), l.sectionsOfBlock("A").stream().map(DetectionSection::id).toList());
        assertEquals(13, l.sectionsOfDistrict(1).size());
        assertEquals(1, l.districtOfSection("sc7").orElseThrow().id());
        assertEquals("sa1", l.sectionByContact(1).orElseThrow().id());
        assertEquals(Set.of(1), l.districtsAt(new TrackLocation("w.DIVERGING", 10)));
        assertEquals(List.of("st"), l.sectionsAt(new TrackLocation("w.STRAIGHT", 100)).stream().map(DetectionSection::id).toList());
        // Ort am Kantenende gehört nur zu den Bereichen dieser Kante (Block A), nicht zur Folgekante c1 (Block B)
        assertEquals(List.of("A"), l.blocksAt(new TrackLocation("a3", 1000)).stream().map(Block::id).toList());
    }

    public void testBlockGeometryEndsAndDirection() {
        TrackLayout l = station().build();
        BlockGeometry a = l.geometry("A").orElseThrow();
        assertNear(3000, a.lengthMm(), "Länge Block A");
        assertEquals(1, a.endsA().size());
        assertSame(DirectedLocation.of("a1", 0, false), a.endsA().getFirst());
        assertSame(DirectedLocation.of("a3", 1000, true), a.endsB().getFirst());
        assertEquals(Block.Direction.A_TO_B, a.direction("a2", true).orElseThrow());
        assertEquals(Block.Direction.B_TO_A, a.direction("a2", false).orElseThrow());
        assertNear(4 * R500_LEN + 1000, l.geometry("B").orElseThrow().lengthMm(), "Länge Block B");
        assertSame(DirectedLocation.of("x3", 1000, true), l.geometry("S").orElseThrow().endsB().getFirst());
        // Signal am Ende B von A, gilt für A → B
        assertEquals(List.of(DirectedLocation.of("a3", 1000, true)), l.signalLocations("sigA"));
        assertEquals(Block.Direction.A_TO_B, l.signalsOfBlock("A").getFirst().direction());
        // Haltebereich A + B
        assertNear(3000 + 4 * R500_LEN + 1000, l.holdingAreaLengthMm("H").orElseThrow(), "Länge Haltebereich");
        assertEquals(List.of("H"), l.holdingAreasOfBlock("B").stream().map(HoldingArea::id).toList());
    }

    public void testReportPointsDerivedFromSectionBoundaries() {
        TrackLayout l = station().build();
        // a1: Einfahrt von c8 her (vorwärts bei 0) und von a2 her (rückwärts bei 1000)
        List<ReportPoint> a1 = l.reportPoints("sa1");
        assertEquals(2, a1.size());
        assertTrue(a1.stream().anyMatch(p -> p.at().sameAs(DirectedLocation.of("a1", 0, true), 1e-6)), a1.toString());
        assertTrue(a1.stream().anyMatch(p -> p.at().sameAs(DirectedLocation.of("a1", 1000, false), 1e-6)), a1.toString());
        // c1+c2 als ein Abschnitt: kein Meldepunkt am inneren Stoß
        assertEquals(2, l.reportPoints("sc12").size());
        // Abschnitt über die Weiche: Einfahrt an der Spitze in beide Zweige und an beiden Zweigenden
        assertEquals(4, l.reportPoints("st").size());
        // vom Prellbock her fährt niemand ein
        assertEquals(1, l.reportPoints("sx3").size());
        // überschrieben: x1 vorwärts 30 mm hinter der Grenze
        ReportPoint x1 = l.reportPoints("sx1").stream().filter(ReportPoint::overridden).findFirst().orElseThrow();
        assertSame(DirectedLocation.of("x1", 0, true), x1.boundary());
        assertSame(DirectedLocation.of("x1", 30, true), x1.at());
        assertEquals(1L, l.reportPoints("sx1").stream().filter(ReportPoint::overridden).count());
    }

    public void testBlockWithTurnoutHasTwoEnds() {
        Fixture f = station();
        // Block T über b1 und den Weichenabschnitt: Ende B am geraden Zweig und am Abzweig
        f.blocks.remove("B");
        f.holdingAreas.clear();
        f.block("B", "c1", "sc12", "sc34");
        f.recommended = 2;
        f.block("T", "b1", "sb1", "st");
        TrackLayout l = f.build();
        assertClean(l);
        BlockGeometry t = l.geometry("T").orElseThrow();
        assertEquals(2, t.endsB().size());
        assertNear(1000 + 200 + TrackGraphTest.W_DIV, t.lengthMm(), "Länge Block T");
    }

    // --- Fehler -------------------------------------------------------------------------------------------------

    public void testErrorBlockWithoutSensor() {
        Fixture f = station();
        f.blocks.put("E", Block.of("E", DirectedLocation.of("a1", 0, true)));
        assertOnly(f.build(), Code.BLOCK_WITHOUT_SENSOR);
    }

    public void testErrorOverlappingSections() {
        Fixture f = station();
        f.section("sov", new TrackRange("a2", 500, 1000));
        List<LayoutFinding> r = assertOnly(f.build(), Code.OVERLAPPING_SECTIONS);
        assertEquals(1, r.size());
        assertTrue(r.getFirst().message().contains("sa2") && r.getFirst().message().contains("sov"), r.toString());
    }

    public void testTouchingSectionsDoNotOverlap() {
        Fixture f = station();
        f.sections.put("sa2", DetectionSection.of("sa2", 2, 1, 50, 500, new TrackRange("a2", 0, 400)));
        f.section("sa2b", new TrackRange("a2", 400, 1000));
        f.blocks.put("A", f.blocks.get("A").withSections("sa1", "sa2", "sa2b", "sa3"));
        assertClean(f.build());
    }

    public void testErrorDuplicateContact() {
        Fixture f = station();
        f.sections.put("sx3", DetectionSection.of("sx3", 1, 1, 50, 500, f.full("x3")));
        TrackLayout l = f.build();
        assertOnly(l, Code.DUPLICATE_CONTACT);
        assertEquals("sa1", l.sectionByContact(1).orElseThrow().id()); // erste Angabe gilt
    }

    public void testErrorDuplicateAddress() {
        Fixture f = station();
        f.signals.set(0, new BlockSignal("sigA", "A", Block.End.B, List.of(1)));
        assertOnly(f.build(), Code.DUPLICATE_ADDRESS);
    }

    public void testErrorDuplicateId() {
        Fixture f = station();
        f.stopPoints.add(StopPoint.of("hS", DirectedLocation.of("x1", 100, true)));
        assertOnly(f.build(), Code.DUPLICATE_ID);
    }

    public void testErrorUnknownReferences() {
        Fixture f = station();
        f.signals.add(new BlockSignal("sigX", "nix", Block.End.A, List.of()));
        assertEquals(1, assertOnly(f.build(), Code.UNKNOWN_REFERENCE).size());

        Fixture g = station();
        g.holdingAreas.add(HoldingArea.of("H2", "C", "nix"));
        g.turnoutAddresses.add(new TurnoutAddress("gibtsnicht", 77));
        g.overrides.add(new ReportPointOverride("nix", DirectedLocation.of("x1", 0, true), DirectedLocation.of("x1", 5, true)));
        g.overrides.add(new ReportPointOverride("sx2", DirectedLocation.of("x2", 300, true), DirectedLocation.of("x2", 305, true)));
        g.stopPoints.add(StopPoint.of("h2", DirectedLocation.of("keine", 5, true)));
        g.unverified.add("keinStück");
        g.blocks.put("C", g.blocks.get("C").withSections("sc56", "sc7", "sc8", "fehlt"));
        assertEquals(7, assertOnly(g.build(), Code.UNKNOWN_REFERENCE).size());
    }

    public void testErrorInvalidLocation() {
        Fixture f = station();
        f.stopPoints.add(StopPoint.of("h2", DirectedLocation.of("x2", 1500, true)));
        assertOnly(f.build(), Code.INVALID_LOCATION);

        Fixture g = station();
        g.overrides.set(0, new ReportPointOverride("sx1", DirectedLocation.of("x1", 0, true), DirectedLocation.of("x2", 30, true)));
        assertOnly(g.build(), Code.INVALID_LOCATION); // Meldepunkt außerhalb seines Abschnitts
    }

    public void testErrorSectionInSeveralBlocks() {
        Fixture f = station();
        f.recommended = 1;
        f.block("X", "x1", "sx1");
        assertOnly(f.build(), Code.SECTION_IN_SEVERAL_BLOCKS);
    }

    public void testErrorBlockGeometry() {
        Fixture f = station();
        f.blocks.put("S", Block.of("S", DirectedLocation.of("x2", 0, true), "sx1", "sx2", "sx3"));
        TrackLayout l = f.build();
        assertOnly(l, Code.BLOCK_GEOMETRY);
        assertTrue(l.geometry("S").isEmpty(), "keine Geometrie bei Fehler");

        Fixture g = station();
        g.recommended = 1;
        g.blocks.put("A", g.blocks.get("A").withSections("sa1", "sa3")); // Lücke a2
        g.holdingAreas.clear();
        assertOnly(g.build(), Code.BLOCK_GEOMETRY);
    }

    public void testErrorHoldingAreaNotContiguous() {
        Fixture f = station();
        f.holdingAreas.add(HoldingArea.of("H2", "A", "S"));
        assertOnly(f.build(), Code.HOLDING_AREA_NOT_CONTIGUOUS);
    }

    public void testErrorOverlappingDistricts() {
        Fixture f = station();
        f.districts.add(BoosterDistrict.of(2, "Zweiter Bezirk", new TrackRange("a1", 0, 100)));
        assertOnly(f.build(), Code.OVERLAPPING_DISTRICTS);
    }

    // --- Kehrschleife -------------------------------------------------------------------------------------------

    /** Kehrschleife aus TrackGraphTest: Block G auf g0, Weichenabschnitt, Schleifenblock L (l1, c1–c4, l2). */
    static Fixture reversingLoop(boolean ownDistrict) {
        Fixture f = new Fixture(TrackGraphTest.reversingLoop());
        f.section("sg1", new TrackRange("g0", 0, 300));
        f.section("sg2", new TrackRange("g0", 300, 600));
        f.section("sg3", new TrackRange("g0", 600, 1000));
        f.section("st", f.full("w.STRAIGHT"), f.full("w.DIVERGING"));
        f.block("G", "g0", "sg1", "sg2", "sg3");
        int loop = ownDistrict ? 2 : 1;
        if (ownDistrict) f.districts.add(BoosterDistrict.reversingLoop(2, "Kehrschleife"));
        f.sections.put("sl1", DetectionSection.of("sl1", 21, loop, 50, 500, f.full("l1")));
        f.sections.put("slc", DetectionSection.of("slc", 22, loop, 50, 500, f.full("c1"), f.full("c2"), f.full("c3"), f.full("c4")));
        f.sections.put("sl2", DetectionSection.of("sl2", 23, loop, 50, 500, f.full("l2")));
        f.block("L", "l1", "sl1", "slc", "sl2");
        return f;
    }

    public void testErrorReversingLoopWithoutOwnDistrict() {
        List<LayoutFinding> r = assertOnly(reversingLoop(false).build(), Code.REVERSING_LOOP_WITHOUT_DISTRICT);
        assertEquals(1, r.size());
    }

    public void testReversingLoopWithOwnDistrictIsAccepted() {
        assertClean(reversingLoop(true).build());
    }

    public void testWyeIsReversingLoop() {
        // Gleisdreieck: drei Weichen, jede Spitze an einem Schenkel der anderen
        TrackGraph.Builder g = TrackGraph.builder()
                .placeTurnout("w1", W, "w1", W_BR).placeTurnout("w2", W, "w2", W_BR).placeTurnout("w3", W, "w3", W_BR)
                .place("e1", G1000).place("e2", G1000).place("e3", G1000)
                .place("s12", G1000).place("s23", G1000).place("s31", G1000)
                .connect("e1", Port.B, "w1", Port.TIP).bufferStop("e1", Port.A)
                .connect("e2", Port.B, "w2", Port.TIP).bufferStop("e2", Port.A)
                .connect("e3", Port.B, "w3", Port.TIP).bufferStop("e3", Port.A)
                .connect("w1", Port.STRAIGHT, "s12", Port.A).connect("s12", Port.B, "w2", Port.DIVERGING)
                .connect("w2", Port.STRAIGHT, "s23", Port.A).connect("s23", Port.B, "w3", Port.DIVERGING)
                .connect("w3", Port.STRAIGHT, "s31", Port.A).connect("s31", Port.B, "w1", Port.DIVERGING);
        Fixture f = new Fixture(g.build());
        f.recommended = 1;
        for (String e : List.of("e1", "e2", "e3")) {
            f.sectionsPerEdge(e);
            f.block("B" + e, e, "s" + e);
        }
        for (String w : List.of("w1", "w2", "w3")) f.section("s" + w, f.full(w + ".STRAIGHT"), f.full(w + ".DIVERGING"));
        f.sectionsPerEdge("s12", "s23", "s31");
        List<LayoutFinding> r = f.build().findings();
        assertEquals(List.of(Code.REVERSING_LOOP_WITHOUT_DISTRICT), r.stream().map(LayoutFinding::code).distinct().toList());
    }

    // --- Hinweise -----------------------------------------------------------------------------------------------

    public void testHintFewSensorsInBlock() {
        Fixture f = station();
        f.blocks.put("S", f.blocks.get("S").withSections("sx1", "sx2"));
        List<LayoutFinding> r = assertOnly(f.build(), Code.FEW_SENSORS_IN_BLOCK);
        assertEquals("S", r.getFirst().subject());
    }

    public void testHintUnknownLength() {
        Fixture f = station();
        f.unverified.add("x2");
        assertOnly(f.build(), Code.UNKNOWN_LENGTH);
    }

    public void testHintTrackWithoutSensor() {
        Fixture f = station();
        f.sections.put("sx3", DetectionSection.of("sx3", 13, 1, 50, 500, new TrackRange("x3", 0, 900)));
        f.districts.set(0, BoosterDistrict.of(1, "Hauptbezirk", new TrackRange("x3", 900, 1000)));
        List<LayoutFinding> r = assertOnly(f.build(), Code.TRACK_WITHOUT_SENSOR);
        assertEquals("x3", r.getFirst().subject());
    }

    public void testHintTrackWithoutDistrict() {
        Fixture f = station();
        f.sections.put("sx3", new DetectionSection("sx3", 13, List.of(f.full("x3")), 50, 500, OptionalInt.empty()));
        TrackLayout l = f.build();
        assertOnly(l, Code.TRACK_WITHOUT_DISTRICT);
        assertEquals(Set.of(), l.districtsAt(new TrackLocation("x3", 500)));
    }

    public void testHintTurnoutWithoutSection() {
        Fixture f = station();
        f.sections.remove("st");
        f.districts.set(0, BoosterDistrict.of(1, "Hauptbezirk", f.full("w.STRAIGHT"), f.full("w.DIVERGING")));
        List<LayoutFinding> r = assertOnly(f.build(), Code.TURNOUT_WITHOUT_SECTION);
        assertEquals("w", r.getFirst().subject());
    }

    public void testHintDeadEndWithoutExit() {
        // Pendelstrecke: Prellbock – p1 … p6 – Prellbock, zwei Blöcke, keine Weiche
        TrackGraph.Builder g = TrackGraph.builder();
        for (int i = 1; i <= 6; i++) g.place("p" + i, G1000);
        for (int i = 1; i < 6; i++) g.connect("p" + i, Port.B, "p" + (i + 1), Port.A);
        Fixture f = new Fixture(g.bufferStop("p1", Port.A).bufferStop("p6", Port.B).build());
        f.sectionsPerEdge("p1", "p2", "p3", "p4", "p5", "p6");
        f.block("P1", "p1", "sp1", "sp2", "sp3");
        f.block("P2", "p4", "sp4", "sp5", "sp6");
        assertOnly(f.build(), Code.DEAD_END_WITHOUT_EXIT);
    }

    public void testHintUnreachableNode() {
        Fixture f = station(g -> g.place("z1", G1000).bufferStop("z1", Port.A).bufferStop("z1", Port.B));
        f.sectionsPerEdge("z1");
        List<LayoutFinding> r = assertOnly(f.build(), Code.UNREACHABLE_NODE);
        assertEquals(1, r.size());
        assertTrue(r.getFirst().message().contains("end:z1.A"), r.toString());
    }

    public void testHintBlockWithoutExit() {
        Fixture f = station();
        f.blocks.put("S", f.blocks.get("S").withDirections(EnumSet.of(Block.Direction.A_TO_B))); // nur zum Prellbock hin
        List<LayoutFinding> r = assertOnly(f.build(), Code.BLOCK_WITHOUT_EXIT);
        assertEquals("S", r.getFirst().subject());
    }

    // --- Doppelkreuzungsweiche ----------------------------------------------------------------------------------

    /** DKW mit vier Anschlussgleisen, je ein Block mit drei Abschnitten; DKW-Abschnitt über alle vier Fahrwege. */
    static Fixture doubleSlip(DoubleSlipLogic logic) {
        Fixture f = new Fixture(TrackGraphTest.doubleSlip(logic, DoubleSlipClearances.uniform(90)));
        for (Port p : DoubleSlipClearances.OUTER_PORTS) {
            String e = "x" + p;
            f.section(e + "1", new TrackRange(e, 0, 300));
            f.section(e + "2", new TrackRange(e, 300, 600));
            f.section(e + "3", new TrackRange(e, 600, 1000));
            f.block("B" + p, e, e + "1", e + "2", e + "3");
        }
        f.section("sd", f.full("d.A1_B1"), f.full("d.A1_B2"), f.full("d.A2_B1"), f.full("d.A2_B2"));
        f.turnoutAddresses.add(new TurnoutAddress("d.A", 5));
        f.turnoutAddresses.add(new TurnoutAddress("d.B", 6));
        return f;
    }

    public void testHintDoubleSlipLogicUnverified() {
        List<LayoutFinding> r = assertOnly(doubleSlip(DoubleSlipLogic.provisionalAssumption()).build(),
                Code.DOUBLE_SLIP_LOGIC_UNVERIFIED);
        assertEquals(1, r.size());
        assertEquals("d", r.getFirst().subject());
    }

    public void testVerifiedDoubleSlipHasNoFindings() {
        DoubleSlipLogic p = DoubleSlipLogic.provisionalAssumption();
        assertClean(doubleSlip(new DoubleSlipLogic(p.settings(), true)).build());
    }

    public void testDoubleSlipWithoutSectionIsTurnoutHint() {
        Fixture f = doubleSlip(new DoubleSlipLogic(DoubleSlipLogic.provisionalAssumption().settings(), true));
        f.sections.remove("sd");
        f.districts.set(0, BoosterDistrict.of(1, "Hauptbezirk", f.full("d.A1_B1"), f.full("d.A1_B2"),
                f.full("d.A2_B1"), f.full("d.A2_B2")));
        assertOnly(f.build(), Code.TURNOUT_WITHOUT_SECTION);
    }

    // --- Eingaben -----------------------------------------------------------------------------------------------

    public void testObjectValidation() {
        TrackRange r = new TrackRange("a", 0, 10);
        assertThrows(IllegalArgumentException.class, () -> DetectionSection.of("s", 0, 1, 0, 0, r));
        assertThrows(IllegalArgumentException.class, () -> DetectionSection.of("s", 1, 1, -1, 0, r));
        assertThrows(IllegalArgumentException.class, () -> new DetectionSection("s", 1, List.of(), 0, 0, OptionalInt.empty()));
        DirectedLocation at = DirectedLocation.of("a", 0, true);
        assertThrows(IllegalArgumentException.class, () -> new Block("b", List.of(), at, Set.of(), Set.of(), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Block("b", List.of(), at,
                EnumSet.allOf(Block.Direction.class), Set.of(), 500, 400));
        assertThrows(IllegalArgumentException.class, () -> new SpeedChangePoint("v", at, 0, SpeedChangePoint.Reference.TAIL));
        assertThrows(IllegalArgumentException.class, () -> new HoldingArea("h", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TurnoutAddress("w", 0));
        assertThrows(IllegalArgumentException.class, () -> new BlockSignal("s", "b", Block.End.A, List.of(0)));
        assertThrows(IllegalArgumentException.class, () -> new LayoutFinding(LayoutFinding.Severity.HINT,
                Code.BLOCK_WITHOUT_SENSOR, "x", "y"));
        assertThrows(IllegalArgumentException.class, () -> TrackLayout.builder(station().graph).recommendedSectionsPerBlock(0));
    }
}
