package at.schmoegl.railcore.z21.check;

import at.schmoegl.railcore.core.TrackPower;
import at.schmoegl.railcore.diag.Journal;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static at.schmoegl.railcore.TestRunner.assertEquals;

/** Sicherheitsverhalten des Prüfprogramms gegen die simulierte Z21 (TimeSource.Manual). */
public class Z21CheckTest {

    static final String POWER_ON = "07 00 40 00 21 81 A0";
    static final String POWER_OFF = "07 00 40 00 21 80 A1";
    static final String TURNOUT_DIV_ON = "09 00 40 00 53 00 00 89 DA";
    static final String TURNOUT_DIV_OFF = "09 00 40 00 53 00 00 81 D2";
    static final String TURNOUT_STR_ON = "09 00 40 00 53 00 00 88 DB";
    static final String TURNOUT_STR_OFF = "09 00 40 00 53 00 00 80 D3";
    static final String DRIVE_9001_STEP1 = "0A 00 40 00 E4 13 E3 29 82 BF";
    static final String HALT_9001 = "0A 00 40 00 E4 13 E3 29 80 BD";
    static final String ESTOP_9001 = "0A 00 40 00 E4 13 E3 29 81 BC";

    private final SimulatedZ21 sim = new SimulatedZ21();
    private final Journal journal = Journal.inMemory();

    private CheckReport run(ScriptedOperator op, String... args) {
        List<String> a = new ArrayList<>(List.of("--host", "127.0.0.1", "--dauer", "2", "--bestaetigung-s", "10"));
        a.addAll(List.of(args));
        CheckConfig cfg = CheckConfig.parse(a.toArray(String[]::new));
        return new Z21Check(cfg, sim, op, journal, sim.clock).run();
    }

    private static Finding f(CheckReport r, String topic) {
        return r.find(topic).orElseThrow(() -> new AssertionError("Ergebnis fehlt: " + topic + " in " + r.findings()));
    }

    // ---------------------------------------------------------------- Stufe 1

    public void testPassiveSendsOnlyPassiveCommands() {
        CheckReport r = run(new ScriptedOperator());
        for (byte[] p : sim.sent) assertEquals(CommandClass.PASSIVE, CommandClass.of(p));
        assertEquals(Outcome.CONFIRMED, f(r, "Verbindung").outcome());
        assertEquals("123456", f(r, "Seriennummer").detail());
        assertEquals(true, f(r, "Hardware-Typ").detail().contains("Z21 schwarz (Variante 2013)"));
        assertEquals("1.43", f(r, "Firmware (HW-Info)").detail());
        assertEquals("1.43", f(r, "Firmware (X-Bus)").detail());
        assertEquals(Outcome.CONFIRMED, f(r, "Broadcast-Flag CAN-Belegtmelder").outcome());
        assertEquals(Outcome.CONFIRMED, f(r, "Stellvertreter-Adresse 9001").outcome());
        assertEquals(Outcome.NOT_TESTED, f(r, "Aktive Prüfungen").outcome());
        assertEquals(Outcome.CONFIRMED, f(r, "Antwortzeiten").outcome());
        // Lebenszeichen jede Sekunde während der Beobachtung
        assertEquals(true, sim.sentMatching("07 00 40 00 21 24 05").size() >= 2);
    }

    public void testMissingRepliesAreUnknownNeverConfirmed() {
        sim.silent.addAll(List.of("serial", "hwinfo", "flags", "can"));
        sim.xFirmwareSupported = false;
        sim.supportedFlags = 0x00000003L;
        CheckReport r = run(new ScriptedOperator());
        assertEquals(Outcome.UNKNOWN, f(r, "Seriennummer").outcome());
        assertEquals(Outcome.UNKNOWN, f(r, "Hardware-Typ").outcome());
        assertEquals(Outcome.NEGATIVE, f(r, "Firmware (X-Bus)").outcome());
        assertEquals(Outcome.UNKNOWN, f(r, "Broadcast-Flag CAN-Belegtmelder").outcome());
        assertEquals(Outcome.UNKNOWN, f(r, "CAN-Detector 10808").outcome());
        assertEquals(true, r.find("Firmware-Erwartung").isEmpty());
    }

    public void testFlagsNotTakenOverAreNegative() {
        sim.supportedFlags = 0x00000003L;
        CheckReport r = run(new ScriptedOperator());
        assertEquals(Outcome.CONFIRMED, f(r, "Broadcast-Flag R-Bus").outcome());
        assertEquals(Outcome.NEGATIVE, f(r, "Broadcast-Flag Lok-Broadcast").outcome());
    }

    public void testNoAnswerAtAllRefusesActiveStage() {
        sim.dead = true;
        CheckReport r = run(new ScriptedOperator("JA", "JA", "JA", "JA", "JA"), "--stufe", "aktiv");
        assertEquals(0L, r.count(Outcome.CONFIRMED));
        assertEquals(Outcome.UNKNOWN, f(r, "Verbindung").outcome());
        assertEquals(Outcome.NOT_TESTED, f(r, "Aktive Prüfungen").outcome());
        assertEquals(false, sim.anySent(CommandClass.ACTIVE));
        assertEquals(false, sim.anySent(CommandClass.SAFETY));
    }

    public void testObservationRecordsCanRailComAndLocoBroadcast() {
        sim.broadcastAt(500, SimulatedZ21.packet(0xC4, 0x34, 0x12, 5, 0, 2, 0x01, 0x00, 0x11, 0, 0));
        sim.broadcastAt(600, SimulatedZ21.packet(0xC4, 0x34, 0x12, 5, 0, 2, 0x11, 0x03, 0x80, 0, 0));
        sim.broadcastAt(700, sim.locoInfo(3));
        CheckReport r = run(new ScriptedOperator());
        assertEquals(Outcome.CONFIRMED, f(r, "CAN-Detector 10808").outcome());
        assertEquals(Outcome.CONFIRMED, f(r, "RailCom-Adressen je Abschnitt").outcome());
        Finding dir = f(r, "RailCom-Richtungsbits");
        assertEquals(true, dir.verifyOnHardware());
        assertEquals(true, dir.detail().contains("Lok 3"));
        assertEquals(true, dir.detail().contains("[2]"));
        assertEquals(Outcome.CONFIRMED, f(r, "Lok-Broadcast").outcome());
        assertEquals(Outcome.CONFIRMED, f(r, "F29–F31").outcome());
    }

    public void testPacketTypesAndLocoSamplesAreReported() {
        sim.broadcastAt(700, sim.locoInfo(3));
        CheckReport r = run(new ScriptedOperator());
        assertEquals(true, f(r, "Empfangene Datensätze je Typ").detail().contains("0x40 X=0xEF"));
        assertEquals(Outcome.CONFIRMED, f(r, "Lokmeldungen roh (Beispiele)").outcome());
        assertEquals(true, r.find("Lokmeldungen dekodiert").isEmpty());
    }

    public void testRailComDirectionSequenceAndLastAddresses() {
        // Lok 3 in Abschnitt Modul 5 Eingang 3: Bits 14/15 = 3, dann 2, dann Abschnitt leer
        sim.broadcastAt(500, SimulatedZ21.packet(0xC4, 0x34, 0x12, 5, 0, 2, 0x11, 0x03, 0xC0, 0, 0));
        sim.broadcastAt(600, SimulatedZ21.packet(0xC4, 0x34, 0x12, 5, 0, 2, 0x11, 0x03, 0x80, 0, 0));
        sim.broadcastAt(700, SimulatedZ21.packet(0xC4, 0x34, 0x12, 5, 0, 2, 0x11, 0x00, 0x00, 0, 0));
        CheckReport r = run(new ScriptedOperator());
        assertEquals("Lok 3: 3 → 2", f(r, "RailCom-Richtungsbits (Verlauf je Lok)").detail());
        assertEquals(true, f(r, "RailCom-Adressen je Abschnitt (letzte Meldung)").detail().endsWith("Eingang 3: []"));
    }

    public void testClosedInputDoesNotEndObservation() {
        ScriptedOperator op = new ScriptedOperator();
        op.inputClosed = true;
        long start = sim.clock.nanoTime();
        run(op);
        assertEquals(0, journal.byCode("BEDIENEREINGABE").size());
        assertEquals(true, sim.clock.nanoTime() - start >= 2_000_000_000L);
    }

    public void testClosedInputRefusesActiveStage() {
        ScriptedOperator op = new ScriptedOperator();
        op.inputClosed = true;
        run(op, "--stufe", "aktiv");
        for (byte[] p : sim.sent) assertEquals(false, CommandClass.of(p) == CommandClass.ACTIVE);
    }

    public void testProxyPollingSeesHandControllerWithoutBroadcast() {
        // Abfrage 1 = Lokinfo beim Start; Handregler ab Abfrage 3 vorwärts Fahrstufe 5 (0x80 | 6),
        // ab Abfrage 6 rückwärts Fahrstufe 0 (0x00) – ohne jede Meldung der Zentrale
        sim.handControllerBeforeQuery(3, 9001, 0x86);
        sim.handControllerBeforeQuery(6, 9001, 0x00);
        CheckReport r = run(new ScriptedOperator());
        Finding f = f(r, "Stellvertreter-Adresse per Abfrage");
        assertEquals(Outcome.CONFIRMED, f.outcome());
        assertEquals(true, f.detail().contains("Fahrstufe 5, vorwärts"));
        assertEquals(true, f.detail().contains("Fahrstufe 0, rückwärts"));
        assertEquals(Outcome.UNKNOWN, f(r, "Handregler-Befehle auf Stellvertreter-Adresse sichtbar").outcome());
        for (byte[] p : sim.sent) assertEquals(CommandClass.PASSIVE, CommandClass.of(p));
    }

    public void testProxyPollingCanBeSwitchedOff() {
        CheckReport r = run(new ScriptedOperator(), "--abfrage-ms", "0");
        assertEquals(Outcome.NOT_TESTED, f(r, "Stellvertreter-Adresse per Abfrage").outcome());
        assertEquals(1, sim.sentMatching("09 00 40 00 E3 F0").size());
    }

    public void testMouseCommandOnProxyIsDetected() {
        sim.broadcastAt(800, sim.locoInfo(9001));
        CheckReport r = run(new ScriptedOperator());
        assertEquals(Outcome.CONFIRMED, f(r, "Handregler-Befehle auf Stellvertreter-Adresse").outcome());
    }

    public void testWithoutF29ByteIsNegative() {
        sim.f29 = false;
        CheckReport r = run(new ScriptedOperator());
        assertEquals(Outcome.NEGATIVE, f(r, "F29–F31").outcome());
    }

    // ---------------------------------------------------------------- Stufe 2

    public void testFullActiveRun() {
        ScriptedOperator op = new ScriptedOperator("JA", "JA", "1", "JA", "1", "JA", "JA", "nein", "", "JA");
        CheckReport r = run(op, "--stufe", "aktiv");
        assertEquals(0, op.remainingAnswers());
        assertEquals(Outcome.CONFIRMED, f(r, "Gleisspannung einschalten").outcome());
        assertEquals(Outcome.CONFIRMED, f(r, "Weiche W001 (Adresse 1) auf Abzweig").outcome());
        assertEquals(Outcome.CONFIRMED, f(r, "10836-Adressierung (Adresse 1, Abzweig)").outcome());
        assertEquals(Outcome.CONFIRMED, f(r, "POM-Zubehör").outcome());
        assertEquals(true, f(r, "POM-Zubehör").detail().contains("161"));
        assertEquals(Outcome.CONFIRMED, f(r, "Fahren auf Stellvertreter-Adresse 9001").outcome());
        assertEquals(Outcome.CONFIRMED, f(r, "Halt auf Stellvertreter-Adresse 9001").outcome());
        assertEquals(Outcome.CONFIRMED, f(r, "Kein Decoder auf Stellvertreter-Adresse").outcome());
        assertEquals(Outcome.NOT_TESTED, f(r, "Rückschreiben auf die Maus-Anzeige").outcome());
        assertEquals(Outcome.CONFIRMED, f(r, "Gleisspannung ausschalten").outcome());
        // Reihenfolge: Weichenausgang wird nach dem Einschalten wieder abgeschaltet
        assertEquals(true, sim.indexOf(TURNOUT_DIV_ON) < sim.indexOf(TURNOUT_DIV_OFF));
        assertEquals(true, sim.indexOf(TURNOUT_STR_ON) < sim.indexOf(TURNOUT_STR_OFF));
        // Fahren nur mit Fahrstufe 1, danach Fahrstufe 0 und Nothalt
        assertEquals(1, sim.sentMatching(DRIVE_9001_STEP1).size());
        assertEquals(true, sim.indexOf(DRIVE_9001_STEP1) < sim.indexOf(HALT_9001));
        assertEquals(true, sim.indexOf(HALT_9001) < sim.indexOf(ESTOP_9001));
        assertEquals(true, sim.indexOf(POWER_ON) >= 0);
        assertEquals(true, sim.indexOf(POWER_OFF) > sim.indexOf(HALT_9001));
        assertEquals(TrackPower.OFF, sim.power);
        // Kein Fahrbefehl über Fahrstufe 1 und keine CV-Schreibbefehle
        for (byte[] p : sim.sent) {
            String h = SimulatedZ21.hex(p);
            if (h.startsWith("0A 00 40 00 E4 13")) assertEquals(true, (p[8] & 0x7F) <= 2);
            assertEquals(false, h.startsWith("0C 00 40 00 E6 30") || h.startsWith("0A 00 40 00 24 12"));
        }
    }

    public void testLowercaseJaAborts() {
        CheckReport r = run(new ScriptedOperator("ja"), "--stufe", "aktiv");
        assertEquals(Outcome.ABORTED, f(r, "Gleisspannung einschalten").outcome());
        assertEquals(Outcome.NOT_TESTED, f(r, "Restliche aktive Prüfungen").outcome());
        assertEquals(false, sim.anySent(CommandClass.ACTIVE));
    }

    public void testStaleInputIsNotAConfirmation() {
        ScriptedOperator op = new ScriptedOperator(ScriptedOperator.TIMEOUT);
        op.typeAhead = "JA";
        CheckReport r = run(op, "--stufe", "aktiv");
        assertEquals(Outcome.ABORTED, f(r, "Gleisspannung einschalten").outcome());
        assertEquals(false, sim.anySent(CommandClass.ACTIVE));
        assertEquals(1, journal.byCode("ZEITUEBERSCHREITUNG").size());
    }

    public void testEmergencyStopStateIsNotLifted() {
        sim.power = TrackPower.EMERGENCY_STOP;
        CheckReport r = run(new ScriptedOperator("JA", "JA", "JA"), "--stufe", "aktiv");
        assertEquals(Outcome.NOT_TESTED, f(r, "Gleisspannung einschalten").outcome());
        assertEquals(false, sim.anySent(CommandClass.ACTIVE));
    }

    public void testShortCircuitIsNotSwitchedOn() {
        sim.power = TrackPower.SHORT_CIRCUIT;
        run(new ScriptedOperator("JA"), "--stufe", "aktiv");
        assertEquals(-1, sim.indexOf(POWER_ON));
    }

    public void testDriveWithoutEchoSendsEmergencyStop() {
        sim.power = TrackPower.ON;
        sim.echoLoco = false;
        CheckReport r = run(new ScriptedOperator("JA"), "--stufe", "aktiv", "--weiche", "0", "--pom-cv", "0");
        Finding d = f(r, "Fahren auf Stellvertreter-Adresse 9001");
        assertEquals(Outcome.UNKNOWN, d.outcome());
        int drive = sim.indexOf(DRIVE_9001_STEP1);
        assertEquals(true, drive >= 0);
        assertEquals(true, sim.lastIndexOf(ESTOP_9001) > drive);
        // Gleisspannung war schon an: das Programm schaltet sie nicht aus
        assertEquals(-1, sim.indexOf(POWER_OFF));
    }

    public void testLinkLossDuringDriveStops() {
        sim.power = TrackPower.ON;
        sim.dieAfterDrive = true;
        CheckReport r = run(new ScriptedOperator("JA"), "--stufe", "aktiv", "--weiche", "0", "--pom-cv", "0",
                "--fahrzeit-ms", "5000", "--verbindung-ms", "3000");
        assertEquals(Outcome.UNKNOWN, f(r, "Verbindung (Stufe 2)").outcome());
        assertEquals(true, sim.lastIndexOf(ESTOP_9001) > sim.indexOf(DRIVE_9001_STEP1));
        assertEquals(true, journal.byCode("SICHERHEITSHALT").size() >= 1);
    }

    public void testUnconfirmedPowerOnAbortsAndOffersPowerOff() {
        sim.silent.add("power");
        CheckReport r = run(new ScriptedOperator("JA", "nein"), "--stufe", "aktiv");
        assertEquals(Outcome.UNKNOWN, f(r, "Gleisspannung einschalten").outcome());
        assertEquals(-1, sim.indexOf(TURNOUT_DIV_ON));
        assertEquals(-1, sim.indexOf(DRIVE_9001_STEP1));
        assertEquals(Outcome.NOT_TESTED, f(r, "Gleisspannung ausschalten").outcome());
    }

    public void testTurnoutOffsetIsReported() {
        CheckReport r = run(new ScriptedOperator("JA", "JA", "5", "nein"), "--stufe", "aktiv");
        Finding a = f(r, "10836-Adressierung (Adresse 1, Abzweig)");
        assertEquals(Outcome.NEGATIVE, a.outcome());
        assertEquals(true, a.detail().contains("Versatz 4"));
        assertEquals(true, a.verifyOnHardware());
        // Abbruch bei "Gerade" (Antwort "nein"): Ausgang ist trotzdem abgeschaltet, keine Fahrt
        assertEquals(true, sim.indexOf(TURNOUT_DIV_OFF) > sim.indexOf(TURNOUT_DIV_ON));
        assertEquals(-1, sim.indexOf(DRIVE_9001_STEP1));
    }

    public void testVehicleOnProxyIsTestError() {
        sim.power = TrackPower.ON;
        CheckReport r = run(new ScriptedOperator("JA", "ja", "ja"), "--stufe", "aktiv", "--weiche", "0", "--pom-cv", "0");
        assertEquals(Outcome.NEGATIVE, f(r, "Kein Decoder auf Stellvertreter-Adresse").outcome());
        assertEquals(1, journal.byCode("DECODER_AUF_STELLVERTRETER").size());
    }

    public void testEmergencyShutdownWithoutActivityIsSilent() {
        CheckConfig cfg = CheckConfig.parse(new String[]{"--host", "127.0.0.1"});
        new Z21Check(cfg, sim, new ScriptedOperator(), journal, sim.clock).emergencyShutdown();
        assertEquals(0, sim.sent.size());
    }

    public void testReportIsGerman() {
        sim.silent.add("serial");
        CheckReport r = run(new ScriptedOperator());
        String text = r.render(LocalDateTime.of(2026, 11, 4, 12, 0));
        assertEquals(true, text.contains("RailCore – Prüfbericht Z21"));
        assertEquals(true, text.contains("[unbekannt – keine Rückmeldung] Seriennummer"));
        assertEquals(true, text.contains("An Hardware zu prüfen"));
        assertEquals(true, text.contains("RailCom-Richtungsbits"));
    }
}
