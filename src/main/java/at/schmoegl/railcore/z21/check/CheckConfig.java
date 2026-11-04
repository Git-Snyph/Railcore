package at.schmoegl.railcore.z21.check;

import at.schmoegl.railcore.z21.Z21Protocol;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Einstellungen des Z21-Prüfprogramms. Alle Grenzen werden beim Anlegen geprüft; unzulässige Werte werden
 * abgelehnt statt still korrigiert.
 *
 * @param turnoutAddress     Weichenadresse für die aktive Weichenprüfung, 0 = Weichenprüfung auslassen
 * @param proxyAddress       Stellvertreter-Adresse (9000–9999, Entscheidung 7)
 * @param driveSpeedStep     Fahrstufe für die Fahrprüfung (128-Fahrstufen-Modus), 1..{@link #MAX_DRIVE_STEP}
 * @param pomDecoderAddress  Decoderadresse für POM-Zubehör; Standard (weiche − 1) / 4 – an Hardware zu prüfen
 * @param pomCv              CV für den POM-Lesetest, 0 = POM-Prüfung auslassen
 * @param proxyPollMs        Abstand der Lokinfo-Abfragen der Stellvertreter-Adresse während der Beobachtung, 0 = aus
 */
public record CheckConfig(String host, int port, Mode mode, int observeSeconds, int replyTimeoutMs, int linkTimeoutMs,
                          int confirmTimeoutSeconds, int turnoutAddress, int turnoutPulseMs, int proxyAddress,
                          int driveSpeedStep, int driveMillis, int pomDecoderAddress, int pomCv, int cvTimeoutMs,
                          int proxyPollMs, Path reportDir, Path logDir) {

    /** Stufe 1 (nur lesen) oder Stufe 2 (Eingriffe nach Einzelbestätigung). */
    public enum Mode {
        PASSIVE("passiv"), ACTIVE("aktiv");

        public final String label;

        Mode(String label) { this.label = label; }
    }

    /** Höchste zulässige Fahrstufe der Fahrprüfung. Sicherheitsgrenze, nicht übersteuerbar. */
    public static final int MAX_DRIVE_STEP = 10;
    public static final int PROXY_MIN = 9000, PROXY_MAX = 9999;

    public CheckConfig {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Host fehlt");
        range("Port", port, 1, 65535);
        if (mode == null) throw new IllegalArgumentException("Stufe fehlt");
        range("Beobachtungsdauer (s)", observeSeconds, 0, 86_400);
        range("Antwortzeit (ms)", replyTimeoutMs, 50, 60_000);
        range("Verbindungs-Zeitüberschreitung (ms)", linkTimeoutMs, replyTimeoutMs, 120_000);
        range("Bestätigungszeit (s)", confirmTimeoutSeconds, 5, 3600);
        range("Weichenadresse", turnoutAddress, 0, 2048);
        range("Weichen-Impulsdauer (ms)", turnoutPulseMs, 50, 2000);
        if (proxyAddress < PROXY_MIN || proxyAddress > PROXY_MAX)
            throw new IllegalArgumentException("Stellvertreter-Adresse muss im Bereich 9000–9999 liegen (Entscheidung 7): " + proxyAddress);
        range("Fahrstufe", driveSpeedStep, 1, MAX_DRIVE_STEP);
        range("Fahrzeit (ms)", driveMillis, 200, 5000);
        range("POM-Decoderadresse", pomDecoderAddress, 0, 0x7FF);
        range("POM-CV", pomCv, 0, 1024);
        range("CV-Antwortzeit (ms)", cvTimeoutMs, replyTimeoutMs, 60_000);
        if (proxyPollMs != 0) range("Abfrageabstand Stellvertreter (ms)", proxyPollMs, 100, 5000);
        if (reportDir == null || logDir == null) throw new IllegalArgumentException("Verzeichnis fehlt");
    }

    private static void range(String name, int v, int min, int max) {
        if (v < min || v > max) throw new IllegalArgumentException(name + " muss zwischen " + min + " und " + max + " liegen: " + v);
    }

    /** Standardwerte: passiv, 30 s Beobachtung, Weiche 1, Stellvertreter 9001, Fahrstufe 1. */
    public static CheckConfig defaults() { return parse(new String[0]); }

    public static boolean wantsHelp(String[] args) {
        for (String a : args) if (a.equals("--hilfe") || a.equals("-h") || a.equals("--help")) return true;
        return false;
    }

    public static CheckConfig parse(String[] args) {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("--host", "192.168.0.111");
        o.put("--port", String.valueOf(Z21Protocol.PORT));
        o.put("--stufe", "passiv");
        o.put("--dauer", "30");
        o.put("--antwort-ms", "1000");
        o.put("--verbindung-ms", "3000");
        o.put("--bestaetigung-s", "120");
        o.put("--weiche", "1");
        o.put("--puls-ms", "150");
        o.put("--stellvertreter", "9001");
        o.put("--fahrstufe", "1");
        o.put("--fahrzeit-ms", "2000");
        o.put("--pom-decoder", "");
        o.put("--pom-cv", "8");
        o.put("--cv-ms", "5000");
        o.put("--abfrage-ms", "250");
        o.put("--bericht", "berichte");
        o.put("--journal", "logs");
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (!o.containsKey(k)) throw new IllegalArgumentException("Unbekannte Option: " + k);
            if (i + 1 >= args.length) throw new IllegalArgumentException("Wert fehlt für " + k);
            o.put(k, args[++i]);
        }
        Mode mode = switch (o.get("--stufe").trim().toLowerCase()) {
            case "passiv", "1" -> Mode.PASSIVE;
            case "aktiv", "2" -> Mode.ACTIVE;
            default -> throw new IllegalArgumentException("Stufe muss 'passiv' oder 'aktiv' sein: " + o.get("--stufe"));
        };
        int turnout = num(o, "--weiche");
        String pomDec = o.get("--pom-decoder").trim();
        int pomDecoder = pomDec.isEmpty() ? Math.max(0, turnout - 1) / 4 : Integer.parseInt(pomDec);
        return new CheckConfig(o.get("--host").trim(), num(o, "--port"), mode, num(o, "--dauer"), num(o, "--antwort-ms"),
                num(o, "--verbindung-ms"), num(o, "--bestaetigung-s"), turnout, num(o, "--puls-ms"),
                num(o, "--stellvertreter"), num(o, "--fahrstufe"), num(o, "--fahrzeit-ms"), pomDecoder,
                num(o, "--pom-cv"), num(o, "--cv-ms"), num(o, "--abfrage-ms"),
                Path.of(o.get("--bericht")), Path.of(o.get("--journal")));
    }

    private static int num(Map<String, String> o, String k) {
        try {
            return Integer.parseInt(o.get(k).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Zahl erwartet für " + k + ": " + o.get(k));
        }
    }

    public CheckConfig withMode(Mode m) {
        return new CheckConfig(host, port, m, observeSeconds, replyTimeoutMs, linkTimeoutMs, confirmTimeoutSeconds,
                turnoutAddress, turnoutPulseMs, proxyAddress, driveSpeedStep, driveMillis, pomDecoderAddress, pomCv,
                cvTimeoutMs, proxyPollMs, reportDir, logDir);
    }

    public static String usage() {
        return """
                Z21-Prüfprogramm – Aufruf: gradlew z21Check --console=plain --args="<Optionen>"
                  --host <IP>              Adresse der Z21 (Standard 192.168.0.111)
                  --port <Port>            UDP-Port (Standard 21105)
                  --stufe passiv|aktiv     passiv = nur lesen (Standard); aktiv = Eingriffe nach Einzelbestätigung
                  --dauer <s>              Beobachtungsdauer für Melder-/RailCom-/Lokmeldungen (Standard 30)
                  --antwort-ms <ms>        Wartezeit auf eine Antwort (Standard 1000)
                  --verbindung-ms <ms>     Stille, ab der in Stufe 2 angehalten wird (Standard 3000)
                  --bestaetigung-s <s>     Wartezeit auf die Bedienereingabe (Standard 120)
                  --weiche <Adresse>       Weiche für die Weichenprüfung, 0 = auslassen (Standard 1)
                  --puls-ms <ms>           Einschaltdauer des Weichenausgangs (Standard 150)
                  --stellvertreter <Adr.>  Stellvertreter-Adresse 9000–9999 (Standard 9001)
                  --fahrstufe <n>          Fahrstufe der Fahrprüfung, 1–10 von 126 (Standard 1)
                  --fahrzeit-ms <ms>       Dauer der Fahrprüfung, 200–5000 (Standard 2000)
                  --pom-decoder <Adr.>     Decoderadresse für POM-Zubehör (Standard (Weiche−1)/4, an Hardware zu prüfen)
                  --pom-cv <CV>            CV für den POM-Lesetest, 0 = auslassen (Standard 8)
                  --cv-ms <ms>             Wartezeit auf eine CV-Antwort (Standard 5000)
                  --abfrage-ms <ms>        Stellvertreter-Adresse während der Beobachtung abfragen, 100–5000, 0 = aus (Standard 250)
                  --bericht <Ordner>       Ordner für den Prüfbericht (Standard berichte)
                  --journal <Ordner>       Ordner für das Journal (Standard logs)
                """;
    }
}
