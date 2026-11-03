package at.schmoegl.railcore.layout;

/**
 * Weiche oder anderes schaltbares Zubehör.
 *
 * @param occupancyContact   Belegtmelder der Weiche (null = keiner)
 * @param feedbackStraight   Lagerückmeldung "gerade" (null = keine)
 * @param feedbackDiverging  Lagerückmeldung "abzweigend" (null = keine)
 * @param inverted           Ausgänge vertauscht angeschlossen
 */
public record Turnout(String id, String name, int address, boolean inverted, Integer occupancyContact,
                      Integer feedbackStraight, Integer feedbackDiverging) {
}
