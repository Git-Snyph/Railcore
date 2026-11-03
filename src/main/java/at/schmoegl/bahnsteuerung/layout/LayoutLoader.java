package at.schmoegl.bahnsteuerung.layout;

import at.schmoegl.bahnsteuerung.core.Contacts;
import at.schmoegl.bahnsteuerung.core.TurnoutPosition;
import at.schmoegl.bahnsteuerung.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static at.schmoegl.bahnsteuerung.util.Json.*;

/**
 * Liest einen Gleisplan aus JSON. Längen in Millimetern. Rückmelder als "17:1" (Modul:Eingang) oder Kontaktnummer.
 * Beispiel siehe {@code beispiele/demo-anlage.json}.
 */
public final class LayoutLoader {
    private LayoutLoader() {}

    public static Layout load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static Layout parse(String json) {
        Map<String, Object> root = obj(Json.parse(json));
        List<Block> blocks = new ArrayList<>();
        for (Object o : arr(root.get("blocks"))) blocks.add(block(obj(o)));
        List<Turnout> turnouts = new ArrayList<>();
        for (Object o : arr(root.get("turnouts"))) turnouts.add(turnout(obj(o)));
        List<Route> routes = new ArrayList<>();
        for (Object o : arr(root.get("routes"))) routes.add(route(obj(o)));
        List<Signal> signals = new ArrayList<>();
        for (Object o : arr(root.get("signals"))) signals.add(signal(obj(o)));
        List<Light> lights = new ArrayList<>();
        for (Object o : arr(root.get("lights"))) lights.add(light(obj(o)));
        List<Station> stations = new ArrayList<>();
        for (Object o : arr(root.get("stations"))) {
            Map<String, Object> m = obj(o);
            stations.add(new Station(reqStr(m, "id"), str(m, "name", null), strings(m.get("blocks"))));
        }
        List<StagingYard> yards = new ArrayList<>();
        for (Object o : arr(root.get("stagingYards"))) {
            Map<String, Object> m = obj(o);
            List<StagingYard.Track> tracks = new ArrayList<>();
            for (Object t : arr(m.get("tracks"))) {
                Map<String, Object> tm = obj(t);
                tracks.add(new StagingYard.Track(reqStr(tm, "id"), strings(tm.get("blocks")),
                        BlockEnd.valueOf(str(tm, "exitEnd", "B").toUpperCase())));
            }
            yards.add(new StagingYard(reqStr(m, "id"), str(m, "name", null), tracks));
        }
        List<Turntable> turntables = new ArrayList<>();
        for (Object o : arr(root.get("turntables"))) {
            Map<String, Object> m = obj(o);
            List<Turntable.Exit> exits = new ArrayList<>();
            for (Object e : arr(m.get("exits"))) {
                Map<String, Object> em = obj(e);
                exits.add(new Turntable.Exit((int) reqNum(em, "position"), BlockSide.parse(reqStr(em, "block"))));
            }
            turntables.add(new Turntable(reqStr(m, "id"), str(m, "name", null), reqStr(m, "bridgeBlock"), (int) num(m, "baseAddress", 0), exits));
        }
        return new Layout(str(root, "name", "Anlage"), num(root, "scale", 87), blocks, turnouts, routes, signals, lights,
                stations, yards, turntables, timetable(obj(root.get("timetable"))), hardware(obj(root.get("hardware"))));
    }

    private static Block block(Map<String, Object> m) {
        List<Sensor> sensors = new ArrayList<>();
        for (Object o : arr(m.get("sensors"))) {
            Map<String, Object> s = obj(o);
            double from = num(s, "from", num(s, "at", 0));
            double to = num(s, "to", from);
            sensors.add(new Sensor(contactOrNull(s, "contact"), from, to));
        }
        return new Block(reqStr(m, "id"), str(m, "name", null),
                Block.Type.valueOf(str(m, "type", "NORMAL").toUpperCase()), num(m, "length", 0), sensors,
                num(m, "stopMargin", 30), Block.Directions.valueOf(str(m, "directions", "BOTH").toUpperCase()),
                num(m, "maxSpeedKmh", 0));
    }

    private static Turnout turnout(Map<String, Object> m) {
        return new Turnout(reqStr(m, "id"), str(m, "name", null), (int) reqNum(m, "address"), bool(m, "inverted", false),
                contactOrNull(m, "occupancyContact"), contactOrNull(m, "feedbackStraight"), contactOrNull(m, "feedbackDiverging"));
    }

    private static Route route(Map<String, Object> m) {
        List<TurnoutSetting> ts = new ArrayList<>();
        for (Map.Entry<String, Object> e : obj(m.get("turnouts")).entrySet())
            ts.add(new TurnoutSetting(e.getKey(), position(e.getValue().toString())));
        return new Route(reqStr(m, "id"), BlockSide.parse(reqStr(m, "from")), BlockSide.parse(reqStr(m, "to")),
                num(m, "length", 0), ts, num(m, "maxSpeedKmh", 0), bool(m, "bidirectional", true));
    }

    private static Signal signal(Map<String, Object> m) {
        Map<Signal.Aspect, List<AccessoryOutput>> aspects = new EnumMap<>(Signal.Aspect.class);
        for (Map.Entry<String, Object> e : obj(m.get("aspects")).entrySet()) {
            List<AccessoryOutput> outs = new ArrayList<>();
            for (Object o : arr(e.getValue())) outs.add(output(o.toString()));
            aspects.put(Signal.Aspect.valueOf(e.getKey().toUpperCase()), outs);
        }
        return new Signal(reqStr(m, "id"), str(m, "name", null), BlockSide.parse(reqStr(m, "at")), aspects);
    }

    private static Light light(Map<String, Object> m) {
        return new Light(reqStr(m, "id"), str(m, "name", null), str(m, "group", null), output(reqStr(m, "on")),
                m.containsKey("off") ? output(reqStr(m, "off")) : null, time(m.get("onAt")), time(m.get("offAt")));
    }

    private static Timetable timetable(Map<String, Object> m) {
        if (m.isEmpty()) return Timetable.empty();
        List<Timetable.Entry> entries = new ArrayList<>();
        for (Object o : arr(m.get("entries"))) {
            Map<String, Object> e = obj(o);
            entries.add(new Timetable.Entry(reqStr(e, "train"), time(e.get("departure")), reqStr(e, "from"), reqStr(e, "to"),
                    strings(e.get("via")), (int) num(e, "repeatMinutes", 0), (int) num(e, "dwellSeconds", 40)));
        }
        LocalTime start = time(m.get("startTime"));
        return new Timetable(num(m, "timeFactor", 1), start == null ? LocalTime.of(6, 0) : start, entries);
    }

    private static Hardware hardware(Map<String, Object> m) {
        if (m.isEmpty()) return Hardware.empty();
        List<Hardware.Device> devices = new ArrayList<>();
        for (Object o : arr(m.get("devices"))) {
            Map<String, Object> d = obj(o);
            Map<String, String> props = new LinkedHashMap<>();
            obj(d.get("properties")).forEach((k, v) -> props.put(k, String.valueOf(v)));
            devices.add(new Hardware.Device(reqStr(d, "id"), str(d, "name", null),
                    Hardware.DeviceType.valueOf(str(d, "type", "OTHER").toUpperCase()), str(d, "bus", null),
                    (int) num(d, "firstAddress", 0), (int) num(d, "addressCount", 0), props));
        }
        List<Hardware.Cable> cables = new ArrayList<>();
        for (Object o : arr(m.get("cables"))) {
            Map<String, Object> c = obj(o);
            cables.add(new Hardware.Cable(reqStr(c, "id"), reqStr(c, "from"), reqStr(c, "to"), str(c, "kind", null),
                    str(c, "color", null), str(c, "note", null)));
        }
        return new Hardware(devices, cables);
    }

    /** "12" = Adresse 12 gerade, "12:diverging" bzw. "12:abzweig" = abzweigend. */
    static AccessoryOutput output(String s) {
        int i = s.indexOf(':');
        if (i < 0) return new AccessoryOutput(Integer.parseInt(s.trim()), TurnoutPosition.STRAIGHT);
        return new AccessoryOutput(Integer.parseInt(s.substring(0, i).trim()), position(s.substring(i + 1)));
    }

    static TurnoutPosition position(String s) {
        return switch (s.trim().toLowerCase()) {
            case "straight", "gerade", "g", "1" -> TurnoutPosition.STRAIGHT;
            case "diverging", "abzweig", "abzweigend", "a", "2" -> TurnoutPosition.DIVERGING;
            default -> throw new IllegalArgumentException("Weichenlage unbekannt: " + s + " (gerade/abzweig)");
        };
    }

    private static Integer contactOrNull(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        String s = v instanceof Double d ? String.valueOf(d.intValue()) : v.toString();
        return Contacts.parse(s);
    }

    private static List<String> strings(Object o) {
        List<String> l = new ArrayList<>();
        for (Object x : arr(o)) l.add(x.toString());
        return l;
    }

    private static LocalTime time(Object o) { return o == null ? null : LocalTime.parse(o.toString()); }
}
