package at.schmoegl.railcore.layout;

/** Die beiden Enden eines Blocks. Koordinaten im Block laufen von A (0 mm) nach B (Länge). */
public enum BlockEnd {
    A, B;

    public BlockEnd opposite() { return this == A ? B : A; }
}
