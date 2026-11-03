package at.schmoegl.bahnsteuerung.layout;

import java.time.LocalTime;
import java.util.List;

/**
 * Fahrplan mit Modellzeit. timeFactor = wie viel schneller die Modelluhr läuft als die echte Zeit.
 * Die Ausführung folgt mit der Vollautomatik.
 */
public record Timetable(double timeFactor, LocalTime startTime, List<Entry> entries) {

    /**
     * @param repeatMinutes  0 = einmalig, sonst Takt in Modellminuten
     * @param dwellSeconds   Haltezeit am Ziel in Modellsekunden
     */
    public record Entry(String trainId, LocalTime departure, String fromStation, String toStation,
                        List<String> via, int repeatMinutes, int dwellSeconds) {
        public Entry { via = List.copyOf(via); }
    }

    public Timetable { entries = List.copyOf(entries); }

    public static Timetable empty() { return new Timetable(1.0, LocalTime.of(6, 0), List.of()); }
}
