package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.layout.graph.TrackPlan.Connection;
import at.schmoegl.railcore.layout.graph.TrackPlan.ConnectionKind;
import at.schmoegl.railcore.layout.graph.TrackPlan.CrossingPlacement;
import at.schmoegl.railcore.layout.graph.TrackPlan.DoubleSlipPlacement;
import at.schmoegl.railcore.layout.graph.TrackPlan.PlacedPiece;
import at.schmoegl.railcore.layout.graph.TrackPlan.PlainPlacement;
import at.schmoegl.railcore.layout.graph.TrackPlan.PlanDistrict;
import at.schmoegl.railcore.layout.graph.TrackPlan.PlanRange;
import at.schmoegl.railcore.layout.graph.TrackPlan.PlanSection;
import at.schmoegl.railcore.layout.graph.TrackPlan.PortRef;
import at.schmoegl.railcore.layout.graph.TrackPlan.TurnoutPlacement;
import at.schmoegl.railcore.layout.graph.TrackPlanReport.Phase;
import at.schmoegl.railcore.layout.graph.TrackPlanReport.Problem;
import at.schmoegl.railcore.util.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Liest und schreibt die Gleisplan-Datei (JSON, vorläufiges Format Version {@value TrackPlan#FORMAT_VERSION},
 * Entscheidung 35) mit der eigenen JSON-Verarbeitung ({@link Json}). Aufbau und Felder beschreibt das
 * Entwicklerwiki ({@code docs/wiki/de/entwickler/architektur.md}); Beispiele liegen unter
 * {@code src/test/resources/anlagen/}.
 *
 * <p>Streng, weil ein Tippfehler sonst stillschweigend einen Standardwert ergäbe (im Zweifel halt): unbekannte
 * Felder, doppelte Schlüssel, falsche Typen, nicht ganze Zahlen, wo ganze verlangt sind, und eine andere
 * Formatversion sind Fehler. Alle Fehler werden gesammelt und gemeinsam gemeldet ({@link TrackPlanException}).
 *
 * <p>Schreiben ist verlustfrei: {@code parse(write(p)).equals(p)}. Optionale Angaben mit Standardwert werden
 * weggelassen; Sicherheitsangaben (erlaubte Richtungen, {@code reversingLoop}, {@code hardwareVerified}) und alle
 * Listen auf oberster Ebene werden immer geschrieben. Punkte werden in der Reihenfolge Halte-, Geschwindigkeits-,
 * Meldepunkte geschrieben.
 */
public final class TrackPlanJson {

    private TrackPlanJson() {}

    // ============================================================================================================
    // Lesen
    // ============================================================================================================

    /** Liest die Datei; alle Format- und Verweisfehler gesammelt als {@link TrackPlanException}. */
    public static TrackPlan parse(String text) {
        Object root;
        try {
            root = Json.parse(text, true);
        } catch (RuntimeException e) {
            throw new TrackPlanException(List.of(new Problem(Phase.SYNTAX, "", e.getMessage())));
        }
        Reader r = new Reader();
        TrackPlan plan = r.plan(root);
        if (!r.problems.isEmpty()) throw new TrackPlanException(r.problems);
        List<Problem> refs = plan.referenceProblems();
        if (!refs.isEmpty()) throw new TrackPlanException(refs);
        return plan;
    }

    private static final class Reader {
        final List<Problem> problems = new ArrayList<>();
        /** Art je Katalog-Typ (erster Eintrag gilt; doppelte meldet {@link TrackPlan#referenceProblems()}). */
        final Map<String, TrackPiece> types = new HashMap<>();
        /** Katalog-Typen, deren Eintrag fehlerhaft war: Stücke dieses Typs nicht zusätzlich als unbekannt melden. */
        final Set<String> failedTypes = new HashSet<>();

        void problem(String where, String message) { problems.add(Problem.format(where, message)); }

        /** Baut ein Objekt; scheitert die Prüfung im Konstruktor, wird sie als Formatfehler gemeldet. */
        <T> T build(String where, int before, Supplier<T> s) {
            if (problems.size() > before) return null;
            try {
                return s.get();
            } catch (IllegalArgumentException e) {
                problem(where, e.getMessage());
                return null;
            }
        }

        /** JSON-Objekt mit Pfad für Meldungen und Prüfung auf unbekannte Felder. */
        final class Obj {
            final Map<String, Object> m;
            final String path;

            Obj(Map<String, Object> m, String path) {
                this.m = m;
                this.path = path;
            }

            boolean has(String k) { return m.containsKey(k) && m.get(k) != null; }

            void allow(String... keys) {
                Set<String> allowed = new LinkedHashSet<>(Arrays.asList(keys));
                for (String k : m.keySet())
                    if (!allowed.contains(k)) problem(path, "unbekanntes Feld \"" + k + "\" (erlaubt: " + String.join(", ", allowed) + ")");
            }

            String field(String k) { return path + "." + k; }

            Object req(String k) {
                Object v = m.get(k);
                if (v == null) problem(path, "Pflichtfeld \"" + k + "\" fehlt");
                return v;
            }

            String str(String k) {
                Object v = req(k);
                if (v == null) return "";
                if (v instanceof String s) return s;
                problem(field(k), "Text erwartet, gefunden: " + Json.write(v));
                return "";
            }

            String optStr(String k, String def) { return has(k) ? str(k) : def; }

            double num(String k) {
                Object v = req(k);
                if (v == null) return 0;
                if (v instanceof Double d && Double.isFinite(d)) return d;
                problem(field(k), "endliche Zahl erwartet, gefunden: " + Json.write(v));
                return 0;
            }

            OptionalDouble optNum(String k) { return has(k) ? OptionalDouble.of(num(k)) : OptionalDouble.empty(); }

            int integer(String k) {
                Object v = req(k);
                if (v == null) return 0;
                if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) return (int) d.doubleValue();
                problem(field(k), "ganze Zahl erwartet, gefunden: " + Json.write(v));
                return 0;
            }

            OptionalInt optInt(String k) { return has(k) ? OptionalInt.of(integer(k)) : OptionalInt.empty(); }

            boolean bool(String k) {
                Object v = req(k);
                if (v == null) return false;
                if (v instanceof Boolean b) return b;
                problem(field(k), "true oder false erwartet, gefunden: " + Json.write(v));
                return false;
            }

            boolean optBool(String k, boolean def) { return has(k) ? bool(k) : def; }

            <E extends Enum<E>> E enumValue(String k, Class<E> type) {
                String s = str(k);
                if (s.isEmpty()) return null;
                return parseEnum(field(k), s, type);
            }

            Obj obj(String k) {
                Object v = req(k);
                if (v == null) return null;
                return toObj(v, field(k));
            }

            List<Object> arr(String k) {
                Object v = req(k);
                if (v == null) return List.of();
                if (v instanceof List<?> l) return new ArrayList<>(l);
                problem(field(k), "Liste erwartet, gefunden: " + Json.write(v));
                return List.of();
            }

            List<Object> optArr(String k) { return has(k) ? arr(k) : List.of(); }

            List<String> strings(String k, boolean required) {
                List<String> out = new ArrayList<>();
                List<Object> l = required ? arr(k) : optArr(k);
                for (int i = 0; i < l.size(); i++) {
                    if (l.get(i) instanceof String s) out.add(s);
                    else problem(field(k) + "[" + i + "]", "Text erwartet, gefunden: " + Json.write(l.get(i)));
                }
                return out;
            }
        }

        <E extends Enum<E>> E parseEnum(String where, String s, Class<E> type) {
            try {
                return Enum.valueOf(type, s);
            } catch (IllegalArgumentException e) {
                List<String> names = new ArrayList<>();
                for (E c : type.getEnumConstants()) names.add(c.name());
                problem(where, "unbekannter Wert \"" + s + "\" (erlaubt: " + String.join(", ", names) + ")");
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        Obj toObj(Object v, String path) {
            if (v instanceof Map<?, ?> map) return new Obj((Map<String, Object>) map, path);
            problem(path, "Objekt erwartet, gefunden: " + Json.write(v));
            return null;
        }

        /** Elemente einer Liste als Objekte; Pfad mit Kennung, falls vorhanden ({@code pieces[3] (w1)}). */
        List<Obj> elements(List<Object> list, String name) {
            List<Obj> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                String path = name + "[" + i + "]";
                if (list.get(i) instanceof Map<?, ?> map && map.get("id") instanceof String id) path += " (" + id + ")";
                Obj o = toObj(list.get(i), path);
                if (o != null) out.add(o);
            }
            return out;
        }

        // --- Wurzel ---------------------------------------------------------------------------------------------

        TrackPlan plan(Object rootValue) {
            Obj root = toObj(rootValue, "Datei");
            if (root == null) return null;
            if (!root.has("formatVersion")) {
                problem("Datei", "Pflichtfeld \"formatVersion\" fehlt (erwartet " + TrackPlan.FORMAT_VERSION + ")");
                return null;
            }
            int version = root.integer("formatVersion");
            if (!problems.isEmpty()) return null;
            if (version != TrackPlan.FORMAT_VERSION) {
                problem("Datei.formatVersion", "Formatversion " + version + " wird nicht unterstützt (erwartet "
                        + TrackPlan.FORMAT_VERSION + ")");
                return null;
            }
            root.allow("formatVersion", "name", "recommendedSectionsPerBlock", "catalog", "pieces", "connections",
                    "sections", "blocks", "holdingAreas", "districts", "signals", "points", "turnoutAddresses");
            String name = root.optStr("name", "");
            int recommended = root.optInt("recommendedSectionsPerBlock").orElse(TrackPlan.DEFAULT_SECTIONS_PER_BLOCK);

            List<TrackPiece> catalog = new ArrayList<>();
            for (Obj o : elements(root.arr("catalog"), "catalog")) {
                TrackPiece t = catalogEntry(o);
                if (t != null) catalog.add(t);
                else if (o.m.get("id") instanceof String id) failedTypes.add(id);
            }
            for (TrackPiece t : catalog) types.putIfAbsent(t.id(), t);
            List<PlacedPiece> pieces = new ArrayList<>();
            for (Obj o : elements(root.arr("pieces"), "pieces")) add(pieces, piece(o));
            List<Connection> connections = new ArrayList<>();
            for (Obj o : elements(root.arr("connections"), "connections")) add(connections, connection(o));
            List<PlanSection> sections = new ArrayList<>();
            for (Obj o : elements(root.optArr("sections"), "sections")) add(sections, section(o));
            List<Block> blocks = new ArrayList<>();
            for (Obj o : elements(root.optArr("blocks"), "blocks")) add(blocks, block(o));
            List<HoldingArea> holdingAreas = new ArrayList<>();
            for (Obj o : elements(root.optArr("holdingAreas"), "holdingAreas")) add(holdingAreas, holdingArea(o));
            List<PlanDistrict> districts = new ArrayList<>();
            for (Obj o : elements(root.optArr("districts"), "districts")) add(districts, district(o));
            List<BlockSignal> signals = new ArrayList<>();
            for (Obj o : elements(root.optArr("signals"), "signals")) add(signals, signal(o));
            List<StopPoint> stops = new ArrayList<>();
            List<SpeedChangePoint> speeds = new ArrayList<>();
            List<ReportPointOverride> reports = new ArrayList<>();
            for (Obj o : elements(root.optArr("points"), "points")) point(o, stops, speeds, reports);
            List<TurnoutAddress> addresses = new ArrayList<>();
            for (Obj o : elements(root.optArr("turnoutAddresses"), "turnoutAddresses")) add(addresses, turnoutAddress(o));

            int before = 0; // nur bauen, wenn bis hier alles fehlerfrei ist
            return build("Datei", before, () -> new TrackPlan(version, name, catalog, pieces, connections, sections,
                    blocks, holdingAreas, districts, signals, stops, speeds, reports, addresses, recommended));
        }

        static <T> void add(List<T> list, T value) { if (value != null) list.add(value); }

        // --- Katalog --------------------------------------------------------------------------------------------

        TrackPiece catalogEntry(Obj o) {
            int before = problems.size();
            String id = o.str("id");
            String kind = o.str("kind");
            switch (kind) {
                case "plain" -> {
                    o.allow("id", "kind", "geometry");
                    Obj g = o.obj("geometry");
                    TrackGeometry geometry = g == null ? null : geometry(g);
                    return build(o.path, before, () -> TrackPiece.plain(id, geometry));
                }
                case "turnout" -> {
                    o.allow("id", "kind", "hand", "suggestion");
                    TrackPiece.Hand hand = o.enumValue("hand", TrackPiece.Hand.class);
                    Obj s = o.obj("suggestion");
                    TurnoutBranches b = s == null ? null : branches(s);
                    return build(o.path, before, () -> new TrackPiece.TurnoutPiece(id, hand, b));
                }
                case "crossing" -> {
                    o.allow("id", "kind", "suggestion");
                    Obj s = o.obj("suggestion");
                    TrackPiece.CrossingDimensions d = s == null ? null : crossing(s);
                    return build(o.path, before, () -> new TrackPiece.CrossingPiece(id, d));
                }
                case "doubleSlip" -> {
                    o.allow("id", "kind", "suggestion", "clearanceSuggestion", "logic");
                    Obj s = o.obj("suggestion");
                    TrackPiece.DoubleSlipLengths l = s == null ? null : routeLengths(s);
                    Obj c = o.obj("clearanceSuggestion");
                    DoubleSlipClearances cl = c == null ? null : slipClearances(c);
                    Obj lo = o.obj("logic");
                    DoubleSlipLogic logic = lo == null ? null : logic(lo);
                    return build(o.path, before, () -> new TrackPiece.DoubleSlipPiece(id, l, cl, logic));
                }
                case "" -> { return null; }
                default -> {
                    problem(o.field("kind"), "unbekannte Art \"" + kind + "\" (erlaubt: plain, turnout, crossing, doubleSlip)");
                    return null;
                }
            }
        }

        TrackGeometry geometry(Obj o) {
            int before = problems.size();
            String kind = o.str("kind");
            switch (kind) {
                case "straight" -> {
                    o.allow("kind", "lengthMm");
                    double l = o.num("lengthMm");
                    return build(o.path, before, () -> TrackGeometry.straight(l));
                }
                case "arc" -> {
                    o.allow("kind", "radiusMm", "angleDeg");
                    double r = o.num("radiusMm");
                    double a = o.num("angleDeg");
                    return build(o.path, before, () -> TrackGeometry.arc(r, a));
                }
                case "compound" -> {
                    o.allow("kind", "parts");
                    List<TrackGeometry> parts = new ArrayList<>();
                    for (Obj p : elements(o.arr("parts"), o.field("parts"))) add(parts, geometry(p));
                    return build(o.path, before, () -> new TrackGeometry.Compound(parts));
                }
                case "" -> { return null; }
                default -> {
                    problem(o.field("kind"), "unbekannte Geometrie \"" + kind + "\" (erlaubt: straight, arc, compound)");
                    return null;
                }
            }
        }

        TurnoutBranches branches(Obj o) {
            int before = problems.size();
            o.allow("straightLengthMm", "divergingLengthMm", "straightClearanceMm", "divergingClearanceMm");
            double sl = o.num("straightLengthMm");
            double dl = o.num("divergingLengthMm");
            double sc = o.num("straightClearanceMm");
            double dc = o.num("divergingClearanceMm");
            return build(o.path, before, () -> new TurnoutBranches(sl, dl, sc, dc));
        }

        TrackPiece.CrossingDimensions crossing(Obj o) {
            int before = problems.size();
            o.allow("lineALengthMm", "lineBLengthMm", "crossingFromA1Mm", "crossingFromB1Mm");
            double a = o.num("lineALengthMm");
            double b = o.num("lineBLengthMm");
            double ka = o.num("crossingFromA1Mm");
            double kb = o.num("crossingFromB1Mm");
            return build(o.path, before, () -> new TrackPiece.CrossingDimensions(a, b, ka, kb));
        }

        /** Objekt mit Anschlussnamen als Schlüssel und Zahlen als Werten. */
        Map<Port, Double> portNumbers(Obj o, List<Port> allowed) {
            Map<Port, Double> out = new EnumMap<>(Port.class);
            List<String> names = allowed.stream().map(Port::name).toList();
            o.allow(names.toArray(String[]::new));
            for (Port p : allowed) {
                double v = o.num(p.name());
                out.put(p, v);
            }
            return out;
        }

        TrackPiece.DoubleSlipLengths routeLengths(Obj o) {
            int before = problems.size();
            Map<Port, Double> m = portNumbers(o, List.of(Port.A1_B1, Port.A1_B2, Port.A2_B1, Port.A2_B2));
            return build(o.path, before, () -> new TrackPiece.DoubleSlipLengths(m));
        }

        DoubleSlipClearances slipClearances(Obj o) {
            int before = problems.size();
            Map<Port, Double> m = portNumbers(o, DoubleSlipClearances.OUTER_PORTS);
            return build(o.path, before, () -> new DoubleSlipClearances(m));
        }

        DoubleSlipLogic logic(Obj o) {
            int before = problems.size();
            o.allow("hardwareVerified", "routes");
            boolean verified = o.optBool("hardwareVerified", false);
            Obj routes = o.obj("routes");
            Map<Port, DoubleSlipLogic.Setting> settings = new EnumMap<>(Port.class);
            if (routes != null) {
                List<Port> all = List.of(Port.A1_B1, Port.A1_B2, Port.A2_B1, Port.A2_B2);
                routes.allow(all.stream().map(Port::name).toArray(String[]::new));
                for (Port r : all) {
                    Obj s = routes.obj(r.name());
                    if (s == null) continue;
                    int b = problems.size();
                    s.allow("sideA", "sideB");
                    TurnoutPosition a = s.enumValue("sideA", TurnoutPosition.class);
                    TurnoutPosition bb = s.enumValue("sideB", TurnoutPosition.class);
                    DoubleSlipLogic.Setting setting = build(s.path, b, () -> new DoubleSlipLogic.Setting(a, bb));
                    if (setting != null) settings.put(r, setting);
                }
            }
            return build(o.path, before, () -> new DoubleSlipLogic(settings, verified));
        }

        // --- verlegte Stücke und Verbindungen -------------------------------------------------------------------

        PlacedPiece piece(Obj o) {
            int before = problems.size();
            String id = o.str("id");
            String type = o.str("type");
            if (problems.size() > before) return null;
            TrackPiece t = types.get(type);
            if (t == null) {
                if (!failedTypes.contains(type))
                    problems.add(Problem.reference(o.path, "unbekannter Typ " + type + " (nicht im Katalog)"));
                return null;
            }
            switch (t) {
                case TrackPiece.PlainPiece _ -> {
                    o.allow("id", "type", "lengthMm", "lengthUnknown");
                    OptionalDouble length = o.optNum("lengthMm");
                    boolean unknown = o.optBool("lengthUnknown", false);
                    return build(o.path, before, () -> new PlainPlacement(id, type, length, unknown));
                }
                case TrackPiece.TurnoutPiece _ -> {
                    o.allow("id", "type", "turnout", "branches", "lengthUnknown");
                    String turnout = o.str("turnout");
                    Obj b = o.obj("branches");
                    TurnoutBranches br = b == null ? null : branches(b);
                    boolean unknown = o.optBool("lengthUnknown", false);
                    return build(o.path, before, () -> new TurnoutPlacement(id, type, turnout, br, unknown));
                }
                case TrackPiece.CrossingPiece _ -> {
                    o.allow("id", "type", "dimensions", "lengthUnknown");
                    Obj d = o.obj("dimensions");
                    TrackPiece.CrossingDimensions dims = d == null ? null : crossing(d);
                    boolean unknown = o.optBool("lengthUnknown", false);
                    return build(o.path, before, () -> new CrossingPlacement(id, type, dims, unknown));
                }
                case TrackPiece.DoubleSlipPiece _ -> {
                    o.allow("id", "type", "turnoutA", "turnoutB", "routeLengthsMm", "clearancesMm", "lengthUnknown");
                    String ta = o.str("turnoutA");
                    String tb = o.str("turnoutB");
                    Obj l = o.obj("routeLengthsMm");
                    TrackPiece.DoubleSlipLengths lengths = l == null ? null : routeLengths(l);
                    Obj c = o.obj("clearancesMm");
                    DoubleSlipClearances cl = c == null ? null : slipClearances(c);
                    boolean unknown = o.optBool("lengthUnknown", false);
                    return build(o.path, before, () -> new DoubleSlipPlacement(id, type, ta, tb, lengths, cl, unknown));
                }
            }
        }

        PortRef portRef(Obj o, String k) {
            String s = o.str(k);
            if (s.isEmpty()) return null;
            int dot = s.lastIndexOf('.');
            if (dot < 1 || dot == s.length() - 1) {
                problem(o.field(k), "Anschluss als \"stück.ANSCHLUSS\" erwartet, gefunden: \"" + s + "\"");
                return null;
            }
            Port port = parseEnum(o.field(k), s.substring(dot + 1), Port.class);
            return port == null ? null : new PortRef(s.substring(0, dot), port);
        }

        Connection connection(Obj o) {
            int before = problems.size();
            String kind = o.str("kind");
            switch (kind) {
                case "joint", "isolating" -> {
                    o.allow("kind", "a", "b");
                    PortRef a = portRef(o, "a");
                    PortRef b = portRef(o, "b");
                    return build(o.path, before, () -> kind.equals("joint") ? Connection.joint(a, b) : Connection.isolating(a, b));
                }
                case "end" -> {
                    o.allow("kind", "a");
                    PortRef a = portRef(o, "a");
                    return build(o.path, before, () -> Connection.end(a));
                }
                case "" -> { return null; }
                default -> {
                    problem(o.field("kind"), "unbekannte Verbindung \"" + kind + "\" (erlaubt: joint, isolating, end)");
                    return null;
                }
            }
        }

        // --- Auflagen -------------------------------------------------------------------------------------------

        PlanRange range(Object v, String path) {
            if (v instanceof String edge) return build(path, problems.size(), () -> PlanRange.whole(edge));
            Obj o = toObj(v, path);
            if (o == null) return null;
            int before = problems.size();
            o.allow("edge", "fromMm", "toMm");
            String edge = o.str("edge");
            double from = o.num("fromMm");
            double to = o.num("toMm");
            return build(path, before, () -> PlanRange.of(edge, from, to));
        }

        List<PlanRange> ranges(Obj o, String k, boolean required) {
            List<PlanRange> out = new ArrayList<>();
            List<Object> l = required ? o.arr(k) : o.optArr(k);
            for (int i = 0; i < l.size(); i++) add(out, range(l.get(i), o.field(k) + "[" + i + "]"));
            return out;
        }

        DirectedLocation location(Obj o) {
            int before = problems.size();
            o.allow("edge", "offsetMm", "forward");
            String edge = o.str("edge");
            double offset = o.num("offsetMm");
            boolean forward = o.bool("forward");
            return build(o.path, before, () -> DirectedLocation.of(edge, offset, forward));
        }

        DirectedLocation location(Obj parent, String k) {
            Obj o = parent.obj(k);
            return o == null ? null : location(o);
        }

        PlanSection section(Obj o) {
            int before = problems.size();
            o.allow("id", "contact", "district", "responseDelayMs", "releaseDelayMs", "ranges");
            String id = o.str("id");
            int contact = o.integer("contact");
            OptionalInt district = o.optInt("district");
            int response = o.integer("responseDelayMs");
            int release = o.integer("releaseDelayMs");
            List<PlanRange> ranges = ranges(o, "ranges", true);
            return build(o.path, before, () -> new PlanSection(id, contact, ranges, response, release, district));
        }

        Block block(Obj o) {
            int before = problems.size();
            o.allow("id", "sections", "endA", "directions", "trainTypes", "minTrainLengthMm", "maxTrainLengthMm");
            String id = o.str("id");
            List<String> sections = o.strings("sections", true);
            DirectedLocation endA = location(o, "endA");
            Set<Block.Direction> directions = EnumSet.noneOf(Block.Direction.class);
            List<String> dirNames = o.strings("directions", true);
            for (int i = 0; i < dirNames.size(); i++) {
                Block.Direction d = parseEnum(o.field("directions") + "[" + i + "]", dirNames.get(i), Block.Direction.class);
                if (d != null) directions.add(d);
            }
            Set<String> trainTypes = new LinkedHashSet<>(o.strings("trainTypes", false));
            double min = o.optNum("minTrainLengthMm").orElse(0);
            double max = o.optNum("maxTrainLengthMm").orElse(Double.POSITIVE_INFINITY);
            return build(o.path, before, () -> new Block(id, sections, endA, directions, trainTypes, min, max));
        }

        HoldingArea holdingArea(Obj o) {
            int before = problems.size();
            o.allow("id", "blocks");
            String id = o.str("id");
            List<String> blocks = o.strings("blocks", true);
            return build(o.path, before, () -> new HoldingArea(id, blocks));
        }

        PlanDistrict district(Obj o) {
            int before = problems.size();
            o.allow("id", "name", "reversingLoop", "extraRanges");
            int id = o.integer("id");
            String name = o.optStr("name", "");
            boolean loop = o.optBool("reversingLoop", false);
            List<PlanRange> extra = ranges(o, "extraRanges", false);
            return build(o.path, before, () -> new PlanDistrict(id, name, loop, extra));
        }

        BlockSignal signal(Obj o) {
            int before = problems.size();
            o.allow("id", "block", "end", "addresses");
            String id = o.str("id");
            String block = o.str("block");
            Block.End end = o.enumValue("end", Block.End.class);
            List<Integer> addresses = new ArrayList<>();
            List<Object> raw = o.optArr("addresses");
            for (int i = 0; i < raw.size(); i++) {
                if (raw.get(i) instanceof Double d && d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE)
                    addresses.add((int) d.doubleValue());
                else problem(o.field("addresses") + "[" + i + "]", "ganze Zahl erwartet, gefunden: " + Json.write(raw.get(i)));
            }
            return build(o.path, before, () -> new BlockSignal(id, block, end, addresses));
        }

        void point(Obj o, List<StopPoint> stops, List<SpeedChangePoint> speeds, List<ReportPointOverride> reports) {
            int before = problems.size();
            String kind = o.str("kind");
            switch (kind) {
                case "stop" -> {
                    o.allow("kind", "id", "at", "trainTypes", "maxTrainLengthMm");
                    String id = o.str("id");
                    DirectedLocation at = location(o, "at");
                    Set<String> types = new LinkedHashSet<>(o.strings("trainTypes", false));
                    double max = o.optNum("maxTrainLengthMm").orElse(Double.POSITIVE_INFINITY);
                    add(stops, build(o.path, before, () -> new StopPoint(id, at, types, max)));
                }
                case "speed" -> {
                    o.allow("kind", "id", "at", "speedKmh", "reference");
                    String id = o.str("id");
                    DirectedLocation at = location(o, "at");
                    double speed = o.num("speedKmh");
                    SpeedChangePoint.Reference ref = o.enumValue("reference", SpeedChangePoint.Reference.class);
                    add(speeds, build(o.path, before, () -> new SpeedChangePoint(id, at, speed, ref)));
                }
                case "report" -> {
                    o.allow("kind", "section", "boundary", "at");
                    String section = o.str("section");
                    DirectedLocation boundary = location(o, "boundary");
                    DirectedLocation at = location(o, "at");
                    add(reports, build(o.path, before, () -> new ReportPointOverride(section, boundary, at)));
                }
                case "" -> { }
                default -> problem(o.field("kind"), "unbekannte Punktart \"" + kind + "\" (erlaubt: stop, speed, report)");
            }
        }

        TurnoutAddress turnoutAddress(Obj o) {
            int before = problems.size();
            o.allow("turnout", "address");
            String turnout = o.str("turnout");
            int address = o.integer("address");
            return build(o.path, before, () -> new TurnoutAddress(turnout, address));
        }
    }

    // ============================================================================================================
    // Schreiben
    // ============================================================================================================

    /** Schreibt das Modell als JSON-Text (verlustfrei, siehe Klassenbeschreibung). */
    public static String write(TrackPlan plan) { return Json.write(tree(plan)) + "\n"; }

    /** Das Modell als JSON-Baum (Map/List/Zahl/Text/Wahrheitswert), wie {@link Json#parse} ihn liefert. */
    public static Map<String, Object> tree(TrackPlan p) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", p.formatVersion());
        if (!p.name().isEmpty()) root.put("name", p.name());
        root.put("recommendedSectionsPerBlock", p.recommendedSectionsPerBlock());
        root.put("catalog", map(p.catalog(), TrackPlanJson::catalogEntry));
        root.put("pieces", map(p.pieces(), TrackPlanJson::piece));
        root.put("connections", map(p.connections(), TrackPlanJson::connection));
        root.put("sections", map(p.sections(), TrackPlanJson::section));
        root.put("blocks", map(p.blocks(), TrackPlanJson::block));
        root.put("holdingAreas", map(p.holdingAreas(), h -> obj("id", h.id(), "blocks", h.blockIds())));
        root.put("districts", map(p.districts(), TrackPlanJson::district));
        root.put("signals", map(p.signals(), TrackPlanJson::signal));
        List<Object> points = new ArrayList<>();
        for (StopPoint s : p.stopPoints()) points.add(stopPoint(s));
        for (SpeedChangePoint s : p.speedChangePoints())
            points.add(obj("kind", "speed", "id", s.id(), "at", location(s.at()), "speedKmh", s.speedKmh(),
                    "reference", s.reference().name()));
        for (ReportPointOverride r : p.reportPointOverrides())
            points.add(obj("kind", "report", "section", r.sectionId(), "boundary", location(r.boundary()),
                    "at", location(r.at())));
        root.put("points", points);
        root.put("turnoutAddresses", map(p.turnoutAddresses(), a -> obj("turnout", a.turnoutId(), "address", a.address())));
        return root;
    }

    private interface ToJson<T> { Object apply(T t); }

    private static <T> List<Object> map(List<T> list, ToJson<T> f) {
        List<Object> out = new ArrayList<>();
        for (T t : list) out.add(f.apply(t));
        return out;
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Object catalogEntry(TrackPiece t) {
        return switch (t) {
            case TrackPiece.PlainPiece p -> obj("id", p.id(), "kind", "plain", "geometry", geometry(p.geometry()));
            case TrackPiece.TurnoutPiece p -> obj("id", p.id(), "kind", "turnout", "hand", p.hand().name(),
                    "suggestion", branches(p.suggestion()));
            case TrackPiece.CrossingPiece p -> obj("id", p.id(), "kind", "crossing", "suggestion", crossing(p.suggestion()));
            case TrackPiece.DoubleSlipPiece p -> obj("id", p.id(), "kind", "doubleSlip",
                    "suggestion", portNumbers(p.suggestion().lengthsMm()),
                    "clearanceSuggestion", portNumbers(p.clearanceSuggestion().clearancesMm()),
                    "logic", logic(p.logic()));
        };
    }

    private static Object geometry(TrackGeometry g) {
        return switch (g) {
            case TrackGeometry.Straight s -> obj("kind", "straight", "lengthMm", s.lengthMm());
            case TrackGeometry.Arc a -> obj("kind", "arc", "radiusMm", a.radiusMm(), "angleDeg", a.angleDeg());
            case TrackGeometry.Compound c -> obj("kind", "compound", "parts", map(c.parts(), TrackPlanJson::geometry));
        };
    }

    private static Object branches(TurnoutBranches b) {
        return obj("straightLengthMm", b.straightLengthMm(), "divergingLengthMm", b.divergingLengthMm(),
                "straightClearanceMm", b.straightClearanceMm(), "divergingClearanceMm", b.divergingClearanceMm());
    }

    private static Object crossing(TrackPiece.CrossingDimensions d) {
        return obj("lineALengthMm", d.lineALengthMm(), "lineBLengthMm", d.lineBLengthMm(),
                "crossingFromA1Mm", d.crossingFromA1Mm(), "crossingFromB1Mm", d.crossingFromB1Mm());
    }

    /** Anschlussname → Zahl, in Reihenfolge der Anschlüsse. */
    private static Object portNumbers(Map<Port, Double> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Port p : Port.values()) if (m.containsKey(p)) out.put(p.name(), m.get(p));
        return out;
    }

    private static Object logic(DoubleSlipLogic l) {
        Map<String, Object> routes = new LinkedHashMap<>();
        for (Port p : Port.values())
            if (l.settings().containsKey(p)) {
                DoubleSlipLogic.Setting s = l.settings().get(p);
                routes.put(p.name(), obj("sideA", s.sideA().name(), "sideB", s.sideB().name()));
            }
        return obj("hardwareVerified", l.hardwareVerified(), "routes", routes);
    }

    private static Object piece(PlacedPiece p) {
        Map<String, Object> m = obj("id", p.id(), "type", p.type());
        switch (p) {
            case PlainPlacement x -> { if (x.lengthMm().isPresent()) m.put("lengthMm", x.lengthMm().getAsDouble()); }
            case TurnoutPlacement x -> {
                m.put("turnout", x.turnoutId());
                m.put("branches", branches(x.branches()));
            }
            case CrossingPlacement x -> m.put("dimensions", crossing(x.dimensions()));
            case DoubleSlipPlacement x -> {
                m.put("turnoutA", x.turnoutIdA());
                m.put("turnoutB", x.turnoutIdB());
                m.put("routeLengthsMm", portNumbers(x.routeLengths().lengthsMm()));
                m.put("clearancesMm", portNumbers(x.clearances().clearancesMm()));
            }
        }
        if (p.lengthUnknown()) m.put("lengthUnknown", true);
        return m;
    }

    private static Object connection(Connection c) {
        return switch (c.kind()) {
            case JOINT -> obj("kind", "joint", "a", c.a().toString(), "b", c.b().toString());
            case ISOLATING -> obj("kind", "isolating", "a", c.a().toString(), "b", c.b().toString());
            case END -> obj("kind", "end", "a", c.a().toString());
        };
    }

    private static Object range(PlanRange r) {
        return r.wholeEdge() ? r.edgeId() : obj("edge", r.edgeId(), "fromMm", r.fromMm(), "toMm", r.toMm());
    }

    private static Object section(PlanSection s) {
        Map<String, Object> m = obj("id", s.id(), "contact", s.contact());
        if (s.district().isPresent()) m.put("district", s.district().getAsInt());
        m.put("responseDelayMs", s.responseDelayMs());
        m.put("releaseDelayMs", s.releaseDelayMs());
        m.put("ranges", map(s.ranges(), TrackPlanJson::range));
        return m;
    }

    private static Object location(DirectedLocation l) {
        return obj("edge", l.edgeId(), "offsetMm", l.offsetMm(), "forward", l.forward());
    }

    private static Object block(Block b) {
        Map<String, Object> m = obj("id", b.id(), "sections", b.sectionIds(), "endA", location(b.endA()));
        List<Object> dirs = new ArrayList<>();
        for (Block.Direction d : Block.Direction.values()) if (b.allows(d)) dirs.add(d.name());
        m.put("directions", dirs);
        if (!b.trainTypes().isEmpty()) m.put("trainTypes", new ArrayList<Object>(new TreeSet<>(b.trainTypes())));
        if (b.minTrainLengthMm() > 0) m.put("minTrainLengthMm", b.minTrainLengthMm());
        if (Double.isFinite(b.maxTrainLengthMm())) m.put("maxTrainLengthMm", b.maxTrainLengthMm());
        return m;
    }

    private static Object district(PlanDistrict d) {
        Map<String, Object> m = obj("id", d.id());
        if (!d.name().isEmpty()) m.put("name", d.name());
        m.put("reversingLoop", d.reversingLoop());
        if (!d.extraRanges().isEmpty()) m.put("extraRanges", map(d.extraRanges(), TrackPlanJson::range));
        return m;
    }

    private static Object signal(BlockSignal s) {
        Map<String, Object> m = obj("id", s.id(), "block", s.blockId(), "end", s.end().name());
        if (!s.addresses().isEmpty()) m.put("addresses", new ArrayList<Object>(s.addresses()));
        return m;
    }

    private static Object stopPoint(StopPoint s) {
        Map<String, Object> m = obj("kind", "stop", "id", s.id(), "at", location(s.at()));
        if (!s.trainTypes().isEmpty()) m.put("trainTypes", new ArrayList<Object>(new TreeSet<>(s.trainTypes())));
        if (Double.isFinite(s.maxTrainLengthMm())) m.put("maxTrainLengthMm", s.maxTrainLengthMm());
        return m;
    }
}
