package at.schmoegl.railcore.control;

/**
 * Kennzeichnung eines Zuges nach einer Störung (Lastenheft 7). Eine Kennzeichnung wird nie automatisch aufgehoben,
 * sondern nur durch die Bedienhandlung {@link ControlCommand.ReleaseTrainCaution}.
 */
public enum TrainCaution {
    /**
     * Kurzschluss, Überlast oder Spannungsausfall in einem Bezirk, in dem der Zug stehen kann: Position unsicher.
     * Der Zug darf nicht anfahren oder schneller werden; Bremsen und Halten bleiben erlaubt (geordnet halten).
     */
    POWER_FAULT,
    /**
     * Die Zentrale meldet für die Lok einen anderen Fahrbefehl als den gesendeten (Fremdsteuerung der echten Adresse,
     * verlorener Befehl): erlaubter Wert wurde erneut gesendet, der Zug fährt mit größerem Sicherheitsabstand.
     */
    FOREIGN_CONTROL;

    /** Sperrt diese Kennzeichnung Anfahren und Beschleunigen? */
    public boolean blocksIncrease() { return this == POWER_FAULT; }

    public String german() {
        return switch (this) {
            case POWER_FAULT -> "Position unsicher (Kurzschluss/Spannungsausfall im Bezirk)";
            case FOREIGN_CONTROL -> "größerer Sicherheitsabstand (Lok fuhr anders als gesendet)";
        };
    }
}
