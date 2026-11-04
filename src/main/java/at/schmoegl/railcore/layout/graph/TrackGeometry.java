package at.schmoegl.railcore.layout.graph;

import java.util.List;

/**
 * Geometrie eines Gleisstrangs, gemessen auf der Gleismittellinie (Entscheidung 40/E2). Aus ihr wird die Länge
 * der Kanten im Gleisgraph berechnet; Längen werden nie von Hand in den Graph eingetragen.
 *
 * <p>Bewusst nur Länge, noch keine Lage in der Ebene: Anfangs- und Endpunkt (x, y, Richtung) für Zeichnung und
 * Schließprüfung kommen mit der Geometrie-Rechnung in Schritt B3.
 */
public sealed interface TrackGeometry permits TrackGeometry.Straight, TrackGeometry.Arc, TrackGeometry.Compound {

    /** Länge auf der Gleismittellinie in mm, immer größer als 0. */
    double lengthMm();

    static Straight straight(double lengthMm) { return new Straight(lengthMm); }

    static Arc arc(double radiusMm, double angleDeg) { return new Arc(radiusMm, angleDeg); }

    static Compound compound(TrackGeometry... parts) { return new Compound(List.of(parts)); }

    /** Gerades Stück. */
    record Straight(double lengthMm) implements TrackGeometry {
        public Straight {
            requirePositive(lengthMm, "Länge");
        }
    }

    /** Kreisbogen; Länge = Radius · Winkel (Bogenmaß). */
    record Arc(double radiusMm, double angleDeg) implements TrackGeometry {
        public Arc {
            requirePositive(radiusMm, "Radius");
            requirePositive(angleDeg, "Winkel");
            if (angleDeg > 360) throw new IllegalArgumentException("Winkel über 360°: " + angleDeg);
        }

        @Override
        public double lengthMm() { return radiusMm * Math.toRadians(angleDeg); }
    }

    /** Aneinandergereihte Teilstücke, z. B. Abzweig einer Weiche als Bogen plus gerades Anschlussstück. */
    record Compound(List<TrackGeometry> parts) implements TrackGeometry {
        public Compound {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) throw new IllegalArgumentException("Zusammengesetzte Geometrie ohne Teilstücke");
        }

        @Override
        public double lengthMm() {
            double sum = 0;
            for (TrackGeometry g : parts) sum += g.lengthMm();
            return sum;
        }
    }

    private static void requirePositive(double value, String what) {
        if (!(value > 0) || !Double.isFinite(value))
            throw new IllegalArgumentException(what + " muss eine endliche Zahl größer 0 sein: " + value);
    }
}
