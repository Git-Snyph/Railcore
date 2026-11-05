package at.schmoegl.railcore.layout.graph;

/** Gemeinsame Prüfung von Kennungen der Auflagen. */
final class LayoutIds {
    private LayoutIds() {}

    static String require(String id, String what) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException(what + " ohne Kennung");
        return id;
    }

    static double requireLength(double mm, String what) {
        if (!(mm >= 0) || Double.isNaN(mm)) throw new IllegalArgumentException(what + " ungültig: " + mm);
        return mm;
    }
}
