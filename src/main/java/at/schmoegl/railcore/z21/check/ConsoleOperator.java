package at.schmoegl.railcore.z21.check;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Konsole als Bediener. Ein Hintergrund-Thread liest Zeilen von System.in in eine Warteschlange.
 * Ist die Eingabe geschlossen (keine Konsole), liefert {@link #poll()} eine leere Zeile – jede Bestätigung
 * gilt damit als abgelehnt.
 */
public final class ConsoleOperator implements Operator {

    private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private volatile boolean endOfInput;

    public ConsoleOperator() {
        String enc = System.getProperty("stdin.encoding");
        Charset cs = enc != null && Charset.isSupported(enc) ? Charset.forName(enc) : Charset.defaultCharset();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, cs))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            } catch (IOException ignored) {
                // Eingabe nicht lesbar: wie geschlossene Eingabe behandeln
            }
            endOfInput = true;
        }, "z21-pruefung-konsole");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void info(String text) {
        System.out.println(text);
        System.out.flush();
    }

    @Override
    public void prompt(String text) {
        System.out.println();
        System.out.println(text);
        System.out.print("> ");
        System.out.flush();
    }

    @Override
    public String poll() {
        String s = lines.poll();
        if (s == null && endOfInput) return "";
        return s;
    }

    @Override
    public void discardPending() { lines.clear(); }

    @Override
    public boolean isInputClosed() { return endOfInput && lines.isEmpty(); }
}
