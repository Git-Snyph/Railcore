package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.layout.graph.LayoutFinding.Code;
import at.schmoegl.railcore.layout.graph.TrackPlanReport.Phase;
import at.schmoegl.railcore.layout.graph.TrackPlanReport.Problem;
import at.schmoegl.railcore.util.Json;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static at.schmoegl.railcore.TestRunner.assertEquals;
import static at.schmoegl.railcore.TestRunner.assertThrows;
import static at.schmoegl.railcore.layout.graph.TrackGeometryTest.assertNear;

/**
 * Gleisplan-Datei (Schritt B3): synthetische Testanlagen unter {@code src/test/resources/anlagen/} laden, Prüfbericht,
 * Hin- und Rückweg Datei → Modell → Datei → Modell, und fehlerhafte Dateien mit verständlicher Meldung.
 */
public class TrackPlanTest {

    static final List<String> LAYOUTS = List.of("oval.json", "bahnhof.json", "schattenbahnhof.json",
            "kehrschleife-mit-bezirk.json", "kehrschleife-ohne-bezirk.json");

    private static void assertTrue(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    static Path file(String name) {
        URL url = TrackPlanTest.class.getResource("/anlagen/" + name);
        if (url == null) throw new AssertionError("Testanlage fehlt: " + name);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    static String text(String name) {
        try {
            return Files.readString(file(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static TrackPlanReport load(String name) {
        TrackPlanReport r = TrackPlanLoader.load(file(name));
        assertEquals(List.of(), r.loadErrors());
        assertTrue(r.loaded(), "geladen: " + name);
        return r;
    }

    private static List<Code> codes(TrackPlanReport r) { return r.findings().stream().map(LayoutFinding::code).toList(); }

    /** JSON mit einfachen Anführungszeichen, damit die Testfälle lesbar bleiben. */
    private static String j(String s) { return s.replace('\'', '"'); }

    /** Kleinste gültige Datei: ein gerades Stück zwischen zwei Gleisenden; {@code extra} kommt auf oberster Ebene dazu. */
    private static String minimal(String extra) {
        return minimal(TrackPlan.FORMAT_VERSION, extra);
    }

    /** Wie {@link #minimal(String)}, mit gegebener Formatversion. */
    private static String minimal(int version, String extra) {
        return j("{'formatVersion': " + version + ", 'catalog': [{'id': 'G', 'kind': 'plain', 'geometry': {'kind': 'straight', 'lengthMm': 1000}}],"
                + " 'pieces': [{'id': 'p', 'type': 'G'}], 'connections': [{'kind': 'end', 'a': 'p.A'}, {'kind': 'end', 'a': 'p.B'}]"
                + (extra.isEmpty() ? "" : ", " + extra) + "}");
    }

    /** Laden muss mit Ladefehlern scheitern; liefert sie. */
    private static List<Problem> loadErrors(String json) {
        TrackPlanReport r = TrackPlanLoader.load(json);
        assertTrue(!r.loadErrors().isEmpty(), "Ladefehler erwartet: " + r);
        assertTrue(!r.loaded() && !r.permitsAutomatic(), "nicht geladen, Automatik gesperrt");
        assertEquals(List.of(), r.findings());
        return r.loadErrors();
    }

    private static Problem single(List<Problem> problems, Phase phase, String... fragments) {
        assertEquals(1, problems.size());
        Problem p = problems.getFirst();
        assertEquals(phase, p.phase());
        for (String f : fragments) assertTrue(p.toString().contains(f), "Meldung soll „" + f + "“ enthalten: " + p);
        return p;
    }

    // --- Testanlagen --------------------------------------------------------------------------------------------

    public void testOvalHasNoFindings() {
        TrackPlanReport r = load("oval.json");
        assertEquals(List.of(), r.findings());
        assertTrue(r.permitsAutomatic(), "Automatik erlaubt");
        TrackLayout l = r.layout().orElseThrow();
        assertEquals(14, l.graph().edges().size());
        assertNear(6000 + 1000 * Math.PI, l.graph().totalLengthMm(), "Umfang aus Geometrie");
        assertEquals(2, l.blocks().size());
        assertEquals(NodeKind.ISOLATING_JOINT, l.graph().node("joint:c4.B~s1.A").kind());
        assertEquals(1, l.signalLocations("sigNordB").size());
        assertEquals("Nord", l.blockOfSection("snk").orElseThrow().id());
    }

    public void testStationOnlyHintsUnverifiedDoubleSlip() {
        TrackPlanReport r = load("bahnhof.json");
        assertEquals(List.of(Code.DOUBLE_SLIP_LOGIC_UNVERIFIED), codes(r));
        assertEquals("D1", r.findings().getFirst().subject());
        assertTrue(r.permitsAutomatic(), "Hinweis sperrt die Automatik nicht");
        TrackLayout l = r.layout().orElseThrow();
        TrackGraph g = l.graph();
        // eigene Länge (Entscheidung 42) statt Typlänge
        assertNear(769, g.edge("g2c").lengthMm(), "gekürztes Gleis");
        // Instanzwerte der DKW, nicht der Typ-Vorschlag (100 mm)
        assertNear(110, ((TrackNode.DoubleSlipNode) g.node("D1.B")).clearances().clearanceMm(Port.B1), "Grenzzeichen B1");
        assertNear(236.5, g.edge("D1.A2_B1").lengthMm(), "Fahrweglänge");
        // Grenzzeichen des Abzweigs von W1 liegt hinter der Weiche, auf beiden Fahrwegen der DKW ab A1
        List<TrackLocation> c = g.clearancePoints("W1", TurnoutPosition.DIVERGING);
        assertEquals(Set.of("D1.A1_B1", "D1.A1_B2"), Set.copyOf(c.stream().map(TrackLocation::edgeId).toList()));
        assertEquals(7, l.blocks().size());
        assertEquals(Set.of("Güterzug"), l.block("Ladegleis").orElseThrow().trainTypes());
        assertNear(2769, l.holdingAreaLengthMm("Bahnsteig2").orElseThrow(), "Länge Haltebereich");
        assertTrue(l.reportPoints("sG1a").stream().anyMatch(ReportPoint::overridden), "Meldepunkt überschrieben");
        assertEquals(6, l.signals().size());
        assertEquals(4, l.turnoutAddresses().size());
    }

    public void testShadowStationOnlyHintsUnknownLength() {
        TrackPlanReport r = load("schattenbahnhof.json");
        assertEquals(List.of(Code.UNKNOWN_LENGTH), codes(r));
        assertEquals("m2", r.findings().getFirst().subject());
        assertTrue(r.permitsAutomatic(), "Hinweis sperrt die Automatik nicht");
        TrackLayout l = r.layout().orElseThrow();
        assertNear(1500, l.graph().edge("m2").lengthMm(), "geschätzte Länge");
        assertEquals(11, l.blocks().size());
        assertEquals(4, l.holdingAreas().size());
        for (int i = 1; i <= 4; i++) assertNear(6000, l.holdingAreaLengthMm("Gleis" + i).orElseThrow(), "Gleis " + i);
        assertEquals(Set.of(Block.Direction.A_TO_B), l.block("T3B").orElseThrow().directions());
        // Plan-Modell trägt die Markierung je Stück
        TrackPlan plan = r.plan().orElseThrow();
        assertEquals(List.of("m2"), plan.pieces().stream().filter(TrackPlan.PlacedPiece::lengthUnknown)
                .map(TrackPlan.PlacedPiece::id).toList());
    }

    public void testReversingLoopWithOwnDistrictHasNoFindings() {
        TrackPlanReport r = load("kehrschleife-mit-bezirk.json");
        assertEquals(List.of(), r.findings());
        assertTrue(r.permitsAutomatic(), "Automatik erlaubt");
        assertTrue(r.layout().orElseThrow().district(2).orElseThrow().reversingLoop(), "Kehrschleifenbezirk");
    }

    public void testReversingLoopWithoutOwnDistrictIsError() {
        TrackPlanReport r = load("kehrschleife-ohne-bezirk.json");
        assertEquals(List.of(Code.REVERSING_LOOP_WITHOUT_DISTRICT), codes(r));
        assertTrue(r.findings().getFirst().isError(), "Fehler, nicht Hinweis");
        assertTrue(r.loaded(), "Gleisplan gebaut, damit der Bericht vollständig ist");
        assertTrue(!r.permitsAutomatic(), "Automatik gesperrt");
        assertTrue(r.text().contains("Automatik gesperrt"), r.text());
    }

    public void testBothReversingLoopFilesDifferOnlyInDistrictFlag() {
        TrackPlan with = TrackPlanJson.parse(text("kehrschleife-mit-bezirk.json"));
        TrackPlan without = TrackPlanJson.parse(text("kehrschleife-ohne-bezirk.json"));
        assertEquals(with.pieces(), without.pieces());
        assertEquals(with.connections(), without.connections());
        assertEquals(with.sections(), without.sections());
        assertTrue(with.districts().get(1).reversingLoop() && !without.districts().get(1).reversingLoop(), "Kennzeichen");
    }

    // --- Hin- und Rückweg ---------------------------------------------------------------------------------------

    public void testRoundTripOfAllLayouts() {
        for (String name : LAYOUTS) {
            String original = text(name);
            TrackPlan first = TrackPlanJson.parse(original);
            String written = TrackPlanJson.write(first);
            TrackPlan second = TrackPlanJson.parse(written);
            assertEquals(first, second);
            // Datei inhaltlich gleich: geschriebener JSON-Baum = gelesener (ohne Kommentare und Formatierung)
            assertEquals(Json.parse(original), Json.parse(written));
            assertEquals(written, TrackPlanJson.write(second));
            // gebaute Modelle gleich
            TrackPlanReport a = TrackPlanLoader.build(first);
            TrackPlanReport b = TrackPlanLoader.load(written);
            assertEquals(List.copyOf(a.layout().orElseThrow().graph().edges()), List.copyOf(b.layout().orElseThrow().graph().edges()));
            assertEquals(a.findings(), b.findings());
        }
    }

    public void testRoundTripOfCrossingCompoundAndExplicitRanges() {
        String json = j("""
                {'formatVersion': 1, 'name': 'Kreuzung',
                 'catalog': [
                  {'id': 'G', 'kind': 'plain', 'geometry': {'kind': 'compound', 'parts': [
                    {'kind': 'straight', 'lengthMm': 600}, {'kind': 'arc', 'radiusMm': 400, 'angleDeg': 30}]}},
                  {'id': 'K', 'kind': 'crossing', 'suggestion': {'lineALengthMm': 300, 'lineBLengthMm': 300,
                    'crossingFromA1Mm': 150, 'crossingFromB1Mm': 150}}],
                 'pieces': [
                  {'id': 'k', 'type': 'K', 'dimensions': {'lineALengthMm': 320, 'lineBLengthMm': 280,
                    'crossingFromA1Mm': 100, 'crossingFromB1Mm': 140}, 'lengthUnknown': true},
                  {'id': 'xA1', 'type': 'G'}, {'id': 'xA2', 'type': 'G'}, {'id': 'xB1', 'type': 'G'}, {'id': 'xB2', 'type': 'G', 'lengthMm': 900}],
                 'connections': [
                  {'kind': 'joint', 'a': 'k.A1', 'b': 'xA1.A'}, {'kind': 'end', 'a': 'xA1.B'},
                  {'kind': 'isolating', 'a': 'k.A2', 'b': 'xA2.A'}, {'kind': 'end', 'a': 'xA2.B'},
                  {'kind': 'joint', 'a': 'k.B1', 'b': 'xB1.A'}, {'kind': 'end', 'a': 'xB1.B'},
                  {'kind': 'joint', 'a': 'k.B2', 'b': 'xB2.A'}, {'kind': 'end', 'a': 'xB2.B'}],
                 'sections': [{'id': 's1', 'contact': 1, 'responseDelayMs': 0, 'releaseDelayMs': 0,
                   'ranges': [{'edge': 'xA1', 'fromMm': 0, 'toMm': 400.5}, 'k.A1']}],
                 'districts': [{'id': 0, 'reversingLoop': false, 'extraRanges': [{'edge': 'xA2', 'fromMm': 10, 'toMm': 20}, 'xB1']}],
                 'points': [{'kind': 'report', 'section': 's1', 'boundary': {'edge': 'xA1', 'offsetMm': 400.5, 'forward': false},
                   'at': {'edge': 'xA1', 'offsetMm': 380, 'forward': false}}]}
                """);
        TrackPlan plan = TrackPlanJson.parse(json);
        String written = TrackPlanJson.write(plan);
        assertEquals(plan, TrackPlanJson.parse(written));
        // fehlende Listen werden als leere Listen geschrieben; sonst gleicher Inhalt
        assertEquals(0, plan.blocks().size());
        assertEquals(OptionalDouble.of(900), ((TrackPlan.PlainPlacement) plan.pieces().get(4)).lengthMm());
        TrackPlanReport r = TrackPlanLoader.build(plan);
        TrackGraph g = r.layout().orElseThrow().graph();
        assertNear(100, g.edge("k.A1").lengthMm(), "halber Strang aus Instanzmaß");
        assertNear(220, g.edge("k.A2").lengthMm(), "halber Strang aus Instanzmaß");
        assertNear(600 + 400 * Math.PI / 6, g.edge("xA1").lengthMm(), "zusammengesetzte Geometrie");
        assertTrue(codes(r).contains(Code.UNKNOWN_LENGTH), r.text());
        assertTrue(r.layout().orElseThrow().reportPoints("s1").stream().anyMatch(ReportPoint::overridden), r.text());
    }

    // --- fehlerhafte Dateien ------------------------------------------------------------------------------------

    public void testBrokenJsonReportsLine() {
        single(loadErrors("{\n 'formatVersion': 1,\n 'catalog': [\n".replace('\'', '"')), Phase.SYNTAX, "JSON-Fehler in Zeile 4");
        single(loadErrors("{\"formatVersion\": 1, \"name\": \"a\\u12\"}"), Phase.SYNTAX, "\\u");
        single(loadErrors("{\"formatVersion\": 1-2}"), Phase.SYNTAX, "Ungültige Zahl");
        single(loadErrors(""), Phase.SYNTAX, "Unerwartetes Ende");
    }

    public void testDuplicateKeyIsError() {
        single(loadErrors(minimal("'name': 'a', 'name': 'b'")), Phase.SYNTAX, "Schlüssel \"name\" doppelt");
    }

    public void testFormatVersionRequiredAndChecked() {
        single(loadErrors(j("{'catalog': []}")), Phase.FORMAT, "formatVersion", "fehlt");
        single(loadErrors(j("{'formatVersion': 3}")), Phase.FORMAT, "Formatversion 3 wird nicht unterstützt", "1 bis 2");
        single(loadErrors(j("{'formatVersion': 0}")), Phase.FORMAT, "Formatversion 0 wird nicht unterstützt");
        single(loadErrors(j("[1, 2]")), Phase.FORMAT, "Objekt erwartet");
    }

    public void testMinimalFileLoads() {
        TrackPlanReport r = TrackPlanLoader.load(minimal(""));
        assertTrue(r.loaded(), r.text());
        // ohne Block, Melder und Bezirk: nur Hinweise
        assertEquals(List.of(), r.errors());
        assertEquals(List.of(Code.TRACK_WITHOUT_SENSOR, Code.TRACK_WITHOUT_DISTRICT, Code.UNREACHABLE_NODE), codes(r));
    }

    public void testMissingRequiredFieldsAreCollected() {
        List<Problem> p = loadErrors(minimal("""
                'sections': [{'id': 's', 'contact': 1, 'responseDelayMs': 50, 'ranges': ['p']}],
                'blocks': [{'id': 'B', 'sections': ['s'], 'endA': {'edge': 'p', 'offsetMm': 0}, 'directions': ['A_TO_B']}]
                """.replace('\'', '"')));
        assertEquals(2, p.size());
        assertEquals(Phase.FORMAT, p.get(0).phase());
        assertEquals("sections[0] (s)", p.get(0).where());
        assertTrue(p.get(0).message().contains("Pflichtfeld \"releaseDelayMs\" fehlt"), p.toString());
        assertEquals("blocks[0] (B).endA", p.get(1).where());
        assertTrue(p.get(1).message().contains("Pflichtfeld \"forward\" fehlt"), p.toString());
    }

    public void testTurnoutNeedsInstanceValues() {
        String json = j("{'formatVersion': 1, 'catalog': [{'id': 'W', 'kind': 'turnout', 'hand': 'LEFT', 'suggestion':"
                + " {'straightLengthMm': 200, 'divergingLengthMm': 205, 'straightClearanceMm': 150, 'divergingClearanceMm': 250}}],"
                + " 'pieces': [{'id': 'w', 'type': 'W', 'turnout': 'w'}], 'connections': []}");
        single(loadErrors(json), Phase.FORMAT, "pieces[0] (w)", "Pflichtfeld \"branches\" fehlt");
    }

    public void testUnknownFieldIsError() {
        // Tippfehler darf nicht stillschweigend die Typlänge ergeben
        String json = minimal("").replace("{\"id\": \"p\", \"type\": \"G\"}", "{\"id\": \"p\", \"type\": \"G\", \"lenghtMm\": 900}");
        single(loadErrors(json), Phase.FORMAT, "pieces[0] (p)", "unbekanntes Feld \"lenghtMm\"");
        // Instanzwerte einer anderen Art sind ebenfalls unbekannt
        String wrongKind = minimal("").replace("{\"id\": \"p\", \"type\": \"G\"}", "{\"id\": \"p\", \"type\": \"G\", \"turnout\": \"w\"}");
        single(loadErrors(wrongKind), Phase.FORMAT, "unbekanntes Feld \"turnout\"");
        single(loadErrors(minimal("'routes': []".replace('\'', '"'))), Phase.FORMAT, "Datei", "unbekanntes Feld \"routes\"");
    }

    public void testWrongTypesAndValues() {
        single(loadErrors(minimal(j("'sections': [{'id': 's', 'contact': '1', 'responseDelayMs': 0, 'releaseDelayMs': 0, 'ranges': ['p']}]"))),
                Phase.FORMAT, "sections[0] (s).contact", "ganze Zahl erwartet");
        single(loadErrors(minimal(j("'sections': [{'id': 's', 'contact': 1.5, 'responseDelayMs': 0, 'releaseDelayMs': 0, 'ranges': ['p']}]"))),
                Phase.FORMAT, "ganze Zahl erwartet, gefunden: 1.5");
        single(loadErrors(minimal(j("'sections': [{'id': 's', 'contact': 0, 'responseDelayMs': 0, 'releaseDelayMs': 0, 'ranges': ['p']}]"))),
                Phase.FORMAT, "Kontakt muss größer 0 sein");
        single(loadErrors(minimal(j("'sections': [{'id': 's', 'contact': 1, 'responseDelayMs': 0, 'releaseDelayMs': 0, 'ranges': []}]"))),
                Phase.FORMAT, "Lage fehlt");
        single(loadErrors(minimal("").replace("\"lengthMm\": 1000", "\"lengthMm\": -5")), Phase.FORMAT, "catalog[0] (G)", "größer 0");
        single(loadErrors(minimal("").replace("\"lengthMm\": 1000", "\"lengthMm\": 1e999")), Phase.FORMAT, "endliche Zahl erwartet");
        single(loadErrors(minimal(j("'blocks': [{'id': 'B', 'sections': [], 'endA': {'edge': 'p', 'offsetMm': 0, 'forward': true},"
                + " 'directions': ['VORWAERTS']}]"))), Phase.FORMAT, "blocks[0] (B).directions[0]", "unbekannter Wert \"VORWAERTS\"");
        single(loadErrors(minimal(j("'blocks': [{'id': 'B', 'sections': [], 'endA': {'edge': 'p', 'offsetMm': 0, 'forward': true},"
                + " 'directions': []}]"))), Phase.FORMAT, "mindestens eine erlaubte Richtung");
        single(loadErrors(minimal(j("'points': [{'kind': 'halt', 'id': 'x'}]"))), Phase.FORMAT, "unbekannte Punktart \"halt\"");
    }

    public void testUnknownReferencesBeforeGraphIsBuilt() {
        single(loadErrors(minimal("").replace("\"type\": \"G\"", "\"type\": \"G2\"")), Phase.REFERENCE, "pieces[0] (p)",
                "unbekannter Typ G2");
        single(loadErrors(minimal("").replace("\"a\": \"p.B\"", "\"a\": \"q.B\"")), Phase.REFERENCE, "connections[1]",
                "unbekanntes Stück q");
        single(loadErrors(minimal("").replace("\"a\": \"p.B\"", "\"a\": \"p.TIP\"")), Phase.REFERENCE,
                "Stück p (Typ G) hat keinen Anschluss TIP");
        single(loadErrors(minimal("").replace("\"a\": \"p.B\"", "\"a\": \"p.X\"")), Phase.FORMAT, "unbekannter Wert \"X\"");
        single(loadErrors(minimal("").replace("\"a\": \"p.B\"", "\"a\": \"pB\"")), Phase.FORMAT, "\"stück.ANSCHLUSS\"");
        single(loadErrors(minimal("").replace("{\"id\": \"p\", \"type\": \"G\"}", "{\"id\": \"p\", \"type\": \"G\"}, {\"id\": \"p\", \"type\": \"G\"}")),
                Phase.REFERENCE, "Stückkennung p mehrfach");
        // ungültige Kennung: Verbindungen zu „p“ zeigen auf ein unbekanntes Stück, die Kennung selbst ist Formatfehler
        List<Problem> p = loadErrors(minimal("").replace("\"id\": \"p\"", "\"id\": \"p.1\""));
        assertEquals(List.of(Phase.FORMAT, Phase.REFERENCE, Phase.REFERENCE), p.stream().map(Problem::phase).toList());
        assertTrue(p.getFirst().message().contains("ungültige Stückkennung 'p.1'"), p.toString());
        // Folgefehler eines fehlerhaften Katalogeintrags werden nicht zusätzlich gemeldet
        single(loadErrors(minimal("").replace("\"kind\": \"straight\"", "\"kind\": \"gerade\"")), Phase.FORMAT,
                "unbekannte Geometrie \"gerade\"");
    }

    public void testGraphErrorsAreLoadErrors() {
        List<Problem> p = loadErrors(minimal("").replace(", {\"kind\": \"end\", \"a\": \"p.B\"}", ""));
        single(p, Phase.GRAPH, "Offener Anschluss p.B");
    }

    public void testUnknownEdgeIsCheckFinding() {
        // Kanten entstehen erst mit dem Graph: unbekannte Kante ist ein Prüf-Fehler im gemeinsamen Bericht
        TrackPlanReport r = TrackPlanLoader.load(minimal(j(
                "'sections': [{'id': 's', 'contact': 1, 'responseDelayMs': 0, 'releaseDelayMs': 0, 'ranges': ['nix']}]")));
        assertTrue(r.loaded(), r.text());
        assertTrue(!r.permitsAutomatic(), "Automatik gesperrt");
        LayoutFinding f = r.errors().getFirst();
        assertEquals(Code.UNKNOWN_REFERENCE, f.code());
        assertTrue(f.message().contains("unbekannte Kante nix"), f.toString());
    }

    public void testMissingFileIsLoadError() {
        TrackPlanReport r = TrackPlanLoader.load(Path.of("gibt-es-nicht", "anlage.json"));
        single(r.loadErrors(), Phase.FILE, "Datei nicht lesbar");
        assertTrue(r.plan().isEmpty() && !r.permitsAutomatic(), "nichts geladen");
    }

    public void testParseThrowsWithAllProblems() {
        TrackPlanException e = null;
        try {
            TrackPlanJson.parse(minimal(j("'sections': [{'id': 's'}]")));
        } catch (TrackPlanException x) {
            e = x;
        }
        assertTrue(e != null, "Ausnahme erwartet");
        assertEquals(4, e.problems().size()); // contact, responseDelayMs, releaseDelayMs, ranges
        assertTrue(e.getMessage().startsWith("Gleisplan-Datei ungültig"), e.getMessage());
    }

    public void testBuildChecksReferencesOfProgrammaticPlan() {
        TrackPlan good = TrackPlanJson.parse(minimal(""));
        TrackPlan mismatch = new TrackPlan(1, "", good.catalog(),
                List.of(new TrackPlan.CrossingPlacement("p", "G", TrackPiece.CrossingDimensions.symmetric(300, 300), false)),
                good.connections(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), 3, Optional.empty(), Catenary.UNSPECIFIED);
        single(TrackPlanLoader.build(mismatch).loadErrors(), Phase.REFERENCE, "Typ G passt nicht zur Art des Stücks");
    }

    public void testReportText() {
        TrackPlanReport r = load("bahnhof.json");
        String t = r.text();
        assertTrue(t.startsWith("Gleisplan „Bahnhof mit Weichen“: 0 Ladefehler, 0 Fehler, 1 Hinweise – Automatik erlaubt"), t);
        assertTrue(t.contains("DOUBLE_SLIP_LOGIC_UNVERIFIED"), t);
        TrackPlanReport bad = TrackPlanLoader.load("{");
        assertTrue(bad.text().contains("Ladefehler SYNTAX"), bad.text());
    }

    public void testModelValidation() {
        assertThrows(IllegalArgumentException.class, () -> TrackPlan.Connection.end(null));
        assertThrows(IllegalArgumentException.class, () -> new TrackPlan.Connection(TrackPlan.ConnectionKind.JOINT,
                new TrackPlan.PortRef("a", Port.A), null));
        assertThrows(IllegalArgumentException.class, () -> TrackPlan.PlanRange.of("e", 5, 1));
        assertThrows(IllegalArgumentException.class, () -> new TrackPlan.PlainPlacement("p", "G", OptionalDouble.of(0), false));
        assertEquals(TrackPlan.PlanRange.whole("e"), new TrackPlan.PlanRange("e", true, 3, 4));
    }

    // --- optionale Angaben ab Formatversion 2 (Entscheidung 46, Option A) ---------------------------------------

    private static TrackPlan.PlacedPiece piece(TrackPlan plan, String id) {
        return plan.pieces().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    public void testStationCarriesOptionalFields() {
        TrackPlanReport r = load("bahnhof.json");
        TrackPlan plan = r.plan().orElseThrow();
        assertEquals(Optional.of(Gauge.of(NemGauge.H0)), plan.gauge());
        assertEquals(Catenary.PRESENT, plan.catenary());
        // Spurweite: eigene Angabe überschreibt die der Anlage, mehrere bei Mehrschienengleis
        Set<Gauge> dual = Set.of(Gauge.of(NemGauge.H0m), Gauge.of(NemGauge.H0));
        assertEquals(dual, plan.gaugesOf(piece(plan, "a1")));
        assertEquals(dual, plan.gaugesOf(piece(plan, "D1")));
        assertEquals(Set.of(Gauge.custom(10.5), Gauge.of(NemGauge.H0m), Gauge.of(NemGauge.H0)),
                plan.gaugesOf(piece(plan, "a3")));
        assertEquals(Set.of(Gauge.of(NemGauge.H0)), plan.gaugesOf(piece(plan, "zo")));
        assertEquals(Set.of(), piece(plan, "zo").properties().gauges());
        // Oberleitung: Anlagenstandard, je Stück überschreibbar, jede Stückart
        assertEquals(Catenary.PRESENT, plan.catenaryOf(piece(plan, "g1a")));
        assertEquals(Catenary.UNSPECIFIED, piece(plan, "g1a").properties().catenary());
        assertEquals(Catenary.PRESENT, piece(plan, "W1").properties().catenary());
        assertEquals(Catenary.ABSENT, plan.catenaryOf(piece(plan, "D1")));
        assertEquals(Catenary.ABSENT, plan.catenaryOf(piece(plan, "l1")));
        assertEquals(Catenary.PRESENT, plan.catenaryOf(piece(plan, "W2")));
        // Block-Angaben kommen unverändert im geprüften Gleisplan an
        TrackLayout l = r.layout().orElseThrow();
        Block ost = l.block("Ost").orElseThrow();
        assertEquals(Set.of(Block.Direction.A_TO_B, Block.Direction.B_TO_A), ost.noReversing());
        assertTrue(!ost.reversingAllowed(Block.Direction.A_TO_B) && !ost.reversingAllowed(Block.Direction.B_TO_A), "Wendeverbot");
        assertTrue(ost.stoppingAllowed(Block.Direction.A_TO_B), "Halten A → B erlaubt");
        assertTrue(!ost.stoppingAllowed(Block.Direction.B_TO_A), "Halteverbot B → A");
        Block g1 = l.block("Gleis1").orElseThrow();
        assertEquals(PlatformSide.RIGHT, g1.platformSide());
        assertEquals(PlatformSide.RIGHT, g1.platformSide(Block.Direction.A_TO_B));
        assertEquals(PlatformSide.LEFT, g1.platformSide(Block.Direction.B_TO_A));
        assertTrue(g1.reversingAllowed(Block.Direction.A_TO_B) && g1.stoppingAllowed(Block.Direction.B_TO_A), "ohne Verbot");
        assertEquals(PlatformSide.BOTH, l.block("Gleis2").orElseThrow().platformSide(Block.Direction.B_TO_A));
        assertEquals(PlatformSide.NONE, l.block("Auszieh").orElseThrow().platformSide(Block.Direction.B_TO_A));
        assertEquals(PlatformSide.UNSPECIFIED, l.block("West").orElseThrow().platformSide());
        StopPoint kurz = plan.stopPoints().stream().filter(s -> s.id().equals("hG1kurz")).findFirst().orElseThrow();
        assertEquals(PlatformSide.LEFT, kurz.platformSide());
        assertEquals(PlatformSide.UNSPECIFIED, plan.stopPoints().getFirst().platformSide());
        // reine Datenhaltung: keine neuen Befunde
        assertEquals(List.of(Code.DOUBLE_SLIP_LOGIC_UNVERIFIED), codes(r));
    }

    public void testOptionalFieldsDefaultWhenMissing() {
        TrackPlan plan = TrackPlanJson.parse(minimal(j("'blocks': [{'id': 'B', 'sections': [],"
                + " 'endA': {'edge': 'p', 'offsetMm': 0, 'forward': true}, 'directions': ['A_TO_B']}],"
                + " 'points': [{'kind': 'stop', 'id': 'h', 'at': {'edge': 'p', 'offsetMm': 500, 'forward': true}}]")));
        assertEquals(Optional.empty(), plan.gauge());
        assertEquals(Catenary.UNSPECIFIED, plan.catenary());
        assertEquals(TrackPlan.PieceProperties.NONE, plan.pieces().getFirst().properties());
        assertEquals(Set.of(), plan.gaugesOf(plan.pieces().getFirst()));
        // fehlt beides (Anlage und Stück), bleibt die Oberleitung ohne Angabe
        assertEquals(Catenary.UNSPECIFIED, plan.catenaryOf(plan.pieces().getFirst()));
        Block b = plan.blocks().getFirst();
        assertEquals(Set.of(), b.noReversing());
        assertEquals(Set.of(), b.noStopping());
        assertEquals(PlatformSide.UNSPECIFIED, b.platformSide());
        assertEquals(PlatformSide.UNSPECIFIED, plan.stopPoints().getFirst().platformSide());
        // Standardwerte werden nicht geschrieben
        String written = TrackPlanJson.write(plan);
        for (String k : List.of("gauge", "catenary", "noReversing", "noStopping", "platformSide"))
            assertTrue(!written.contains(k), k + " nicht geschrieben: " + written);
    }

    public void testOptionalFieldsRoundTrip() {
        String json = minimal(j("'gauge': {'gauge': 'CUSTOM', 'customGaugeMm': 10.5}, 'catenary': 'ABSENT',"
                + " 'blocks': [{'id': 'B', 'sections': [],"
                + " 'endA': {'edge': 'p', 'offsetMm': 0, 'forward': true}, 'directions': ['A_TO_B', 'B_TO_A'],"
                + " 'noReversing': ['B_TO_A', 'B_TO_A'], 'noStopping': ['B_TO_A', 'A_TO_B'], 'platformSide': 'LEFT'}],"
                + " 'points': [{'kind': 'stop', 'id': 'h', 'at': {'edge': 'p', 'offsetMm': 500, 'forward': false},"
                + " 'platformSide': 'BOTH'}]"))
                .replace("{\"id\": \"p\", \"type\": \"G\"}",
                        "{\"id\": \"p\", \"type\": \"G\", \"gauges\": [\"0e\", \"H0e\"], \"catenary\": \"PRESENT\"}");
        TrackPlan plan = TrackPlanJson.parse(json);
        assertEquals(Optional.of(Gauge.custom(10.5)), plan.gauge());
        assertEquals(Catenary.ABSENT, plan.catenary());
        assertEquals(new TrackPlan.PieceProperties(Set.of(Gauge.of(NemGauge.H0e), Gauge.of(NemGauge.ZERO_E)), Catenary.PRESENT),
                plan.pieces().getFirst().properties());
        assertEquals(Catenary.PRESENT, plan.catenaryOf(plan.pieces().getFirst())); // Stück überschreibt Anlage
        assertEquals(Set.of(Block.Direction.B_TO_A), plan.blocks().getFirst().noReversing()); // doppelt zählt einmal
        String written = TrackPlanJson.write(plan);
        assertEquals(plan, TrackPlanJson.parse(written));
        assertEquals(written, TrackPlanJson.write(TrackPlanJson.parse(written)));
        // feste Reihenfolge: Spurweiten aufsteigend, Richtungen wie in der Aufzählung
        assertTrue(written.contains("\"gauges\": [\"H0e\", \"0e\"]"), written);
        Map<?, ?> tree = (Map<?, ?>) Json.parse(written);
        assertEquals(Map.of("gauge", "CUSTOM", "customGaugeMm", 10.5), tree.get("gauge"));
        assertEquals("ABSENT", tree.get("catenary"));
        assertEquals("BOTH", ((Map<?, ?>) ((List<?>) tree.get("points")).getFirst()).get("platformSide"));
        assertEquals(List.of("A_TO_B", "B_TO_A"), ((Map<?, ?>) ((List<?>) tree.get("blocks")).getFirst()).get("noStopping"));
        assertTrue(TrackPlanLoader.build(plan).loaded(), "baut");
    }

    public void testVersion1StillLoadsAndIsLifted() {
        // eine Datei der Version 1 lädt weiter und wird beim Lesen auf die aktuelle Version gehoben
        String v1 = minimal(1, j("'blocks': [{'id': 'B', 'sections': [],"
                + " 'endA': {'edge': 'p', 'offsetMm': 0, 'forward': true}, 'directions': ['A_TO_B']}]"));
        TrackPlanReport r = TrackPlanLoader.load(v1);
        assertTrue(r.loaded(), r.text());
        TrackPlan plan = r.plan().orElseThrow();
        assertEquals(TrackPlan.FORMAT_VERSION, plan.formatVersion());
        assertEquals(PlatformSide.UNSPECIFIED, plan.blocks().getFirst().platformSide());
        assertEquals(TrackPlan.PieceProperties.NONE, plan.pieces().getFirst().properties());
        String written = TrackPlanJson.write(plan);
        assertTrue(written.contains("\"formatVersion\": " + TrackPlan.FORMAT_VERSION + ","), written);
        assertEquals(plan, TrackPlanJson.parse(written));
        // gleicher Inhalt wie dieselbe Datei als Version 2
        assertEquals(plan, TrackPlanJson.parse(v1.replace("\"formatVersion\": 1", "\"formatVersion\": 2")));
    }

    public void testVersion1RejectsNewFields() {
        // als Version 1 gekennzeichnet, aber mit Angaben ab Version 2: unbekannte Felder (Tippfehler in der Version)
        single(loadErrors(minimal(1, "\"gauge\": \"H0\"")), Phase.FORMAT, "Datei", "unbekanntes Feld \"gauge\"");
        single(loadErrors(minimal(1, "\"catenary\": \"PRESENT\"")), Phase.FORMAT, "Datei", "unbekanntes Feld \"catenary\"");
        single(loadErrors(minimal(1, "").replace("{\"id\": \"p\", \"type\": \"G\"}",
                "{\"id\": \"p\", \"type\": \"G\", \"catenary\": \"PRESENT\"}")), Phase.FORMAT, "pieces[0] (p)",
                "unbekanntes Feld \"catenary\"");
        single(loadErrors(minimal(1, j("'blocks': [{'id': 'B', 'sections': [], 'endA': {'edge': 'p', 'offsetMm': 0,"
                + " 'forward': true}, 'directions': ['A_TO_B'], 'noStopping': ['A_TO_B']}]"))), Phase.FORMAT,
                "blocks[0] (B)", "unbekanntes Feld \"noStopping\"");
        single(loadErrors(minimal(1, j("'points': [{'kind': 'stop', 'id': 'h', 'at': {'edge': 'p', 'offsetMm': 5,"
                + " 'forward': true}, 'platformSide': 'LEFT'}]"))), Phase.FORMAT, "points[0] (h)",
                "unbekanntes Feld \"platformSide\"");
    }

    private static String withPiece(String fields) {
        return minimal("").replace("{\"id\": \"p\", \"type\": \"G\"}", "{\"id\": \"p\", \"type\": \"G\", " + j(fields) + "}");
    }

    private static String withBlock(String fields) {
        return minimal(j("'blocks': [{'id': 'B', 'sections': [], 'endA': {'edge': 'p', 'offsetMm': 0, 'forward': true},"
                + " 'directions': ['A_TO_B'], " + fields + "}]"));
    }

    public void testInvalidOptionalFields() {
        // unbekannte Aufzählungswerte
        single(loadErrors(withPiece("'catenary': 'JA'")), Phase.FORMAT, "pieces[0] (p).catenary",
                "unbekannter Wert \"JA\"", "PRESENT");
        single(loadErrors(withPiece("'catenary': true")), Phase.FORMAT, "pieces[0] (p).catenary", "Text erwartet");
        single(loadErrors(withBlock("'platformSide': 'OBEN'")), Phase.FORMAT, "blocks[0] (B).platformSide",
                "unbekannter Wert \"OBEN\"");
        single(loadErrors(withBlock("'noReversing': ['VORWAERTS']")), Phase.FORMAT, "blocks[0] (B).noReversing[0]",
                "unbekannter Wert \"VORWAERTS\"");
        single(loadErrors(withBlock("'noStopping': 'A_TO_B'")), Phase.FORMAT, "blocks[0] (B).noStopping", "Liste erwartet");
        single(loadErrors(minimal(j("'points': [{'kind': 'stop', 'id': 'h', 'at': {'edge': 'p', 'offsetMm': 5,"
                + " 'forward': true}, 'platformSide': 'MITTE'}]"))), Phase.FORMAT, "points[0] (h).platformSide",
                "unbekannter Wert \"MITTE\"");
        // „keine Angabe“ heißt Feld weglassen, nicht ausdrücklich UNSPECIFIED
        single(loadErrors(withPiece("'catenary': 'UNSPECIFIED'")), Phase.FORMAT, "pieces[0] (p).catenary", "weglassen");
        single(loadErrors(minimal(j("'catenary': 'UNSPECIFIED'"))), Phase.FORMAT, "Datei.catenary", "weglassen");
        single(loadErrors(withBlock("'platformSide': 'UNSPECIFIED'")), Phase.FORMAT, "blocks[0] (B).platformSide", "weglassen");
        single(loadErrors(minimal(j("'catenary': 'JA'"))), Phase.FORMAT, "Datei.catenary", "unbekannter Wert \"JA\"");
        // Spurweiten: Liste
        single(loadErrors(withPiece("'gauges': []")), Phase.FORMAT, "pieces[0] (p).gauges", "mindestens eine Spurweite");
        single(loadErrors(withPiece("'gauges': ['H0', 'H0']")), Phase.FORMAT, "pieces[0] (p).gauges[1]", "H0 doppelt");
        single(loadErrors(withPiece("'gauges': [{'gauge': 'CUSTOM', 'customGaugeMm': 10.5},"
                + " {'gauge': 'CUSTOM', 'customGaugeMm': 10.5}]")), Phase.FORMAT, "pieces[0] (p).gauges[1]", "doppelt");
        single(loadErrors(withPiece("'gauges': 'H0'")), Phase.FORMAT, "pieces[0] (p).gauges", "Liste erwartet");
        single(loadErrors(withPiece("'gauges': [16.5]")), Phase.FORMAT, "pieces[0] (p).gauges[0]", "Spurname (Text) oder CUSTOM-Objekt");
        // Spurweiten: Bezeichnung nach NEM 010, groß/klein genau, keine Zusatzschreibweisen
        single(loadErrors(withPiece("'gauges': ['HO']")), Phase.FORMAT, "pieces[0] (p).gauges[0]", "unbekannte Spur \"HO\"", "H0m");
        single(loadErrors(minimal(j("'gauge': 'h0'"))), Phase.FORMAT, "Datei.gauge", "unbekannte Spur \"h0\"");
        single(loadErrors(minimal(j("'gauge': 'G'"))), Phase.FORMAT, "unbekannte Spur \"G\"");
        single(loadErrors(minimal(j("'gauge': 'H0n3'"))), Phase.FORMAT, "unbekannte Spur \"H0n3\"");
        single(loadErrors(minimal(j("'gauge': 'H0f'"))), Phase.FORMAT, "unbekannte Spur \"H0f\"");
        // Spurweiten: CUSTOM nur mit eigenem mm-Wert, eigener mm-Wert nur bei CUSTOM
        single(loadErrors(minimal(j("'gauge': 'CUSTOM'"))), Phase.FORMAT, "Datei.gauge", "CUSTOM nur als Objekt");
        single(loadErrors(minimal(j("'gauge': {'gauge': 'CUSTOM'}"))), Phase.FORMAT, "Datei.gauge",
                "Pflichtfeld \"customGaugeMm\" fehlt");
        single(loadErrors(minimal(j("'gauge': {'gauge': 'H0', 'customGaugeMm': 16.5}"))), Phase.FORMAT,
                "Datei.gauge.gauge", "Objektform nur für CUSTOM");
        single(loadErrors(minimal(j("'gauge': {'gauge': 'CUSTOM', 'customGaugeMm': 0}"))), Phase.FORMAT, "Datei.gauge",
                "eigene Spurweite muss größer 0 sein");
        single(loadErrors(minimal(j("'gauge': {'gauge': 'CUSTOM', 'customGaugeMm': 10.5, 'name': 'H0n3'}"))), Phase.FORMAT,
                "Datei.gauge", "unbekanntes Feld \"name\"");
    }

    public void testNemGaugesMatchNem010() {
        // Stichproben gegen NEM 010 (Ausgabe 2011, Tabelle 1): Spurweite in mm und Maßstab
        Object[][] known = {
                {"Z", 6.5, 220.0}, {"N", 9.0, 160.0}, {"TT", 12.0, 120.0}, {"H0", 16.5, 87.0}, {"S", 22.5, 64.0},
                {"0", 32.0, 45.0}, {"I", 45.0, 32.0}, {"II", 64.0, 22.5}, {"III", 89.0, 16.0}, {"V", 127.0, 11.0},
                {"VII", 184.0, 8.0}, {"X", 260.0, 5.5},
                {"Zm", 4.5, 220.0}, {"Nm", 6.5, 160.0}, {"TTm", 9.0, 120.0}, {"H0m", 12.0, 87.0}, {"0m", 22.5, 45.0},
                {"IIm", 45.0, 22.5}, {"Xm", 184.0, 5.5},
                {"Ne", 4.5, 160.0}, {"H0e", 9.0, 87.0}, {"0e", 16.5, 45.0}, {"IIe", 32.0, 22.5}, {"Xe", 127.0, 5.5},
                {"TTi", 4.5, 120.0}, {"H0i", 6.5, 87.0}, {"0i", 12.0, 45.0}, {"Xi", 89.0, 5.5},
                {"H0p", 4.5, 87.0}, {"0p", 9.0, 45.0}, {"IIp", 16.5, 22.5}, {"Xp", 64.0, 5.5}};
        for (Object[] k : known) {
            NemGauge g = NemGauge.byName((String) k[0]).orElseThrow(() -> new AssertionError("fehlt: " + k[0]));
            assertEquals(k[1], g.gaugeMm());
            assertEquals(k[2], g.scale());
            assertEquals(k[0], g.nemName());
        }
        // vollständige Tabelle: 12 Normalspur, 12 m, 11 e, 10 i, 9 p, dazu CUSTOM; Bezeichnungen eindeutig
        assertEquals(12 + 12 + 11 + 10 + 9 + 1, NemGauge.values().length);
        assertEquals((long) NemGauge.values().length,
                java.util.Arrays.stream(NemGauge.values()).map(NemGauge::nemName).distinct().count());
        // je Gruppe gilt: eine Stufe schmaler = Spurweite der nächstkleineren Nenngröße (Diagonale der Tabelle)
        assertEquals(NemGauge.TT.gaugeMm(), NemGauge.H0m.gaugeMm());
        assertEquals(NemGauge.N.gaugeMm(), NemGauge.H0e.gaugeMm());
        assertEquals(NemGauge.TTe.gaugeMm(), NemGauge.H0i.gaugeMm());
        // H0 und 0e: gleiche Spurweite, verschiedene Spur
        assertEquals(Gauge.of(NemGauge.H0).mm(), Gauge.of(NemGauge.ZERO_E).mm());
        assertTrue(!Gauge.of(NemGauge.H0).equals(Gauge.of(NemGauge.ZERO_E)), "verschiedene Spur");
        assertTrue(Double.isNaN(NemGauge.CUSTOM.gaugeMm()), "CUSTOM ohne eigenen Wert");
        assertEquals(10.5, Gauge.custom(10.5).mm());
        assertTrue(NemGauge.byName("CUSTOM").isPresent() && NemGauge.byName("h0").isEmpty(), "Lookup");
        // Reihenfolge in der Datei: nach Spurweite
        assertEquals(List.of(Gauge.of(NemGauge.H0e), Gauge.custom(10.5), Gauge.of(NemGauge.H0m), Gauge.of(NemGauge.H0)),
                new TrackPlan.PieceProperties(Set.of(Gauge.of(NemGauge.H0), Gauge.custom(10.5), Gauge.of(NemGauge.H0e),
                        Gauge.of(NemGauge.H0m)), null).sortedGauges());
    }

    public void testOptionalFieldsModel() {
        assertEquals(PlatformSide.RIGHT, PlatformSide.LEFT.mirrored());
        assertEquals(PlatformSide.LEFT, PlatformSide.RIGHT.mirrored());
        for (PlatformSide s : List.of(PlatformSide.UNSPECIFIED, PlatformSide.NONE, PlatformSide.BOTH))
            assertEquals(s, s.mirrored());
        assertThrows(IllegalArgumentException.class, () -> Gauge.custom(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Gauge.custom(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Gauge.custom(-1));
        assertThrows(IllegalArgumentException.class, () -> Gauge.of(NemGauge.CUSTOM));
        assertThrows(IllegalArgumentException.class, () -> new Gauge(NemGauge.H0, OptionalDouble.of(16.5)));
        assertThrows(IllegalArgumentException.class, () -> new Gauge(null, null));
        assertEquals(TrackPlan.PieceProperties.NONE, new TrackPlan.PieceProperties(null, null));
        DirectedLocation at = DirectedLocation.of("p", 0, true);
        Block b = new Block("b", List.of(), at, Set.of(Block.Direction.A_TO_B), null, 0, 1, null, null, null);
        assertEquals(Set.of(), b.noReversing());
        assertEquals(PlatformSide.UNSPECIFIED, b.platformSide());
        // Ableitungen behalten die neuen Angaben
        Block full = new Block("b", List.of("s"), at, Set.of(Block.Direction.A_TO_B), Set.of(), 0, 1,
                Set.of(Block.Direction.A_TO_B), Set.of(Block.Direction.B_TO_A), PlatformSide.LEFT);
        Block derived = full.withDirections(Set.of(Block.Direction.B_TO_A)).withSections("t");
        assertEquals(full.noReversing(), derived.noReversing());
        assertEquals(full.noStopping(), derived.noStopping());
        assertEquals(PlatformSide.LEFT, derived.platformSide());
        assertEquals(PlatformSide.UNSPECIFIED, new StopPoint("h", at, null, 1, null).platformSide());
    }
}
