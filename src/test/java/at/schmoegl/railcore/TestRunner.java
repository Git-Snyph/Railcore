package at.schmoegl.railcore;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Schlanker Testlauf ohne Fremdbibliothek: sucht im Test-Klassenpfad alle Klassen,
 * deren Name auf {@code Test} endet, und führt jede öffentliche, parameterlose
 * Methode aus, deren Name mit {@code test} beginnt (je Methode eine neue Instanz).
 * Ein Test gilt als fehlgeschlagen, wenn er eine Ausnahme oder einen Fehler wirft.
 * Werden keine Tests gefunden, schlägt der Lauf fehl (im Zweifel nicht grün).
 */
public final class TestRunner {
    private TestRunner() {}

    public static void main(String[] args) throws Exception {
        List<String> classNames = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path root = Path.of(entry);
            if (Files.isDirectory(root) && isTestOutput(root)) collect(root, classNames);
        }
        classNames.sort(Comparator.naturalOrder());

        int run = 0;
        List<String> failures = new ArrayList<>();
        for (String name : classNames) {
            Class<?> type = Class.forName(name);
            if (Modifier.isAbstract(type.getModifiers()) || type.isInterface()) continue;
            List<Method> methods = new ArrayList<>(List.of(type.getMethods()));
            methods.sort(Comparator.comparing(Method::getName));
            for (Method m : methods) {
                if (!m.getName().startsWith("test") || m.getParameterCount() != 0
                        || Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) continue;
                run++;
                String id = type.getSimpleName() + "." + m.getName();
                try {
                    Object instance = type.getDeclaredConstructor().newInstance();
                    m.invoke(instance);
                } catch (InvocationTargetException e) {
                    failures.add(id + ": " + e.getCause());
                    e.getCause().printStackTrace();
                } catch (ReflectiveOperationException e) {
                    failures.add(id + ": " + e);
                }
            }
        }

        if (run == 0) {
            System.err.println("Keine Tests gefunden.");
            System.exit(1);
        }
        if (!failures.isEmpty()) {
            System.err.println(failures.size() + " von " + run + " Tests fehlgeschlagen:");
            failures.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
        System.out.println(run + " Tests bestanden.");
    }

    /** Nur Test-Ausgaben durchsuchen, nicht die Hauptklassen (Gradle: build/classes/java/test). */
    private static boolean isTestOutput(Path root) {
        return root.getFileName() != null && root.getFileName().toString().equals("test");
    }

    private static void collect(Path root, List<String> out) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            files.map(p -> root.relativize(p).toString())
                 .filter(s -> s.endsWith("Test.class") && !s.contains("$"))
                 .map(s -> s.substring(0, s.length() - ".class".length()).replace(java.io.File.separatorChar, '.'))
                 .forEach(out::add);
        }
    }

    /** Hilfsprüfungen für Tests. */
    public static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError("erwartet <" + expected + ">, erhalten <" + actual + ">");
    }

    public static void assertThrows(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (expected.isInstance(t)) return;
            throw new AssertionError("erwartet " + expected.getSimpleName() + ", erhalten " + t, t);
        }
        throw new AssertionError("erwartet " + expected.getSimpleName() + ", keine Ausnahme");
    }
}
