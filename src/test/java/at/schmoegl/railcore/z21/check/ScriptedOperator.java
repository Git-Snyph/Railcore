package at.schmoegl.railcore.z21.check;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bediener mit vorgegebenen Antworten. Eine Antwort wird erst nach der zugehörigen Frage „getippt“;
 * {@link #TIMEOUT} = keine Eingabe. {@link #typeAhead} simuliert vorab getippte (veraltete) Eingaben.
 */
final class ScriptedOperator implements Operator {

    static final String TIMEOUT = "\u0000keine-eingabe";

    final List<String> prompts = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    private final Deque<String> answers = new ArrayDeque<>();
    private String pending;
    String typeAhead;
    /** Simuliert geschlossene Eingabe (keine Konsole): poll() liefert wie die Konsole eine leere Zeile. */
    boolean inputClosed;

    ScriptedOperator(String... answers) { this.answers.addAll(List.of(answers)); }

    @Override
    public boolean isInputClosed() { return inputClosed; }

    @Override
    public void info(String text) { infos.add(text); }

    @Override
    public void prompt(String text) {
        prompts.add(text);
        String a = answers.poll();
        pending = a == null || a.equals(TIMEOUT) ? null : a;
    }

    @Override
    public String poll() {
        if (inputClosed) return "";
        if (typeAhead != null) {
            String t = typeAhead;
            typeAhead = null;
            return t;
        }
        String p = pending;
        pending = null;
        return p;
    }

    @Override
    public void discardPending() {
        typeAhead = null;
        pending = null;
    }

    int remainingAnswers() { return answers.size(); }
}
