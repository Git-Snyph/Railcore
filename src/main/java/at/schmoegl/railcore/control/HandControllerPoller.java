package at.schmoegl.railcore.control;

import at.schmoegl.railcore.core.LocoState;
import at.schmoegl.railcore.safety.CommunicationWatchdog;
import at.schmoegl.railcore.safety.OperatingState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Zeitabhängige Aufgabe des Steuerungs-Executors: fragt die Stellvertreter-Adressen der Handregler im festen Abstand
 * ab ({@link HandControllerConfig}) und trägt die Antworten in den Anlagenzustand ein. Läuft ausschließlich auf dem
 * Steuerungs-Thread, eingeplant über {@link ControlExecutor#schedule} (fester Abstand, kein Aufholen nach Verzug).
 * <p>
 * <b>Einstufung:</b> Die Abfragen laufen über den {@link CommandScheduler} in der Klasse <i>Hand</i> mit Kosten 0:
 * Eine Lokinfo-Abfrage erzeugt kein DCC-Paket auf dem Gleis, verbraucht also kein Gleisbudget; sie wartet aber hinter
 * jedem höher eingestuften Befehl, dem Tokens fehlen. Nicht Komfort, weil Komfort bei Überlast ganz zurückgestellt
 * wird – dann sähe RailCore ein Zurückdrehen des Handreglers auf Halt nicht mehr.
 * <p>
 * <b>Im Zweifel halt:</b> Handregler starten „unbekannt“; bleibt die Antwort länger als {@code staleAfterMillis}
 * aus, gilt der Wert als veraltet ({@code current = false}). Die spätere Handsteuerung darf aus einem veralteten oder
 * unbekannten Wert nie eine Fahrt ableiten. Solange keine Verbindung besteht, wird nicht abgefragt.
 * <p>
 * Stellvertreter mit Fahrstufe &gt; 0 (oder unbekanntem Zustand) werden dem Befehlsplaner als fremd aktive Loks gemeldet: Die Zentrale frischt
 * sie auf dem Gleis auf, das kostet DCC-Kapazität.
 */
final class HandControllerPoller {

    private final ControlExecutor executor;
    private final HandControllerConfig config;
    private final Map<Integer, Long> lastReply = new HashMap<>();
    private long nextDueNanos;
    private boolean installed;

    HandControllerPoller(ControlExecutor executor, HandControllerConfig config) {
        this.executor = Objects.requireNonNull(executor);
        this.config = Objects.requireNonNull(config);
    }

    /** Auf dem Steuerungs-Thread aufrufen: Adressen eintragen („unbekannt“), Beobachter anmelden, erste Abfrage. */
    void install() {
        if (installed) return;
        installed = true;
        if (config.addresses().isEmpty()) {
            executor.journal().info("hand", "HAND_POLL_OFF", "Keine Handregler-Adressen eingetragen – keine Abfrage");
            return;
        }
        executor.plant().defineHandControllers(config.addresses());
        executor.addLocoStateObserver(this::onLocoState);
        executor.journal().info("hand", "HAND_POLL_ON", "Handregler-Abfrage alle " + config.pollMillis() + " ms für "
                + config.addresses().size() + " Stellvertreter-Adressen " + List.copyOf(config.addresses())
                + " (Klasse Hand, kein DCC-Budget)");
        nextDueNanos = executor.clock().nanoTime();
        poll();
    }

    private void poll() {
        long now = executor.clock().nanoTime();
        PlantState plant = executor.plant();
        for (int a : config.addresses()) {
            var view = plant.handController(a).orElseThrow();
            Long last = lastReply.get(a);
            if (view.current() && (last == null || now - last > config.staleNanos()))
                plant.setHandController(a, view.state(), false);
        }
        updateExternalActive();
        if (linkUsable(plant) && executor.scheduler().linkUp()) {
            for (int a : config.addresses()) executor.commands().requestLocoState(a, CommandPriority.MANUAL);
        }
        nextDueNanos += config.pollNanos();
        if (nextDueNanos <= now) nextDueNanos = now + config.pollNanos();   // nach Verzug neu aufsetzen, nicht nachholen
        executor.schedule(nextDueNanos, "Handregler-Abfrage", this::poll);
    }

    /**
     * Stellvertreter, die die Zentrale auffrischt, kosten DCC-Kapazität. Vorsichtig gezählt: auch ein unbekannter
     * Zustand zählt als aktiv (kleineres Budget ist die sichere Seite).
     */
    private void updateExternalActive() {
        int active = 0;
        for (int a : config.addresses()) {
            var view = executor.plant().handController(a).orElseThrow();
            if (view.state() == null || view.state().speedStep() > 0) active++;
        }
        executor.scheduler().setExternalActiveLocos(active);
    }

    private static boolean linkUsable(PlantState plant) {
        return plant.operatingState() != OperatingState.OFFLINE
                && plant.communication() != CommunicationWatchdog.Status.LOST;
    }

    private void onLocoState(LocoState s) {
        if (!config.addresses().contains(s.address())) return;
        lastReply.put(s.address(), executor.clock().nanoTime());
        executor.plant().setHandController(s.address(), s, true);
        updateExternalActive();
    }
}
