package at.schmoegl.railcore.sim;

import at.schmoegl.railcore.util.TimeSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Ereignisgesteuerte Simulationswelt mit {@link TimeSource.Manual}: Loks, Meldepunkte und zeitlich eingeplante
 * Ereignisse (Ankunft von Befehlen, Melderereignisse, Lebenszeichen). Die Uhr springt immer genau zum nächsten
 * Ereignis – dem der Welt oder dem nächsten fälligen Zeitpunkt der Steuerung ({@link Controller}) –, damit
 * eingeplante Befehle exakt zu ihrem Zeitpunkt laufen. Deterministisch über den Seed des {@link Random}.
 * <p>
 * Bewusst schlank: keine Gleistopologie, eine Wegkoordinate; der spätere Simulator baut darauf auf.
 * Nicht thread-sicher; Welt und Steuerung laufen im selben Thread.
 */
public final class SimWorld {

    /** Steuerung, die von der Welt angetrieben wird (z. B. ControlExecutor: nextDueNanos/pump). */
    public interface Controller {
        long nextDueNanos();

        void pump();
    }

    /** Empfänger der Melderereignisse (die simulierte Zentrale). */
    @FunctionalInterface
    public interface FeedbackSink {
        void feedback(int contact, boolean occupied);
    }

    private record Event(long time, long seq, Runnable action) {}

    private final TimeSource.Manual clock;
    private final Random random;
    private final PriorityQueue<Event> events = new PriorityQueue<>(
            Comparator.comparingLong(Event::time).thenComparingLong(Event::seq));
    private final Map<Integer, SimLoco> locos = new LinkedHashMap<>();
    private final List<SimDetector> detectors = new ArrayList<>();
    private long seq;
    private FeedbackSink feedbackSink = (c, o) -> { };

    public SimWorld(TimeSource.Manual clock, Random random) {
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * Zufallsquelle für einen Lauf. Der Seed wird mit SplitMix64 gemischt: {@code new Random(n)} und
     * {@code new Random(n + 1)} liefern sonst stark korrelierte erste Werte, und Serien mit fortlaufenden Seeds
     * wären nicht unabhängig.
     */
    public static Random randomForRun(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new Random(z ^ (z >>> 31));
    }

    public TimeSource.Manual clock() { return clock; }

    public Random random() { return random; }

    public long now() { return clock.nanoTime(); }

    public void setFeedbackSink(FeedbackSink sink) { this.feedbackSink = Objects.requireNonNull(sink); }

    public SimLoco addLoco(SimLoco loco) {
        locos.put(loco.address(), loco);
        return loco;
    }

    public SimLoco loco(int address) { return locos.get(address); }

    public Iterable<SimLoco> locos() { return locos.values(); }

    /** Meldepunkt hinzufügen; seine wahre Auslösestelle wird dabei gezogen. */
    public SimDetector addDetector(SimDetector d) {
        d.arm(random);
        detectors.add(d);
        return d;
    }

    public List<SimDetector> detectors() { return List.copyOf(detectors); }

    /** Ereignis zu einem festen Zeitpunkt (nicht in der Vergangenheit). */
    public void at(long timeNanos, Runnable action) {
        events.add(new Event(Math.max(timeNanos, now()), seq++, Objects.requireNonNull(action)));
    }

    public void after(long delayNanos, Runnable action) { at(now() + Math.max(0, delayNanos), action); }

    /** Nächster Zeitpunkt, an dem in der Welt etwas geschieht (Ereignis oder Überfahren eines Meldepunkts). */
    public long nextEventNanos() {
        long next = events.isEmpty() ? Long.MAX_VALUE : events.peek().time();
        long now = now();
        for (SimDetector d : detectors) {
            if (d.triggered()) continue;
            for (SimLoco l : locos.values()) next = Math.min(next, l.timeOfPosition(d.triggerPositionMm(), now));
        }
        return next;
    }

    /** Welt bis {@code t} fortschreiben; alle Ereignisse und Überfahrten dazwischen zu ihrem exakten Zeitpunkt. */
    public void advanceTo(long t) {
        while (true) {
            long next = nextEventNanos();
            if (next > t) break;
            setClock(next);
            checkCrossings();
            while (!events.isEmpty() && events.peek().time() <= now()) events.poll().action().run();
        }
        setClock(t);
    }

    /**
     * Treibt Welt und Steuerung, bis {@code done} gilt oder die Simulationszeit {@code untilNanos} erreicht ist.
     *
     * @return true, wenn {@code done} erfüllt wurde
     */
    public boolean run(Controller controller, BooleanSupplier done, long untilNanos) {
        controller.pump();
        while (!done.getAsBoolean()) {
            long t = Math.min(nextEventNanos(), controller.nextDueNanos());
            if (t > untilNanos) {
                advanceTo(untilNanos);
                controller.pump();
                return done.getAsBoolean();
            }
            advanceTo(Math.max(t, now()));
            controller.pump();
        }
        return true;
    }

    private void checkCrossings() {
        long now = now();
        for (SimDetector d : detectors) {
            if (d.triggered()) continue;
            for (SimLoco l : locos.values()) {
                if (l.position(now) >= d.triggerPositionMm() - 1e-6) {
                    d.markTriggered(now);
                    long delay = Math.round(d.delayMillis().sample(random) * 1e6);
                    after(delay, () -> {
                        d.markDelivered(now());
                        feedbackSink.feedback(d.contact(), true);
                    });
                    break;
                }
            }
        }
    }

    private void setClock(long t) {
        long now = now();
        if (t > now) clock.advanceNanos(t - now);
    }
}
