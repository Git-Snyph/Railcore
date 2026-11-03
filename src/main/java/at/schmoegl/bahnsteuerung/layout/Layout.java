package at.schmoegl.bahnsteuerung.layout;

import at.schmoegl.bahnsteuerung.core.Contacts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Der vollständige Gleisplan. Unveränderlich; Änderungen erzeugen einen neuen Gleisplan. */
public final class Layout {

    /** Prüfergebnis: ERROR verhindert den Betrieb, WARNING ist ein Hinweis. */
    public record Issue(Level level, String message) {
        public enum Level { ERROR, WARNING }

        @Override public String toString() { return (level == Level.ERROR ? "FEHLER: " : "Hinweis: ") + message; }
    }

    public static final int RECOMMENDED_SENSORS_PER_BLOCK = 3;

    private final String name;
    private final double scale;
    private final Map<String, Block> blocks;
    private final Map<String, Turnout> turnouts;
    private final Map<String, Route> routes;
    private final Map<String, Signal> signals;
    private final Map<String, Light> lights;
    private final Map<String, Station> stations;
    private final Map<String, StagingYard> stagingYards;
    private final Map<String, Turntable> turntables;
    private final Timetable timetable;
    private final Hardware hardware;

    // Abgeleitete Indizes
    private final Map<Integer, String> blockByContact = new HashMap<>();
    private final Map<Integer, String> turnoutByContact = new HashMap<>();
    private final Map<BlockSide, List<Route>> routesFrom = new HashMap<>();

    public Layout(String name, double scale, Collection<Block> blocks, Collection<Turnout> turnouts,
                  Collection<Route> routes, Collection<Signal> signals, Collection<Light> lights,
                  Collection<Station> stations, Collection<StagingYard> stagingYards,
                  Collection<Turntable> turntables, Timetable timetable, Hardware hardware) {
        this.name = name;
        this.scale = scale;
        this.blocks = index(blocks, Block::id, "Block");
        this.turnouts = index(turnouts, Turnout::id, "Weiche");
        this.routes = index(routes, Route::id, "Fahrstraße");
        this.signals = index(signals, Signal::id, "Signal");
        this.lights = index(lights, Light::id, "Licht");
        this.stations = index(stations, Station::id, "Bahnhof");
        this.stagingYards = index(stagingYards, StagingYard::id, "Schattenbahnhof");
        this.turntables = index(turntables, Turntable::id, "Drehscheibe");
        this.timetable = timetable == null ? Timetable.empty() : timetable;
        this.hardware = hardware == null ? Hardware.empty() : hardware;
        for (Block b : this.blocks.values()) for (Sensor s : b.sensors()) blockByContact.put(s.contact(), b.id());
        for (Turnout t : this.turnouts.values()) if (t.occupancyContact() != null) turnoutByContact.put(t.occupancyContact(), t.id());
        for (Route r : this.routes.values()) {
            routesFrom.computeIfAbsent(r.from(), k -> new ArrayList<>()).add(r);
            if (r.bidirectional()) routesFrom.computeIfAbsent(r.to(), k -> new ArrayList<>()).add(r);
        }
    }

    private static <T> Map<String, T> index(Collection<T> items, java.util.function.Function<T, String> id, String what) {
        Map<String, T> m = new LinkedHashMap<>();
        for (T t : items) if (m.put(id.apply(t), t) != null) throw new IllegalArgumentException(what + " doppelt: " + id.apply(t));
        return m;
    }

    public String name() { return name; }

    /** Maßstab, z. B. 87 für H0. */
    public double scale() { return scale; }

    public Collection<Block> blocks() { return blocks.values(); }
    public Collection<Turnout> turnouts() { return turnouts.values(); }
    public Collection<Route> routes() { return routes.values(); }
    public Collection<Signal> signals() { return signals.values(); }
    public Collection<Light> lights() { return lights.values(); }
    public Collection<Station> stations() { return stations.values(); }
    public Collection<StagingYard> stagingYards() { return stagingYards.values(); }
    public Collection<Turntable> turntables() { return turntables.values(); }
    public Timetable timetable() { return timetable; }
    public Hardware hardware() { return hardware; }

    public Block block(String id) {
        Block b = blocks.get(id);
        if (b == null) throw new IllegalArgumentException("Unbekannter Block: " + id);
        return b;
    }

    public Optional<Block> findBlock(String id) { return Optional.ofNullable(blocks.get(id)); }

    public Turnout turnout(String id) {
        Turnout t = turnouts.get(id);
        if (t == null) throw new IllegalArgumentException("Unbekannte Weiche: " + id);
        return t;
    }

    public Optional<Route> route(String id) { return Optional.ofNullable(routes.get(id)); }

    /** Alle Fahrstraßen, die an diesem Blockende beginnen (inkl. in Gegenrichtung befahrbarer). */
    public List<Route> routesFrom(BlockSide side) { return routesFrom.getOrDefault(side, List.of()); }

    public Optional<String> blockForContact(int contact) { return Optional.ofNullable(blockByContact.get(contact)); }

    public Optional<String> turnoutForContact(int contact) { return Optional.ofNullable(turnoutByContact.get(contact)); }

    /** Vorbild-km/h → Modell-mm/s. */
    public double kmhToMmPerSec(double kmh) { return kmh / 3.6 * 1000.0 / scale; }

    /** Modell-mm/s → Vorbild-km/h. */
    public double mmPerSecToKmh(double mms) { return mms * scale / 1000.0 * 3.6; }

    // ---------------------------------------------------------------- Prüfung

    public List<Issue> validate() {
        List<Issue> out = new ArrayList<>();
        Map<Integer, String> contactUse = new HashMap<>();
        for (Block b : blocks.values()) {
            if (b.sensors().isEmpty()) out.add(err("Block " + b.id() + " hat keinen Rückmelder (mindestens 1 ist Pflicht)"));
            else if (b.sensors().size() < RECOMMENDED_SENSORS_PER_BLOCK)
                out.add(warn("Block " + b.id() + " hat " + b.sensors().size() + " Rückmelder, empfohlen sind " + RECOMMENDED_SENSORS_PER_BLOCK));
            if (b.lengthMm() <= 0) out.add(warn("Block " + b.id() + ": Länge unbekannt – bitte vermessen"));
            for (Sensor s : b.sensors()) {
                if (b.lengthMm() > 0 && (s.fromMm() < 0 || s.toMm() > b.lengthMm() + 0.5))
                    out.add(err("Block " + b.id() + ": Rückmelder " + Contacts.format(s.contact()) + " liegt außerhalb des Blocks"));
                String prev = contactUse.put(s.contact(), "Block " + b.id());
                if (prev != null && !prev.equals("Block " + b.id()))
                    out.add(err("Rückmelder " + Contacts.format(s.contact()) + " doppelt verwendet: " + prev + " und Block " + b.id()));
            }
        }
        Map<Integer, String> addrUse = new HashMap<>();
        for (Turnout t : turnouts.values()) {
            String prev = addrUse.put(t.address(), "Weiche " + t.id());
            if (prev != null) out.add(err("Zubehöradresse " + t.address() + " doppelt: " + prev + " und Weiche " + t.id()));
            if (t.occupancyContact() != null) {
                String p2 = contactUse.put(t.occupancyContact(), "Weiche " + t.id());
                if (p2 != null) out.add(err("Rückmelder " + Contacts.format(t.occupancyContact()) + " doppelt: " + p2 + " und Weiche " + t.id()));
            }
        }
        for (Route r : routes.values()) {
            for (BlockSide s : List.of(r.from(), r.to()))
                if (!blocks.containsKey(s.blockId())) out.add(err("Fahrstraße " + r.id() + ": unbekannter Block " + s.blockId()));
            for (TurnoutSetting ts : r.turnouts())
                if (!turnouts.containsKey(ts.turnoutId())) out.add(err("Fahrstraße " + r.id() + ": unbekannte Weiche " + ts.turnoutId()));
        }
        for (Signal s : signals.values())
            if (!blocks.containsKey(s.at().blockId())) out.add(err("Signal " + s.id() + ": unbekannter Block " + s.at().blockId()));
        for (Station st : stations.values())
            for (String bid : st.blockIds()) if (!blocks.containsKey(bid)) out.add(err("Bahnhof " + st.id() + ": unbekannter Block " + bid));
        for (StagingYard y : stagingYards.values())
            for (StagingYard.Track t : y.tracks())
                for (String bid : t.blockIds()) if (!blocks.containsKey(bid)) out.add(err("Schattenbahnhof " + y.id() + "/" + t.id() + ": unbekannter Block " + bid));
        return out;
    }

    private static Issue err(String m) { return new Issue(Issue.Level.ERROR, m); }

    private static Issue warn(String m) { return new Issue(Issue.Level.WARNING, m); }
}
