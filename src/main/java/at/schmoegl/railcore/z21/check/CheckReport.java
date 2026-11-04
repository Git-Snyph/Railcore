package at.schmoegl.railcore.z21.check;

import at.schmoegl.railcore.diag.Journal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/** Ergebnis des Z21-Prüfprogramms und dessen Ausgabe als deutscher Prüfbericht (Textdatei). */
public record CheckReport(CheckConfig config, List<Finding> findings, List<Journal.Entry> protocol) {

    /** Erstes Ergebnis, dessen Prüfpunkt mit {@code topicPrefix} beginnt. */
    public Optional<Finding> find(String topicPrefix) {
        return findings.stream().filter(f -> f.topic().startsWith(topicPrefix)).findFirst();
    }

    public long count(Outcome o) { return findings.stream().filter(f -> f.outcome() == o).count(); }

    /** @param createdAt Uhrzeit nur zur Anzeige (keine Rechnung) */
    public String render(LocalDateTime createdAt) {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("RailCore – Prüfbericht Z21").append(nl);
        sb.append("==========================").append(nl);
        sb.append("Erstellt:          ").append(createdAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))).append(nl);
        sb.append("Zentrale:          ").append(config.host()).append(':').append(config.port()).append(nl);
        sb.append("Stufe:             ").append(config.mode().label).append(nl);
        sb.append("Beobachtungsdauer: ").append(config.observeSeconds()).append(" s").append(nl);
        sb.append("Stellvertreter:    ").append(config.proxyAddress()).append(nl);
        sb.append("Weiche:            ").append(config.turnoutAddress() == 0 ? "ausgelassen" : config.turnoutAddress()).append(nl);
        sb.append(nl);
        sb.append("Hinweis: Ein Ergebnis gilt nur als „bestätigt“, wenn die Zentrale (oder der Bediener) geantwortet hat.").append(nl);
        sb.append("Fehlende Antworten stehen als „unbekannt“, nie als in Ordnung. Punkte mit „an Hardware zu prüfen“").append(nl);
        sb.append("sind in ihrer Deutung nicht gesichert und dürfen nicht als Tatsache übernommen werden.").append(nl);
        sb.append(nl);
        sb.append(String.format("Übersicht: %d bestätigt, %d abweichend, %d unbekannt, %d nicht geprüft, %d abgebrochen%n",
                count(Outcome.CONFIRMED), count(Outcome.NEGATIVE), count(Outcome.UNKNOWN), count(Outcome.NOT_TESTED),
                count(Outcome.ABORTED)));
        sb.append(nl).append("Ergebnisse").append(nl).append("----------").append(nl);
        for (Finding f : findings) sb.append(f).append(nl);
        sb.append(nl).append("An Hardware zu prüfen").append(nl).append("---------------------").append(nl);
        boolean any = false;
        for (Finding f : findings) {
            if (!f.verifyOnHardware()) continue;
            any = true;
            sb.append("- ").append(f.topic()).append(" [").append(f.outcome().label).append("]").append(nl);
        }
        if (!any) sb.append("- keine").append(nl);
        sb.append(nl).append("Ablauf (Journal ab Stufe INFO; Rohdaten im Journal unter DEBUG)").append(nl)
                .append("---------------------------------------------------------------").append(nl);
        for (Journal.Entry e : protocol) sb.append(e).append(nl);
        return sb.toString();
    }

    /** Schreibt den Bericht als UTF-8-Textdatei und liefert deren Pfad. */
    public Path write(Path dir, LocalDateTime createdAt) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("z21-pruefbericht-" + createdAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
        Files.writeString(file, render(createdAt), StandardCharsets.UTF_8);
        return file;
    }
}
