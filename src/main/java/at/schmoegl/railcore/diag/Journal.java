package at.schmoegl.railcore.diag;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Ereignisprotokoll (Diagnose). Hält die letzten Einträge im Speicher und schreibt optional täglich rotierende
 * Protokolldateien. Thread-sicher.
 */
public final class Journal {

    /**
     * @param source  z. B. "z21", "melder", "zug:RJ 162", "sicherheit"
     * @param code    maschinenlesbarer Code, z. B. "GHOST_OCCUPANCY", für Filter und Statistik
     */
    public record Entry(Instant time, Severity severity, String source, String code, String message) {
        private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

        @Override
        public String toString() {
            return F.format(time) + " " + String.format("%-8s", severity) + " [" + source + "] " + code + ": " + message;
        }
    }

    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();
    private final Path logDir;
    private LocalDate fileDate;
    private BufferedWriter writer;
    private Severity minLevel = Severity.DEBUG;

    public Journal(int capacity, Path logDir) {
        this.capacity = capacity;
        this.logDir = logDir;
    }

    /** Nur im Speicher (für Tests). */
    public static Journal inMemory() { return new Journal(10_000, null); }

    public void setMinLevel(Severity s) { this.minLevel = s; }

    public void addListener(Consumer<Entry> l) { listeners.add(l); }

    public void debug(String src, String code, String msg) { log(Severity.DEBUG, src, code, msg); }
    public void info(String src, String code, String msg) { log(Severity.INFO, src, code, msg); }
    public void warn(String src, String code, String msg) { log(Severity.WARNING, src, code, msg); }
    public void error(String src, String code, String msg) { log(Severity.ERROR, src, code, msg); }
    public void critical(String src, String code, String msg) { log(Severity.CRITICAL, src, code, msg); }

    public void log(Severity sev, String source, String code, String message) {
        if (sev.ordinal() < minLevel.ordinal()) return;
        Entry e = new Entry(Instant.now(), sev, source, code, message);
        synchronized (this) {
            entries.addLast(e);
            while (entries.size() > capacity) entries.removeFirst();
            writeToFile(e);
        }
        for (Consumer<Entry> l : listeners) {
            try { l.accept(e); } catch (RuntimeException ex) { System.err.println("Journal-Listener: " + ex); }
        }
    }

    public synchronized List<Entry> recent(int max) {
        List<Entry> all = new ArrayList<>(entries);
        return all.subList(Math.max(0, all.size() - max), all.size());
    }

    public synchronized List<Entry> byCode(String code) {
        return entries.stream().filter(e -> e.code().equals(code)).toList();
    }

    public synchronized long count(Severity atLeast) {
        return entries.stream().filter(e -> e.severity().ordinal() >= atLeast.ordinal()).count();
    }

    private void writeToFile(Entry e) {
        if (logDir == null) return;
        try {
            LocalDate today = LocalDate.now();
            if (writer == null || !today.equals(fileDate)) {
                if (writer != null) writer.close();
                Files.createDirectories(logDir);
                writer = Files.newBufferedWriter(logDir.resolve("railcore-" + today + ".log"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                fileDate = today;
            }
            writer.write(e.toString());
            writer.newLine();
            if (e.severity().ordinal() >= Severity.WARNING.ordinal()) writer.flush();
        } catch (IOException ex) {
            System.err.println("Protokolldatei nicht schreibbar: " + ex);
        }
    }

    public synchronized void flush() {
        try { if (writer != null) writer.flush(); } catch (IOException ignored) { }
    }
}
