package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON support for the value inspector, matching the "JSON value inspector"
 * section of fixtures/SCHEMA.md and the TypeScript reference (core/src/json.ts).
 * <p>
 * Values are represented as: {@link #NULL}, {@link Boolean}, {@link String},
 * {@link Num} (number literal kept verbatim), {@code List<Object>} (array) and
 * {@code LinkedHashMap<String, Object>} (object; duplicate keys keep their first
 * position with the last value).
 */
public final class Json {
  /** JSON null (distinct from Java null, which means "absent"). */
  public static final Object NULL = new Object() {
    @Override
    public String toString() {
      return "null";
    }
  };

  /** A number literal exactly as written, so large or precise values display unchanged. */
  public record Num(@NotNull String text) {
    @Override
    public String toString() {
      return text;
    }
  }

  /** Detection result: {@code value} is set for JSON; {@code error} is set when it looked like JSON but did not parse. */
  public record Detection(boolean isJson, @Nullable Object value, @Nullable String pretty, @Nullable SyntaxError error) {
    public boolean hasError() {
      return error != null;
    }
  }

  /** Where parsing stopped: offset into the trimmed text, 1-based line and column. */
  public record SyntaxError(@NotNull String message, int offset, int line, int column) {
  }

  public static final class ParseException extends Exception {
    private final int offset;

    ParseException(String message, int offset) {
      super(message);
      this.offset = offset;
    }

    public int offset() {
      return offset;
    }
  }

  private static final int MAX_DEPTH = 1000;
  private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

  private Json() {
  }

  public static boolean isContainer(@Nullable Object v) {
    return v instanceof List || v instanceof Map;
  }

  /**
   * Treats text as JSON when, trimmed of XML whitespace, it starts with '{' or '['
   * and parses strictly.
   */
  public static @NotNull Detection detect(@NotNull String text) {
    String t = TextUtil.xmlTrim(text);
    if (!(t.startsWith("{") || t.startsWith("["))) return new Detection(false, null, null, null);
    try {
      Object v = parse(t);
      return new Detection(true, v, pretty(v), null);
    }
    catch (ParseException e) {
      int line = 1;
      int col = 1;
      for (int i = 0; i < e.offset() && i < t.length(); i++) {
        if (t.charAt(i) == '\n') {
          line++;
          col = 1;
        }
        else col++;
      }
      return new Detection(false, null, null, new SyntaxError(e.getMessage(), e.offset(), line, col));
    }
  }

  /** Strict RFC 8259 parse of the whole string. */
  public static @NotNull Object parse(@NotNull String s) throws ParseException {
    Parser p = new Parser(s);
    Object v = p.value(0);
    p.ws();
    if (p.i < s.length()) throw p.fail("Unexpected text after the JSON value");
    return v;
  }

  private static final class Parser {
    final String s;
    int i;

    Parser(String s) {
      this.s = s;
    }

    ParseException fail(String msg) {
      return new ParseException(i >= s.length() ? "Unexpected end of JSON" : msg, i);
    }

    void ws() {
      while (i < s.length()) {
        char c = s.charAt(i);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
        else break;
      }
    }

    char at(int k) {
      return k < s.length() ? s.charAt(k) : '\0';
    }

    Object value(int depth) throws ParseException {
      if (depth > MAX_DEPTH) throw fail("Nesting is too deep");
      ws();
      char c = at(i);
      if (i >= s.length()) throw fail("Unexpected end of JSON");
      if (c == '{') {
        i++;
        LinkedHashMap<String, Object> obj = new LinkedHashMap<>();
        ws();
        if (at(i) == '}' && i < s.length()) {
          i++;
          return obj;
        }
        while (true) {
          ws();
          if (at(i) != '"' || i >= s.length()) throw fail("Expected a property name in double quotes");
          String key = str();
          ws();
          if (at(i) != ':' || i >= s.length()) throw fail("Expected ':' after property name");
          i++;
          obj.put(key, value(depth + 1)); // LinkedHashMap keeps the first position, last value wins
          ws();
          if (i < s.length() && at(i) == ',') {
            i++;
            continue;
          }
          if (i < s.length() && at(i) == '}') {
            i++;
            return obj;
          }
          throw fail("Expected ',' or '}'");
        }
      }
      if (c == '[') {
        i++;
        List<Object> arr = new ArrayList<>();
        ws();
        if (i < s.length() && at(i) == ']') {
          i++;
          return arr;
        }
        while (true) {
          arr.add(value(depth + 1));
          ws();
          if (i < s.length() && at(i) == ',') {
            i++;
            continue;
          }
          if (i < s.length() && at(i) == ']') {
            i++;
            return arr;
          }
          throw fail("Expected ',' or ']'");
        }
      }
      if (c == '"') return str();
      if (s.startsWith("true", i)) {
        i += 4;
        return Boolean.TRUE;
      }
      if (s.startsWith("false", i)) {
        i += 5;
        return Boolean.FALSE;
      }
      if (s.startsWith("null", i)) {
        i += 4;
        return NULL;
      }
      Matcher m = NUMBER.matcher(s);
      m.region(i, s.length());
      if (m.lookingAt() && m.end() > i) {
        String lit = s.substring(i, m.end());
        i = m.end();
        return new Num(lit);
      }
      throw fail("Unexpected character '" + c + "'");
    }

    String str() throws ParseException {
      i++; // opening quote
      StringBuilder out = new StringBuilder();
      int start = i;
      while (true) {
        if (i >= s.length()) throw fail("Unterminated string");
        char c = s.charAt(i);
        if (c == '"') {
          out.append(s, start, i);
          i++;
          return out.toString();
        }
        if (c < 0x20) throw fail("Control character in string");
        if (c == '\\') {
          out.append(s, start, i);
          char e = at(i + 1);
          switch (e) {
            case '"' -> out.append('"');
            case '\\' -> out.append('\\');
            case '/' -> out.append('/');
            case 'b' -> out.append('\b');
            case 'f' -> out.append('\f');
            case 'n' -> out.append('\n');
            case 'r' -> out.append('\r');
            case 't' -> out.append('\t');
            case 'u' -> {
              String hex = i + 6 <= s.length() ? s.substring(i + 2, i + 6) : "";
              if (!hex.matches("[0-9a-fA-F]{4}")) throw fail("Invalid \\u escape");
              out.append((char)Integer.parseInt(hex, 16));
              i += 4;
            }
            default -> throw fail("Invalid escape");
          }
          i += 2;
          start = i;
          continue;
        }
        i++;
      }
    }
  }

  /** Pretty print with 2-space indentation; strings escaped like JSON.stringify; numbers verbatim. */
  public static @NotNull String pretty(@Nullable Object v) {
    StringBuilder sb = new StringBuilder();
    pretty(v, "", sb);
    return sb.toString();
  }

  private static void pretty(Object v, String indent, StringBuilder sb) {
    if (v instanceof List<?> list) {
      if (list.isEmpty()) {
        sb.append("[]");
        return;
      }
      String inner = indent + "  ";
      sb.append("[\n");
      for (int k = 0; k < list.size(); k++) {
        if (k > 0) sb.append(",\n");
        sb.append(inner);
        pretty(list.get(k), inner, sb);
      }
      sb.append('\n').append(indent).append(']');
    }
    else if (v instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        sb.append("{}");
        return;
      }
      String inner = indent + "  ";
      sb.append("{\n");
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!first) sb.append(",\n");
        first = false;
        sb.append(inner).append(quote((String)e.getKey())).append(": ");
        pretty(e.getValue(), inner, sb);
      }
      sb.append('\n').append(indent).append('}');
    }
    else if (v instanceof String str) {
      sb.append(quote(str));
    }
    else {
      sb.append(primitiveText(v));
    }
  }

  /** A JSON string literal, escaped exactly as JavaScript's JSON.stringify does. */
  public static @NotNull String quote(@NotNull String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
    for (int k = 0; k < s.length(); k++) {
      char c = s.charAt(k);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          boolean lone = Character.isHighSurrogate(c) && !(k + 1 < s.length() && Character.isLowSurrogate(s.charAt(k + 1)))
                         || Character.isLowSurrogate(c) && !(k > 0 && Character.isHighSurrogate(s.charAt(k - 1)));
          if (c < 0x20 || lone) sb.append(String.format("\\u%04x", (int)c));
          else sb.append(c);
        }
      }
    }
    return sb.append('"').toString();
  }

  /** Display text of a primitive: strings as-is, numbers as written, true/false/null. */
  public static @NotNull String primitiveText(@Nullable Object v) {
    if (v instanceof String s) return s;
    if (v instanceof Num n) return n.text();
    if (v instanceof Boolean b) return b.toString();
    return "null";
  }

  /** Short label of a container, e.g. "{ 3 keys }" or "[ 5 items ]". */
  public static @NotNull String containerLabel(@NotNull Object v) {
    if (v instanceof List<?> l) return "[ " + l.size() + " item" + (l.size() == 1 ? "" : "s") + " ]";
    int n = ((Map<?, ?>)v).size();
    return "{ " + n + " key" + (n == 1 ? "" : "s") + " }";
  }

  /** Child count of a container. */
  public static int size(@NotNull Object v) {
    return v instanceof List<?> l ? l.size() : ((Map<?, ?>)v).size();
  }

  /** The value at a path of segments (Integer for array indices, String for keys), or null if absent. */
  public static @Nullable Object at(@NotNull Object root, @NotNull List<Object> path) {
    Object v = root;
    for (Object seg : path) {
      if (v instanceof List<?> l && seg instanceof Integer idx) v = idx >= 0 && idx < l.size() ? l.get(idx) : null;
      else if (v instanceof Map<?, ?> m) v = m.get(String.valueOf(seg));
      else return null;
      if (v == null) return null;
    }
    return v;
  }

  /** JSON Pointer (RFC 6901) for a path; "" is the root. */
  public static @NotNull String pointer(@NotNull List<Object> path) {
    StringBuilder sb = new StringBuilder();
    for (Object seg : path) sb.append('/').append(String.valueOf(seg).replace("~", "~0").replace("/", "~1"));
    return sb.toString();
  }

  /** Visits every container in document order with its path (root first). */
  public static void forEachContainer(@NotNull Object root, @NotNull BiConsumer<Object, List<Object>> fn) {
    walk(root, new ArrayList<>(), fn);
  }

  private static void walk(Object v, List<Object> path, BiConsumer<Object, List<Object>> fn) {
    if (!isContainer(v)) return;
    fn.accept(v, List.copyOf(path));
    if (v instanceof List<?> l) {
      for (int k = 0; k < l.size(); k++) {
        path.add(k);
        walk(l.get(k), path, fn);
        path.remove(path.size() - 1);
      }
    }
    else {
      for (Map.Entry<?, ?> e : ((Map<?, ?>)v).entrySet()) {
        path.add(e.getKey());
        walk(e.getValue(), path, fn);
        path.remove(path.size() - 1);
      }
    }
  }
}
