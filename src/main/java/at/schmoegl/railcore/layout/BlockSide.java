package at.schmoegl.railcore.layout;

/** Ein bestimmtes Ende eines bestimmten Blocks, z. B. "Bf1:B". */
public record BlockSide(String blockId, BlockEnd end) {

    public static BlockSide parse(String s) {
        int i = s.lastIndexOf(':');
        if (i < 0) throw new IllegalArgumentException("Blockseite im Format 'Block:A' oder 'Block:B' erwartet: " + s);
        return new BlockSide(s.substring(0, i), BlockEnd.valueOf(s.substring(i + 1).trim().toUpperCase()));
    }

    @Override
    public String toString() { return blockId + ":" + end; }
}
