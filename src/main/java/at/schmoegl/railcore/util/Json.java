package at.schmoegl.railcore.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimaler JSON-Leser und -Schreiber (ohne externe Bibliothek).
 * Objekte werden zu {@code Map<String,Object>}, Arrays zu {@code List<Object>}, Zahlen zu Double.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw j.err("Unerwartete Zeichen am Ende");
        return v;
    }

    private Object value() {
        if (i >= s.length()) throw err("Unerwartetes Ende");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws(); expect(':'); ws();
            m.put(k, value());
            ws();
            if (peek() == ',') { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws(); l.add(value()); ws();
            if (peek() == ',') { i++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("Zeichenkette nicht beendet");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> sb.append(e);
                }
            } else sb.append(c);
        }
    }

    private Double number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw err("Wert erwartet");
        return Double.parseDouble(s.substring(st, i));
    }

    private Object literal(String w, Object v) {
        if (!s.startsWith(w, i)) throw err("'" + w + "' erwartet");
        i += w.length();
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) i++;
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; }
            else break;
        }
    }

    private char peek() { return i < s.length() ? s.charAt(i) : 0; }

    private void expect(char c) {
        if (peek() != c) throw err("'" + c + "' erwartet");
        i++;
    }

    private IllegalArgumentException err(String msg) {
        int line = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) if (s.charAt(k) == '\n') line++;
        return new IllegalArgumentException("JSON-Fehler in Zeile " + line + ": " + msg);
    }

    // ---------------------------------------------------------------- Schreiben

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int ind) {
        if (v == null) sb.append("null");
        else if (v instanceof String str) quote(sb, str);
        else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d); else sb.append(d);
        } else if (v instanceof Boolean || v instanceof Enum<?>) {
            if (v instanceof Enum<?> e) quote(sb, e.name()); else sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int k = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, ind + 1); quote(sb, String.valueOf(e.getKey())); sb.append(": ");
                write(sb, e.getValue(), ind + 1);
                if (++k < m.size()) sb.append(',');
                sb.append('\n');
            }
            pad(sb, ind); sb.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) { sb.append("[]"); return; }
            boolean simple = l.stream().allMatch(x -> !(x instanceof Map) && !(x instanceof List));
            sb.append(simple ? "[" : "[\n");
            for (int k = 0; k < l.size(); k++) {
                if (!simple) pad(sb, ind + 1);
                write(sb, l.get(k), ind + 1);
                if (k < l.size() - 1) sb.append(simple ? ", " : ",\n");
            }
            if (!simple) { sb.append('\n'); pad(sb, ind); }
            sb.append(']');
        } else quote(sb, v.toString());
    }

    private static void pad(StringBuilder sb, int n) { sb.append("  ".repeat(n)); }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- Zugriffshilfen

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o == null ? Map.of() : (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o == null ? List.of() : (List<Object>) o; }

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : v.toString();
    }

    public static String reqStr(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) throw new IllegalArgumentException("Pflichtfeld fehlt: " + k + " in " + m);
        return v.toString();
    }

    public static double num(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        return v == null ? def : ((Number) v).doubleValue();
    }

    public static double reqNum(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) throw new IllegalArgumentException("Zahl fehlt: " + k + " in " + m);
        return n.doubleValue();
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v == null ? def : (Boolean) v;
    }
}
