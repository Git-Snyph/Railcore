package at.schmoegl.railcore.safety;

/**
 * Zustand der Melderüberwachung je Melder (Lastenheft 7, „Ausfall der Rückmelder-Versorgung“). Nur {@link #NORMAL}
 * reicht Meldungen an die Entprellung weiter; in allen anderen Zuständen ist der Melder „unbekannt“.
 */
public enum DetectorCondition {
    /** Meldungen werden normal verarbeitet. */
    NORMAL,
    /** Versorgung ausgefallen (Gleisspannung bzw. Bezirk ohne Strom): der Melder kann nichts melden. */
    SUPPLY_LOST,
    /** Melder antwortet nicht mehr auf die regelmäßige Abfrage (einzelner Melder oder ganzer CAN-Bereich). */
    SILENT,
    /**
     * Versorgung bzw. Antwort ist zurück; der Melder bleibt „unbekannt“, bis eine erneute Abfrage und die
     * Plausibilitätsprüfung bestanden sind. Rückkehr gilt nie als Freimeldung.
     */
    RECHECK;

    public String german() {
        return switch (this) {
            case NORMAL -> "in Ordnung";
            case SUPPLY_LOST -> "Versorgung ausgefallen";
            case SILENT -> "antwortet nicht";
            case RECHECK -> "wird neu eingelesen";
        };
    }
}
