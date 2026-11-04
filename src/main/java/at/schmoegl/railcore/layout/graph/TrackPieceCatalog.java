package at.schmoegl.railcore.layout.graph;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Gleisstückbibliothek: unveränderliche Sammlung von {@link TrackPiece}-Typen, nach Typbezeichnung abrufbar.
 * Enthält keine eingebauten Herstellermaße; die Einträge kommen aus der Konfiguration (ab B3 aus der Datei).
 */
public final class TrackPieceCatalog {

    private final Map<String, TrackPiece> pieces;

    private TrackPieceCatalog(Map<String, TrackPiece> pieces) {
        this.pieces = Map.copyOf(pieces);
    }

    public static TrackPieceCatalog of(TrackPiece... pieces) {
        Builder b = new Builder();
        for (TrackPiece p : pieces) b.add(p);
        return b.build();
    }

    public static Builder builder() { return new Builder(); }

    public Optional<TrackPiece> find(String id) { return Optional.ofNullable(pieces.get(id)); }

    /** Typ mit dieser Bezeichnung; unbekannte Bezeichnung ist ein Fehler. */
    public TrackPiece piece(String id) {
        TrackPiece p = pieces.get(id);
        if (p == null) throw new IllegalArgumentException("Unbekanntes Gleisstück im Katalog: " + id);
        return p;
    }

    public Collection<TrackPiece> pieces() { return pieces.values(); }

    public int size() { return pieces.size(); }

    public static final class Builder {
        private final Map<String, TrackPiece> pieces = new LinkedHashMap<>();

        private Builder() {}

        public Builder add(TrackPiece piece) {
            if (pieces.putIfAbsent(piece.id(), piece) != null)
                throw new IllegalArgumentException("Gleisstück doppelt im Katalog: " + piece.id());
            return this;
        }

        public TrackPieceCatalog build() { return new TrackPieceCatalog(pieces); }
    }
}
