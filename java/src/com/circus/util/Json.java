package com.circus.util;

import java.util.*;

/**
 * Zero-dependency, high-speed RFC 8259 JSON parser and compact serializer.
 */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        if (text == null) return null;
        Parser p = new Parser(text);
        Object val = p.parseValue();
        p.skipWs();
        if (p.pos != p.len) {
            throw new IllegalArgumentException("Trailing JSON content at index " + p.pos);
        }
        return val;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String text) {
        Object v = parse(text);
        if (!(v instanceof List<?>)) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        return (List<Object>) v;
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    public static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Integer i) {
            sb.append(i.intValue());
        } else if (value instanceof Long l) {
            sb.append(l.longValue());
        } else if (value instanceof Double d) {
            double v = d.doubleValue();
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                sb.append("null");
            } else if (v == Math.rint(v) && Math.abs(v) < 1e15) {
                // Keep clean representation like Python floats or ints when exact
                sb.append(v);
            } else {
                sb.append(v);
            }
        } else if (value instanceof Float f) {
            float v = f.floatValue();
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                sb.append("null");
            } else {
                sb.append(v);
            }
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue() ? "true" : "false");
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0, n = list.size(); i < n; i++) {
                if (i > 0) sb.append(',');
                writeValue(sb, list.get(i));
            }
            sb.append(']');
        } else if (value instanceof Collection<?> col) {
            sb.append('[');
            boolean first = true;
            for (Object item : col) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else if (value instanceof int[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
        } else if (value instanceof double[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
        } else if (value instanceof Object[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                writeValue(sb, arr[i]);
            }
            sb.append(']');
        } else if (value instanceof Number n) {
            sb.append(n);
        } else {
            writeString(sb, value.toString());
        }
    }

    public static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // Convenience typed accessors
    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isEmpty()) return Integer.parseInt(s);
        return def;
    }

    public static double dblVal(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isEmpty()) return Double.parseDouble(s);
        return def;
    }

    public static boolean boolVal(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        return def;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> listVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v instanceof List<?> l) ? (List<Object>) l : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v instanceof Map<?, ?> mp) ? (Map<String, Object>) mp : null;
    }

    private static final class Parser {
        final String s;
        final int len;
        int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
        }

        void skipWs() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parseValue() {
            skipWs();
            if (pos >= len) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield parseNumber();
                    throw new IllegalArgumentException("Unexpected char '" + c + "' at " + pos);
                }
            };
        }

        Object parseLiteral(String lit, Object val) {
            if (s.startsWith(lit, pos)) {
                pos += lit.length();
                return val;
            }
            throw new IllegalArgumentException("Expected " + lit + " at " + pos);
        }

        Map<String, Object> parseObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < len && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (pos >= len || s.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected string key at " + pos);
                }
                String key = parseString();
                skipWs();
                if (pos >= len || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected ':' at " + pos);
                }
                pos++;
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (pos >= len) throw new IllegalArgumentException("Unterminated object");
                char sep = s.charAt(pos++);
                if (sep == '}') return map;
                if (sep != ',') throw new IllegalArgumentException("Expected ',' or '}' at " + (pos - 1));
            }
        }

        List<Object> parseArray() {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < len && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (pos >= len) throw new IllegalArgumentException("Unterminated array");
                char sep = s.charAt(pos++);
                if (sep == ']') return list;
                if (sep != ',') throw new IllegalArgumentException("Expected ',' or ']' at " + (pos - 1));
            }
        }

        String parseString() {
            pos++; // '"'
            int start = pos;
            StringBuilder sb = null;
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == '"') {
                    String res = (sb == null) ? s.substring(start, pos) : sb.append(s, start, pos).toString();
                    pos++;
                    return res;
                }
                if (c == '\\') {
                    if (sb == null) sb = new StringBuilder(Math.max(16, pos - start + 16));
                    sb.append(s, start, pos);
                    pos++;
                    if (pos >= len) throw new IllegalArgumentException("Unterminated escape");
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > len) throw new IllegalArgumentException("Bad unicode escape");
                            int code = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) code);
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid escape \\" + esc);
                    }
                    start = pos;
                } else {
                    pos++;
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        Number parseNumber() {
            int start = pos;
            boolean isFloat = false;
            if (s.charAt(pos) == '-') pos++;
            while (pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            if (pos < len && s.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            }
            if (pos < len && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < len && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            }
            String num = s.substring(start, pos);
            if (isFloat) {
                return Double.parseDouble(num);
            }
            long l = Long.parseLong(num);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return (int) l;
            }
            return l;
        }
    }
}
