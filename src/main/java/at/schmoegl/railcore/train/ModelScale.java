package at.schmoegl.railcore.train;

import java.util.Objects;

/**
 * Modellmaßstab für die Umrechnung von Vorbild-Geschwindigkeiten (km/h) in Modell-mm/s. Die Zeit wird nicht
 * skaliert: 230 km/h Vorbild in H0 (1:87) sind 230 / 3,6 / 87 m/s ≈ 734 mm/s.
 */
public record ModelScale(String name, double ratio) {

    public static final ModelScale H0 = new ModelScale("H0", 87.0);

    public ModelScale {
        Objects.requireNonNull(name);
        if (!(ratio >= 1.0 && ratio <= 1_000.0)) throw new IllegalArgumentException("Maßstab unplausibel: 1:" + ratio);
    }

    /** Vorbild-km/h → Modell-mm/s. */
    public double mmPerSecond(double prototypeKmh) { return prototypeKmh / 3.6 * 1_000.0 / ratio; }

    /** Modell-mm/s → Vorbild-km/h. */
    public double prototypeKmh(double mmPerSecond) { return mmPerSecond * ratio * 3.6 / 1_000.0; }
}
