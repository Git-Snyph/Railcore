package at.schmoegl.bahnsteuerung.layout;

import java.util.List;
import java.util.Map;

/**
 * Signal am Ende eines Blocks (gilt für Züge, die den Block an diesem Ende verlassen).
 * Jedes Signalbild wird durch eine Folge von Zubehörbefehlen gestellt, z. B. Hp0 = [12 gerade], Hp1 = [12 abzweig].
 */
public record Signal(String id, String name, BlockSide at, Map<Aspect, List<AccessoryOutput>> aspects) {

    public enum Aspect { HP0, HP1, HP2, SH1, VR0, VR1, VR2, DARK }

    public Signal { aspects = Map.copyOf(aspects); }
}
