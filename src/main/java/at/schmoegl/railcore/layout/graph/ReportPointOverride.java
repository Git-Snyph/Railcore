package at.schmoegl.railcore.layout.graph;

/**
 * Überschreibt den abgeleiteten Meldepunkt von {@code sectionId} an der Grenze {@code boundary} (muss einer
 * abgeleiteten Einfahrt des Abschnitts entsprechen) durch {@code at} (muss im Abschnitt liegen).
 */
public record ReportPointOverride(String sectionId, DirectedLocation boundary, DirectedLocation at) {
    public ReportPointOverride {
        LayoutIds.require(sectionId, "Meldepunkt: Meldeabschnitt");
        if (boundary == null || at == null)
            throw new IllegalArgumentException("Meldepunkt " + sectionId + ": Grenze oder Lage fehlt");
    }
}
