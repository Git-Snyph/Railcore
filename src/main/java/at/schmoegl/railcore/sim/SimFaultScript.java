package at.schmoegl.railcore.sim;

import at.schmoegl.railcore.core.Contacts;
import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.DistrictPower;
import at.schmoegl.railcore.core.TrackPower;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Störungsskript für den Simulator (Lastenheft 10, „Störungen auf Knopfdruck oder per Skript“). Eine Zeile je
 * Störung, Zeit in ms ab dem Start des Skripts ({@link #schedule}); {@code #} leitet einen Kommentar ein.
 * Deterministisch: alle Störungen laufen über die Simulationsuhr.
 * <pre>
 * 1000  verspaetung     3500            # Verbindung verspätet: 3,5 s keine Meldung der Zentrale
 * 6000  verbindung-weg  6000            # Verbindung verloren: 6 s nichts in beide Richtungen
 * 0     paketverlust    0.2  [seed]     # Anteil verlorener Pakete (0 = aus)
 * 2000  bezirk          2 kurzschluss   # kurzschluss | ueberlast | aus | ein
 * 2000  gleisspannung   aus             # ein | aus | kurzschluss
 * 500   flackern        2:3 10 40       # Melder, Zahl der Wechsel, Abstand in ms
 * 3000  versorgung-aus  2:1,2:2         # einzelne Melder; „modul:3“ = ganzer CAN-Bereich (8 Melder)
 * 9000  versorgung-ein  modul:3
 * 4000  fremdsteuerung  3 40 vor        # Adresse, Fahrstufe, vor | rueck
 * 7000  haenger         400             # Steuerung rechnet 400 ms nicht (Takt überschritten)
 * 8000  interner-fehler Testfehler      # Ausnahme in der Steuerung
 * </pre>
 * Unbekannte Befehle und falsche Werte werden beim Einlesen mit Zeilennummer abgewiesen.
 */
public final class SimFaultScript {

    /** Eine eingelesene Störung. */
    public record Step(long atMillis, String command, List<String> args, int line) {}

    private final List<Step> steps;

    private SimFaultScript(List<Step> steps) { this.steps = List.copyOf(steps); }

    public List<Step> steps() { return steps; }

    /** Skript einlesen und prüfen. */
    public static SimFaultScript parse(String text) {
        List<Step> out = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            int hash = l.indexOf('#');
            if (hash >= 0) l = l.substring(0, hash);
            l = l.strip();
            if (l.isEmpty()) continue;
            String[] t = l.split("\\s+");
            int no = i + 1;
            if (t.length < 2) throw error(no, "Zeit und Störung erwartet");
            long at = parseLong(t[0], no, "Zeit");
            if (at < 0) throw error(no, "Zeit darf nicht negativ sein");
            Step s = new Step(at, t[1].toLowerCase(Locale.ROOT), List.of(t).subList(2, t.length), no);
            validate(s);
            out.add(s);
        }
        return new SimFaultScript(out);
    }

    /** Alle Störungen in der Welt einplanen, Zeit ab jetzt. */
    public void schedule(SimWorld world, SimCommandStation station) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(station);
        long start = world.now();
        for (Step s : steps) world.at(start + s.atMillis() * 1_000_000L, () -> apply(s, world, station));
    }

    private static void validate(Step s) {
        // Probeweise ohne Welt anwenden: prüft Befehl und Werte
        apply(s, null, null);
    }

    private static void apply(Step s, SimWorld world, SimCommandStation st) {
        List<String> a = s.args();
        int no = s.line();
        boolean run = st != null;
        switch (s.command()) {
            case "verspaetung" -> {
                long ms = millis(a, 0, no);
                if (run) st.delayReplies(ms);
            }
            case "verbindung-weg" -> {
                long ms = millis(a, 0, no);
                if (run) st.loseLink(ms);
            }
            case "paketverlust" -> {
                need(a, 1, no);
                double f = parseDouble(a.get(0), no);
                if (!(f >= 0 && f <= 1)) throw error(no, "Anteil Paketverlust muss zwischen 0 und 1 liegen");
                long seed = a.size() > 1 ? parseLong(a.get(1), no, "Seed") : 1;
                if (run) st.setPacketLoss(f, seed);
            }
            case "bezirk" -> {
                need(a, 2, no);
                int d = (int) parseLong(a.get(0), no, "Bezirk");
                DistrictPower p = switch (a.get(1).toLowerCase(Locale.ROOT)) {
                    case "kurzschluss" -> DistrictPower.SHORT_CIRCUIT;
                    case "ueberlast" -> DistrictPower.OVERLOAD;
                    case "aus" -> DistrictPower.OFF;
                    case "ein" -> DistrictPower.ON;
                    default -> throw error(no, "Bezirk: kurzschluss, ueberlast, aus oder ein erwartet");
                };
                if (run) st.districtPower(d, p);
            }
            case "gleisspannung" -> {
                need(a, 1, no);
                TrackPower p = switch (a.get(0).toLowerCase(Locale.ROOT)) {
                    case "ein" -> TrackPower.ON;
                    case "aus" -> TrackPower.OFF;
                    case "kurzschluss" -> TrackPower.SHORT_CIRCUIT;
                    default -> throw error(no, "Gleisspannung: ein, aus oder kurzschluss erwartet");
                };
                if (run) st.reportTrackPower(p);
            }
            case "flackern" -> {
                need(a, 3, no);
                int c = contact(a.get(0), no);
                int toggles = (int) parseLong(a.get(1), no, "Zahl der Wechsel");
                long period = parseLong(a.get(2), no, "Abstand");
                if (toggles < 1 || period < 1) throw error(no, "Zahl der Wechsel und Abstand müssen positiv sein");
                if (run) st.flicker(c, toggles, period);
            }
            case "versorgung-aus", "versorgung-ein" -> {
                need(a, 1, no);
                List<Integer> cs = contacts(a.get(0), no);
                if (run) {
                    if (s.command().equals("versorgung-aus")) st.cutDetectorSupply(cs);
                    else st.restoreDetectorSupply(cs);
                }
            }
            case "fremdsteuerung" -> {
                need(a, 3, no);
                int addr = (int) parseLong(a.get(0), no, "Adresse");
                int step = (int) parseLong(a.get(1), no, "Fahrstufe");
                Direction d = switch (a.get(2).toLowerCase(Locale.ROOT)) {
                    case "vor" -> Direction.FORWARD;
                    case "rueck" -> Direction.REVERSE;
                    default -> throw error(no, "Richtung: vor oder rueck erwartet");
                };
                if (step < 0 || step > 126) throw error(no, "Fahrstufe außerhalb 0–126");
                if (run) st.foreignDrive(addr, step, d);
            }
            case "haenger" -> {
                long ms = millis(a, 0, no);
                if (run) world.stallController(ms);
            }
            case "interner-fehler" -> {
                String what = a.isEmpty() ? "Simulierter interner Fehler" : String.join(" ", a);
                if (run) world.injectInternalError(what);
            }
            default -> throw error(no, "unbekannte Störung „" + s.command() + "“");
        }
    }

    private static long millis(List<String> a, int i, int no) {
        need(a, i + 1, no);
        long ms = parseLong(a.get(i), no, "Dauer");
        if (ms < 1) throw error(no, "Dauer muss positiv sein");
        return ms;
    }

    private static int contact(String s, int no) {
        try {
            return Contacts.parse(s);
        } catch (RuntimeException e) {
            throw error(no, "Melder „" + s + "“ unlesbar (Nummer oder Modul:Eingang)");
        }
    }

    private static List<Integer> contacts(String s, int no) {
        List<Integer> out = new ArrayList<>();
        if (s.toLowerCase(Locale.ROOT).startsWith("modul:")) {
            int m = (int) parseLong(s.substring(6), no, "Modul");
            if (m < 1) throw error(no, "Modul muss mindestens 1 sein");
            for (int i = 1; i <= 8; i++) out.add(Contacts.of(m, i));
            return out;
        }
        for (String part : s.split(",")) out.add(contact(part, no));
        return out;
    }

    private static void need(List<String> a, int n, int no) {
        if (a.size() < n) throw error(no, n + " Werte erwartet");
    }

    private static long parseLong(String s, int no, String what) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw error(no, what + " „" + s + "“ ist keine ganze Zahl");
        }
    }

    private static double parseDouble(String s, int no) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw error(no, "„" + s + "“ ist keine Zahl");
        }
    }

    private static IllegalArgumentException error(int line, String msg) {
        return new IllegalArgumentException("Störungsskript Zeile " + line + ": " + msg);
    }
}
