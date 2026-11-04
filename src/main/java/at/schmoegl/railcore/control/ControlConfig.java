package at.schmoegl.railcore.control;

/**
 * Einstellungen des Steuerungs-Executors (Entscheidung 23). Alle Grenzen sind konfigurierbar und werden beim
 * Anlegen geprüft; unplausible Werte werden abgelehnt statt still korrigiert.
 *
 * @param tickMillis               Rechentakt des Executors (Standard 50 ms = 20×/s)
 * @param watchdogToleranceMillis  zulässige Verspätung über einen Takt hinaus, danach löst der Executor-Watchdog
 *                                 Nothalt aus (Standard 200 ms, also kein vollständiger Takt seit 250 ms)
 * @param watchdogCheckMillis      Prüfintervall des Executor-Watchdogs
 * @param commWarnMillis           Verbindung zur Zentrale „verspätet“ (Lastenheft 7: 3 s)
 * @param commLostMillis           Verbindung zur Zentrale „verloren“ (Lastenheft 7: 5 s)
 * @param feedbackReleaseMillis    Freimeldeverzögerung der Rückmelder
 * @param flickerWarnThreshold     ab so vielen Flacker-Wechseln wird gewarnt
 * @param queueCapacity            Länge der Ereigniswarteschlange; Überlauf führt zu Nothalt und Störung
 */
public record ControlConfig(int tickMillis, int watchdogToleranceMillis, int watchdogCheckMillis,
                            int commWarnMillis, int commLostMillis, int feedbackReleaseMillis,
                            int flickerWarnThreshold, int queueCapacity) {

    public static ControlConfig defaults() {
        return new ControlConfig(50, 200, 20, 3_000, 5_000, 500, 5, 10_000);
    }

    public ControlConfig {
        require(tickMillis >= 5 && tickMillis <= 1_000, "Takt muss zwischen 5 und 1000 ms liegen: " + tickMillis);
        require(watchdogToleranceMillis >= tickMillis, "Watchdog-Toleranz muss mindestens einen Takt betragen: " + watchdogToleranceMillis);
        require(watchdogToleranceMillis <= 5_000, "Watchdog-Toleranz über 5 s ist nicht zulässig: " + watchdogToleranceMillis);
        require(watchdogCheckMillis >= 1 && watchdogCheckMillis <= watchdogToleranceMillis,
                "Watchdog-Prüfintervall muss zwischen 1 ms und der Toleranz liegen: " + watchdogCheckMillis);
        require(commWarnMillis > 0 && commWarnMillis < commLostMillis, "Verbindungswarnung muss vor dem Verbindungsverlust liegen");
        require(feedbackReleaseMillis >= 0, "Freimeldeverzögerung darf nicht negativ sein");
        require(flickerWarnThreshold >= 1, "Flacker-Schwellwert muss mindestens 1 sein");
        require(queueCapacity >= 16, "Warteschlange zu klein: " + queueCapacity);
    }

    public ControlConfig withTickMillis(int v) {
        return new ControlConfig(v, watchdogToleranceMillis, watchdogCheckMillis, commWarnMillis, commLostMillis,
                feedbackReleaseMillis, flickerWarnThreshold, queueCapacity);
    }

    public ControlConfig withWatchdog(int toleranceMillis, int checkMillis) {
        return new ControlConfig(tickMillis, toleranceMillis, checkMillis, commWarnMillis, commLostMillis,
                feedbackReleaseMillis, flickerWarnThreshold, queueCapacity);
    }

    public ControlConfig withQueueCapacity(int v) {
        return new ControlConfig(tickMillis, watchdogToleranceMillis, watchdogCheckMillis, commWarnMillis, commLostMillis,
                feedbackReleaseMillis, flickerWarnThreshold, v);
    }

    long tickNanos() { return tickMillis * 1_000_000L; }

    /** Ab dieser Zeit ohne vollständigen Takt gilt der Executor als hängend. */
    long stallNanos() { return (tickMillis + (long) watchdogToleranceMillis) * 1_000_000L; }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
