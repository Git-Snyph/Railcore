package at.schmoegl.railcore.core;

/** Ereignisse einer Zentrale. Alle Methoden sind optional. Aufruf erfolgt aus Hintergrund-Threads. */
public interface CommandStationListener {

    default void onConnectionChanged(boolean connected, String info) {}

    /** Jede empfangene Nachricht der Zentrale (Lebenszeichen für den Watchdog). */
    default void onHeartbeat(long timestampNanos) {}

    default void onTrackPower(TrackPower state) {}

    /**
     * Zustand eines Booster-Bezirks hat sich geändert (Kurzschluss, Überlast, aus, ein), gemeldet von Zentrale, Booster
     * oder Belegtmelder. Ein Adapter meldet das nur, wenn er es an der Zentrale tatsächlich erkennt.
     *
     * @param district Nummer des Bezirks (Zuordnung laut Gleisplan)
     */
    default void onPowerDistrict(int district, DistrictPower state, long timestampNanos) {}

    default void onLocoState(LocoState state) {}

    default void onTurnoutPosition(int address, TurnoutPosition position) {}

    /**
     * Rückmelder hat sich geändert.
     *
     * @param contact   fortlaufende Kontaktnummer ab 1 (siehe {@link Contacts})
     * @param occupied  true = belegt
     * @param timestampNanos Zeitpunkt des Empfangs (System.nanoTime)
     */
    default void onFeedback(int contact, boolean occupied, long timestampNanos) {}

    /**
     * RailCom: Lokadressen, die ein Rückmelder im Abschnitt erkennt (leere Liste = keine Lok erkannt).
     * Damit ist bekannt, WELCHER Zug einen Abschnitt belegt.
     */
    default void onRailComAddresses(int contact, java.util.List<Integer> addresses, long timestampNanos) {}

    /** Ergebnis einer CV-Lese- oder Schreiboperation. */
    default void onCvResult(int cv, int value) {}

    /** CV-Operation fehlgeschlagen (keine Antwort vom Decoder oder Kurzschluss). */
    default void onCvFailed(String reason) {}

    /** Fähigkeiten der Zentrale wurden (neu) erkannt, z. B. nach Abfrage der Firmware. */
    default void onCapabilities(StationCapabilities capabilities) {}

    default void onError(String message, Throwable cause) {}
}
