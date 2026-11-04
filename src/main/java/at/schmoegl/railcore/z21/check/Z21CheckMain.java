package at.schmoegl.railcore.z21.check;

import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.diag.Severity;
import at.schmoegl.railcore.util.TimeSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Startpunkt des Z21-Prüfprogramms: {@code gradlew z21Check --console=plain --args="--host 192.168.0.111"}.
 * Standard ist Stufe 1 (passiv). Stufe 2 nur mit {@code --stufe aktiv}, jeder Eingriff einzeln bestätigt.
 */
public final class Z21CheckMain {

    private Z21CheckMain() {}

    public static void main(String[] args) {
        if (CheckConfig.wantsHelp(args)) {
            System.out.println(CheckConfig.usage());
            return;
        }
        CheckConfig cfg;
        try {
            cfg = CheckConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Fehler: " + e.getMessage());
            System.err.println(CheckConfig.usage());
            System.exit(2);
            return;
        }
        Journal journal = new Journal(100_000, cfg.logDir());
        journal.addListener(e -> {
            if (e.severity().ordinal() >= Severity.INFO.ordinal()) System.out.println(e);
        });
        System.out.println("RailCore – Z21-Prüfprogramm, Stufe " + cfg.mode().label + ", Zentrale " + cfg.host() + ":" + cfg.port());
        if (cfg.mode() == CheckConfig.Mode.ACTIVE) {
            System.out.println("ACHTUNG Stufe 2: Eingriffe (Gleisspannung, Weiche, POM, Fahren auf Stellvertreter-Adresse "
                    + cfg.proxyAddress() + ") werden einzeln angekündigt und nur nach Eingabe von JA ausgeführt.");
        }
        int exit = 0;
        try (UdpZ21Transport link = new UdpZ21Transport(cfg.host(), cfg.port())) {
            ConsoleOperator op = new ConsoleOperator();
            Z21Check check = new Z21Check(cfg, link, op, journal, TimeSource.SYSTEM);
            Thread hook = new Thread(check::emergencyShutdown, "z21-pruefung-halt");
            Runtime.getRuntime().addShutdownHook(hook);
            CheckReport report = check.run();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // JVM wird bereits beendet
            }
            Path file = report.write(cfg.reportDir(), LocalDateTime.now());
            System.out.println();
            System.out.println("Prüfbericht: " + file.toAbsolutePath());
        } catch (IOException e) {
            journal.error(Z21Check.SRC, "FEHLER", "Prüfung nicht möglich: " + e.getMessage());
            exit = 1;
        } finally {
            journal.flush();
        }
        System.exit(exit);
    }
}
