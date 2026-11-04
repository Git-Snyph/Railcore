package at.schmoegl.railcore.z21.check;

import at.schmoegl.railcore.core.Direction;
import at.schmoegl.railcore.core.SpeedSteps;
import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.core.TurnoutPosition;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.diag.Severity;
import at.schmoegl.railcore.util.TimeSource;
import at.schmoegl.railcore.z21.Z21Message;
import at.schmoegl.railcore.z21.Z21Protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Z21-Prüfprogramm: erkennt die Fähigkeiten der Zentrale und klärt die offenen Hardware-Fragen.
 *
 * <p><b>Stufe 1 (passiv, Standard)</b> sendet ausschließlich Abfragen und Abos ({@link CommandClass#PASSIVE}):
 * Seriennummer, HW-Info, Firmware, Freischaltcode, Status, Systemzustand, Broadcast-Flags, R-Bus, CAN-Melder,
 * Lok- und Weicheninfo; danach werden Meldungen eine einstellbare Zeit mitgeschnitten.
 *
 * <p><b>Stufe 2 (aktiv)</b> – Gleisspannung, Weiche stellen, POM-Zubehör lesen, Fahren auf der
 * Stellvertreter-Adresse – nur nach ausdrücklicher Einzelbestätigung („JA“). Jede andere Eingabe,
 * Zeitüberschreitung, fehlende Rückmeldung oder Verbindungsstille bricht die Stufe ab; dabei werden ein
 * aktiver Weichenausgang abgeschaltet und an die Stellvertreter-Adresse sofort ein Nothalt gesendet.
 *
 * <p>Ein Ergebnis ohne Rückmeldung ist nie „bestätigt“, sondern „unbekannt“. Zeit nur über {@link TimeSource}.
 * Einfädig: nur der Shutdown-Hook ruft zusätzlich {@link #emergencyShutdown()} auf.
 */
public final class Z21Check {

    static final String SRC = "z21-pruefung";
    private static final long MS = 1_000_000L;
    private static final long KEEPALIVE_NANOS = 1000 * MS;
    private static final long POLL_MS = 50;

    /** Anforderung aller Broadcast-Flags, deren Unterstützung erkannt werden soll. */
    static final int REQUESTED_FLAGS = Z21Protocol.BC_DRIVING_SWITCHING | Z21Protocol.BC_RBUS | Z21Protocol.BC_RAILCOM
            | Z21Protocol.BC_SYSTEM_STATE | Z21Protocol.BC_ALL_LOCOS | Z21Protocol.BC_RAILCOM_ALL | Z21Protocol.BC_CAN_DETECTOR;

    private final CheckConfig cfg;
    private final Z21Transport link;
    private final Operator op;
    private final Journal journal;
    private final TimeSource time;

    private final List<Z21Message> received = new ArrayList<>();
    private final List<Finding> findings = new ArrayList<>();
    private final List<Journal.Entry> protocol = new ArrayList<>();

    private long lastReceiveNanos;
    private long lastKeepAliveNanos;
    private boolean anyReceived;
    private boolean activeStage;
    private boolean linkBroken;
    private boolean powerSwitchedOnByUs;

    // Sicherheitszustand: wird auch vom Shutdown-Hook gelesen
    private final Object safetyLock = new Object();
    private boolean driveStarted;
    private ActiveOutput turnoutOutput;
    private volatile boolean finished;

    private record ActiveOutput(int address, TurnoutPosition position) {}

    /** Nur von {@link #confirm} erzeugt: Nachweis der Bedienerbestätigung für genau einen Schritt. */
    private record Confirmation(String step) {}

    private static final class LinkLost extends RuntimeException {
        LinkLost(String m) { super(m, null, false, false); }
    }

    private static final class Aborted extends RuntimeException {
        Aborted(String m) { super(m, null, false, false); }
    }

    // ---------------------------------------------------------------- Ergebnisse der passiven Stufe
    private Z21Message.SerialNumber serial;
    private Z21Message.HwInfo hwInfo;
    private Z21Message.Code code;
    private Z21Message.FirmwareVersion xVersion;
    private Z21Message xFirmware;
    private Z21Message.PowerState status;
    private Z21Message.SystemState systemState;
    private Z21Message.BroadcastFlags flags;
    private final Z21Message.RBusFeedback[] rbus = new Z21Message.RBusFeedback[2];
    private Z21Message.Loco proxyInfo;
    private Z21Message.Turnout turnoutInfo;
    private long maxSilenceNanos;
    private final List<Long> responseTimesMs = new ArrayList<>();

    public Z21Check(CheckConfig cfg, Z21Transport link, Operator op, Journal journal, TimeSource time) {
        this.cfg = cfg;
        this.link = link;
        this.op = op;
        this.journal = journal;
        this.time = time;
        journal.addListener(e -> {
            if (e.source().equals(SRC) && e.severity().ordinal() >= Severity.INFO.ordinal()) {
                synchronized (protocol) { protocol.add(e); }
            }
        });
    }

    /** Führt die Prüfung aus. Wirft keine Ausnahme wegen der Zentrale; Störungen stehen im Bericht. */
    public CheckReport run() {
        journal.info(SRC, "START", "Prüfung startet: " + cfg.host() + ":" + cfg.port() + ", Stufe " + cfg.mode().label
                + ", Beobachtung " + cfg.observeSeconds() + " s");
        lastReceiveNanos = time.nanoTime();
        lastKeepAliveNanos = lastReceiveNanos;
        try {
            runPassive();
            if (cfg.mode() == CheckConfig.Mode.ACTIVE) {
                if (linkBroken) add("Aktive Prüfungen", Outcome.NOT_TESTED, "Verbindung gestört – im Zweifel halt", false);
                else runActive();
            } else {
                add("Aktive Prüfungen", Outcome.NOT_TESTED, "Stufe passiv – keine Eingriffe", false);
            }
        } finally {
            safeStop("Prüfungsende");
            try {
                transmit(Z21Protocol.logoff(), "Abmelden");
            } catch (LinkLost ignored) {
                // bereits protokolliert
            }
            finished = true;
            journal.info(SRC, "ENDE", "Prüfung beendet");
        }
        List<Journal.Entry> p;
        synchronized (protocol) { p = List.copyOf(protocol); }
        return new CheckReport(cfg, List.copyOf(findings), p);
    }

    /** Für den Shutdown-Hook (z. B. Strg+C): sichert Ausgang und Stellvertreter-Adresse, falls nötig. */
    public void emergencyShutdown() {
        if (!finished) safeStop("Programm wird beendet");
    }

    // ================================================================ Stufe 1: passiv

    private void runPassive() {
        try {
            serial = request("Seriennummer", Z21Protocol.getSerialNumber(), Z21Message.SerialNumber.class, m -> true);
            hwInfo = request("Hardware-Info", Z21Protocol.getHwInfo(), Z21Message.HwInfo.class, m -> true);
            code = request("Freischaltcode", Z21Protocol.getCode(), Z21Message.Code.class, m -> true);
            xVersion = request("X-Bus-Version", Z21Protocol.getVersion(), Z21Message.FirmwareVersion.class, m -> true);
            xFirmware = request("Firmware (X-Bus)", Z21Protocol.getFirmwareVersion(), Z21Message.class,
                    m -> m instanceof Z21Message.XFirmware || m instanceof Z21Message.UnknownCommand);
            status = request("Status", Z21Protocol.getStatus(), Z21Message.PowerState.class, m -> true);
            systemState = request("Systemzustand", Z21Protocol.systemStateGetData(), Z21Message.SystemState.class, m -> true);
            sendPassive(Z21Protocol.setBroadcastFlags(REQUESTED_FLAGS),
                    "Broadcast-Flags setzen 0x" + String.format("%08X", REQUESTED_FLAGS));
            flags = request("Broadcast-Flags lesen", Z21Protocol.getBroadcastFlags(), Z21Message.BroadcastFlags.class, m -> true);
            for (int g = 0; g < 2; g++) {
                int group = g;
                rbus[g] = request("R-Bus Gruppe " + g, Z21Protocol.rmBusGetData(g), Z21Message.RBusFeedback.class,
                        m -> m.group() == group);
            }
            sendPassive(Z21Protocol.canDetectorRequest(Z21Protocol.CAN_ALL), "CAN-Melder abfragen (alle)");
            pumpFor(cfg.replyTimeoutMs());
            int proxy = cfg.proxyAddress();
            proxyInfo = request("Lokinfo Stellvertreter-Adresse " + proxy, Z21Protocol.getLocoInfo(proxy),
                    Z21Message.Loco.class, m -> m.state().address() == proxy);
            if (cfg.turnoutAddress() > 0) {
                int t = cfg.turnoutAddress();
                turnoutInfo = request("Weicheninfo Adresse " + t, Z21Protocol.getTurnoutInfo(t), Z21Message.Turnout.class,
                        m -> m.address() == t);
            }
            observe();
        } catch (LinkLost e) {
            linkBroken = true;
            journal.error(SRC, "VERBINDUNG_VERLOREN", e.getMessage());
            add("Verbindung", Outcome.UNKNOWN, "Abbruch der passiven Stufe: " + e.getMessage(), false);
        }
        evaluatePassive();
    }

    private void observe() {
        if (cfg.observeSeconds() > 0) {
            op.info("Beobachtung läuft " + cfg.observeSeconds() + " s. Jetzt Melder belegen, Loks mit RailCom auf "
                    + "überwachte Abschnitte stellen oder (nur wenn auf Adresse " + cfg.proxyAddress()
                    + " sicher kein Decoder ist) die Stellvertreter-Adresse an der WLAN-Maus aufrufen. "
                    + "Eingabe + Enter beendet die Beobachtung vorzeitig.");
            op.discardPending();
            journal.info(SRC, "BEOBACHTUNG", "Mitschnitt " + cfg.observeSeconds() + " s");
            long end = time.nanoTime() + cfg.observeSeconds() * 1000L * MS;
            // Geschlossene Eingabe (keine Konsole) ist keine Bedienereingabe: Beobachtung läuft die volle Zeit.
            boolean inputClosed = op.isInputClosed();
            if (inputClosed) journal.info(SRC, "BEOBACHTUNG", "Eingabe geschlossen – Beobachtung läuft die volle Zeit");
            while (time.nanoTime() < end) {
                if (!inputClosed && op.poll() != null) {
                    journal.info(SRC, "BEDIENEREINGABE", "Beobachtung vorzeitig beendet");
                    break;
                }
                pump(Math.min(POLL_MS, Math.max(1, (end - time.nanoTime()) / MS)));
                maxSilenceNanos = Math.max(maxSilenceNanos, time.nanoTime() - lastReceiveNanos);
            }
        }
    }

    private void evaluatePassive() {
        add("Verbindung", anyReceived ? Outcome.CONFIRMED : Outcome.UNKNOWN,
                anyReceived ? "Zentrale antwortet" : "keine einzige Meldung der Zentrale empfangen", false);
        if (maxSilenceNanos > (long) cfg.linkTimeoutMs() * MS)
            add("Verbindung während der Beobachtung", Outcome.NEGATIVE,
                    "längste Stille " + maxSilenceNanos / MS + " ms (Grenze " + cfg.linkTimeoutMs() + " ms)", false);

        if (responseTimesMs.isEmpty()) {
            add("Antwortzeiten", Outcome.UNKNOWN, "keine Antwort gemessen", false);
        } else {
            long min = responseTimesMs.stream().mapToLong(Long::longValue).min().orElse(0);
            long max = responseTimesMs.stream().mapToLong(Long::longValue).max().orElse(0);
            double avg = responseTimesMs.stream().mapToLong(Long::longValue).average().orElse(0);
            add("Antwortzeiten", Outcome.CONFIRMED, String.format("%d Antworten: min %d ms, Mittel %.0f ms, max %d ms",
                    responseTimesMs.size(), min, avg, max), false);
        }
        add("Seriennummer", serial != null ? Outcome.CONFIRMED : Outcome.UNKNOWN,
                serial != null ? String.valueOf(serial.serial()) : "keine Antwort", false);
        if (hwInfo != null) {
            add("Hardware-Typ", Outcome.CONFIRMED, String.format("0x%08X = %s", hwInfo.hardwareType(),
                    hardwareName(hwInfo.hardwareType())), !KNOWN_HW.containsKey(hwInfo.hardwareType()));
            long fw = hwInfo.bcdFirmware();
            add("Firmware (HW-Info)", Outcome.CONFIRMED, Z21Protocol.formatBcdVersion(fw), false);
            // Erwartung laut Lastenheft Kap. 8 – nur Hinweis; maßgeblich sind die beobachteten Meldungen
            add("Firmware-Erwartung", Outcome.CONFIRMED, "CAN-Melder/RailCom (ab 1.30): " + (fw >= 0x0130 ? "erwartet" : "nicht erwartet")
                    + ", F29–F31 (ab 1.42): " + (fw >= 0x0142 ? "erwartet" : "nicht erwartet")
                    + " – Fähigkeiten werden nur aus Antworten abgeleitet, nicht aus der Version", false);
        } else {
            add("Hardware-Typ", Outcome.UNKNOWN, "keine Antwort auf LAN_GET_HWINFO", false);
            add("Firmware (HW-Info)", Outcome.UNKNOWN, "keine Antwort auf LAN_GET_HWINFO", false);
        }
        switch (xFirmware) {
            case Z21Message.XFirmware f -> add("Firmware (X-Bus)", Outcome.CONFIRMED, Z21Protocol.formatBcdVersion(f.bcdVersion()), false);
            case Z21Message.UnknownCommand _ -> add("Firmware (X-Bus)", Outcome.NEGATIVE, "Befehl unbekannt (ältere Firmware)", false);
            case null, default -> add("Firmware (X-Bus)", Outcome.UNKNOWN, "keine Antwort", false);
        }
        add("X-Bus-Version / Zentralenkennung", xVersion != null ? Outcome.CONFIRMED : Outcome.UNKNOWN,
                xVersion != null ? String.format("X-Bus %d.%d, Kennung 0x%02X%s", xVersion.xBusVersion() >> 4,
                        xVersion.xBusVersion() & 0x0F, xVersion.commandStationId(),
                        xVersion.commandStationId() == 0x12 ? " (Z21)" : "") : "keine Antwort", false);
        add("Freischaltung", code != null ? Outcome.CONFIRMED : Outcome.UNKNOWN,
                code != null ? codeName(code.code()) : "keine Antwort auf LAN_GET_CODE", false);
        add("Gleisspannung / Status", status != null ? Outcome.CONFIRMED : Outcome.UNKNOWN,
                status != null ? powerName(status.state()) : "keine Antwort auf LAN_X_GET_STATUS", false);
        evaluateSystemState();
        evaluateFlags();
        evaluateRBus();
        evaluateCan();
        evaluateLocos();
        if (cfg.turnoutAddress() > 0) {
            add("Weicheninfo Adresse " + cfg.turnoutAddress(), turnoutInfo != null ? Outcome.CONFIRMED : Outcome.UNKNOWN,
                    turnoutInfo != null ? "Zentrale meldet Lage " + turnoutName(turnoutInfo.position()) : "keine Antwort", false);
        }
    }

    private void evaluateSystemState() {
        if (systemState == null) {
            add("Systemzustand", Outcome.UNKNOWN, "keine Antwort auf LAN_SYSTEMSTATE_GETDATA", false);
            add("Fähigkeiten (Systemzustand)", Outcome.UNKNOWN, "keine Antwort", false);
            add("Weichenadressierung RCN-213", Outcome.UNKNOWN, "keine Antwort", true);
            return;
        }
        Z21Message.SystemState s = systemState;
        add("Systemzustand", Outcome.CONFIRMED, String.format(
                "Hauptgleis %d mA (gefiltert %d mA), Programmiergleis %d mA, %d °C, Versorgung %d mV, intern %d mV, "
                        + "Zustand 0x%02X [%s], erweitert 0x%02X [%s]",
                s.mainCurrentMa(), s.filteredMainCurrentMa(), s.progCurrentMa(), s.temperatureC(), s.supplyVoltageMv(),
                s.vccVoltageMv(), s.centralState(), bits(s.centralState(), STATE_BITS), s.centralStateEx(),
                bits(s.centralStateEx(), STATE_EX_BITS)), false);
        if (s.capabilities() == 0) {
            add("Fähigkeiten (Systemzustand)", Outcome.UNKNOWN, "nicht gemeldet (erst ab Firmware 1.42)", false);
        } else {
            add("Fähigkeiten (Systemzustand)", Outcome.CONFIRMED,
                    String.format("0x%02X [%s]", s.capabilities(), bits(s.capabilities(), CAPABILITY_BITS)), false);
        }
        boolean rcn = (s.centralStateEx() & 0x20) != 0;
        add("Weichenadressierung RCN-213", Outcome.CONFIRMED,
                "Bit 0x20 im erweiterten Zustand " + (rcn ? "gesetzt" : "nicht gesetzt")
                        + " – Bedeutung laut Spezifikation „Weichenadressen nach RCN-213“; maßgeblich ist die Weichenprüfung in Stufe 2",
                true);
    }

    private void evaluateFlags() {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(Z21Protocol.BC_DRIVING_SWITCHING, "Fahr- und Schaltinfos");
        names.put(Z21Protocol.BC_RBUS, "R-Bus-Rückmelder");
        names.put(Z21Protocol.BC_RAILCOM, "RailCom (abonnierte Loks)");
        names.put(Z21Protocol.BC_SYSTEM_STATE, "Systemzustand");
        names.put(Z21Protocol.BC_ALL_LOCOS, "Lok-Broadcast (alle Loks)");
        names.put(Z21Protocol.BC_RAILCOM_ALL, "RailCom (alle Loks)");
        names.put(Z21Protocol.BC_CAN_DETECTOR, "CAN-Belegtmelder");
        for (var e : names.entrySet()) {
            String topic = "Broadcast-Flag " + e.getValue();
            if (flags == null) add(topic, Outcome.UNKNOWN, "Flags nicht zurückgelesen", false);
            else if ((flags.flags() & e.getKey()) != 0)
                add(topic, Outcome.CONFIRMED, String.format("0x%08X übernommen", e.getKey()), false);
            else add(topic, Outcome.NEGATIVE, String.format("0x%08X nicht übernommen", e.getKey()), false);
        }
        if (flags != null) journal.info(SRC, "BROADCAST_FLAGS", String.format("angefordert 0x%08X, zurückgelesen 0x%08X",
                REQUESTED_FLAGS, flags.flags()));
    }

    private void evaluateRBus() {
        if (rbus[0] == null && rbus[1] == null) {
            add("R-Bus", Outcome.UNKNOWN, "keine Antwort auf LAN_RMBUS_GETDATA", false);
            return;
        }
        StringJoiner active = new StringJoiner(", ");
        for (Z21Message.RBusFeedback fb : rbus) {
            if (fb == null) continue;
            for (int i = 0; i < 10 && i < fb.modules().length; i++) {
                int v = fb.modules()[i] & 0xFF;
                if (v != 0) active.add(String.format("Modul %d = 0x%02X", fb.group() * 10 + i + 1, v));
            }
        }
        add("R-Bus", Outcome.CONFIRMED, "Antwort erhalten" + (rbus[0] == null || rbus[1] == null ? " (nur eine Gruppe)" : "")
                + "; belegte Eingänge: " + (active.length() == 0 ? "keine" : active)
                + ". Die Zentrale antwortet auch ohne angeschlossene Module – Vorhandensein nicht nachweisbar", true);
    }

    private void evaluateCan() {
        Map<String, TreeSet<Integer>> portsByModule = new TreeMap<>();
        Map<String, String> lastState = new TreeMap<>();
        Map<String, Map<Integer, TreeSet<Integer>>> railcomBySection = new TreeMap<>();
        Map<Integer, Direction> lastLocoDirection = new TreeMap<>();
        int occupancy = 0, railcom = 0, overloads = 0;
        for (Z21Message m : received) {
            switch (m) {
                case Z21Message.CanOccupancy o -> {
                    occupancy++;
                    if (o.overload() > 0) overloads++;
                    String mod = String.format("NId 0x%04X Modul %d", o.networkId(), o.module());
                    portsByModule.computeIfAbsent(mod, k -> new TreeSet<>()).add(o.port() + 1);
                    lastState.put(mod + " Eingang " + (o.port() + 1), String.format("%s, %s, Rohwert 0x%04X",
                            o.occupied() ? "belegt" : "frei", o.trackVoltage() ? "Spannung" : "ohne Spannung", o.rawValue())
                            + (o.overload() > 0 ? ", Überlast " + o.overload() : ""));
                }
                case Z21Message.CanRailCom r -> {
                    railcom++;
                    String sec = String.format("NId 0x%04X Modul %d Eingang %d", r.networkId(), r.module(), r.port() + 1);
                    var byAddr = railcomBySection.computeIfAbsent(sec, k -> new TreeMap<>());
                    if (r.address1() != 0) byAddr.computeIfAbsent(r.address1(), k -> new TreeSet<>()).add(r.dirBits1());
                    if (r.address2() != 0) byAddr.computeIfAbsent(r.address2(), k -> new TreeSet<>()).add(r.dirBits2());
                }
                case Z21Message.Loco l -> lastLocoDirection.put(l.state().address(), l.state().direction());
                default -> { }
            }
        }
        if (occupancy + railcom == 0) {
            add("CAN-Detector 10808", Outcome.UNKNOWN,
                    "keine CAN-Meldung empfangen – Detector nicht angeschlossen, Flag nicht unterstützt oder keine Antwort", false);
        } else {
            StringJoiner sj = new StringJoiner("; ");
            portsByModule.forEach((k, v) -> sj.add(k + " Eingänge " + v));
            add("CAN-Detector 10808", Outcome.CONFIRMED, occupancy + " Belegt- und " + railcom + " RailCom-Meldungen; " + sj, false);
        }
        if (!lastState.isEmpty()) {
            StringJoiner sj = new StringJoiner("; ");
            lastState.forEach((k, v) -> sj.add(k + ": " + v));
            add("Belegtzustand je Abschnitt (zuletzt)", Outcome.CONFIRMED, sj.toString(), false);
        }
        if (overloads > 0) add("Überlast an CAN-Meldern", Outcome.NEGATIVE, overloads + " Überlastmeldungen", false);
        if (railcomBySection.isEmpty()) {
            add("RailCom-Adressen je Abschnitt", Outcome.UNKNOWN, "keine RailCom-Meldung vom 10808 empfangen", false);
            add("RailCom-Richtungsbits", Outcome.UNKNOWN, "keine RailCom-Adresse beobachtet", true);
        } else {
            StringJoiner addrs = new StringJoiner("; ");
            StringJoiner dirs = new StringJoiner("; ");
            railcomBySection.forEach((sec, byAddr) -> {
                addrs.add(sec + ": " + byAddr.keySet());
                byAddr.forEach((a, bits) -> {
                    Direction d = lastLocoDirection.get(a);
                    dirs.add("Lok " + a + " in " + sec + ": Bits 14/15 = " + bits
                            + (d != null ? ", Zentrale meldet Fahrtrichtung " + (d == Direction.FORWARD ? "vorwärts" : "rückwärts")
                            : ", Fahrtrichtung laut Zentrale unbekannt"));
                });
            });
            add("RailCom-Adressen je Abschnitt", Outcome.CONFIRMED, addrs.toString(), false);
            add("RailCom-Richtungsbits", Outcome.CONFIRMED, "beobachtete Rohwerte: " + dirs
                    + ". Deutung (Aufgleisrichtung oder Fahrtrichtung) nicht gesichert", true);
        }
        Map<Integer, Integer> rcData = new TreeMap<>();
        for (Z21Message m : received) if (m instanceof Z21Message.RailComData d) rcData.merge(d.locoAddress(), 1, Integer::sum);
        add("RailCom-Daten der Zentrale (LAN_RAILCOM_DATACHANGED)", rcData.isEmpty() ? Outcome.UNKNOWN : Outcome.CONFIRMED,
                rcData.isEmpty() ? "keine Meldung" : "Meldungen je Lokadresse: " + rcData, false);
    }

    private void evaluateLocos() {
        int proxy = cfg.proxyAddress();
        TreeSet<Integer> otherLocos = new TreeSet<>();
        int withF29 = 0, locoInfos = 0, unsolicitedProxy = 0;
        for (int i = 0; i < received.size(); i++) {
            if (!(received.get(i) instanceof Z21Message.Loco l)) continue;
            locoInfos++;
            if (l.f29to31Present()) withF29++;
            if (l.state().address() != proxy) otherLocos.add(l.state().address());
            else if (l != proxyInfo) unsolicitedProxy++; // alles außer der Antwort auf die eigene Abfrage
        }
        if (!otherLocos.isEmpty()) {
            add("Lok-Broadcast (alle Loks)", Outcome.CONFIRMED, "Lokmeldungen ohne Abo für Adressen " + otherLocos, false);
        } else {
            add("Lok-Broadcast (alle Loks)", Outcome.UNKNOWN,
                    "keine Lokmeldung für nicht abonnierte Adressen beobachtet (keine Lok bewegt?)", false);
        }
        if (locoInfos == 0) add("F29–F31 in Lokmeldung", Outcome.UNKNOWN, "keine Lokmeldung empfangen", true);
        else if (withF29 > 0) add("F29–F31 in Lokmeldung", Outcome.CONFIRMED,
                withF29 + " von " + locoInfos + " Lokmeldungen enthalten das Byte für F29–F31", true);
        else add("F29–F31 in Lokmeldung", Outcome.NEGATIVE,
                    "keine der " + locoInfos + " Lokmeldungen enthält das Byte für F29–F31", true);

        String topic = "Stellvertreter-Adresse " + proxy;
        if (proxyInfo == null) {
            add(topic, Outcome.UNKNOWN, "keine Lokinfo für die Adresse", true);
        } else {
            var s = proxyInfo.state();
            if (s.speedStep() != 0) {
                journal.warn(SRC, "STELLVERTRETER_IN_BENUTZUNG", topic + " hat Fahrstufe " + s.speedStep());
                add(topic, Outcome.NEGATIVE, "Zentrale meldet Fahrstufe " + s.speedStep()
                        + " – Adresse wird offenbar benutzt; vor Stufe 2 klären", true);
            } else {
                add(topic, Outcome.CONFIRMED, "Zentrale nimmt die Adresse an (Fahrstufe 0"
                        + (proxyInfo.busy() ? ", von anderem Gerät gesteuert" : "") + ")", true);
            }
        }
        add("Handregler-Befehle auf Stellvertreter-Adresse sichtbar", unsolicitedProxy > 0 ? Outcome.CONFIRMED : Outcome.UNKNOWN,
                unsolicitedProxy > 0 ? unsolicitedProxy + " Lokmeldungen ohne eigene Abfrage"
                        : "während der Beobachtung keine Lokmeldung für " + proxy + " (Maus nicht bedient?)", true);
    }

    // ================================================================ Stufe 2: aktiv

    private void runActive() {
        activeStage = true;
        lastReceiveNanos = time.nanoTime();
        try {
            if (!anyReceived) {
                add("Aktive Prüfungen", Outcome.NOT_TESTED, "keine Rückmeldung der Zentrale – im Zweifel halt", false);
                return;
            }
            op.info("Stufe 2 (aktiv): Jeder Eingriff wird einzeln angekündigt und erst nach Eingabe von JA ausgeführt.");
            TrackPower power = queryPower();
            switch (power) {
                case ON -> journal.info(SRC, "GLEISSPANNUNG", "Gleisspannung ist bereits eingeschaltet");
                case OFF -> powerOnStep();
                default -> {
                    add("Gleisspannung einschalten", Outcome.NOT_TESTED, "Zentrale meldet " + powerName(power)
                            + " – das Prüfprogramm hebt diesen Zustand nicht auf", false);
                    throw new Aborted("Zentrale nicht betriebsbereit: " + powerName(power));
                }
            }
            if (cfg.turnoutAddress() > 0) {
                turnoutStep(TurnoutPosition.DIVERGING);
                turnoutStep(TurnoutPosition.STRAIGHT);
            } else {
                add("Weichenprüfung", Outcome.NOT_TESTED, "ausgelassen (--weiche 0)", false);
            }
            if (cfg.pomCv() > 0) pomStep();
            else add("POM-Zubehör", Outcome.NOT_TESTED, "ausgelassen (--pom-cv 0)", false);
            driveStep();
        } catch (Aborted e) {
            journal.warn(SRC, "ABBRUCH", e.getMessage());
            safeStop("Abbruch: " + e.getMessage());
            add("Restliche aktive Prüfungen", Outcome.NOT_TESTED, "nicht durchgeführt nach Abbruch: " + e.getMessage(), false);
        } catch (LinkLost e) {
            linkBroken = true;
            journal.error(SRC, "VERBINDUNG_VERLOREN", e.getMessage());
            safeStop("Verbindungsverlust: " + e.getMessage());
            add("Verbindung (Stufe 2)", Outcome.UNKNOWN, "im Zweifel halt: " + e.getMessage(), false);
            add("Restliche aktive Prüfungen", Outcome.NOT_TESTED, "nicht durchgeführt nach Verbindungsverlust", false);
        } finally {
            if (powerSwitchedOnByUs && !linkBroken) powerOffStep();
            activeStage = false;
        }
    }

    private void powerOnStep() {
        String topic = "Gleisspannung einschalten";
        Confirmation c = confirm(topic, "Die Gleisspannung der GESAMTEN Anlage wird eingeschaltet. Die Zentrale sendet danach "
                + "wieder die zuletzt gespeicherten Fahrstufen – vorher sicherstellen, dass keine Lok eine Fahrstufe "
                + "ungleich 0 hat und kein Handregler fährt.");
        if (c == null) abort(topic, "Bediener hat nicht bestätigt");
        int mark = received.size();
        powerSwitchedOnByUs = true;
        sendActive(Z21Protocol.trackPowerOn(), "Gleisspannung ein", c);
        Z21Message.PowerState p = awaitSince(mark, Z21Message.PowerState.class,
                s -> s.state() == TrackPower.ON || s.state() == TrackPower.SHORT_CIRCUIT, cfg.replyTimeoutMs());
        if (p == null) {
            add(topic, Outcome.UNKNOWN, "Einschalten nicht bestätigt", false);
            throw new Aborted("Gleisspannung ein nicht bestätigt – im Zweifel halt");
        }
        if (p.state() != TrackPower.ON) {
            add(topic, Outcome.NEGATIVE, "Zentrale meldet " + powerName(p.state()), false);
            throw new Aborted("Gleisspannung: " + powerName(p.state()));
        }
        add(topic, Outcome.CONFIRMED, "Zentrale meldet Gleisspannung ein", false);
    }

    private void powerOffStep() {
        String topic = "Gleisspannung ausschalten";
        try {
            Confirmation c = confirm(topic, "Die Gleisspannung der gesamten Anlage wird wieder ausgeschaltet (Zustand vor "
                    + "der Prüfung). Jede andere Eingabe lässt sie eingeschaltet.");
            if (c == null) {
                journal.warn(SRC, "GLEISSPANNUNG_BLEIBT", "Gleisspannung bleibt auf Wunsch des Bedieners eingeschaltet");
                add(topic, Outcome.NOT_TESTED, "Gleisspannung bleibt eingeschaltet", false);
                return;
            }
            int mark = received.size();
            sendActive(Z21Protocol.trackPowerOff(), "Gleisspannung aus", c);
            Z21Message.PowerState p = awaitSince(mark, Z21Message.PowerState.class,
                    s -> s.state() == TrackPower.OFF, cfg.replyTimeoutMs());
            add(topic, p != null ? Outcome.CONFIRMED : Outcome.UNKNOWN,
                    p != null ? "Zentrale meldet Gleisspannung aus" : "Ausschalten nicht bestätigt", false);
        } catch (LinkLost e) {
            linkBroken = true;
            journal.error(SRC, "VERBINDUNG_VERLOREN", e.getMessage());
            add(topic, Outcome.UNKNOWN, "Verbindungsverlust: " + e.getMessage(), false);
        }
    }

    private void turnoutStep(TurnoutPosition pos) {
        ensurePowerOn();
        int a = cfg.turnoutAddress();
        String label = turnoutName(pos);
        String topic = String.format("Weiche W%03d (Adresse %d) auf %s", a, a, label);
        Confirmation c = confirm(topic, String.format("Weiche W%03d, Adresse %d, wird auf %s gestellt. Auf und vor der Weiche "
                + "darf kein Fahrzeug stehen. Der Ausgang wird nach %d ms wieder abgeschaltet.", a, a, label, cfg.turnoutPulseMs()));
        if (c == null) abort(topic, "Bediener hat nicht bestätigt");
        int mark = received.size();
        synchronized (safetyLock) { turnoutOutput = new ActiveOutput(a, pos); }
        sendActive(Z21Protocol.setTurnout(a, pos, true), "Weichenausgang ein", c);
        pumpFor(cfg.turnoutPulseMs());
        releaseTurnoutOutput();
        Z21Message.Turnout info = awaitSince(mark, Z21Message.Turnout.class, t -> t.address() == a, cfg.replyTimeoutMs());
        if (info == null) add(topic, Outcome.UNKNOWN, "keine Weichenmeldung der Zentrale", false);
        else if (info.position() == pos) add(topic, Outcome.CONFIRMED, "Zentrale meldet Lage " + label, false);
        else add(topic, Outcome.NEGATIVE, "Zentrale meldet Lage " + turnoutName(info.position()), false);

        String seen = ask("Welche Weiche hat sich bewegt? Nummer eingeben (z. B. " + a + "), 'keine' eingeben oder leer "
                + "lassen, wenn nicht erkennbar:");
        String atopic = "10836-Adressierung (Adresse " + a + ", " + label + ")";
        if (seen == null || seen.isBlank()) {
            add(atopic, Outcome.UNKNOWN, "keine Beobachtung des Bedieners", true);
        } else if (seen.trim().equalsIgnoreCase("keine")) {
            add(atopic, Outcome.NEGATIVE, "laut Bediener hat sich keine Weiche bewegt", true);
        } else {
            try {
                int n = Integer.parseInt(seen.trim().replaceFirst("^[Ww]0*", ""));
                if (n == a) add(atopic, Outcome.CONFIRMED, "Adresse " + a + " schaltet Weiche W" + String.format("%03d", n)
                        + " (Bedienerbeobachtung)", true);
                else add(atopic, Outcome.NEGATIVE, "Adresse " + a + " schaltet laut Bediener Weiche " + n
                        + " (Versatz " + (n - a) + ")", true);
            } catch (NumberFormatException e) {
                add(atopic, Outcome.UNKNOWN, "Eingabe nicht auswertbar: '" + seen + "'", true);
            }
        }
    }

    private void releaseTurnoutOutput() {
        synchronized (safetyLock) {
            if (turnoutOutput != null) {
                sendSafety(Z21Protocol.setTurnout(turnoutOutput.address(), turnoutOutput.position(), false), "Weichenausgang aus");
                turnoutOutput = null;
            }
        }
    }

    private void pomStep() {
        ensurePowerOn();
        int dec = cfg.pomDecoderAddress();
        int cv = cfg.pomCv();
        String topic = "POM-Zubehör: CV " + cv + " lesen (Decoderadresse " + dec + ")";
        Confirmation c = confirm(topic, "Auf dem Hauptgleis wird ein POM-Lesebefehl für CV " + cv + " an den Zubehördecoder "
                + "mit Decoderadresse " + dec + " (ganzer Decoder) gesendet. Es wird nichts geschrieben. "
                + "Eine Antwort ist nur über RailCom möglich.");
        if (c == null) abort(topic, "Bediener hat nicht bestätigt");
        int mark = received.size();
        sendActive(Z21Protocol.cvPomAccessory(dec, null, cv, false, 0), "POM-Zubehör lesen", c);
        Z21Message r = awaitSince(mark, Z21Message.class,
                m -> (m instanceof Z21Message.CvResult cr && cr.cv() == cv) || m instanceof Z21Message.CvFailed,
                cfg.cvTimeoutMs());
        switch (r) {
            case Z21Message.CvResult cr -> add(topic, Outcome.CONFIRMED, "CV " + cv + " = " + cr.value(), true);
            case Z21Message.CvFailed f -> add(topic, Outcome.NEGATIVE, f.reason(), true);
            case null, default -> add(topic, Outcome.UNKNOWN, "keine Antwort innerhalb " + cfg.cvTimeoutMs() + " ms", true);
        }
    }

    private void driveStep() {
        ensurePowerOn();
        int a = cfg.proxyAddress();
        int step = cfg.driveSpeedStep();
        String topic = "Fahren auf Stellvertreter-Adresse " + a;
        Confirmation c = confirm(topic, String.format("Fahrbefehl auf Stellvertreter-Adresse %d: Fahrstufe %d (128-Fahrstufen-Modus) "
                + "vorwärts für %d ms, danach Fahrstufe 0 und Nothalt. Auf Adresse %d darf KEIN Decoder hören (Entscheidung 7). "
                + "Beobachten Sie die Anlage; jede Eingabe während der Fahrt hält sofort an.", a, step, cfg.driveMillis(), a));
        if (c == null) abort(topic, "Bediener hat nicht bestätigt");
        int mark = received.size();
        synchronized (safetyLock) { driveStarted = true; }
        sendActive(Z21Protocol.setLocoDrive(a, step, SpeedSteps.S128, Direction.FORWARD), "Fahrbefehl", c);
        Z21Message.Loco echo = awaitSince(mark, Z21Message.Loco.class,
                l -> l.state().address() == a && l.state().speedStep() == step, cfg.replyTimeoutMs());
        if (echo == null) {
            emergencyStopProxy("Fahrbefehl nicht bestätigt");
            add(topic, Outcome.UNKNOWN, "keine Lokmeldung mit Fahrstufe " + step + " – Nothalt gesendet", true);
            throw new Aborted("Fahrbefehl nicht bestätigt – im Zweifel halt");
        }
        op.discardPending();
        long end = time.nanoTime() + cfg.driveMillis() * MS;
        String interrupt = null;
        while (time.nanoTime() < end) {
            pump(Math.min(POLL_MS, Math.max(1, (end - time.nanoTime()) / MS)));
            interrupt = op.poll();
            if (interrupt != null) break;
        }
        int stopMark = received.size();
        if (interrupt != null) {
            journal.warn(SRC, "BEDIENEREINGABE", "Eingabe während der Fahrt – sofortiger Halt");
            emergencyStopProxy("Bedienereingabe während der Fahrt");
        } else {
            sendSafety(Z21Protocol.setLocoDrive(a, 0, SpeedSteps.S128, Direction.FORWARD), "Fahrstufe 0");
            emergencyStopProxy("Fahrzeit abgelaufen");
        }
        Z21Message.Loco stopped = awaitSince(stopMark, Z21Message.Loco.class,
                l -> l.state().address() == a && l.state().speedStep() == 0, cfg.replyTimeoutMs());
        add(topic, Outcome.CONFIRMED, "Zentrale hat Fahrstufe " + step + " auf Adresse " + a + " angenommen"
                + (echo.f29to31Present() ? "; Lokmeldung mit F29–F31-Byte" : ""), true);
        add("Halt auf Stellvertreter-Adresse " + a, stopped != null ? Outcome.CONFIRMED : Outcome.UNKNOWN,
                stopped != null ? "Zentrale meldet Fahrstufe 0" : "Halt nicht bestätigt – Nothalt wurde gesendet", false);
        String moved = ask("Hat sich während der Fahrprüfung ein Fahrzeug bewegt? 'ja' oder 'nein' eingeben:");
        String mtopic = "Kein Decoder auf Stellvertreter-Adresse " + a;
        if (moved == null || moved.isBlank()) add(mtopic, Outcome.UNKNOWN, "keine Beobachtung des Bedieners", true);
        else if (moved.trim().equalsIgnoreCase("nein")) add(mtopic, Outcome.CONFIRMED, "laut Bediener keine Bewegung", true);
        else if (moved.trim().equalsIgnoreCase("ja")) {
            journal.critical(SRC, "DECODER_AUF_STELLVERTRETER", "Fahrzeug reagiert auf Adresse " + a + " – Prüffehler (Entscheidung 7)");
            add(mtopic, Outcome.NEGATIVE, "PRÜFFEHLER: ein Fahrzeug reagiert auf die Stellvertreter-Adresse (Entscheidung 7)", true);
        } else add(mtopic, Outcome.UNKNOWN, "Eingabe nicht auswertbar: '" + moved + "'", true);
        String mouse = ask("Falls eine WLAN-Maus auf Adresse " + a + " stand: Hat ihre Anzeige die Fahrstufe " + step
                + " übernommen? 'ja', 'nein' oder leer lassen, wenn keine Maus verwendet wurde:");
        String mstopic = "Rückschreiben auf die Maus-Anzeige (Adresse " + a + ")";
        if (mouse == null || mouse.isBlank()) add(mstopic, Outcome.NOT_TESTED, "keine Maus verwendet bzw. keine Angabe", true);
        else if (mouse.trim().equalsIgnoreCase("ja")) add(mstopic, Outcome.CONFIRMED, "laut Bediener übernommen", true);
        else if (mouse.trim().equalsIgnoreCase("nein")) add(mstopic, Outcome.NEGATIVE, "laut Bediener nicht übernommen", true);
        else add(mstopic, Outcome.UNKNOWN, "Eingabe nicht auswertbar: '" + mouse + "'", true);
        if (interrupt != null) throw new Aborted("Fahrprüfung durch Bediener angehalten");
    }

    private void emergencyStopProxy(String reason) {
        synchronized (safetyLock) {
            sendSafety(Z21Protocol.emergencyStopLoco(cfg.proxyAddress(), SpeedSteps.S128, Direction.FORWARD),
                    "Nothalt Adresse " + cfg.proxyAddress() + " (" + reason + ")");
        }
    }

    /** Im Zweifel halt: aktiven Weichenausgang abschalten, Nothalt an die Stellvertreter-Adresse. */
    private void safeStop(String reason) {
        synchronized (safetyLock) {
            if (turnoutOutput != null) {
                journal.warn(SRC, "SICHERHEITSHALT", "Weichenausgang wird abgeschaltet: " + reason);
                releaseTurnoutOutput();
            }
            if (driveStarted) {
                journal.warn(SRC, "SICHERHEITSHALT", "Nothalt Stellvertreter-Adresse: " + reason);
                emergencyStopProxy(reason);
            }
        }
    }

    private TrackPower queryPower() {
        Z21Message.PowerState s = request("Status", Z21Protocol.getStatus(), Z21Message.PowerState.class, m -> true);
        if (s == null) throw new LinkLost("Statusabfrage unbeantwortet");
        return s.state();
    }

    private void ensurePowerOn() {
        TrackPower p = queryPower();
        if (p != TrackPower.ON) throw new Aborted("Gleisspannung nicht eingeschaltet (" + powerName(p) + ") – im Zweifel halt");
    }

    private void abort(String topic, String reason) {
        add(topic, Outcome.ABORTED, reason, false);
        throw new Aborted(topic + ": " + reason);
    }

    // ================================================================ Bediener

    private Confirmation confirm(String step, String text) {
        op.discardPending();
        journal.info(SRC, "BESTAETIGUNG_ANGEFORDERT", step + " – " + text);
        op.prompt("EINGRIFF: " + text + "\n'JA' eintippen und Enter drücken. Jede andere Eingabe bricht ab.");
        String in = awaitOperator();
        if (in == null) {
            journal.warn(SRC, "ZEITUEBERSCHREITUNG", step + ": keine Eingabe innerhalb " + cfg.confirmTimeoutSeconds() + " s");
            return null;
        }
        journal.info(SRC, "BEDIENEREINGABE", step + ": '" + in + "'");
        if (!in.trim().equals("JA")) {
            journal.warn(SRC, "NICHT_BESTAETIGT", step);
            return null;
        }
        journal.info(SRC, "BESTAETIGT", step);
        return new Confirmation(step);
    }

    private String ask(String question) {
        op.discardPending();
        op.prompt(question);
        String in = awaitOperator();
        journal.info(SRC, "BEDIENEREINGABE", question + " → " + (in == null ? "(keine Eingabe)" : "'" + in + "'"));
        return in;
    }

    /** Wartet auf eine Eingabe und hält dabei die Verbindung aktiv bzw. überwacht sie. */
    private String awaitOperator() {
        long deadline = time.nanoTime() + cfg.confirmTimeoutSeconds() * 1000L * MS;
        while (true) {
            String in = op.poll();
            if (in != null) return in;
            if (time.nanoTime() >= deadline) return null;
            pump(POLL_MS);
        }
    }

    // ================================================================ Senden und Empfangen

    private void sendPassive(byte[] p, String what) {
        if (CommandClass.of(p) != CommandClass.PASSIVE) throw new IllegalStateException("kein passiver Befehl: " + what);
        transmit(p, what);
    }

    private void sendActive(byte[] p, String what, Confirmation c) {
        if (cfg.mode() != CheckConfig.Mode.ACTIVE || c == null)
            throw new IllegalStateException("Eingriff ohne Stufe 2 bzw. ohne Bestätigung: " + what);
        journal.info(SRC, "EINGRIFF", what + " (bestätigt: " + c.step() + "): " + Z21Protocol.hex(p));
        transmit(p, what);
    }

    /** Sicherheitsbefehle: jederzeit erlaubt; ein Sendefehler wird gemeldet, aber nicht weitergeworfen. */
    private void sendSafety(byte[] p, String what) {
        if (CommandClass.of(p) != CommandClass.SAFETY) throw new IllegalStateException("kein Sicherheitsbefehl: " + what);
        journal.warn(SRC, "SICHERHEITSBEFEHL", what + ": " + Z21Protocol.hex(p));
        try {
            link.send(p);
        } catch (IOException | RuntimeException e) {
            journal.critical(SRC, "SICHERHEITSBEFEHL_FEHLGESCHLAGEN", what + ": " + e);
        }
    }

    private void transmit(byte[] p, String what) {
        journal.debug(SRC, "SENDEN", what + ": " + Z21Protocol.hex(p));
        try {
            link.send(p);
        } catch (IOException e) {
            throw new LinkLost("Senden fehlgeschlagen (" + what + "): " + e.getMessage());
        }
    }

    private <T extends Z21Message> T request(String what, byte[] p, Class<T> type, Predicate<T> match) {
        int mark = received.size();
        long sent = time.nanoTime();
        sendPassive(p, what);
        T r = awaitSince(mark, type, match, cfg.replyTimeoutMs());
        if (r == null) {
            journal.warn(SRC, "KEINE_ANTWORT", what + ": keine Rückmeldung innerhalb " + cfg.replyTimeoutMs() + " ms");
        } else {
            long ms = (time.nanoTime() - sent) / MS;
            responseTimesMs.add(ms);
            journal.info(SRC, "ANTWORT", what + " nach " + ms + " ms: " + describe(r));
        }
        return r;
    }

    /** Sucht ab Position {@code mark} eine passende Meldung und empfängt bei Bedarf bis zur Zeitgrenze. */
    private <T extends Z21Message> T awaitSince(int mark, Class<T> type, Predicate<T> match, long timeoutMs) {
        long deadline = time.nanoTime() + timeoutMs * MS;
        int idx = mark;
        while (true) {
            for (; idx < received.size(); idx++) {
                Z21Message m = received.get(idx);
                if (type.isInstance(m) && match.test(type.cast(m))) return type.cast(m);
            }
            long rem = deadline - time.nanoTime();
            if (rem <= 0) return null;
            pump(Math.min(POLL_MS, Math.max(1, rem / MS)));
        }
    }

    private void pumpFor(long ms) {
        long end = time.nanoTime() + ms * MS;
        while (time.nanoTime() < end) pump(Math.min(POLL_MS, Math.max(1, (end - time.nanoTime()) / MS)));
    }

    /** Ein Empfangsdurchgang; danach Lebenszeichen senden und in Stufe 2 die Verbindung prüfen. */
    private void pump(long ms) {
        byte[] p;
        try {
            p = link.receive(ms);
        } catch (IOException e) {
            throw new LinkLost("Empfang fehlgeschlagen: " + e.getMessage());
        }
        long now = time.nanoTime();
        if (p != null) {
            lastReceiveNanos = now;
            anyReceived = true;
            journal.debug(SRC, "EMPFANG", Z21Protocol.hex(p));
            for (Z21Message m : Z21Protocol.decode(p, p.length)) {
                received.add(m);
                journal.debug(SRC, "MELDUNG", describe(m));
            }
        }
        if (now - lastKeepAliveNanos >= KEEPALIVE_NANOS) {
            lastKeepAliveNanos = now;
            transmit(Z21Protocol.getStatus(), "Lebenszeichen (Status)");
        }
        if (activeStage && now - lastReceiveNanos > cfg.linkTimeoutMs() * MS)
            throw new LinkLost("keine Meldung der Zentrale seit " + (now - lastReceiveNanos) / MS + " ms");
    }

    private void add(String topic, Outcome o, String detail, boolean verify) {
        Finding f = new Finding(topic, o, detail, verify);
        findings.add(f);
        journal.log(o == Outcome.CONFIRMED || o == Outcome.NOT_TESTED ? Severity.INFO : Severity.WARNING, SRC, "ERGEBNIS", f.toString());
    }

    // ================================================================ Klartexte

    static String describe(Z21Message m) {
        return switch (m) {
            case Z21Message.Unknown u -> String.format("unbekannt (Header 0x%02X): %s", u.header(), Z21Protocol.hex(u.data()));
            case Z21Message.RBusFeedback r -> "R-Bus Gruppe " + r.group() + ": " + Z21Protocol.hex(r.modules());
            default -> m.toString();
        };
    }

    /** Hardware-Typen laut Z21-LAN-Spezifikation. */
    static final Map<Long, String> KNOWN_HW = Map.ofEntries(
            Map.entry(0x200L, "Z21 schwarz (Variante 2012)"),
            Map.entry(0x201L, "Z21 schwarz (Variante 2013)"),
            Map.entry(0x202L, "SmartRail"),
            Map.entry(0x203L, "z21 weiß (Startset 2013)"),
            Map.entry(0x204L, "z21 start (2016)"),
            Map.entry(0x205L, "Single Booster 10806"),
            Map.entry(0x206L, "Dual Booster 10807"),
            Map.entry(0x211L, "Z21 XL Series 10870"),
            Map.entry(0x212L, "XL Booster 10869"),
            Map.entry(0x301L, "Z21 Switch Decoder 10836"),
            Map.entry(0x302L, "Z21 Signal Decoder 10837"));

    static String hardwareName(long type) { return KNOWN_HW.getOrDefault(type, "unbekannter Typ"); }

    static String codeName(int c) {
        return switch (c) {
            case 0 -> "keine Sperre";
            case 1 -> "z21 start gesperrt – Fahr- und Schaltbefehle über LAN nicht möglich";
            case 2 -> "z21 start freigeschaltet";
            default -> "unbekannter Code " + c;
        };
    }

    static String powerName(TrackPower p) {
        return switch (p) {
            case ON -> "Gleisspannung ein";
            case OFF -> "Gleisspannung aus";
            case EMERGENCY_STOP -> "Nothalt";
            case SHORT_CIRCUIT -> "Kurzschluss";
            case PROGRAMMING -> "Programmiermodus";
            case UNKNOWN -> "unbekannt";
        };
    }

    static String turnoutName(TurnoutPosition p) {
        return switch (p) {
            case STRAIGHT -> "Gerade";
            case DIVERGING -> "Abzweig";
            case UNKNOWN -> "unbekannt (noch nicht geschaltet)";
        };
    }

    static final String[] STATE_BITS = {"Nothalt", "Gleisspannung aus", "Kurzschluss", "Bit 3", "Bit 4", "Programmiermodus", "Bit 6", "Bit 7"};
    static final String[] STATE_EX_BITS = {"Übertemperatur", "Versorgung zu schwach", "Kurzschluss extern", "Kurzschluss intern",
            "Bit 4", "RCN-213 (an Hardware zu prüfen)", "Bit 6", "Bit 7"};
    static final String[] CAPABILITY_BITS = {"DCC", "MM", "Bit 2", "RailCom", "Lokbefehle", "Zubehörbefehle", "Melderbefehle",
            "Freischaltcode nötig"};

    static String bits(int v, String[] names) {
        StringJoiner sj = new StringJoiner(", ");
        for (int i = 0; i < 8; i++) if ((v >> i & 1) != 0) sj.add(names[i]);
        return sj.length() == 0 ? "–" : sj.toString();
    }
}
