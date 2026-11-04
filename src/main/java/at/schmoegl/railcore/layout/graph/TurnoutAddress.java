package at.schmoegl.railcore.layout.graph;

/**
 * Zubehöradresse einer logischen Weiche ({@link TurnoutRequirement#turnoutId()}), damit die Prüfung doppelte
 * Adressen erkennt. Adressierungsmodus (Roco/RCN-213), Umkehrung und Stellzeit folgen mit der Weichen-Konfiguration.
 */
public record TurnoutAddress(String turnoutId, int address) {
    public TurnoutAddress {
        LayoutIds.require(turnoutId, "Weichenadresse");
        if (address < 1) throw new IllegalArgumentException("Weiche " + turnoutId + ": Adresse muss größer 0 sein: " + address);
    }
}
