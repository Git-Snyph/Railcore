package at.schmoegl.railcore.layout.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Spur nach NEM 010 (Ausgabe 2011, Tabelle 1): Nenngröße mit Zusatzbuchstabe für die Vorbildspurweite, dazu
 * Modellspurweite in mm und Maßstab. Entscheidung des Nutzers: Spurweite im Gleisplan als NEM-Spurname, nicht als
 * mm-Wert; {@link #CUSTOM} mit eigenem mm-Wert für alles, was NEM 010 nicht abdeckt (z. B. H0n3 nach NMRA, 10,5 mm,
 * oder die mit „n“ bezeichneten abweichenden Spurweiten aus NEM 010 Abschnitt 3.2).
 *
 * <p>Gruppen der Vorbildspurweite (NEM 010): ohne Zusatz 1250–1700 mm (Normalspur), {@code m} 850 bis unter 1250 mm,
 * {@code e} 650 bis unter 850 mm, {@code i} 400 bis unter 650 mm (Industriebahn; im deutschen Sprachraum auch
 * {@code f}, Feldbahn – in der Datei gilt nur {@code i}), {@code p} 300 bis unter 400 mm. Für Nenngröße I und
 * größer erlaubt NEM 010 auch arabische Ziffern; in der Datei gelten nur die römischen.
 *
 * <p>„G“ (Gartenbahn, 45 mm) ist keine NEM-010-Bezeichnung; sie entspricht in der Regel {@link #IIm}.
 *
 * <p>Die mm-Werte werden später für Geometrie- und Entgleisungsprüfungen gebraucht; zwei Spuren können dieselbe
 * Spurweite haben (H0 und 0e: 16,5 mm), unterscheiden sich aber im Maßstab und damit im Lichtraum.
 */
public enum NemGauge {
    // Normalspur (Vorbild 1250–1700 mm)
    Z("Z", 6.5, 220), N("N", 9, 160), TT("TT", 12, 120), H0("H0", 16.5, 87), S("S", 22.5, 64), ZERO("0", 32, 45),
    I("I", 45, 32), II("II", 64, 22.5), III("III", 89, 16), V("V", 127, 11), VII("VII", 184, 8), X("X", 260, 5.5),
    // Meterspur m (Vorbild 850 bis unter 1250 mm)
    Zm("Zm", 4.5, 220), Nm("Nm", 6.5, 160), TTm("TTm", 9, 120), H0m("H0m", 12, 87), Sm("Sm", 16.5, 64),
    ZERO_M("0m", 22.5, 45), Im("Im", 32, 32), IIm("IIm", 45, 22.5), IIIm("IIIm", 64, 16), Vm("Vm", 89, 11),
    VIIm("VIIm", 127, 8), Xm("Xm", 184, 5.5),
    // Schmalspur e (Vorbild 650 bis unter 850 mm)
    Ne("Ne", 4.5, 160), TTe("TTe", 6.5, 120), H0e("H0e", 9, 87), Se("Se", 12, 64), ZERO_E("0e", 16.5, 45),
    Ie("Ie", 22.5, 32), IIe("IIe", 32, 22.5), IIIe("IIIe", 45, 16), Ve("Ve", 64, 11), VIIe("VIIe", 89, 8),
    Xe("Xe", 127, 5.5),
    // Industriebahn i (Vorbild 400 bis unter 650 mm)
    TTi("TTi", 4.5, 120), H0i("H0i", 6.5, 87), Si("Si", 9, 64), ZERO_I("0i", 12, 45), Ii("Ii", 16.5, 32),
    IIi("IIi", 22.5, 22.5), IIIi("IIIi", 32, 16), Vi("Vi", 45, 11), VIIi("VIIi", 64, 8), Xi("Xi", 89, 5.5),
    // p (Vorbild 300 bis unter 400 mm)
    H0p("H0p", 4.5, 87), Sp("Sp", 6.5, 64), ZERO_P("0p", 9, 45), Ip("Ip", 12, 32), IIp("IIp", 16.5, 22.5),
    IIIp("IIIp", 22.5, 16), Vp("Vp", 32, 11), VIIp("VIIp", 45, 8), Xp("Xp", 64, 5.5),
    /** keine NEM-010-Spur: Spurweite in mm steht in {@link Gauge#customMm()} */
    CUSTOM("CUSTOM", Double.NaN, Double.NaN);

    private final String nemName;
    private final double gaugeMm;
    private final double scale;

    NemGauge(String nemName, double gaugeMm, double scale) {
        this.nemName = nemName;
        this.gaugeMm = gaugeMm;
        this.scale = scale;
    }

    /** Bezeichnung nach NEM 010, so auch in der Datei (z. B. {@code "H0m"}, {@code "0e"}). */
    public String nemName() { return nemName; }

    /** Modellspurweite in mm nach NEM 010; {@link Double#NaN} bei {@link #CUSTOM}. */
    public double gaugeMm() { return gaugeMm; }

    /** Maßstabszahl der Nenngröße (87 für 1:87); {@link Double#NaN} bei {@link #CUSTOM}. */
    public double scale() { return scale; }

    private static final Map<String, NemGauge> BY_NAME = new HashMap<>();

    static {
        for (NemGauge g : values()) BY_NAME.put(g.nemName, g);
    }

    /** Spur zur Bezeichnung (groß/klein genau wie in NEM 010). */
    public static Optional<NemGauge> byName(String name) { return Optional.ofNullable(BY_NAME.get(name)); }
}
