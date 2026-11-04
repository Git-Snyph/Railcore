package at.schmoegl.railcore.control;

/**
 * Einstellungen der Überwachungen, die Störungen ohne Gleisgraph erkennen (Lastenheft 7, Schritt A aus
 * Entscheidungen 35 und 37). Alle Werte konfigurierbar und beim Anlegen geprüft; nichts davon ist fest einprogrammiert.
 *
 * @param detectorQueryMillis      Abstand der regelmäßigen Melderabfrage für die Erkennung stummer Melder
 *                                 (einzelner Melder, ganzer CAN-Bereich); 0 = keine regelmäßige Abfrage. Ab Werk an
 *                                 (2 s, Entscheidung 37), wirkt aber nur, wenn die Zentrale die Melderabfrage
 *                                 erkennbar unterstützt
 *                                 ({@link at.schmoegl.railcore.core.StationCapabilities.Feature#DETECTOR_STATUS_QUERY})
 * @param detectorSilenceMillis    ohne Meldung so lange gilt ein Melder als stumm (mindestens zwei Abfrageabstände;
 *                                 ab Werk 5 s)
 * @param recheckAttempts          so viele erneute Abfragen nach Rückkehr der Versorgung, bis zwei übereinstimmende
 *                                 Antworten vorliegen; danach bleibt der Melder „unbekannt“ (Warnung)
 * @param locoEchoGraceMillis      so lange nach einem Fahrbefehl gilt eine Lokmeldung mit dem vorher gesendeten Wert
 *                                 noch als verspätetes Echo, nicht als Abweichung
 * @param conflictDivergences      so viele abweichende Lokmeldungen derselben Lok innerhalb von
 *                                 {@code conflictWindowMillis} gelten als Wechselspiel zwischen Handregler und RailCore:
 *                                 Halt und Bedienerentscheid statt erneutem Senden (Entscheidung 37, ab Werk 3)
 * @param conflictWindowMillis     Zeitfenster dafür (ab Werk 10 s)
 */
public record SupervisionConfig(int detectorQueryMillis, int detectorSilenceMillis, int recheckAttempts,
                                int locoEchoGraceMillis, int conflictDivergences, int conflictWindowMillis) {

    /**
     * Standard (Entscheidung 37): Melderabfrage alle 2 s, stumm nach 5 s, 5 Versuche zum Neueinlesen, Echo-Spielraum
     * 500 ms, Halt nach 3 Abweichungen in 10 s.
     */
    public static SupervisionConfig defaults() { return new SupervisionConfig(2_000, 5_000, 5, 500, 3, 10_000); }

    public SupervisionConfig {
        if (detectorQueryMillis < 0 || detectorQueryMillis > 60_000)
            throw new IllegalArgumentException("Abstand der Melderabfrage muss zwischen 0 und 60000 ms liegen: " + detectorQueryMillis);
        if (detectorQueryMillis > 0 && detectorSilenceMillis < 2 * detectorQueryMillis)
            throw new IllegalArgumentException("„Melder stumm nach“ muss mindestens zwei Abfrageabstände betragen: " + detectorSilenceMillis);
        if (detectorSilenceMillis > 600_000)
            throw new IllegalArgumentException("„Melder stumm nach“ über 10 min ist nicht zulässig: " + detectorSilenceMillis);
        if (recheckAttempts < 2 || recheckAttempts > 100)
            throw new IllegalArgumentException("Versuche zum Neueinlesen müssen zwischen 2 und 100 liegen: " + recheckAttempts);
        if (locoEchoGraceMillis < 0 || locoEchoGraceMillis > 10_000)
            throw new IllegalArgumentException("Echo-Spielraum muss zwischen 0 und 10000 ms liegen: " + locoEchoGraceMillis);
        if (conflictDivergences < 1 || conflictDivergences > 100)
            throw new IllegalArgumentException("Abweichungen bis zum Halt müssen zwischen 1 und 100 liegen: " + conflictDivergences);
        if (conflictWindowMillis < 1_000 || conflictWindowMillis > 600_000)
            throw new IllegalArgumentException("Zeitfenster für Abweichungen muss zwischen 1 s und 10 min liegen: " + conflictWindowMillis);
    }

    public SupervisionConfig withDetectorQuery(int queryMillis, int silenceMillis) {
        return new SupervisionConfig(queryMillis, silenceMillis, recheckAttempts, locoEchoGraceMillis, conflictDivergences,
                conflictWindowMillis);
    }

    public SupervisionConfig withLocoEchoGraceMillis(int v) {
        return new SupervisionConfig(detectorQueryMillis, detectorSilenceMillis, recheckAttempts, v, conflictDivergences,
                conflictWindowMillis);
    }

    public SupervisionConfig withConflict(int divergences, int windowMillis) {
        return new SupervisionConfig(detectorQueryMillis, detectorSilenceMillis, recheckAttempts, locoEchoGraceMillis,
                divergences, windowMillis);
    }

    long detectorQueryNanos() { return detectorQueryMillis * 1_000_000L; }

    long detectorSilenceNanos() { return detectorSilenceMillis * 1_000_000L; }

    long locoEchoGraceNanos() { return locoEchoGraceMillis * 1_000_000L; }

    long conflictWindowNanos() { return conflictWindowMillis * 1_000_000L; }
}
