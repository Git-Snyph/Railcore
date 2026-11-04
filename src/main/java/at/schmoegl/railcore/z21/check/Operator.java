package at.schmoegl.railcore.z21.check;

/**
 * Bediener an der Konsole. Eingaben werden nicht blockierend abgeholt, damit das Prüfprogramm während des
 * Wartens die Verbindung zur Z21 weiter überwacht.
 */
public interface Operator {

    /** Hinweis ohne erwartete Eingabe. */
    void info(String text);

    /** Frage, auf die eine Eingabe erwartet wird. */
    void prompt(String text);

    /** Nächste eingegebene Zeile oder {@code null}, wenn noch nichts eingegeben wurde. */
    String poll();

    /** Verwirft vorab getippte Eingaben, damit eine alte Zeile nie als Bestätigung gilt. */
    void discardPending();
}
