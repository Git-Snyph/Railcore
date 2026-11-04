package at.schmoegl.railcore.control;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unveränderliche Messwerte des {@link CommandScheduler} – entweder seit dem Start oder für ein Messfenster
 * (periodischer Bericht, Momentaufnahme). Zeiten in ms.
 *
 * @param budgetPerSecond      aktuelles Budget für neue Befehle je Sekunde
 * @param activeLocos          aktive Loks im Rechenmodell
 * @param backlog              aktuell wartende Befehle
 * @param maxBacklog           größter Rückstau im Zeitraum
 * @param utilization          Auslastung des Budgets im Zeitraum (gesendete Kosten / angebotene Kosten)
 * @param waits                Wartezeit je Klasse (vom ersten Einreihen bis zum Senden)
 * @param submitted            angenommene Befehle
 * @param sent                 gesendete Befehle
 * @param suppressed           nicht gesendet, weil unverändert gegenüber dem zuletzt gesendeten und bestätigten Wert
 * @param coalesced            zusammengefasst (ein wartender Wert wurde durch einen neueren ersetzt)
 * @param rejectedLowerPriority abgewiesen, weil ein höher eingestufter Befehl für dasselbe Ziel noch wartet
 * @param droppedAtDispatch    beim Senden verworfen (Sperre); Fahrbefehle dabei durch Halt ersetzt
 * @param droppedOnEmergency   beim Nothalt verworfene wartende Fahrbefehle
 * @param retransmissions      Neuübertragungen nach Neuverbinden
 * @param overloadEvents       Beginn einer Überlast im Zeitraum
 * @param overloaded           Überlast aktuell erkannt
 * @param overloadReasons      Befund der Überlast (leer, wenn keine)
 */
public record SchedulerMetrics(double budgetPerSecond, int activeLocos, int backlog, int maxBacklog, double utilization,
                               Map<CommandPriority, TimingStatistics.Summary> waits, long submitted, long sent,
                               long suppressed, long coalesced, long rejectedLowerPriority, long droppedAtDispatch,
                               long droppedOnEmergency, long retransmissions, long overloadEvents, boolean overloaded,
                               List<String> overloadReasons) {

    public static final SchedulerMetrics EMPTY = new SchedulerMetrics(0, 0, 0, 0, 0, Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0,
            false, List.of());

    public SchedulerMetrics {
        EnumMap<CommandPriority, TimingStatistics.Summary> m = new EnumMap<>(CommandPriority.class);
        m.putAll(waits);
        waits = Collections.unmodifiableMap(m);
        overloadReasons = List.copyOf(overloadReasons);
    }

    /** Wartezeit einer Klasse (leer, wenn keine Messwerte). */
    public TimingStatistics.Summary waitOf(CommandPriority p) {
        return waits.getOrDefault(p, TimingStatistics.Summary.EMPTY);
    }

    /** Text für Journal und Bericht. */
    public String format() {
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT,
                "Budget %.1f/s, aktive Loks %d, Rückstau %d (max %d), Auslastung %.0f %%, gesendet %d, unverändert %d, "
                        + "zusammengefasst %d, niedriger abgewiesen %d, beim Senden verworfen %d, beim Nothalt verworfen %d, "
                        + "Neuübertragungen %d, Überlast %s",
                budgetPerSecond, activeLocos, backlog, maxBacklog, utilization * 100, sent, suppressed, coalesced,
                rejectedLowerPriority, droppedAtDispatch, droppedOnEmergency, retransmissions,
                overloaded ? "ja" : (overloadEvents > 0 ? "zeitweise (" + overloadEvents + "×)" : "nein")));
        for (CommandPriority p : CommandPriority.QUEUED) {
            TimingStatistics.Summary s = waitOf(p);
            if (s.count() == 0) continue;
            b.append(String.format(Locale.ROOT, "; Wartezeit %s: n=%d, min %.1f, mittel %.1f, p95 %.1f, max %.1f ms",
                    p.german(), s.count(), s.minMs(), s.meanMs(), s.p95Ms(), s.maxMs()));
        }
        return b.toString();
    }
}
