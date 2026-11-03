package at.schmoegl.bahnsteuerung.layout;

import java.time.LocalTime;

/**
 * Lichtausgang (Zubehöradresse). on = Ausgang, der "ein" bedeutet.
 * onAt/offAt: optionale Schaltzeiten nach Modellzeit (z. B. Straßenbeleuchtung 19:30–06:15).
 */
public record Light(String id, String name, String group, AccessoryOutput on, AccessoryOutput off,
                    LocalTime onAt, LocalTime offAt) {}
