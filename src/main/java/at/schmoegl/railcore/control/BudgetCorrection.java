package at.schmoegl.railcore.control;

/**
 * Erweiterungsstelle: Nachführung des Befehlsbudgets aus der gemessenen Antwortzeit der Zentrale (Echo der Z21).
 * <p>
 * <b>Noch inaktiv</b> (Entscheidung 27): Das Echo ist an der Hardware nicht bestätigt. Bis die aktive Prüfstufe
 * des Z21-Prüfprogramms es bestätigt hat, verwendet der {@link CommandScheduler} ausschließlich {@link #NONE}
 * (Budget = Rechenmodell); eine andere Umsetzung lässt sich nur paketintern einsetzen. Eine künftige Umsetzung darf
 * das Budget nur <b>senken</b> oder bis höchstens zum Rechenmodell anheben, nie darüber.
 */
interface BudgetCorrection {

    /** @return korrigiertes Budget (Befehle/s) zum Budget aus dem Rechenmodell */
    double correct(double modelBudgetPerSecond);

    /** Gemessene Antwortzeit eines gesendeten Befehls (Echo) – Eingang für eine künftige Nachführung. */
    void echoMeasured(long roundTripNanos);

    /** Keine Nachführung: es gilt das Rechenmodell. */
    BudgetCorrection NONE = new BudgetCorrection() {
        @Override public double correct(double modelBudgetPerSecond) { return modelBudgetPerSecond; }
        @Override public void echoMeasured(long roundTripNanos) { }
    };
}
