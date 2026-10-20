package dev.xmlgridview.intellij.model;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON as an element tree (Java port of parseJsonDocument in the TS core): the root value is an
 * element named {@code root}, an object member is a child named after its key, array items are
 * children named {@code item}, and a scalar is the element's text. Elements record their JSON
 * type and source offsets. Comments and trailing commas (JSONC) are accepted. See
 * fixtures/SCHEMA.md, "JSON".
 */
public final class JsonModelBuilder {
  public static final String ROOT = "root";
  public static final String ITEM = "item";
  private static final int OBJECT = 0, ARRAY = 1, STRING = 2, NUMBER = 3, BOOLEAN = 4, NULL = 5;
  private static final Pattern SCALAR = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null");
  private static final int CHUNK = 1 << 20;

  private final String s;
  private final int n;
  private final @Nullable DoubleConsumer progress;
  private int pos;
  private int nextCheck = CHUNK;
  private final ArrayList<XNode> elements = new ArrayList<>();
  private final ArrayList<Open> open = new ArrayList<>();
  private final Map<String, String> names = new java.util.HashMap<>();
  private @Nullable XNode root;

  private static final class Failure extends RuntimeException {
    final int offset;

    Failure(String message, int offset) {
      super(message, null, false, false);
      this.offset = offset;
    }
  }

  /** A container being parsed and its children so far. */
  private record Open(XNode node, ArrayList<XNode> children) {
  }

  private JsonModelBuilder(String s, @Nullable DoubleConsumer progress) {
    this.s = s;
    this.n = s.length();
    this.progress = progress;
  }

  /** Whether a file name looks like JSON (by extension). */
  public static boolean isJsonFileName(@NotNull String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    for (String ext : new String[]{".json", ".jsonc", ".json5", ".geojson", ".har", ".webmanifest"}) if (lower.endsWith(ext)) return true;
    return lower.endsWith(".babelrc") || lower.endsWith(".eslintrc");
  }

  public static @NotNull XmlDocumentModel build(@NotNull CharSequence text, @Nullable DoubleConsumer progress) {
    JsonModelBuilder b = new JsonModelBuilder(text.toString(), progress);
    List<ParseError> errors = new ArrayList<>();
    try {
      b.ws();
      if (b.pos >= b.n) throw new Failure("The document is empty", b.pos);
      b.value(ROOT, b.pos, null);
      b.ws();
      if (b.pos < b.n) throw new Failure("Unexpected '" + b.s.charAt(b.pos) + "' after the end of the document", b.pos);
    }
    catch (Failure f) {
      errors.add(error(b.s, f.getMessage(), f.offset));
      // Close what was open so the partial tree is usable.
      for (int i = b.open.size() - 1; i >= 0; i--) {
        Open o = b.open.get(i);
        o.node().finish(b.n, null, -1, 0, o.children().toArray(new XNode[0]));
      }
    }
    if (progress != null) progress.accept(1.0);
    b.elements.trimToSize();
    return new XmlDocumentModel(b.root, b.elements, errors, b.s, List.of(), true);
  }

  private void ws() {
    while (pos < n) {
      char c = s.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') pos++;
      else if (c == '/' && pos + 1 < n && s.charAt(pos + 1) == '/') {
        while (pos < n && s.charAt(pos) != '\n' && s.charAt(pos) != '\r') pos++;
      }
      else if (c == '/' && pos + 1 < n && s.charAt(pos + 1) == '*') {
        int end = s.indexOf("*/", pos + 2);
        if (end < 0) throw new Failure("Unterminated comment", pos);
        pos = end + 2;
      }
      else return;
    }
  }

  private XNode element(String name, int start, @Nullable XNode parent, int type) {
    int index;
    if (parent == null) index = root == null ? 0 : 1;
    else index = open.get(open.size() - 1).children().size();
    XNode el = new XNode(elements.size(), names.computeIfAbsent(name, k -> k), "", start, List.of(), Map.of(), s, parent, index);
    el.setJsonType(type);
    elements.add(el);
    if (parent != null) open.get(open.size() - 1).children().add(el);
    else if (root == null) root = el;
    return el;
  }

  /** A string at pos (the opening quote); returns the decoded value, or null when it has no escapes (then it is the source between the quotes). */
  private @Nullable String string() {
    int start = pos;
    pos++;
    StringBuilder out = null;
    int from = pos;
    while (true) {
      if (pos >= n) throw new Failure("Unterminated string", start);
      char c = s.charAt(pos);
      if (c == '"') {
        String result = out == null ? null : out.append(s, from, pos).toString();
        pos++;
        return result;
      }
      if (c == '\\') {
        if (out == null) out = new StringBuilder();
        out.append(s, from, pos);
        char e = pos + 1 < n ? s.charAt(pos + 1) : 0;
        pos += 2;
        switch (e) {
          case '"', '\\', '/' -> out.append(e);
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> {
            if (pos + 4 > n || !s.substring(pos, pos + 4).matches("[0-9a-fA-F]{4}")) throw new Failure("Invalid \\u escape", pos - 2);
            out.append((char)Integer.parseInt(s.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> throw new Failure("Invalid escape", pos - 2);
        }
        from = pos;
        continue;
      }
      if (c == '\n' || c == '\r') throw new Failure("Unterminated string", start);
      pos++;
    }
  }

  private String key() {
    int start = pos;
    String decoded = string();
    return decoded != null ? decoded : s.substring(start + 1, pos - 1);
  }

  private void value(String name, int start, @Nullable XNode parent) {
    if (pos >= nextCheck) {
      nextCheck = pos + CHUNK;
      ProgressManager.checkCanceled();
      if (progress != null) progress.accept((double)pos / n);
    }
    ws();
    int vStart = pos;
    char c = pos < n ? s.charAt(pos) : 0;
    if (c == '{' || c == '[') {
      XNode el = element(name, start, parent, c == '{' ? OBJECT : ARRAY);
      Open o = new Open(el, new ArrayList<>(4));
      open.add(o);
      pos++;
      char close = c == '{' ? '}' : ']';
      ws();
      boolean first = true;
      while (pos >= n || s.charAt(pos) != close) {
        if (pos >= n) throw new Failure("Expected '" + close + "'", pos);
        if (!first) {
          if (s.charAt(pos) != ',') throw new Failure("Expected ',' or '" + close + "'", pos);
          pos++;
          ws();
          if (pos < n && s.charAt(pos) == close) break;
        }
        first = false;
        if (c == '{') {
          if (pos >= n || s.charAt(pos) != '"') throw new Failure("Expected a property name in double quotes", pos);
          int keyStart = pos;
          String key = key();
          ws();
          if (pos >= n || s.charAt(pos) != ':') throw new Failure("Expected ':'", pos);
          pos++;
          value(key, keyStart, el);
        }
        else {
          ws();
          value(ITEM, pos, el);
        }
        ws();
      }
      pos++;
      open.remove(open.size() - 1);
      el.finish(pos, null, -1, 0, o.children().isEmpty() ? null : o.children().toArray(new XNode[0]));
      return;
    }
    if (c == '"') {
      String decoded = string();
      XNode el = element(name, start, parent, STRING);
      if (decoded != null) el.finish(pos, decoded, decoded.isEmpty() ? -1 : vStart, pos, null);
      else el.finish(pos, null, pos - vStart > 2 ? vStart + 1 : -1, pos - 1, null);
      return;
    }
    Matcher m = SCALAR.matcher(s).region(pos, Math.min(n, pos + 400));
    if (!m.lookingAt()) throw new Failure(pos >= n ? "Unexpected end of input" : "Unexpected '" + s.charAt(pos) + "'", pos);
    String v = m.group();
    int type = v.equals("true") || v.equals("false") ? BOOLEAN : v.equals("null") ? NULL : NUMBER;
    pos += v.length();
    XNode el = element(name, start, parent, type);
    el.finish(pos, null, vStart, pos, null);
  }

  private static ParseError error(String text, String message, int offset) {
    int line = 1;
    int lineStart = 0;
    for (int i = 0; i < offset && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
        lineStart = i + 1;
      }
    }
    return new ParseError(message, line, offset - lineStart + 1, offset);
  }
}
