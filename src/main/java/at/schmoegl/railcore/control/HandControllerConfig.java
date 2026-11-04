package at.schmoegl.railcore.control;

import java.util.Set;
import java.util.TreeSet;

/**
 * Abfrage der Handregler an der Zentrale über Stellvertreter-Adressen (Entscheidung 7, Lastenheft 7). Die Z21 meldet
 * Befehle von Handreglern weder per Broadcast noch an Abonnenten; RailCore fragt die Stellvertreter-Adressen deshalb
 * regelmäßig ab (an der Z21 per Prüfprogramm bestätigt).
 *
 * @param pollMillis       Abstand der Abfragen (Standard 250 ms)
 * @param staleAfterMillis ohne Antwort so lange gilt der gelesene Wert als veraltet (Standard 750 ms = drei Abfragen)
 * @param addresses        abzufragende Stellvertreter-Adressen, nur 9000–9999 (keine fest einprogrammierte Anzahl)
 */
public record HandControllerConfig(int pollMillis, int staleAfterMillis, Set<Integer> addresses) {

    public static final int PROXY_MIN = 9000, PROXY_MAX = 9999;

    /** Standard: 250 ms, veraltet nach 750 ms, keine Adresse eingetragen (nichts wird abgefragt). */
    public static HandControllerConfig defaults() { return new HandControllerConfig(250, 750, Set.of()); }

    public HandControllerConfig {
        if (pollMillis < 50 || pollMillis > 10_000)
            throw new IllegalArgumentException("Abfrageabstand der Handregler muss zwischen 50 und 10000 ms liegen: " + pollMillis);
        if (staleAfterMillis < 2 * pollMillis || staleAfterMillis > 60_000)
            throw new IllegalArgumentException("„Veraltet nach“ muss mindestens zwei Abfrageabstände und höchstens 60 s betragen: "
                    + staleAfterMillis);
        for (int a : addresses)
            if (a < PROXY_MIN || a > PROXY_MAX)
                throw new IllegalArgumentException("Handregler-Adresse " + a + " liegt nicht im Stellvertreter-Bereich 9000–9999");
        addresses = Set.copyOf(new TreeSet<>(addresses));
    }

    public HandControllerConfig withAddresses(Set<Integer> v) { return new HandControllerConfig(pollMillis, staleAfterMillis, v); }

    public HandControllerConfig withTiming(int poll, int staleAfter) { return new HandControllerConfig(poll, staleAfter, addresses); }

    long pollNanos() { return pollMillis * 1_000_000L; }

    long staleNanos() { return staleAfterMillis * 1_000_000L; }
}
