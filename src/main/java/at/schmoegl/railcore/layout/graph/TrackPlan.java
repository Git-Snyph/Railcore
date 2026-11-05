package at.schmoegl.railcore.layout.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gleisplan-Datei als Modell (Schritt B3, Entscheidungen 35, 40/E2, 43): genau das, was in der JSON-Datei steht –
 * Gleisstück-Katalog, verlegte Stücke mit ihren Instanzwerten, Verbindungen und Auflagen. Daraus baut
 * {@link TrackPlanLoader} den {@link TrackGraph} und den geprüften {@link TrackLayout}.
 *
 * <p>Warum ein eigenes Modell: Graph und Gleisplan sind berechnet (Knoten, Kanten, Meldepunkte) und lassen sich
 * nicht verlustfrei in die Datei zurückschreiben; dieses Modell dagegen schon ({@link TrackPlanJson#write}). Es
 * ist unveränderlich, ohne Betriebszustand und vergleicht sich über {@code equals} (Hin- und Rückweg).
 *
 * <p>Vorläufiges Format (Entscheidung 35), Version {@link #FORMAT_VERSION}; bis Meilenstein 3 maßgeblich, danach
 * Austauschformat neben SQLite. Version 1 wird weiter gelesen und beim Lesen auf die aktuelle Version gehoben.
 *
 * <p>Spurweite und Oberleitung (Entscheidung 46, Option A): je ein Standardwert für die ganze Anlage
 * ({@code gauge}, {@code catenary}), den ein verlegtes Stück mit eigenen Angaben überschreiben kann
 * ({@link PieceProperties}; Spurweiten als Liste, mehrere bei Mehrschienengleis einer Spur-in-Spur-Anlage). Spurweite
 * als NEM-010-Spur ({@link NemGauge}) oder {@link NemGauge#CUSTOM} mit eigenem mm-Wert (Entscheidung des Nutzers).
 *
 * @param name                        Anzeigename der Anlage ("" = ohne)
 * @param catalog                     Gleisstück-Typen in Dateireihenfolge
 * @param recommendedSectionsPerBlock empfohlene Mindestzahl Meldeabschnitte je Block (Standard 3)
 * @param gauge                       Spurweite der Anlage; leer = keine Angabe
 * @param catenary                    Oberleitung der Anlage ({@link Catenary#UNSPECIFIED} = keine Angabe)
 */
public record TrackPlan(int formatVersion, String name, List<TrackPiece> catalog, List<PlacedPiece> pieces,
                        List<Connection> connections, List<PlanSection> sections, List<Block> blocks,
                        List<HoldingArea> holdingAreas, List<PlanDistrict> districts, List<BlockSignal> signals,
                        List<StopPoint> stopPoints, List<SpeedChangePoint> speedChangePoints,
                        List<ReportPointOverride> reportPointOverrides, List<TurnoutAddress> turnoutAddresses,
                        int recommendedSectionsPerBlock, Optional<Gauge> gauge, Catenary catenary) {

    /** Formatversion, die dieser Stand schreibt. */
    public static final int FORMAT_VERSION = 2;

    /** Älteste Formatversion, die dieser Stand noch liest (ohne die Felder ab Version 2). */
    public static final int OLDEST_READABLE_VERSION = 1;

    /** Standard der empfohlenen Abschnitte je Block (Lastenheft Kapitel 4). */
    public static final int DEFAULT_SECTIONS_PER_BLOCK = 3;

    /** Erlaubte Stückkennung, wie {@link TrackGraph.Builder}: ohne „.“, „:“, „~“ und Leerzeichen. */
    static final Pattern PIECE_ID = Pattern.compile("[^.:~\\s]+");

    public TrackPlan {
        name = name == null ? "" : name;
        catalog = List.copyOf(catalog);
        pieces = List.copyOf(pieces);
        connections = List.copyOf(connections);
        sections = List.copyOf(sections);
        blocks = List.copyOf(blocks);
        holdingAreas = List.copyOf(holdingAreas);
        districts = List.copyOf(districts);
        signals = List.copyOf(signals);
        stopPoints = List.copyOf(stopPoints);
        speedChangePoints = List.copyOf(speedChangePoints);
        reportPointOverrides = List.copyOf(reportPointOverrides);
        turnoutAddresses = List.copyOf(turnoutAddresses);
        if (recommendedSectionsPerBlock < 1)
            throw new IllegalArgumentException("Empfohlene Abschnittszahl muss mindestens 1 sein: " + recommendedSectionsPerBlock);
        if (gauge == null) gauge = Optional.empty();
        if (catenary == null) catenary = Catenary.UNSPECIFIED;
    }

    /**
     * Wirksame Spurweiten eines verlegten Stücks: die eigenen, sonst die der Anlage, sonst leer (keine Angabe).
     * Reine Auskunft aus der Datei, keine Gleiswahl.
     */
    public Set<Gauge> gaugesOf(PlacedPiece piece) {
        if (!piece.properties().gauges().isEmpty()) return piece.properties().gauges();
        return gauge.map(Set::of).orElse(Set.of());
    }

    /**
     * Wirksame Oberleitung eines verlegten Stücks: die eigene Angabe, sonst die der Anlage, sonst
     * {@link Catenary#UNSPECIFIED}. Reine Auskunft aus der Datei, keine Gleiswahl.
     */
    public Catenary catenaryOf(PlacedPiece piece) {
        return piece.properties().catenary() != Catenary.UNSPECIFIED ? piece.properties().catenary() : catenary;
    }

    // --- Eigenschaften eines verlegten Stücks -------------------------------------------------------------------

    /**
     * Angaben zu einem verlegten Stück ohne Einfluss auf Graph und Länge (Entscheidung 46, Option A), Grundlage
     * für die spätere Gleiswahl nach Spurweite und Traktion (Meilenstein 4).
     *
     * @param gauges   eigene Spurweiten (mehrere bei Mehrschienengleis); leer = Spurweite der Anlage
     * @param catenary eigene Angabe zur Oberleitung; {@link Catenary#UNSPECIFIED} = Angabe der Anlage
     */
    public record PieceProperties(Set<Gauge> gauges, Catenary catenary) {
        /** Keine eigenen Angaben. */
        public static final PieceProperties NONE = new PieceProperties(Set.of(), Catenary.UNSPECIFIED);

        public PieceProperties {
            gauges = gauges == null ? Set.of() : Set.copyOf(gauges);
            if (catenary == null) catenary = Catenary.UNSPECIFIED;
        }

        /** Eigene Spurweiten in fester Reihenfolge ({@link Gauge#ORDER}, so in der Datei). */
        public List<Gauge> sortedGauges() { return gauges.stream().sorted(Gauge.ORDER).toList(); }
    }

    // --- verlegte Stücke ----------------------------------------------------------------------------------------

    /**
     * Verlegtes Gleisstück mit seinen Instanzwerten. {@code type} verweist auf einen Katalogeintrag passender Art.
     * {@code lengthUnknown}: Länge unbekannt bzw. geschätzt (Entscheidung 43, Punkt 2) – die Prüfung meldet das als
     * Hinweis, weil Position und Bremsweg davon abhängen.
     */
    public sealed interface PlacedPiece
            permits PlainPlacement, TurnoutPlacement, CrossingPlacement, DoubleSlipPlacement {
        String id();

        String type();

        boolean lengthUnknown();

        /** Spurweite und Oberleitung ({@link PieceProperties#NONE} = keine eigenen Angaben). */
        PieceProperties properties();
    }

    /** Gerades Stück, Bogen oder Flexgleis; {@code lengthMm} überschreibt die Typlänge (Entscheidung 42). */
    public record PlainPlacement(String id, String type, OptionalDouble lengthMm, boolean lengthUnknown,
                                 PieceProperties properties) implements PlacedPiece {
        public PlainPlacement {
            requireText(id, "Stückkennung");
            requireText(type, "Stück " + id + ": Typ");
            if (lengthMm == null) lengthMm = OptionalDouble.empty();
            if (lengthMm.isPresent() && (!(lengthMm.getAsDouble() > 0) || !Double.isFinite(lengthMm.getAsDouble())))
                throw new IllegalArgumentException("Stück " + id + ": eigene Länge muss größer 0 sein: " + lengthMm.getAsDouble());
            if (properties == null) properties = PieceProperties.NONE;
        }

        public PlainPlacement(String id, String type, OptionalDouble lengthMm, boolean lengthUnknown) {
            this(id, type, lengthMm, lengthUnknown, PieceProperties.NONE);
        }
    }

    /** Weiche mit logischer Weichenkennung und eigenen Zweiglängen und Grenzzeichen (Entscheidung 41). */
    public record TurnoutPlacement(String id, String type, String turnoutId, TurnoutBranches branches,
                                   boolean lengthUnknown, PieceProperties properties) implements PlacedPiece {
        public TurnoutPlacement {
            requireText(id, "Stückkennung");
            requireText(type, "Stück " + id + ": Typ");
            requireText(turnoutId, "Weiche " + id + ": Weichenkennung");
            if (branches == null) throw new IllegalArgumentException("Weiche " + id + ": Zweiglängen und Grenzzeichen fehlen");
            if (properties == null) properties = PieceProperties.NONE;
        }

        public TurnoutPlacement(String id, String type, String turnoutId, TurnoutBranches branches, boolean lengthUnknown) {
            this(id, type, turnoutId, branches, lengthUnknown, PieceProperties.NONE);
        }
    }

    /** Kreuzung mit eigenen Maßen (Entscheidung 42). */
    public record CrossingPlacement(String id, String type, TrackPiece.CrossingDimensions dimensions,
                                    boolean lengthUnknown, PieceProperties properties) implements PlacedPiece {
        public CrossingPlacement {
            requireText(id, "Stückkennung");
            requireText(type, "Stück " + id + ": Typ");
            if (dimensions == null) throw new IllegalArgumentException("Kreuzung " + id + ": Maße fehlen");
            if (properties == null) properties = PieceProperties.NONE;
        }

        public CrossingPlacement(String id, String type, TrackPiece.CrossingDimensions dimensions, boolean lengthUnknown) {
            this(id, type, dimensions, lengthUnknown, PieceProperties.NONE);
        }
    }

    /** Doppelkreuzungsweiche mit Kennungen beider Hälften, eigenen Fahrweglängen und Grenzzeichen (Entscheidung 41). */
    public record DoubleSlipPlacement(String id, String type, String turnoutIdA, String turnoutIdB,
                                      TrackPiece.DoubleSlipLengths routeLengths, DoubleSlipClearances clearances,
                                      boolean lengthUnknown, PieceProperties properties) implements PlacedPiece {
        public DoubleSlipPlacement {
            requireText(id, "Stückkennung");
            requireText(type, "Stück " + id + ": Typ");
            requireText(turnoutIdA, "Doppelkreuzungsweiche " + id + ": Kennung Seite A");
            requireText(turnoutIdB, "Doppelkreuzungsweiche " + id + ": Kennung Seite B");
            if (routeLengths == null || clearances == null)
                throw new IllegalArgumentException("Doppelkreuzungsweiche " + id + ": Fahrweglängen oder Grenzzeichen fehlen");
            if (properties == null) properties = PieceProperties.NONE;
        }

        public DoubleSlipPlacement(String id, String type, String turnoutIdA, String turnoutIdB,
                                   TrackPiece.DoubleSlipLengths routeLengths, DoubleSlipClearances clearances,
                                   boolean lengthUnknown) {
            this(id, type, turnoutIdA, turnoutIdB, routeLengths, clearances, lengthUnknown, PieceProperties.NONE);
        }
    }

    // --- Verbindungen -------------------------------------------------------------------------------------------

    /** Anschluss eines verlegten Stücks, in der Datei als {@code "stück.ANSCHLUSS"}. */
    public record PortRef(String pieceId, Port port) {
        public PortRef {
            requireText(pieceId, "Anschluss: Stückkennung");
            if (port == null) throw new IllegalArgumentException("Anschluss " + pieceId + ": Anschlussname fehlt");
        }

        @Override public String toString() { return pieceId + "." + port; }
    }

    /** Art der Verbindung. */
    public enum ConnectionKind {
        /** Stoß zwischen zwei Anschlüssen */
        JOINT,
        /** Trennstelle: elektrisch getrennter Stoß */
        ISOLATING,
        /** Gleisende bzw. Prellbock an einem Anschluss ({@code b} ist {@code null}) */
        END
    }

    public record Connection(ConnectionKind kind, PortRef a, PortRef b) {
        public Connection {
            if (kind == null || a == null) throw new IllegalArgumentException("Verbindung unvollständig");
            if ((kind == ConnectionKind.END) != (b == null))
                throw new IllegalArgumentException("Verbindung " + a + ": Gleisende hat genau einen Anschluss, Stoß und"
                        + " Trennstelle genau zwei");
        }

        public static Connection joint(PortRef a, PortRef b) { return new Connection(ConnectionKind.JOINT, a, b); }

        public static Connection isolating(PortRef a, PortRef b) { return new Connection(ConnectionKind.ISOLATING, a, b); }

        public static Connection end(PortRef a) { return new Connection(ConnectionKind.END, a, null); }
    }

    // --- Auflagen mit Bereichen ---------------------------------------------------------------------------------

    /**
     * Bereich auf einer Kante wie {@link TrackRange}; zusätzlich „ganze Kante“ ({@link #whole}), weil die Länge
     * berechneter Kanten (z. B. Bogen) erst nach dem Bau des Graphs feststeht und eine abgeschriebene Zahl leicht
     * zu kurz wäre.
     */
    public record PlanRange(String edgeId, boolean wholeEdge, double fromMm, double toMm) {
        public PlanRange {
            requireText(edgeId, "Bereich: Kante");
            if (wholeEdge) {
                fromMm = 0;
                toMm = 0;
            } else {
                new TrackRange(edgeId, fromMm, toMm); // gleiche Prüfung wie im Gleisplan
            }
        }

        public static PlanRange whole(String edgeId) { return new PlanRange(edgeId, true, 0, 0); }

        public static PlanRange of(String edgeId, double fromMm, double toMm) {
            return new PlanRange(edgeId, false, fromMm, toMm);
        }

        /**
         * Bereich im Graph. Ganze Kante einer unbekannten Kante wird zum Bereich ohne Länge auf dieser Kante; die
         * Prüfung meldet dann „unbekannte Kante“ ({@link LayoutFinding.Code#UNKNOWN_REFERENCE}).
         */
        TrackRange resolve(TrackGraph graph) {
            if (!wholeEdge) return new TrackRange(edgeId, fromMm, toMm);
            for (TrackEdge e : graph.edges()) if (e.id().equals(edgeId)) return new TrackRange(edgeId, 0, e.lengthMm());
            return new TrackRange(edgeId, 0, 0);
        }
    }

    /** Meldeabschnitt in Dateiform (wie {@link DetectionSection}, Bereiche als {@link PlanRange}). */
    public record PlanSection(String id, int contact, List<PlanRange> ranges, int responseDelayMs, int releaseDelayMs,
                              OptionalInt district) {
        public PlanSection {
            ranges = ranges == null ? List.of() : List.copyOf(ranges);
            if (district == null) district = OptionalInt.empty();
            // gleiche Prüfung wie im Gleisplan, mit Platzhalter-Bereichen
            List<TrackRange> probe = new ArrayList<>();
            for (PlanRange r : ranges) probe.add(new TrackRange(r.edgeId(), 0, 1));
            new DetectionSection(id, contact, probe, responseDelayMs, releaseDelayMs, district);
        }

        DetectionSection resolve(TrackGraph graph) {
            List<TrackRange> out = new ArrayList<>();
            for (PlanRange r : ranges) out.add(r.resolve(graph));
            return new DetectionSection(id, contact, out, responseDelayMs, releaseDelayMs, district);
        }
    }

    /** Booster-Bezirk in Dateiform (wie {@link BoosterDistrict}, Bereiche als {@link PlanRange}). */
    public record PlanDistrict(int id, String name, boolean reversingLoop, List<PlanRange> extraRanges) {
        public PlanDistrict {
            name = name == null ? "" : name;
            extraRanges = extraRanges == null ? List.of() : List.copyOf(extraRanges);
            new BoosterDistrict(id, name, reversingLoop, List.of());
        }

        BoosterDistrict resolve(TrackGraph graph) {
            List<TrackRange> out = new ArrayList<>();
            for (PlanRange r : extraRanges) out.add(r.resolve(graph));
            return new BoosterDistrict(id, name, reversingLoop, out);
        }
    }

    // --- Verweise -----------------------------------------------------------------------------------------------

    /**
     * Prüft die Verweise innerhalb der Datei, die schon vor dem Bau des Graphs feststehen: Katalog- und
     * Stückkennungen eindeutig und gültig, Typ vorhanden und von passender Art, Verbindungen nur zu bekannten
     * Stücken und deren Anschlüssen. Verweise auf Kanten, Abschnitte, Blöcke usw. prüft {@link TrackLayout}.
     */
    public List<TrackPlanReport.Problem> referenceProblems() {
        List<TrackPlanReport.Problem> out = new ArrayList<>();
        Map<String, TrackPiece> types = new HashMap<>();
        for (int i = 0; i < catalog.size(); i++) {
            TrackPiece t = catalog.get(i);
            if (types.putIfAbsent(t.id(), t) != null)
                out.add(TrackPlanReport.Problem.reference("catalog[" + i + "] (" + t.id() + ")",
                        "Typ " + t.id() + " mehrfach im Katalog"));
        }
        // Kennung → Typ; ein fehlerhaftes Stück steht mit null darin, damit es in Verbindungen nicht zusätzlich als
        // unbekannt gemeldet wird (jede Ursache genau einmal)
        Map<String, TrackPiece> pieceTypes = new HashMap<>();
        for (int i = 0; i < pieces.size(); i++) {
            PlacedPiece p = pieces.get(i);
            String where = "pieces[" + i + "] (" + p.id() + ")";
            if (pieceTypes.containsKey(p.id())) {
                out.add(TrackPlanReport.Problem.reference(where, "Stückkennung " + p.id() + " mehrfach vergeben"));
                continue;
            }
            pieceTypes.put(p.id(), null);
            if (!PIECE_ID.matcher(p.id()).matches()) {
                out.add(TrackPlanReport.Problem.format(where, "ungültige Stückkennung '" + p.id()
                        + "' (ohne „.“, „:“, „~“ und Leerzeichen)"));
                continue;
            }
            TrackPiece t = types.get(p.type());
            if (t == null) {
                out.add(TrackPlanReport.Problem.reference(where, "unbekannter Typ " + p.type()));
                continue;
            }
            boolean fits = switch (p) {
                case PlainPlacement _ -> t instanceof TrackPiece.PlainPiece;
                case TurnoutPlacement _ -> t instanceof TrackPiece.TurnoutPiece;
                case CrossingPlacement _ -> t instanceof TrackPiece.CrossingPiece;
                case DoubleSlipPlacement _ -> t instanceof TrackPiece.DoubleSlipPiece;
            };
            if (!fits) {
                out.add(TrackPlanReport.Problem.reference(where, "Typ " + p.type() + " passt nicht zur Art des Stücks"));
                continue;
            }
            pieceTypes.put(p.id(), t);
        }
        Set<String> reported = new HashSet<>();
        for (int i = 0; i < connections.size(); i++) {
            Connection c = connections.get(i);
            String where = "connections[" + i + "]";
            for (PortRef r : c.b() == null ? List.of(c.a()) : List.of(c.a(), c.b())) {
                TrackPiece t = pieceTypes.get(r.pieceId());
                if (!pieceTypes.containsKey(r.pieceId())) {
                    if (reported.add(where + r)) out.add(TrackPlanReport.Problem.reference(where, "unbekanntes Stück " + r.pieceId()));
                } else if (t != null && !t.ports().contains(r.port())) {
                    out.add(TrackPlanReport.Problem.reference(where, "Stück " + r.pieceId() + " (Typ " + t.id()
                            + ") hat keinen Anschluss " + r.port() + ", nur " + t.ports()));
                }
            }
        }
        return out;
    }

    private static void requireText(String s, String what) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(what + " fehlt");
    }
}
