package at.schmoegl.railcore.layout.graph;

/**
 * Meldepunkt (Lastenheft Kapitel 4): Stelle, an der ein Meldeabschnitt für Züge in einer Fahrtrichtung anspricht;
 * Abgleichpunkt für die Position. {@link TrackLayout} leitet ihn aus den Grenzen des Abschnitts ab
 * ({@code boundary}: Einfahrt in den Abschnitt); ein {@link ReportPointOverride} kann die Lage verschieben (z. B.
 * weil der Melder erst mit der ersten stromabnehmenden Achse anspricht).
 *
 * @param boundary   abgeleitete Abschnittsgrenze (Einfahrt) mit Fahrtrichtung
 * @param at         maßgebliche Lage (= {@code boundary}, wenn nicht überschrieben)
 * @param overridden ob {@code at} aus einem {@link ReportPointOverride} stammt
 */
public record ReportPoint(String sectionId, DirectedLocation boundary, DirectedLocation at, boolean overridden) {
    public ReportPoint {
        LayoutIds.require(sectionId, "Meldepunkt: Meldeabschnitt");
        if (boundary == null || at == null) throw new IllegalArgumentException("Meldepunkt " + sectionId + ": Lage fehlt");
    }
}
