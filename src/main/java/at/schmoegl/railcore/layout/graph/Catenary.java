package at.schmoegl.railcore.layout.graph;

/**
 * Oberleitung über einem verlegten Gleisstück (Entscheidung 46, Option A): Grundlage dafür, ob ein Zug mit
 * elektrischer Traktion dort fahren darf. Reine Angabe im Gleisplan; ausgewertet wird sie erst mit der Gleiswahl
 * (Meilenstein 4).
 */
public enum Catenary {
    /** keine Angabe (Standard): die Angabe schränkt nichts ein */
    UNSPECIFIED,
    /** Gleis mit Oberleitung */
    PRESENT,
    /** Gleis ohne Oberleitung */
    ABSENT
}
