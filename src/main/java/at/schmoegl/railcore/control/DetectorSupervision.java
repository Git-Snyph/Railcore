package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.Contacts;
import at.schmoegl.railcore.core.StationCapabilities;
import at.schmoegl.railcore.diag.Journal;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.DetectorCondition;
import at.schmoegl.railcore.safety.FeedbackFilter;
import at.schmoegl.railcore.safety.OperatingState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Überwachung der Belegtmelder auf Ausfall (Lastenheft 7, „Ausfall der Rückmelder-Versorgung“). Läuft ausschließlich
 * auf dem Steuerungs-Thread und sitzt vor der Entprellung: jede Rohmeldung der Zentrale geht zuerst durch
 * {@link #accept}.
 * <ul>
 *   <li><b>Versorgung ausgefallen</b> ({@link #supplyLost}, ausgelöst durch Gleisspannung aus, Kurzschluss oder einen
 *       Bezirk ohne Strom; der 10808 wird aus der Gleisspannung versorgt): betroffene Melder werden „unbekannt“,
 *       der letzte gültige Zustand bleibt mit Zeitstempel sichtbar, Meldungen werden verworfen.</li>
 *   <li><b>Stumme Melder</b>: Unterstützt die Zentrale die Melderabfrage
 *       ({@link StationCapabilities.Feature#DETECTOR_STATUS_QUERY}) und ist die regelmäßige Abfrage eingeschaltet
 *       ({@link SupervisionConfig#detectorQueryMillis()}), gilt ein Melder ohne Meldung seit
 *       {@link SupervisionConfig#detectorSilenceMillis()} als stumm; das Journal fasst stumme Melder je CAN-Bereich
 *       (Modul) zusammen.</li>
 *   <li><b>Rückkehr</b> von Versorgung oder Antwort ist nie eine Freimeldung: der Melder bleibt „unbekannt“, RailCore
 *       fragt neu ab und gibt ihn erst frei, wenn zwei Antworten auf getrennte Abfragen übereinstimmen
 *       (Plausibilitätsprüfung ohne Gleisgraph; der Abgleich mit den erwarteten Zugpositionen folgt mit der
 *       Positionsverfolgung). Erst dann läuft die Meldung durch die Entprellung, unbekannt → frei also zusätzlich erst
 *       nach der Freimeldeverzögerung. Ohne Melderabfrage bleibt der Melder bis zum Neuverbinden „unbekannt“.</li>
 * </ul>
 * Bei Verbindungsverlust setzt {@link #reset()} die Überwachung zurück; alle Melder sind dann ohnehin „unbekannt“,
 * und die Zentrale liest beim Verbinden neu ein.
 */
final class DetectorSupervision {

    private static final class Watch {
        DetectorCondition condition = DetectorCondition.NORMAL;
        long lastSeenNanos = Long.MIN_VALUE;
        /** Nur Antworten nach diesem Zeitpunkt zählen beim Neueinlesen. */
        long awaitAfterNanos = Long.MIN_VALUE;
        Boolean candidate;
        int attempts;
        boolean gaveUp;
    }

    private final ControlExecutor executor;
    private final SupervisionConfig config;
    private final FeedbackFilter filter;
    private final Journal journal;
    private final Map<Integer, Watch> watches = new HashMap<>();
    private long activeSinceNanos = Long.MIN_VALUE;
    private long nextQueryNanos = Long.MIN_VALUE;
    private boolean noQueryReported;

    DetectorSupervision(ControlExecutor executor, SupervisionConfig config, FeedbackFilter filter) {
        this.executor = Objects.requireNonNull(executor);
        this.config = Objects.requireNonNull(config);
        this.filter = Objects.requireNonNull(filter);
        this.journal = executor.journal();
    }

    private Watch watch(int contact) { return watches.computeIfAbsent(contact, k -> new Watch()); }

    /** Überwachungszustand eines Melders. */
    DetectorCondition condition(int contact) {
        Watch w = watches.get(contact);
        return w == null ? DetectorCondition.NORMAL : w.condition;
    }

    /**
     * Rohmeldung der Zentrale prüfen.
     *
     * @return true, wenn die Meldung an die Entprellung weitergegeben werden darf
     */
    boolean accept(int contact, boolean occupied, long ts) {
        long now = executor.clock().nanoTime();
        Watch w = watch(contact);
        w.lastSeenNanos = now;
        switch (w.condition) {
            case NORMAL -> { return true; }
            case SUPPLY_LOST -> {
                journal.debug("melder", "DETECTOR_REPORT_IGNORED", "Meldung von " + Contacts.format(contact)
                        + " verworfen: Versorgung ausgefallen");
                return false;
            }
            case SILENT -> {
                journal.info("melder", "DETECTOR_ANSWERS_AGAIN", "Melder " + Contacts.format(contact)
                        + " meldet sich wieder – bleibt unbekannt, wird neu eingelesen (Rückkehr ist keine Freimeldung)");
                startRecheck(contact, w, now);
                return false;
            }
            case RECHECK -> { return recheckAnswer(contact, w, occupied, ts, now); }
        }
        return false;
    }

    private boolean recheckAnswer(int contact, Watch w, boolean occupied, long ts, long now) {
        if (ts <= w.awaitAfterNanos) return false;        // Antwort auf keine (neue) Abfrage
        if (w.candidate == null || w.candidate != occupied) {
            if (w.candidate != null)
                journal.info("melder", "DETECTOR_RECHECK_MISMATCH", "Melder " + Contacts.format(contact)
                        + ": Antworten beim Neueinlesen widersprechen sich – erneute Abfrage");
            w.candidate = occupied;
            requestAgain(contact, w, now);
            return false;
        }
        // zwei übereinstimmende Antworten auf getrennte Abfragen: wieder in Betrieb (über die Entprellung)
        setCondition(contact, w, DetectorCondition.NORMAL);
        w.candidate = null;
        w.attempts = 0;
        w.gaveUp = false;
        journal.info("melder", "DETECTOR_RECHECKED", "Melder " + Contacts.format(contact) + " neu eingelesen ("
                + (occupied ? "belegt" : "frei") + ", zweimal übereinstimmend) – wieder in Betrieb"
                + (occupied ? "" : "; frei erst nach der Freimeldeverzögerung"));
        return true;
    }

    private void requestAgain(int contact, Watch w, long now) {
        w.attempts++;
        if (w.attempts > config.recheckAttempts()) {
            if (!w.gaveUp) {
                w.gaveUp = true;
                journal.warn("melder", "DETECTOR_RECHECK_FAILED", "Melder " + Contacts.format(contact) + " nach "
                        + config.recheckAttempts() + " Abfragen nicht plausibel – bleibt unbekannt, bitte prüfen");
            }
            return;
        }
        w.awaitAfterNanos = now;
        query("Neueinlesen " + Contacts.format(contact));
    }

    private void startRecheck(int contact, Watch w, long now) {
        setCondition(contact, w, DetectorCondition.RECHECK);
        w.candidate = null;
        w.attempts = 0;
        w.gaveUp = false;
        w.awaitAfterNanos = now;
        w.attempts++;
        query("Neueinlesen " + Contacts.format(contact));
    }

    /** Versorgung der Melder ausgefallen (Gleisspannung, Kurzschluss, Bezirk ohne Strom). */
    void supplyLost(Collection<Integer> contacts, String why) {
        List<Integer> changed = new ArrayList<>();
        for (int c : contacts) {
            Watch w = watch(c);
            if (w.condition == DetectorCondition.SUPPLY_LOST) continue;
            setCondition(c, w, DetectorCondition.SUPPLY_LOST);
            w.candidate = null;
            filter.forget(c);
            changed.add(c);
        }
        if (changed.isEmpty()) return;
        journal.warn("melder", "DETECTOR_SUPPLY_LOST", changed.size() + " Melder ohne Versorgung (" + why + "): "
                + describe(changed) + " – „unbekannt“, letzter gültiger Zustand bleibt sichtbar");
    }

    /** Versorgung wieder da: betroffene Melder werden neu eingelesen, nie als frei angenommen. */
    void supplyRestored(Collection<Integer> contacts, String why) {
        long now = executor.clock().nanoTime();
        List<Integer> changed = new ArrayList<>();
        for (int c : contacts) {
            Watch w = watches.get(c);
            if (w == null || w.condition != DetectorCondition.SUPPLY_LOST) continue;
            setCondition(c, w, DetectorCondition.RECHECK);
            w.candidate = null;
            w.attempts = 1;
            w.gaveUp = false;
            w.awaitAfterNanos = now;
            changed.add(c);
        }
        if (changed.isEmpty()) return;
        journal.info("melder", "DETECTOR_SUPPLY_RESTORED", "Versorgung zurück (" + why + ") für " + changed.size()
                + " Melder: " + describe(changed) + " – bleiben unbekannt bis zur erneuten Abfrage und Plausibilitätsprüfung");
        query("Versorgung zurück");
    }

    /** Verbindungsverlust: Überwachung zurücksetzen (alle Melder sind ohnehin „unbekannt“). */
    void reset() {
        for (var e : watches.entrySet()) {
            Watch w = e.getValue();
            if (w.condition != DetectorCondition.NORMAL) setCondition(e.getKey(), w, DetectorCondition.NORMAL);
            w.candidate = null;
            w.attempts = 0;
            w.gaveUp = false;
            w.lastSeenNanos = Long.MIN_VALUE;
        }
        activeSinceNanos = Long.MIN_VALUE;
        nextQueryNanos = Long.MIN_VALUE;
    }

    /** Takt: regelmäßige Abfrage, stumme Melder erkennen, ausbleibende Antworten beim Neueinlesen wiederholen. */
    void onTick(long now) {
        boolean usable = linkUsable();
        boolean canQuery = usable && queryAvailable();
        if (canQuery) retryRechecks(now);
        if (!canQuery || config.detectorQueryMillis() == 0) {
            activeSinceNanos = Long.MIN_VALUE;
            return;
        }
        if (activeSinceNanos == Long.MIN_VALUE) {
            activeSinceNanos = now;
            nextQueryNanos = now;
            journal.info("melder", "DETECTOR_SUPERVISION_ON", "Melderüberwachung aktiv: Abfrage alle "
                    + config.detectorQueryMillis() + " ms, stumm nach " + config.detectorSilenceMillis() + " ms");
        }
        if (now >= nextQueryNanos) {
            query("regelmäßig");
            nextQueryNanos += config.detectorQueryNanos();
            if (nextQueryNanos <= now) nextQueryNanos = now + config.detectorQueryNanos();
        }
        detectSilence(now);
    }

    private void detectSilence(long now) {
        List<Integer> silent = new ArrayList<>();
        for (int c : executor.plant().knownContacts()) {
            Watch w = watch(c);
            if (w.condition != DetectorCondition.NORMAL) continue;
            long since = Math.max(w.lastSeenNanos, activeSinceNanos);
            if (now - since >= config.detectorSilenceNanos()) {
                setCondition(c, w, DetectorCondition.SILENT);
                filter.forget(c);
                silent.add(c);
            }
        }
        if (silent.isEmpty()) return;
        journal.warn("melder", "DETECTOR_SILENT", describeSilent(silent) + " – seit mindestens "
                + config.detectorSilenceMillis() + " ms keine Meldung: „unbekannt“, letzter gültiger Zustand bleibt sichtbar");
    }

    private void retryRechecks(long now) {
        long timeout = Math.max(config.detectorSilenceNanos(), 1_000_000_000L);
        boolean again = false;
        for (var e : watches.entrySet()) {
            Watch w = e.getValue();
            if (w.condition != DetectorCondition.RECHECK || w.gaveUp) continue;
            if (now - w.awaitAfterNanos >= timeout) {
                requestAgainQuietly(e.getKey(), w, now);
                again = true;
            }
        }
        if (again) query("Neueinlesen ohne Antwort wiederholt");
    }

    private void requestAgainQuietly(int contact, Watch w, long now) {
        w.attempts++;
        if (w.attempts > config.recheckAttempts()) {
            w.gaveUp = true;
            journal.warn("melder", "DETECTOR_RECHECK_FAILED", "Melder " + Contacts.format(contact) + " antwortet nach "
                    + config.recheckAttempts() + " Abfragen nicht – bleibt unbekannt, bitte prüfen");
            return;
        }
        w.awaitAfterNanos = now;
    }

    private void query(String why) {
        if (!linkUsable()) return;
        if (!queryAvailable()) {
            if (!noQueryReported) {
                noQueryReported = true;
                journal.warn("melder", "DETECTOR_QUERY_UNAVAILABLE", "Zentrale bietet keine Melderabfrage ("
                        + executor.plant().capabilities().describe() + ") – betroffene Melder bleiben bis zum "
                        + "Neuverbinden „unbekannt“ (" + why + ")");
            }
            return;
        }
        executor.commands().requestDetectorStatus(CommandPriority.SAFETY);
    }

    private boolean queryAvailable() {
        return executor.plant().capabilities().has(StationCapabilities.Feature.DETECTOR_STATUS_QUERY);
    }

    private boolean linkUsable() {
        PlantState p = executor.plant();
        return p.operatingState() != OperatingState.OFFLINE && p.communication() == CommunicationWatchdog.Status.OK
                && executor.scheduler().linkUp();
    }

    private void setCondition(int contact, Watch w, DetectorCondition c) {
        w.condition = c;
        executor.plant().setDetectorCondition(contact, c);
    }

    /** Stumme Melder je CAN-Bereich (Modul) zusammenfassen. */
    private String describeSilent(List<Integer> newlySilent) {
        Map<Integer, List<Integer>> byModule = new TreeMap<>();
        for (int c : newlySilent) byModule.computeIfAbsent(Contacts.module(c), k -> new ArrayList<>()).add(c);
        Map<Integer, Integer> knownPerModule = new HashMap<>();
        boolean allKnownSilent = true;
        for (int c : executor.plant().knownContacts()) {
            knownPerModule.merge(Contacts.module(c), 1, Integer::sum);
            if (condition(c) == DetectorCondition.NORMAL) allKnownSilent = false;
        }
        List<String> parts = new ArrayList<>();
        for (var e : byModule.entrySet()) {
            int m = e.getKey();
            long silentInModule = executor.plant().knownContacts().stream()
                    .filter(c -> Contacts.module(c) == m && condition(c) == DetectorCondition.SILENT).count();
            if (silentInModule == knownPerModule.getOrDefault(m, 0) && silentInModule > 1)
                parts.add("CAN-Bereich Modul " + m + " (alle " + silentInModule + " Melder)");
            else parts.add(describe(e.getValue()));
        }
        String text = String.join(", ", parts);
        return allKnownSilent ? "Alle Melder stumm (ganzer CAN-Bus oder Versorgung): " + text : "Melder stumm: " + text;
    }

    private static String describe(Collection<Integer> contacts) {
        List<String> s = new ArrayList<>();
        for (int c : new TreeSet<>(contacts)) s.add(Contacts.format(c));
        return String.join(", ", s);
    }
}
