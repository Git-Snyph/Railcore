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
        return j("{'formatVersion': 1, 'catalog': [{'id': 'G', 'kind': 'plain', 'geometry': {'kind': 'straight', 'lengthMm': 1000}}],"
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
        single(loadErrors(j("{'formatVersion': 2}")), Phase.FORMAT, "Formatversion 2 wird nicht unterstützt");
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
                List.of(), 3);
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
}
