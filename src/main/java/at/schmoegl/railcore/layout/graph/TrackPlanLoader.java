package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.layout.graph.TrackPlan.Connection;
import at.schmoegl.railcore.layout.graph.TrackPlan.CrossingPlacement;
import at.schmoegl.railcore.layout.graph.TrackPlan.DoubleSlipPlacement;
import at.schmoegl.railcore.layout.graph.TrackPlan.PlacedPiece;
import at.schmoegl.railcore.layout.graph.TrackPlan.PlainPlacement;
import at.schmoegl.railcore.layout.graph.TrackPlan.TurnoutPlacement;
import at.schmoegl.railcore.layout.graph.TrackPlanReport.Phase;
import at.schmoegl.railcore.layout.graph.TrackPlanReport.Problem;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lädt einen Gleisplan (Schritt B3) in drei Stufen und liefert immer einen gemeinsamen {@link TrackPlanReport}:
 * <ol>
 *   <li>Datei lesen und prüfen ({@link TrackPlanJson#parse}): Syntax, Pflichtfelder, Typen, Verweise auf Katalog,
 *       Stücke und Anschlüsse – <b>bevor</b> der Graph gebaut wird;</li>
 *   <li>Gleisgraph über {@link TrackGraph.Builder} bauen (offene oder doppelt verbundene Anschlüsse,
 *       Grenzzeichen hinter dem Gleisende …);</li>
 *   <li>Gleisplan über {@link TrackLayout.Builder} bauen und prüfen ({@link TrackLayout#findings()}).</li>
 * </ol>
 * Scheitert eine Stufe, folgen die weiteren nicht; der Bericht nennt alle Fehler dieser Stufe.
 *
 * <p>Ohne Zustand und ohne Seiteneffekt außer dem Lesen der Datei; Ergebnis ist unveränderlich.
 */
public final class TrackPlanLoader {

    private TrackPlanLoader() {}

    /** Datei lesen (UTF-8) und laden; ein Lesefehler erscheint als Ladefehler {@link Phase#FILE}. */
    public static TrackPlanReport load(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return failed(null, List.of(new Problem(Phase.FILE, file.toString(), "Datei ist kein gültiges UTF-8")));
        } catch (IOException | RuntimeException e) {
            return failed(null, List.of(new Problem(Phase.FILE, file.toString(), "Datei nicht lesbar: " + e)));
        }
        return load(text);
    }

    /** Text einer Gleisplan-Datei laden. */
    public static TrackPlanReport load(String json) {
        TrackPlan plan;
        try {
            plan = TrackPlanJson.parse(json);
        } catch (TrackPlanException e) {
            return failed(null, e.problems());
        }
        return build(plan);
    }

    /** Graph und Gleisplan aus einem Dateimodell bauen (Verweise werden erneut geprüft). */
    public static TrackPlanReport build(TrackPlan plan) {
        if (plan == null) throw new IllegalArgumentException("Gleisplan fehlt");
        List<Problem> refs = plan.referenceProblems();
        if (!refs.isEmpty()) return failed(plan, refs);

        Map<String, TrackPiece> types = new HashMap<>();
        for (TrackPiece t : plan.catalog()) types.putIfAbsent(t.id(), t);
        TrackGraph.Builder gb = TrackGraph.builder();
        for (PlacedPiece p : plan.pieces()) {
            TrackPiece t = types.get(p.type());
            switch (p) {
                case PlainPlacement x -> {
                    if (x.lengthMm().isPresent()) gb.place(x.id(), t, x.lengthMm().getAsDouble());
                    else gb.place(x.id(), t);
                }
                case TurnoutPlacement x -> gb.placeTurnout(x.id(), (TrackPiece.TurnoutPiece) t, x.turnoutId(), x.branches());
                case CrossingPlacement x -> gb.placeCrossing(x.id(), (TrackPiece.CrossingPiece) t, x.dimensions());
                case DoubleSlipPlacement x -> gb.placeDoubleSlip(x.id(), (TrackPiece.DoubleSlipPiece) t, x.turnoutIdA(),
                        x.turnoutIdB(), x.routeLengths(), x.clearances());
            }
        }
        for (Connection c : plan.connections())
            switch (c.kind()) {
                case JOINT -> gb.connect(c.a().pieceId(), c.a().port(), c.b().pieceId(), c.b().port());
                case ISOLATING -> gb.connectIsolated(c.a().pieceId(), c.a().port(), c.b().pieceId(), c.b().port());
                case END -> gb.bufferStop(c.a().pieceId(), c.a().port());
            }
        TrackGraph graph;
        try {
            graph = gb.build();
        } catch (TrackGraphException e) {
            List<Problem> problems = new ArrayList<>();
            for (String s : e.problems()) problems.add(new Problem(Phase.GRAPH, "", s));
            return failed(plan, problems);
        }

        TrackLayout.Builder lb = TrackLayout.builder(graph).recommendedSectionsPerBlock(plan.recommendedSectionsPerBlock());
        for (TrackPlan.PlanSection s : plan.sections()) lb.section(s.resolve(graph));
        plan.blocks().forEach(lb::block);
        plan.holdingAreas().forEach(lb::holdingArea);
        for (TrackPlan.PlanDistrict d : plan.districts()) lb.district(d.resolve(graph));
        plan.signals().forEach(lb::signal);
        plan.stopPoints().forEach(lb::stopPoint);
        plan.speedChangePoints().forEach(lb::speedChangePoint);
        plan.reportPointOverrides().forEach(lb::reportPointOverride);
        plan.turnoutAddresses().forEach(lb::turnoutAddress);
        for (PlacedPiece p : plan.pieces()) if (p.lengthUnknown()) lb.unverifiedLength(p.id());
        return new TrackPlanReport(Optional.of(plan), List.of(), Optional.of(lb.build()));
    }

    private static TrackPlanReport failed(TrackPlan plan, List<Problem> problems) {
        return new TrackPlanReport(Optional.ofNullable(plan), problems, Optional.empty());
    }
}
