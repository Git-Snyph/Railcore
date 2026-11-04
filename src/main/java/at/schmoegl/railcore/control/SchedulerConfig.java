package at.schmoegl.railcore.control;

import java.util.Locale;

/**
 * Einstellungen des {@link CommandScheduler} (Entscheidung 9, 27). Das Befehlsbudget kommt zunächst aus einem
 * <b>Rechenmodell</b>; alle Startwerte sind Annahmen und einstellbar, bis Messungen an der Hardware vorliegen.
 * <p>
 * <b>Rechenmodell:</b>
 * <pre>
 *   Gleiskapazität   K = 1000 / packetMillis                         [Pakete/s]
 *   nutzbar          N = K · (1 − reserveFraction) − refreshPacketsPerLocoPerSecond · aktive Loks
 *   Budget           B = max(minCommandsPerSecond, N / (1 + repeatsPerCommand))   [neue Befehle/s]
 * </pre>
 * <b>Annahmen der Startwerte</b> (an der Hardware zu prüfen):
 * <ul>
 *   <li>{@code packetMillis} 6,0 ms: ein DCC-Fahrbefehl (128 Fahrstufen, kurze oder lange Adresse) mit Präambel,
 *       Paketpause und RailCom-Cutout dauert etwa 5–8 ms; 6 ms ≈ 167 Pakete/s.</li>
 *   <li>{@code reserveFraction} 10 %: Reserve für Zubehör- und Nebenpakete und die Ungenauigkeit des Modells.</li>
 *   <li>{@code refreshPacketsPerLocoPerSecond} 1,0: die Z21 wiederholt für jede aktive Lok ohnehin etwa einmal je
 *       Sekunde ein Fahrstufenpaket (Auffrischung); Funktionsauffrischung steckt in der Reserve.</li>
 *   <li>{@code repeatsPerCommand} 1: ein neuer Befehl wird von der Z21 mindestens einmal wiederholt
 *       (zusammen 2 Pakete).</li>
 *   <li>{@code turnoutCost} 2: ein Weichenbefehl besteht aus Ein- und Ausschaltpaket.</li>
 *   <li>{@code activeHoldSeconds} 60 s: eine Lok gilt als aktiv, solange sie fährt oder in dieser Zeit einen
 *       Befehl bekam (so lange frischt die Z21 sie vermutlich auf).</li>
 * </ul>
 * Daraus: 0 aktive Loks 75 Befehle/s, 10 → 70, 25 → 62,5, 50 → 50, 100 → 25 Befehle/s.
 * <p>
 * <b>Ausgabe:</b> Token-Bucket mit {@code burstCommands} Inhalt, gleichmäßig über die Zeit verteilt. Haltebefehle
 * (Fahrstufe 0) dürfen das Budget um höchstens {@code haltBorrowCommands} überziehen, damit sie nie auf ein Token
 * warten; die Schuld wird danach abgetragen.
 * <p>
 * <b>Überlast</b> (Meldung, kein Ausbremsen – Entscheidung 27): Rückstau über {@code overloadBacklog}, Wartezeit
 * einer Klasse über ihrer Grenze (p95 je Sekunde; ein Wartender ab der doppelten Grenze), oder Bedarf der
 * Sicherheitsklasse (Bremskurven, gemittelt über {@code demandWindowSeconds}, Standard 3 s) über dem Budget. Aufgehoben erst
 * nach {@code overloadClearSeconds} ohne Befund; Journal gedrosselt ({@code overloadReportSeconds}). Messwerte
 * werden alle {@code metricsReportSeconds} protokolliert.
 */
public record SchedulerConfig(double packetMillis, double reserveFraction, int repeatsPerCommand,
                              double refreshPacketsPerLocoPerSecond, double minCommandsPerSecond,
                              double burstCommands, double haltBorrowCommands, int turnoutCost,
                              int activeHoldSeconds, int overloadBacklog,
                              int safetyWaitLimitMillis, int automaticWaitLimitMillis, int timetableWaitLimitMillis,
                              int manualWaitLimitMillis, int comfortWaitLimitMillis,
                              int overloadClearSeconds, int overloadReportSeconds, int metricsReportSeconds,
                              int demandWindowSeconds) {

    public static SchedulerConfig defaults() {
        return new SchedulerConfig(6.0, 0.10, 1, 1.0, 5.0, 2.0, 2.0, 2, 60, 25,
                50, 150, 300, 500, 5_000, 2, 10, 60, 3);
    }

    public SchedulerConfig {
        require(packetMillis >= 2.0 && packetMillis <= 50.0, "DCC-Paketdauer muss zwischen 2 und 50 ms liegen: " + packetMillis);
        require(reserveFraction >= 0 && reserveFraction <= 0.8, "Reserve muss zwischen 0 und 80 % liegen: " + reserveFraction);
        require(repeatsPerCommand >= 0 && repeatsPerCommand <= 10, "Wiederholungen je Befehl 0–10: " + repeatsPerCommand);
        require(refreshPacketsPerLocoPerSecond >= 0 && refreshPacketsPerLocoPerSecond <= 20,
                "Auffrischung je Lok 0–20 Pakete/s: " + refreshPacketsPerLocoPerSecond);
        require(minCommandsPerSecond >= 1 && minCommandsPerSecond <= 1000 / packetMillis,
                "Mindestbudget muss zwischen 1/s und der Gleiskapazität liegen: " + minCommandsPerSecond);
        require(burstCommands >= 1 && burstCommands <= 50, "Bucket-Inhalt 1–50 Befehle: " + burstCommands);
        require(haltBorrowCommands >= 0 && haltBorrowCommands <= 20, "Überziehen für Halt 0–20 Befehle: " + haltBorrowCommands);
        require(turnoutCost >= 1 && turnoutCost <= 10, "Kosten eines Weichenbefehls 1–10: " + turnoutCost);
        require(activeHoldSeconds >= 0 && activeHoldSeconds <= 3_600, "Haltezeit „aktiv“ 0–3600 s: " + activeHoldSeconds);
        require(overloadBacklog >= 1, "Rückstau-Grenze muss mindestens 1 sein: " + overloadBacklog);
        require(safetyWaitLimitMillis >= 1 && safetyWaitLimitMillis <= automaticWaitLimitMillis
                        && automaticWaitLimitMillis <= timetableWaitLimitMillis && timetableWaitLimitMillis <= 60_000
                        && manualWaitLimitMillis >= safetyWaitLimitMillis && manualWaitLimitMillis <= 60_000
                        && comfortWaitLimitMillis >= 1 && comfortWaitLimitMillis <= 600_000,
                "Wartezeit-Grenzen unplausibel (Sicherheit ≤ Automatik ≤ Fahrplan, alle positiv)");
        require(overloadClearSeconds >= 1 && overloadClearSeconds <= 600, "Aufhebung der Überlast 1–600 s: " + overloadClearSeconds);
        require(overloadReportSeconds >= 1 && overloadReportSeconds <= 3_600, "Überlast-Bericht 1–3600 s: " + overloadReportSeconds);
        require(metricsReportSeconds >= 1 && metricsReportSeconds <= 3_600, "Messwert-Bericht 1–3600 s: " + metricsReportSeconds);
        require(demandWindowSeconds >= 1 && demandWindowSeconds <= 60, "Mittelungsfenster Bremsbedarf 1–60 s: " + demandWindowSeconds);
    }

    /** Gleiskapazität in Paketen je Sekunde. */
    public double trackPacketsPerSecond() { return 1_000.0 / packetMillis; }

    /** Budget für neue Befehle je Sekunde bei {@code activeLocos} aktiven Loks (Rechenmodell). */
    public double budgetPerSecond(int activeLocos) {
        double usable = trackPacketsPerSecond() * (1 - reserveFraction) - refreshPacketsPerLocoPerSecond * Math.max(0, activeLocos);
        return Math.max(minCommandsPerSecond, usable / (1 + repeatsPerCommand));
    }

    /** Wartezeit-Grenze einer Klasse in ns. */
    public long waitLimitNanos(CommandPriority p) {
        int ms = switch (p) {
            case EMERGENCY, SAFETY -> safetyWaitLimitMillis;
            case AUTOMATIC -> automaticWaitLimitMillis;
            case TIMETABLE -> timetableWaitLimitMillis;
            case MANUAL -> manualWaitLimitMillis;
            case COMFORT -> comfortWaitLimitMillis;
        };
        return ms * 1_000_000L;
    }

    public SchedulerConfig withPacketMillis(double v) {
        return new SchedulerConfig(v, reserveFraction, repeatsPerCommand, refreshPacketsPerLocoPerSecond, minCommandsPerSecond,
                burstCommands, haltBorrowCommands, turnoutCost, activeHoldSeconds, overloadBacklog, safetyWaitLimitMillis,
                automaticWaitLimitMillis, timetableWaitLimitMillis, manualWaitLimitMillis, comfortWaitLimitMillis,
                overloadClearSeconds, overloadReportSeconds, metricsReportSeconds, demandWindowSeconds);
    }

    public SchedulerConfig withBurst(double burst, double haltBorrow) {
        return new SchedulerConfig(packetMillis, reserveFraction, repeatsPerCommand, refreshPacketsPerLocoPerSecond, minCommandsPerSecond,
                burst, haltBorrow, turnoutCost, activeHoldSeconds, overloadBacklog, safetyWaitLimitMillis,
                automaticWaitLimitMillis, timetableWaitLimitMillis, manualWaitLimitMillis, comfortWaitLimitMillis,
                overloadClearSeconds, overloadReportSeconds, metricsReportSeconds, demandWindowSeconds);
    }

    public SchedulerConfig withOverloadBacklog(int v) {
        return new SchedulerConfig(packetMillis, reserveFraction, repeatsPerCommand, refreshPacketsPerLocoPerSecond, minCommandsPerSecond,
                burstCommands, haltBorrowCommands, turnoutCost, activeHoldSeconds, v, safetyWaitLimitMillis,
                automaticWaitLimitMillis, timetableWaitLimitMillis, manualWaitLimitMillis, comfortWaitLimitMillis,
                overloadClearSeconds, overloadReportSeconds, metricsReportSeconds, demandWindowSeconds);
    }

    public SchedulerConfig withMetricsReportSeconds(int v) {
        return new SchedulerConfig(packetMillis, reserveFraction, repeatsPerCommand, refreshPacketsPerLocoPerSecond, minCommandsPerSecond,
                burstCommands, haltBorrowCommands, turnoutCost, activeHoldSeconds, overloadBacklog, safetyWaitLimitMillis,
                automaticWaitLimitMillis, timetableWaitLimitMillis, manualWaitLimitMillis, comfortWaitLimitMillis,
                overloadClearSeconds, overloadReportSeconds, v, demandWindowSeconds);
    }

    /** Kurzbeschreibung des Modells für das Journal. */
    public String describe() {
        return String.format(Locale.ROOT, "Paket %.1f ms (%.0f Pakete/s), Reserve %.0f %%, Auffrischung %.1f Pakete/s je aktiver Lok, "
                        + "%d Wiederholung(en) je Befehl, Mindestbudget %.0f/s, Bucket %.0f, Halt darf %.0f überziehen",
                packetMillis, trackPacketsPerSecond(), reserveFraction * 100, refreshPacketsPerLocoPerSecond, repeatsPerCommand,
                minCommandsPerSecond, burstCommands, haltBorrowCommands);
    }

    long activeHoldNanos() { return activeHoldSeconds * 1_000_000_000L; }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
