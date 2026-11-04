package at.schmoegl.railcore.layout.graph;

import at.schmoegl.railcore.core.TurnoutPosition;

import java.util.List;

/**
 * Knoten im Gleisgraph. Kanten enden an einem Anschluss ({@link Port}) eines Knotens; welche Fortsetzungen es von
 * einem Anschluss aus gibt und welche Weichenlage sie verlangen, legt der Knoten fest ({@link #transitions}).
 *
 * <p>Alle Übergänge sind in beide Richtungen gleich: Wer von {@code x} nach {@code y} darf, darf bei derselben
 * Lage auch von {@code y} nach {@code x}. Gegen die Spitze geht es also nur bei passender Lage (kein Auffahren).
 */
public sealed interface TrackNode
        permits TrackNode.BufferStopNode, TrackNode.JointNode, TrackNode.TurnoutNode, TrackNode.CrossingNode,
                TrackNode.DoubleSlipNode {

    String id();

    NodeKind kind();

    /** Alle Anschlüsse; an jedem endet genau eine Kante. */
    List<Port> ports();

    /** Mögliche Fortsetzungen, wenn man über {@code in} in den Knoten einfährt (leer = Gleisende). */
    List<Transition> transitions(Port in);

    /** Ausfahrt über {@code out}, wenn alle {@code requires} erfüllt sind. */
    record Transition(Port out, List<TurnoutRequirement> requires) {
        public Transition {
            requires = List.copyOf(requires);
        }
    }

    /** Gleisende oder Prellbock. */
    record BufferStopNode(String id) implements TrackNode {
        @Override public NodeKind kind() { return NodeKind.BUFFER_STOP; }
        @Override public List<Port> ports() { return List.of(Port.A); }
        @Override public List<Transition> transitions(Port in) {
            requirePort(this, in);
            return List.of();
        }
    }

    /** Stoß ({@code isolating = false}) oder Trennstelle ({@code isolating = true}) zwischen A und B. */
    record JointNode(String id, boolean isolating) implements TrackNode {
        @Override public NodeKind kind() { return isolating ? NodeKind.ISOLATING_JOINT : NodeKind.JOINT; }
        @Override public List<Port> ports() { return List.of(Port.A, Port.B); }
        @Override public List<Transition> transitions(Port in) {
            requirePort(this, in);
            return List.of(new Transition(in == Port.A ? Port.B : Port.A, List.of()));
        }
    }

    /**
     * Weiche, Knoten an der Spitze. Von den Anschlüssen {@code STRAIGHT} und {@code DIVERGING} geht je eine
     * Zweigkante mit der eigenen Länge aus {@link #branches()}.
     */
    record TurnoutNode(String id, String turnoutId, TurnoutBranches branches) implements TrackNode {
        @Override public NodeKind kind() { return NodeKind.TURNOUT; }
        @Override public List<Port> ports() { return List.of(Port.TIP, Port.STRAIGHT, Port.DIVERGING); }
        @Override public List<Transition> transitions(Port in) {
            requirePort(this, in);
            TurnoutRequirement straight = new TurnoutRequirement(turnoutId, TurnoutPosition.STRAIGHT);
            TurnoutRequirement diverging = new TurnoutRequirement(turnoutId, TurnoutPosition.DIVERGING);
            return switch (in) {
                case TIP -> List.of(new Transition(Port.STRAIGHT, List.of(straight)),
                                    new Transition(Port.DIVERGING, List.of(diverging)));
                case STRAIGHT -> List.of(new Transition(Port.TIP, List.of(straight)));
                default -> List.of(new Transition(Port.TIP, List.of(diverging)));
            };
        }
    }

    /** Kreuzung im Kreuzungspunkt: A1↔A2 und B1↔B2, ohne Weichenlage. */
    record CrossingNode(String id) implements TrackNode {
        @Override public NodeKind kind() { return NodeKind.CROSSING; }
        @Override public List<Port> ports() { return List.of(Port.A1, Port.A2, Port.B1, Port.B2); }
        @Override public List<Transition> transitions(Port in) {
            requirePort(this, in);
            Port out = switch (in) {
                case A1 -> Port.A2;
                case A2 -> Port.A1;
                case B1 -> Port.B2;
                default -> Port.B1;
            };
            return List.of(new Transition(out, List.of()));
        }
    }

    /**
     * Eine Seite ({@code sideA = true}: A1/A2, sonst B1/B2) einer Doppelkreuzungsweiche. Die vier Fahrwegkanten
     * verbinden die Knoten beider Seiten; jeder Fahrweg verlangt die Lage beider Hälften nach {@code logic}.
     * {@code clearances} sind die Grenzzeichen der verlegten DKW (beide Knoten tragen dieselben Werte).
     */
    record DoubleSlipNode(String id, boolean sideA, String turnoutIdA, String turnoutIdB, DoubleSlipLogic logic,
                          DoubleSlipClearances clearances) implements TrackNode {
        /** Außenanschlüsse dieser Seite. */
        public List<Port> outerPorts() { return sideA ? List.of(Port.A1, Port.A2) : List.of(Port.B1, Port.B2); }

        @Override public NodeKind kind() { return NodeKind.DOUBLE_SLIP; }

        @Override public List<Port> ports() {
            return sideA ? List.of(Port.A1, Port.A2, Port.A1_B1, Port.A1_B2, Port.A2_B1, Port.A2_B2)
                         : List.of(Port.B1, Port.B2, Port.A1_B1, Port.A1_B2, Port.A2_B1, Port.A2_B2);
        }

        @Override public List<Transition> transitions(Port in) {
            requirePort(this, in);
            if (in.isDoubleSlipRoute())
                return List.of(new Transition(sideA ? in.routeSideA() : in.routeSideB(), requires(in)));
            Port r1 = sideA ? Port.route(in, Port.B1) : Port.route(Port.A1, in);
            Port r2 = sideA ? Port.route(in, Port.B2) : Port.route(Port.A2, in);
            return List.of(new Transition(r1, requires(r1)), new Transition(r2, requires(r2)));
        }

        private List<TurnoutRequirement> requires(Port route) {
            DoubleSlipLogic.Setting s = logic.setting(route);
            return List.of(new TurnoutRequirement(turnoutIdA, s.sideA()), new TurnoutRequirement(turnoutIdB, s.sideB()));
        }
    }

    private static void requirePort(TrackNode node, Port in) {
        if (!node.ports().contains(in))
            throw new IllegalArgumentException("Knoten " + node.id() + " (" + node.kind() + ") hat keinen Anschluss " + in);
    }
}
