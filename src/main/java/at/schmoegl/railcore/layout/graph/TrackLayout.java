package at.schmoegl.railcore.layout.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Gleisplan aus Gleisgraph und Auflagen (Lastenheft Kapitel 4, Schritt B2): Meldeabschnitte, Blöcke, Haltebereiche,
 * Meldepunkte, Haltepunkte, Geschwindigkeitswechselpunkte, Signale, Booster-Bezirke und Weichenadressen.
 * Unveränderlich und ohne Betriebszustand, daher aus jedem Thread lesbar.
 *
 * <p>Beim Bau wird der Gleisplan geprüft ({@link #findings()}, Lastenheft 4 und 7 „Gleisplan prüfen“). Anders als
 * der Graph wirft der Bau bei inhaltlichen Fehlern keine Ausnahme, damit der vollständige Prüfbericht angezeigt
 * werden kann; solange ein Fehler besteht, ist {@link #permitsAutomatic()} {@code false} (im Zweifel halt). Die
 * Indizes enthalten nur gültige Angaben; bei doppelten Kennungen gilt jeweils die erste.
 */
public final class TrackLayout {

    /** Eingaben, wie sie dem Builder übergeben wurden (auch fehlerhafte). */
    record Spec(TrackGraph graph, List<DetectionSection> sections, List<Block> blocks, List<HoldingArea> holdingAreas,
                List<BoosterDistrict> districts, List<BlockSignal> signals, List<StopPoint> stopPoints,
                List<SpeedChangePoint> speedChangePoints, List<ReportPointOverride> reportPointOverrides,
                List<TurnoutAddress> turnoutAddresses, Set<String> unverifiedLengthPieces,
                int recommendedSectionsPerBlock) {}

    private final TrackGraph graph;
    private final LayoutChecker index;
    private final List<LayoutFinding> findings;

    private TrackLayout(Spec spec) {
        this.graph = spec.graph();
        this.index = new LayoutChecker(spec);
        this.findings = List.copyOf(index.findings);
    }

    public static Builder builder(TrackGraph graph) { return new Builder(graph); }

    public TrackGraph graph() { return graph; }

    // --- Prüfung ------------------------------------------------------------------------------------------------

    /** Alle Befunde der Prüfung, Fehler und Hinweise, in fester Reihenfolge. */
    public List<LayoutFinding> findings() { return findings; }

    public List<LayoutFinding> errors() { return findings.stream().filter(LayoutFinding::isError).toList(); }

    public List<LayoutFinding> hints() { return findings.stream().filter(f -> !f.isError()).toList(); }

    /** Automatikbetrieb nur ohne Fehler; Hinweise verhindern ihn nicht. */
    public boolean permitsAutomatic() { return errors().isEmpty(); }

    // --- Objekte ------------------------------------------------------------------------------------------------

    public Collection<DetectionSection> sections() { return index.sections.values(); }

    public Optional<DetectionSection> section(String id) { return Optional.ofNullable(index.sections.get(id)); }

    public Optional<DetectionSection> sectionByContact(int contact) { return Optional.ofNullable(index.byContact.get(contact)); }

    public Collection<Block> blocks() { return index.blocks.values(); }

    public Optional<Block> block(String id) { return Optional.ofNullable(index.blocks.get(id)); }

    public Collection<HoldingArea> holdingAreas() { return index.holdingAreas.values(); }

    public Collection<BoosterDistrict> districts() { return index.districts.values(); }

    public Optional<BoosterDistrict> district(int id) { return Optional.ofNullable(index.districts.get(id)); }

    public Collection<BlockSignal> signals() { return index.signals.values(); }

    public Collection<StopPoint> stopPoints() { return index.stopPoints.values(); }

    public Collection<SpeedChangePoint> speedChangePoints() { return index.speedPoints.values(); }

    public Collection<TurnoutAddress> turnoutAddresses() { return index.turnoutAddresses.values(); }

    // --- Indizes ------------------------------------------------------------------------------------------------

    /** Block, zu dem der Meldeabschnitt gehört (leer = Abschnitt außerhalb von Blöcken, z. B. Weichenabschnitt). */
    public Optional<Block> blockOfSection(String sectionId) {
        String b = index.blockOfSection.get(sectionId);
        return b == null ? Optional.empty() : Optional.ofNullable(index.blocks.get(b));
    }

    /** Bekannte Meldeabschnitte des Blocks in Reihenfolge A → B. */
    public List<DetectionSection> sectionsOfBlock(String blockId) {
        Block b = index.blocks.get(blockId);
        if (b == null) return List.of();
        List<DetectionSection> out = new ArrayList<>();
        for (String s : b.sectionIds()) if (index.sections.containsKey(s)) out.add(index.sections.get(s));
        return List.copyOf(out);
    }

    /** Meldeabschnitte im Bezirk (ohne Gleis ohne Melder, das steht in {@link BoosterDistrict#extraRanges()}). */
    public List<DetectionSection> sectionsOfDistrict(int district) {
        return sections().stream().filter(s -> s.district().isPresent() && s.district().getAsInt() == district).toList();
    }

    /** Bezirk des Meldeabschnitts, sofern zugeordnet und bekannt. */
    public Optional<BoosterDistrict> districtOfSection(String sectionId) {
        DetectionSection s = index.sections.get(sectionId);
        if (s == null || s.district().isEmpty()) return Optional.empty();
        return district(s.district().getAsInt());
    }

    /**
     * Meldeabschnitte, die den Ort abdecken (an einer Abschnittsgrenze auf derselben Kante beide). Ein Ort am
     * Kantenende zählt nur zu den Bereichen dieser Kante, nicht zu denen der Folgekante.
     */
    public List<DetectionSection> sectionsAt(TrackLocation location) {
        List<DetectionSection> out = new ArrayList<>();
        for (String id : index.sectionCoverage.ownersAt(location.edgeId(), location.offsetMm())) out.add(index.sections.get(id));
        return List.copyOf(out);
    }

    /** Blöcke, die den Ort abdecken (an einer Blockgrenze auf derselben Kante beide; Kantenende wie {@link #sectionsAt}). */
    public List<Block> blocksAt(TrackLocation location) {
        List<Block> out = new ArrayList<>();
        for (String id : index.blockCoverage.ownersAt(location.edgeId(), location.offsetMm())) out.add(index.blocks.get(id));
        return List.copyOf(out);
    }

    /** Bezirke, die den Ort abdecken (leer = Gleis ohne Bezirk). */
    public Set<Integer> districtsAt(TrackLocation location) {
        Set<Integer> out = new LinkedHashSet<>();
        for (String id : index.districtCoverage.ownersAt(location.edgeId(), location.offsetMm())) out.add(Integer.valueOf(id));
        return Set.copyOf(out);
    }

    /**
     * Meldeabschnitte, die sich mit dem Bereich überschneiden (mehr als {@link Coverage#TOL}; bloßes Berühren an
     * einer Grenze zählt nicht). Grundlage für die Belegung durch einen Zug (Schritt C1).
     */
    public List<DetectionSection> sectionsOverlapping(TrackRange range) {
        List<DetectionSection> out = new ArrayList<>();
        for (String id : index.sectionCoverage.ownersOverlapping(range.edgeId(), range.fromMm(), range.toMm()))
            out.add(index.sections.get(id));
        return List.copyOf(out);
    }

    /** Blöcke, die sich mit dem Bereich überschneiden (Berühren zählt nicht, wie {@link #sectionsOverlapping}). */
    public List<Block> blocksOverlapping(TrackRange range) {
        List<Block> out = new ArrayList<>();
        for (String id : index.blockCoverage.ownersOverlapping(range.edgeId(), range.fromMm(), range.toMm()))
            out.add(index.blocks.get(id));
        return List.copyOf(out);
    }

    /** Bezirke, die sich mit dem Bereich überschneiden (Berühren zählt nicht, wie {@link #sectionsOverlapping}). */
    public Set<Integer> districtsOverlapping(TrackRange range) {
        Set<Integer> out = new LinkedHashSet<>();
        for (String id : index.districtCoverage.ownersOverlapping(range.edgeId(), range.fromMm(), range.toMm()))
            out.add(Integer.valueOf(id));
        return Collections.unmodifiableSet(out);
    }

    /** Lage des Blocks im Graph; leer, wenn der Block unbekannt ist oder einen Geometriefehler hat. */
    public Optional<BlockGeometry> geometry(String blockId) { return Optional.ofNullable(index.geometries.get(blockId)); }

    /** Meldepunkte des Abschnitts je Fahrtrichtung, abgeleitet oder überschrieben. */
    public List<ReportPoint> reportPoints(String sectionId) { return index.reportPoints.getOrDefault(sectionId, List.of()); }

    public List<BlockSignal> signalsOfBlock(String blockId) {
        return signals().stream().filter(s -> s.blockId().equals(blockId)).toList();
    }

    /** Lagen des Signals im Graph (Blockende, Richtung aus dem Block hinaus); leer bei Fehlern. */
    public List<DirectedLocation> signalLocations(String signalId) {
        BlockSignal s = index.signals.get(signalId);
        if (s == null) return List.of();
        return geometry(s.blockId()).map(g -> g.ends(s.end())).orElse(List.of());
    }

    public List<HoldingArea> holdingAreasOfBlock(String blockId) {
        return holdingAreas().stream().filter(h -> h.blockIds().contains(blockId)).toList();
    }

    /** Länge eines Haltebereichs (Summe der Blocklängen); leer, wenn ein Block fehlt oder fehlerhaft ist. */
    public Optional<Double> holdingAreaLengthMm(String holdingAreaId) {
        HoldingArea h = index.holdingAreas.get(holdingAreaId);
        if (h == null) return Optional.empty();
        double sum = 0;
        for (String b : h.blockIds()) {
            BlockGeometry g = index.geometries.get(b);
            if (g == null) return Optional.empty();
            sum += g.lengthMm();
        }
        return Optional.of(sum);
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Sammelt die Auflagen; {@link #build()} prüft sie gegen den Graph. */
    public static final class Builder {
        private final TrackGraph graph;
        private final List<DetectionSection> sections = new ArrayList<>();
        private final List<Block> blocks = new ArrayList<>();
        private final List<HoldingArea> holdingAreas = new ArrayList<>();
        private final List<BoosterDistrict> districts = new ArrayList<>();
        private final List<BlockSignal> signals = new ArrayList<>();
        private final List<StopPoint> stopPoints = new ArrayList<>();
        private final List<SpeedChangePoint> speedChangePoints = new ArrayList<>();
        private final List<ReportPointOverride> overrides = new ArrayList<>();
        private final List<TurnoutAddress> turnoutAddresses = new ArrayList<>();
        private final Set<String> unverifiedLengths = new LinkedHashSet<>();
        private int recommendedSectionsPerBlock = 3;

        private Builder(TrackGraph graph) {
            if (graph == null) throw new IllegalArgumentException("Gleisgraph fehlt");
            this.graph = graph;
        }

        public Builder section(DetectionSection s) { sections.add(s); return this; }

        public Builder block(Block b) { blocks.add(b); return this; }

        public Builder holdingArea(HoldingArea h) { holdingAreas.add(h); return this; }

        public Builder district(BoosterDistrict d) { districts.add(d); return this; }

        public Builder signal(BlockSignal s) { signals.add(s); return this; }

        public Builder stopPoint(StopPoint p) { stopPoints.add(p); return this; }

        public Builder speedChangePoint(SpeedChangePoint p) { speedChangePoints.add(p); return this; }

        public Builder reportPointOverride(ReportPointOverride o) { overrides.add(o); return this; }

        public Builder turnoutAddress(TurnoutAddress a) { turnoutAddresses.add(a); return this; }

        /**
         * Markiert die Länge eines verlegten Gleisstücks als unbekannt bzw. geschätzt (z. B. Flexgleis noch nicht
         * vermessen); die Prüfung meldet das als Hinweis, weil Position und Bremsweg davon abhängen.
         */
        public Builder unverifiedLength(String pieceInstanceId) { unverifiedLengths.add(pieceInstanceId); return this; }

        /** Empfohlene Mindestzahl Meldeabschnitte je Block (Standard 3, Lastenheft Kapitel 4); darunter Hinweis. */
        public Builder recommendedSectionsPerBlock(int n) {
            if (n < 1) throw new IllegalArgumentException("Empfohlene Abschnittszahl muss mindestens 1 sein: " + n);
            recommendedSectionsPerBlock = n;
            return this;
        }

        public TrackLayout build() {
            for (Object o : List.of(sections, blocks, holdingAreas, districts, signals, stopPoints, speedChangePoints,
                    overrides, turnoutAddresses))
                if (((List<?>) o).contains(null)) throw new IllegalArgumentException("Leere Angabe im Gleisplan");
            return new TrackLayout(new Spec(graph, List.copyOf(sections), List.copyOf(blocks), List.copyOf(holdingAreas),
                    List.copyOf(districts), List.copyOf(signals), List.copyOf(stopPoints), List.copyOf(speedChangePoints),
                    List.copyOf(overrides), List.copyOf(turnoutAddresses), Set.copyOf(unverifiedLengths),
                    recommendedSectionsPerBlock));
        }
    }
}
