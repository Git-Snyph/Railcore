package at.schmoegl.railcore.sim;

import java.util.Objects;
import java.util.Random;

/**
 * Simulierter Meldepunkt (Belegtmelder-Grenze). Spricht an, wenn die Zugspitze die wahre Auslösestelle erreicht;
 * diese streut je Lauf um die Nennposition (Messstreuung des Melders). Die Meldung erreicht die Steuerung nach der
 * Ansprechverzögerung (je Ereignis neu gezogen). Die Steuerung kennt nur Nennposition, mittlere Verzögerung und
 * Streuungen – nie die gezogenen Werte.
 */
public final class SimDetector {

    private final int contact;
    private final double nominalPositionMm;
    private final Distribution delayMillis;
    private final Distribution positionSpreadMm;
    private final int locoAddress;

    private double triggerPositionMm;
    private boolean triggered;
    private long crossingNanos = Long.MIN_VALUE;
    private long deliveredNanos = Long.MIN_VALUE;

    public SimDetector(int contact, double nominalPositionMm, Distribution delayMillis, Distribution positionSpreadMm) {
        this(contact, nominalPositionMm, delayMillis, positionSpreadMm, ANY_LOCO);
    }

    /**
     * Meldepunkt, der nur von einer bestimmten Lok ausgelöst wird (mehrere Loks auf eigener Wegkoordinate in einer
     * Welt, z. B. Lasttests); {@link #ANY_LOCO} = jede Lok.
     */
    public SimDetector(int contact, double nominalPositionMm, Distribution delayMillis, Distribution positionSpreadMm,
                       int locoAddress) {
        this.locoAddress = locoAddress;
        this.contact = contact;
        this.nominalPositionMm = nominalPositionMm;
        this.delayMillis = Objects.requireNonNull(delayMillis);
        this.positionSpreadMm = Objects.requireNonNull(positionSpreadMm);
        this.triggerPositionMm = nominalPositionMm;
    }

    /** Jede Lok löst den Meldepunkt aus. */
    public static final int ANY_LOCO = -1;

    public int contact() { return contact; }

    /** Lok, die den Meldepunkt auslöst, oder {@link #ANY_LOCO}. */
    public int locoAddress() { return locoAddress; }

    public double nominalPositionMm() { return nominalPositionMm; }

    public Distribution delayMillis() { return delayMillis; }

    public Distribution positionSpreadMm() { return positionSpreadMm; }

    /** Wahre Auslösestelle für diesen Lauf ziehen. */
    void arm(Random r) {
        triggerPositionMm = nominalPositionMm + positionSpreadMm.sample(r);
        triggered = false;
    }

    public double triggerPositionMm() { return triggerPositionMm; }

    public boolean triggered() { return triggered; }

    /** Wahrer Zeitpunkt des Überfahrens. */
    public long crossingNanos() { return crossingNanos; }

    /** Zeitpunkt, zu dem die Meldung bei der Zentrale/Steuerung ankam. */
    public long deliveredNanos() { return deliveredNanos; }

    void markTriggered(long crossing) {
        triggered = true;
        crossingNanos = crossing;
    }

    void markDelivered(long t) { deliveredNanos = t; }
}
