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
                List.of(), 3, OptionalDouble.empty());
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
        assertEquals(OptionalDouble.of(16.5), plan.gaugeMm());
        // Spurweite: eigene Angabe überschreibt die der Anlage, mehrere bei Mehrschienengleis
        assertEquals(Set.of(12.0, 16.5), plan.gaugesMmOf(piece(plan, "a1")));
        assertEquals(Set.of(12.0, 16.5), plan.gaugesMmOf(piece(plan, "D1")));
        assertEquals(Set.of(16.5), plan.gaugesMmOf(piece(plan, "zo")));
        assertEquals(Set.of(), piece(plan, "zo").properties().gaugesMm());
        // Oberleitung je Stück, jede Stückart
        assertEquals(Catenary.PRESENT, piece(plan, "g1a").properties().catenary());
        assertEquals(Catenary.PRESENT, piece(plan, "W1").properties().catenary());
        assertEquals(Catenary.ABSENT, piece(plan, "D1").properties().catenary());
        assertEquals(Catenary.ABSENT, piece(plan, "l1").properties().catenary());
        assertEquals(Catenary.UNSPECIFIED, piece(plan, "W2").properties().catenary());
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
        assertEquals(OptionalDouble.empty(), plan.gaugeMm());
        assertEquals(TrackPlan.PieceProperties.NONE, plan.pieces().getFirst().properties());
        assertEquals(Set.of(), plan.gaugesMmOf(plan.pieces().getFirst()));
        Block b = plan.blocks().getFirst();
        assertEquals(Set.of(), b.noReversing());
        assertEquals(Set.of(), b.noStopping());
        assertEquals(PlatformSide.UNSPECIFIED, b.platformSide());
        assertEquals(PlatformSide.UNSPECIFIED, plan.stopPoints().getFirst().platformSide());
        // Standardwerte werden nicht geschrieben
        String written = TrackPlanJson.write(plan);
        for (String k : List.of("gaugeMm", "gaugesMm", "catenary", "noReversing", "noStopping", "platformSide"))
            assertTrue(!written.contains(k), k + " nicht geschrieben: " + written);
    }

    public void testOptionalFieldsRoundTrip() {
        String json = minimal(j("'gaugeMm': 9, 'blocks': [{'id': 'B', 'sections': [],"
                + " 'endA': {'edge': 'p', 'offsetMm': 0, 'forward': true}, 'directions': ['A_TO_B', 'B_TO_A'],"
                + " 'noReversing': ['B_TO_A', 'B_TO_A'], 'noStopping': ['B_TO_A', 'A_TO_B'], 'platformSide': 'LEFT'}],"
                + " 'points': [{'kind': 'stop', 'id': 'h', 'at': {'edge': 'p', 'offsetMm': 500, 'forward': false},"
                + " 'platformSide': 'BOTH'}]"))
                .replace("{\"id\": \"p\", \"type\": \"G\"}",
                        "{\"id\": \"p\", \"type\": \"G\", \"gaugesMm\": [16.5, 9], \"catenary\": \"ABSENT\"}");
        TrackPlan plan = TrackPlanJson.parse(json);
        assertEquals(new TrackPlan.PieceProperties(Set.of(9.0, 16.5), Catenary.ABSENT), plan.pieces().getFirst().properties());
        assertEquals(Set.of(Block.Direction.B_TO_A), plan.blocks().getFirst().noReversing()); // doppelt zählt einmal
        String written = TrackPlanJson.write(plan);
        assertEquals(plan, TrackPlanJson.parse(written));
        assertEquals(written, TrackPlanJson.write(TrackPlanJson.parse(written)));
        // feste Reihenfolge: Spurweiten aufsteigend, Richtungen wie in der Aufzählung
        assertTrue(written.contains("\"gaugesMm\": [9, 16.5]"), written);
        Map<?, ?> tree = (Map<?, ?>) Json.parse(written);
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
        single(loadErrors(minimal(1, "\"gaugeMm\": 16.5")), Phase.FORMAT, "Datei", "unbekanntes Feld \"gaugeMm\"");
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
        // Spurweiten
        single(loadErrors(withPiece("'gaugesMm': []")), Phase.FORMAT, "pieces[0] (p).gaugesMm", "mindestens eine Spurweite");
        single(loadErrors(withPiece("'gaugesMm': [16.5, 16.5]")), Phase.FORMAT, "pieces[0] (p).gaugesMm[1]", "doppelt");
        single(loadErrors(withPiece("'gaugesMm': ['H0']")), Phase.FORMAT, "pieces[0] (p).gaugesMm[0]", "endliche Zahl erwartet");
        single(loadErrors(withPiece("'gaugesMm': [0]")), Phase.FORMAT, "pieces[0] (p)", "Spurweite muss größer 0 sein");
        single(loadErrors(withPiece("'gaugesMm': 16.5")), Phase.FORMAT, "pieces[0] (p).gaugesMm", "Liste erwartet");
        single(loadErrors(minimal("\"gaugeMm\": -16.5")), Phase.FORMAT, "Spurweite der Anlage muss größer 0 sein");
        single(loadErrors(minimal("\"gaugeMm\": \"H0\"")), Phase.FORMAT, "Datei.gaugeMm", "endliche Zahl erwartet");
    }

    public void testOptionalFieldsModel() {
        assertEquals(PlatformSide.RIGHT, PlatformSide.LEFT.mirrored());
        assertEquals(PlatformSide.LEFT, PlatformSide.RIGHT.mirrored());
        for (PlatformSide s : List.of(PlatformSide.UNSPECIFIED, PlatformSide.NONE, PlatformSide.BOTH))
            assertEquals(s, s.mirrored());
        assertThrows(IllegalArgumentException.class, () -> new TrackPlan.PieceProperties(Set.of(Double.NaN), null));
        assertThrows(IllegalArgumentException.class, () -> new TrackPlan.PieceProperties(Set.of(Double.POSITIVE_INFINITY), null));
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
