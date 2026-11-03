package at.schmoegl.bahnsteuerung.core;

import java.util.Map;

/** Erzeugt eine Zentrale eines bestimmten Typs aus Einstellungen (z. B. host=192.168.0.111). */
public interface CommandStationFactory {

    /** Technischer Kurzname, z. B. "z21". */
    String id();

    /** Anzeigename für die Oberfläche. */
    String displayName();

    /** Einstellungen mit Vorgabewerten, die die Oberfläche anzeigen soll. */
    Map<String, String> defaultSettings();

    CommandStation create(Map<String, String> settings);
}
