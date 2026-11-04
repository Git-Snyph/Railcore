package at.schmoegl.railcore.control;

/**
 * Einstellungen der Überwachungen, die Störungen ohne Gleisgraph erkennen (Lastenheft 7, Schritt A aus
 * Entscheidung 35). Alle Werte konfigurierbar und beim Anlegen geprüft.
 *
 * @param detectorQueryMillis      Abstand der regelmäßigen Melderabfrage für die Erkennung stummer Melder
 *                                 (einzelner Melder, ganzer CAN-Bereich); 0 = keine regelmäßige Abfrage. Wirkt nur,
 *                                 wenn die Zentrale die Melderabfrage erkennbar unterstützt
 *                                 ({@link at.schmoegl.railcore.core.StationCapabilities.Feature#DETECTOR_STATUS_QUERY})
 * @param detectorSilenceMillis    ohne Meldung so lange gilt ein Melder als stumm (mindestens zwei Abfrageabstände)
 * @param recheckAttempts          so viele erneute Abfragen nach Rückkehr der Versorgung, bis zwei übereinstimmende
 *                                 Antworten vorliegen; danach bleibt der Melder „unbekannt“ (Warnung)
 * @param locoEchoGraceMillis      so lange nach einem Fahrbefehl gilt eine Lokmeldung mit dem vorher gesendeten Wert
 *                                 noch als verspätetes Echo, nicht als Abweichung
 */
public record SupervisionConfig(int detectorQueryMillis, int detectorSilenceMillis, int recheckAttempts,
                                int locoEchoGraceMillis) {

    /** Standard: Abfrage alle 2 s, stumm nach 5 s, 5 Versuche zum Neueinlesen, Echo-Spielraum 500 ms. */
    public static SupervisionConfig defaults() { return new SupervisionConfig(2_000, 5_000, 5, 500); }

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
    }

    public SupervisionConfig withDetectorQuery(int queryMillis, int silenceMillis) {
        return new SupervisionConfig(queryMillis, silenceMillis, recheckAttempts, locoEchoGraceMillis);
    }

    public SupervisionConfig withLocoEchoGraceMillis(int v) {
        return new SupervisionConfig(detectorQueryMillis, detectorSilenceMillis, recheckAttempts, v);
    }

    long detectorQueryNanos() { return detectorQueryMillis * 1_000_000L; }

    long detectorSilenceNanos() { return detectorSilenceMillis * 1_000_000L; }

    long locoEchoGraceNanos() { return locoEchoGraceMillis * 1_000_000L; }
}
