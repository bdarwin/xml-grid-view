package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Edits for JSON documents (Java port of jsonEdits.ts): value edits and the structural and grid
 * operations of {@link StructureEdits}, as text replacements that keep the formatting. See
 * fixtures/SCHEMA.md, "JSON edits".
 */
public final class JsonEdits {
  private JsonEdits() {
  }

  private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

  private static boolean isContainer(XNode el) {
    String t = el.jsonType();
    return "object".equals(t) || "array".equals(t);
  }

  private static boolean isScalar(XNode el) {
    return el.jsonType() != null && !isContainer(el);
  }

  private static boolean inArray(XNode el) {
    return el.parent() != null && "array".equals(el.parent().jsonType());
  }

  /** The JSON token for a new value: strings stay strings; a number, true, false or null is written as such when {@code typed}. */
  public static @NotNull String token(@NotNull String value, boolean typed) {
    if (typed && (NUMBER.matcher(value).matches() || value.equals("true") || value.equals("false") || value.equals("null"))) return value;
    return quote(value);
  }

  /** JSON string literal, escaped like JavaScript's JSON.stringify. */
  public static @NotNull String quote(@NotNull String s) {
    StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\b' -> b.append("\\b");
        case '\f' -> b.append("\\f");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20 || (Character.isSurrogate(c) && !validSurrogate(s, i))) b.append(String.format("\\u%04x", (int)c));
          else b.append(c);
        }
      }
    }
    return b.append('"').toString();
  }

  private static boolean validSurrogate(String s, int i) {
    char c = s.charAt(i);
    if (Character.isHighSurrogate(c)) return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
    return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
  }

  private static String newline(String s) {
    return s.contains("\r\n") ? "\r\n" : "\n";
  }

  private static int lineStart(String s, int offset) {
    return Math.max(s.lastIndexOf('\n', offset - 1), s.lastIndexOf('\r', offset - 1)) + 1;
  }

  private static @Nullable String ownLineIndent(String s, int offset) {
    int ls = lineStart(s, offset);
    for (int i = ls; i < offset; i++) if (s.charAt(i) != ' ' && s.charAt(i) != '\t') return null;
    return s.substring(ls, offset);
  }

  private static String lineIndent(String s, int offset) {
    int ls = lineStart(s, offset);
    int i = ls;
    while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
    return s.substring(ls, i);
  }

  private static String indentUnit(String s, XmlDocumentModel model) {
    for (XNode el : model.elements()) {
      if (el.parent() == null) continue;
      String b = ownLineIndent(s, el.start());
      String a = lineIndent(s, el.parent().start());
      if (b != null && b.length() > a.length() && b.startsWith(a)) return b.substring(a.length());
    }
    return "  ";
  }

  /** End of a member's key token (just after its closing quote). */
  private static int keyEnd(String s, XNode el) {
    int i = el.start() + 1;
    while (i < s.length() && s.charAt(i) != '"') i += s.charAt(i) == '\\' ? 2 : 1;
    return i + 1;
  }

  /** Where the value starts: after the key and colon for members, else the element's start. */
  static int valueStart(String s, XNode el) {
    if (el.parent() == null || inArray(el)) return el.start();
    int i = keyEnd(s, el);
    boolean colon = false;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
      else if (c == ':' && !colon) {
        colon = true;
        i++;
      }
      else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
        while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
      }
      else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
        int end = s.indexOf("*/", i + 2);
        i = end < 0 ? s.length() : end + 2;
      }
      else break;
    }
    return i;
  }

  /** The TS core's openEnd: the value start, plus one for an object or array. */
  private static int openEnd(String s, XNode el) {
    return valueStart(s, el) + (isContainer(el) ? 1 : 0);
  }

  private static int closeOf(XNode el) {
    return el.end() - 1;
  }

  private static String colon(String s, XmlDocumentModel model) {
    for (XNode el : model.elements()) {
      if (el.parent() == null || !"object".equals(el.parent().jsonType())) continue;
      String sep = s.substring(keyEnd(s, el), valueStart(s, el));
      return sep.matches("[ \\t]*:[ \\t]*") ? sep : ": ";
    }
    return ": ";
  }

  private static String entry(XNode parent, String key, String value, String sep) {
    return "array".equals(parent.jsonType()) ? value : quote(key) + sep + value;
  }

  private static ValueEdits.TextEdit appendEntries(String s, XmlDocumentModel model, XNode el, List<String> entries) {
    String nl = newline(s);
    int open = openEnd(s, el);
    if (el.childCount() > 0) {
      XNode last = el.child(el.childCount() - 1);
      String ind = ownLineIndent(s, last.start());
      StringBuilder t = new StringBuilder();
      for (String e : entries) t.append(',').append(ind == null ? " " : nl + ind).append(e);
      return new ValueEdits.TextEdit(last.end(), 0, t.toString());
    }
    String inner = s.substring(open, closeOf(el));
    if (inner.indexOf('\n') >= 0 || inner.indexOf('\r') >= 0) {
      String ind = lineIndent(s, el.start()) + indentUnit(s, model);
      StringBuilder t = new StringBuilder();
      for (int i = 0; i < entries.size(); i++) t.append(i > 0 ? "," : "").append(nl).append(ind).append(entries.get(i));
      t.append(nl).append(lineIndent(s, el.start()));
      return new ValueEdits.TextEdit(open, closeOf(el) - open, t.toString());
    }
    String pad = "object".equals(el.jsonType()) ? " " : "";
    return new ValueEdits.TextEdit(open, closeOf(el) - open, pad + String.join(", ", entries) + pad);
  }

  private static List<ValueEdits.TextEdit> deleteRuns(String s, XNode parent, Set<XNode> del) {
    List<XNode> kids = parent.children();
    List<ValueEdits.TextEdit> edits = new ArrayList<>();
    boolean all = true;
    for (XNode k : kids) if (!del.contains(k)) all = false;
    if (all) {
      int open = openEnd(s, parent);
      return List.of(new ValueEdits.TextEdit(open, closeOf(parent) - open, ""));
    }
    int i = 0;
    while (i < kids.size()) {
      if (!del.contains(kids.get(i))) {
        i++;
        continue;
      }
      int j = i;
      while (j + 1 < kids.size() && del.contains(kids.get(j + 1))) j++;
      if (j + 1 < kids.size()) {
        XNode next = kids.get(j + 1);
        edits.add(new ValueEdits.TextEdit(kids.get(i).start(), next.start() - kids.get(i).start(), ""));
      }
      else {
        XNode prev = kids.get(i - 1);
        edits.add(new ValueEdits.TextEdit(prev.end(), kids.get(j).end() - prev.end(), ""));
      }
      i = j + 1;
    }
    return edits;
  }

  private static List<ValueEdits.TextEdit> sorted(List<ValueEdits.TextEdit> edits) {
    List<ValueEdits.TextEdit> out = new ArrayList<>(edits);
    out.sort(Comparator.comparingInt(ValueEdits.TextEdit::offset).reversed());
    return List.copyOf(out);
  }

  private static String blankValue(String s, XNode el, String sep) {
    String t = el.jsonType();
    switch (t == null ? "" : t) {
      case "string":
        return "\"\"";
      case "number":
        return "0";
      case "boolean":
        return "false";
      case "null":
        return "null";
      case "array":
        return "[]";
      default: {
        int open = openEnd(s, el);
        if (el.childCount() == 0) return s.substring(open - 1, el.end());
        String nl = newline(s);
        String ind = ownLineIndent(s, el.child(0).start());
        List<String> parts = new ArrayList<>();
        for (XNode c : el.children()) parts.add(quote(c.tag()) + sep + blankValue(s, c, sep));
        if (ind == null) {
          String pad = s.charAt(open) == ' ' ? " " : "";
          return "{" + pad + String.join(", ", parts) + pad + "}";
        }
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < parts.size(); i++) b.append(i > 0 ? "," : "").append(nl).append(ind).append(parts.get(i));
        return b.append(nl).append(lineIndent(s, closeOf(el))).append('}').toString();
      }
    }
  }

  /** Keys that hold a number, boolean or null in some row of {@code array}: new values for them are written typed. */
  private static Set<String> typedKeys(@Nullable XNode array) {
    Set<String> out = new HashSet<>();
    if (array == null) return out;
    for (XNode row : array.children()) for (XNode c : row.children()) if (isScalar(c) && !"string".equals(c.jsonType())) out.add(c.tag());
    return out;
  }

  private static String buildObject(String s, @Nullable XNode template, Map<String, String> values, String sep, String nl, String rowIndent,
                                    String unit) {
    Set<String> typed = typedKeys(template == null ? null : template.parent());
    if (values.size() == 1 && values.containsKey("#text")) {
      return token(values.get("#text"), template != null && !"string".equals(template.jsonType()));
    }
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, String> e : values.entrySet()) {
      if (!e.getKey().equals("#text")) parts.add(quote(e.getKey()) + sep + token(e.getValue(), typed.contains(e.getKey())));
    }
    String ind = template != null ? (template.childCount() > 0 ? ownLineIndent(s, template.child(0).start()) : null)
                                  : unit.isEmpty() ? null : rowIndent + unit;
    if (ind == null) {
      String pad = template != null && s.charAt(openEnd(s, template)) != ' ' ? "" : " ";
      return "{" + pad + String.join(", ", parts) + pad + "}";
    }
    StringBuilder b = new StringBuilder("{");
    for (int i = 0; i < parts.size(); i++) b.append(i > 0 ? "," : "").append(nl).append(ind).append(parts.get(i));
    return b.append(nl).append(rowIndent).append('}').toString();
  }

  public static @NotNull ValueEdits.Result valueEdit(@NotNull XmlDocumentModel model, @NotNull ValueEdits.Target target, @NotNull String value) {
    String s = model.text();
    XNode el = model.byPath(target.path());
    if (el == null) return ValueEdits.Result.fail("The element no longer exists.");
    if (model.hasErrors()) return ValueEdits.Result.fail("The document is not valid JSON; fix it before editing values.");
    if (target.isAttr()) return ValueEdits.Result.fail("JSON has no attributes.");
    if (!isScalar(el)) {
      return ValueEdits.Result.fail("\"" + el.tag() + "\" is an " + el.jsonType() + "; edit its " + ("array".equals(el.jsonType()) ? "items" : "members") + " instead.");
    }
    int v = valueStart(s, el);
    return ValueEdits.Result.ok(new ValueEdits.TextEdit(v, el.end() - v, token(value, !"string".equals(el.jsonType()))));
  }

  public static @NotNull StructureEdits.Result compute(@NotNull XmlDocumentModel model, @NotNull StructureEdits.Op op) {
    String s = model.text();
    if (model.hasErrors()) return StructureEdits.Result.fail("The document is not valid JSON; fix it before editing.");
    String sep = colon(s, model);
    String nl = newline(s);
    switch (op.kind()) {
      case DELETE_ELEMENTS -> {
        Map<XNode, Set<XNode>> byParent = new LinkedHashMap<>();
        Set<XNode> all = new HashSet<>();
        for (String p : op.paths()) {
          XNode el = model.byPath(p);
          if (el == null) return StructureEdits.Result.fail("The element no longer exists.");
          if (el.parent() == null) return StructureEdits.Result.fail("The root value can't be deleted.");
          byParent.computeIfAbsent(el.parent(), k -> new LinkedHashSet<>()).add(el);
          all.add(el);
        }
        List<ValueEdits.TextEdit> edits = new ArrayList<>();
        for (Map.Entry<XNode, Set<XNode>> e : byParent.entrySet()) {
          boolean covered = false;
          for (XNode a = e.getKey(); a != null; a = a.parent()) if (all.contains(a)) covered = true;
          if (!covered) edits.addAll(deleteRuns(s, e.getKey(), e.getValue()));
        }
        return new StructureEdits.Result(sorted(edits), null);
      }
      case SET_CELLS -> {
        return setCells(model, op.cells(), sep);
      }
      default -> {
      }
    }
    XNode el = model.byPath(op.path());
    if (el == null) return StructureEdits.Result.fail("The element no longer exists.");
    boolean isRoot = el.parent() == null;
    switch (op.kind()) {
      case DELETE_ELEMENT -> {
        if (isRoot) return StructureEdits.Result.fail("The root value can't be deleted.");
        return new StructureEdits.Result(deleteRuns(s, el.parent(), Set.of(el)), null);
      }
      case DELETE_ATTR, ADD_ATTR -> {
        return StructureEdits.Result.fail("JSON has no attributes.");
      }
      case RENAME_ELEMENT -> {
        if (isRoot || inArray(el)) return StructureEdits.Result.fail("Only object members have a name.");
        if (op.name().equals(el.tag())) return new StructureEdits.Result(List.of(), null);
        for (XNode sib : el.parent().children()) {
          if (sib != el && sib.tag().equals(op.name())) return StructureEdits.Result.fail("The object already has a member \"" + op.name() + "\".");
        }
        return StructureEdits.Result.ok(new ValueEdits.TextEdit(el.start(), keyEnd(s, el) - el.start(), quote(op.name())));
      }
      case INSERT_ELEMENT -> {
        StructureEdits.Where where = op.where() == null ? StructureEdits.Where.CHILD : op.where();
        XNode container = where == StructureEdits.Where.CHILD ? el : el.parent();
        if (container == null) return StructureEdits.Result.fail("Values can't be added next to the root (a document has one root).");
        if (!isContainer(container)) {
          return StructureEdits.Result.fail("\"" + container.tag() + "\" is a " + container.jsonType() + "; only objects and arrays have members or items.");
        }
        if ("object".equals(container.jsonType())) {
          for (XNode sib : container.children()) {
            if (sib.tag().equals(op.name())) return StructureEdits.Result.fail("The object already has a member \"" + op.name() + "\".");
          }
        }
        String text = entry(container, op.name(), "\"\"", sep);
        if (where == StructureEdits.Where.CHILD) return StructureEdits.Result.ok(appendEntries(s, model, el, List.of(text)));
        String ind = ownLineIndent(s, el.start());
        String gap = ind == null ? " " : nl + ind;
        if (where == StructureEdits.Where.AFTER) return StructureEdits.Result.ok(new ValueEdits.TextEdit(el.end(), 0, "," + gap + text));
        return StructureEdits.Result.ok(new ValueEdits.TextEdit(el.start(), 0, text + "," + gap));
      }
      case DUPLICATE_ELEMENT -> {
        if (isRoot) return StructureEdits.Result.fail("The root value can't be duplicated (a document has one root).");
        if (!inArray(el)) return StructureEdits.Result.fail("Object keys must be unique; duplicate array items instead.");
        String ind = ownLineIndent(s, el.start());
        return StructureEdits.Result.ok(new ValueEdits.TextEdit(el.end(), 0, "," + (ind == null ? " " : nl + ind) + s.substring(el.start(), el.end())));
      }
      case NEW_ROW -> {
        if (isRoot) return StructureEdits.Result.fail("The root value can't be repeated (a document has one root).");
        if (!inArray(el)) return StructureEdits.Result.fail("New rows are added to arrays.");
        String ind = ownLineIndent(s, el.start());
        return StructureEdits.Result.ok(new ValueEdits.TextEdit(el.end(), 0, "," + (ind == null ? " " : nl + ind) + blankValue(s, el, sep)));
      }
      case MOVE_ELEMENT -> {
        if (isRoot) return StructureEdits.Result.fail("The root value can't be moved.");
        List<XNode> sibs = el.parent().children();
        int i = sibs.indexOf(el);
        int j = op.up() ? i - 1 : i + 1;
        if (j < 0 || j >= sibs.size()) return StructureEdits.Result.fail("It is already the " + (op.up() ? "first" : "last") + ".");
        XNode other = sibs.get(j);
        XNode a = other.start() < el.start() ? other : el;
        XNode b = a == other ? el : other;
        return new StructureEdits.Result(List.of(new ValueEdits.TextEdit(b.start(), b.end() - b.start(), s.substring(a.start(), a.end())),
                                                 new ValueEdits.TextEdit(a.start(), a.end() - a.start(), s.substring(b.start(), b.end()))), null);
      }
      case ADD_COLUMN -> {
        List<XNode> rows = new ArrayList<>();
        for (XNode c : el.children()) if (c.tag().equals(op.group()) && "object".equals(c.jsonType())) rows.add(c);
        if (rows.isEmpty()) return StructureEdits.Result.fail("No objects to add \"" + op.name() + "\" to.");
        List<ValueEdits.TextEdit> edits = new ArrayList<>();
        for (XNode row : rows) {
          boolean has = false;
          for (XNode c : row.children()) if (c.tag().equals(op.name())) has = true;
          if (!has) edits.add(appendEntries(s, model, row, List.of(quote(op.name()) + sep + "\"\"")));
        }
        if (edits.isEmpty()) return StructureEdits.Result.fail("Every row already has \"" + op.name() + "\".");
        return new StructureEdits.Result(sorted(edits), null);
      }
      case DELETE_COLUMN -> {
        String key = op.name();
        if (key.equals("#text")) return StructureEdits.Result.fail("The value column can't be deleted; clear its cells instead.");
        List<ValueEdits.TextEdit> edits = new ArrayList<>();
        for (XNode row : el.children()) {
          if (!row.tag().equals(op.group()) || !"object".equals(row.jsonType())) continue;
          Set<XNode> del = new LinkedHashSet<>();
          for (XNode c : row.children()) if (c.tag().equals(key)) del.add(c);
          if (!del.isEmpty()) edits.addAll(deleteRuns(s, row, del));
        }
        if (edits.isEmpty()) return StructureEdits.Result.fail("No row has \"" + key + "\".");
        return new StructureEdits.Result(sorted(edits), null);
      }
      case INSERT_ROWS -> {
        if (op.rows().isEmpty()) return new StructureEdits.Result(List.of(), null);
        if (!"array".equals(el.jsonType())) return StructureEdits.Result.fail("Rows are added to arrays.");
        XNode template = el.childCount() > 0 ? el.child(el.childCount() - 1) : null;
        String rowIndent;
        if (template != null) {
          String own = ownLineIndent(s, template.start());
          rowIndent = own != null ? own : lineIndent(s, template.start());
        }
        else rowIndent = lineIndent(s, el.start()) + indentUnit(s, model);
        String inner = s.substring(openEnd(s, el), closeOf(el));
        String unit = template == null && inner.indexOf('\n') < 0 && inner.indexOf('\r') < 0 ? "" : indentUnit(s, model);
        List<String> texts = new ArrayList<>();
        for (Map<String, String> r : op.rows()) texts.add(buildObject(s, template, r, sep, nl, rowIndent, unit));
        return StructureEdits.Result.ok(appendEntries(s, model, el, texts));
      }
      default -> {
        return StructureEdits.Result.fail("This operation isn't available for JSON.");
      }
    }
  }

  private static StructureEdits.Result setCells(XmlDocumentModel model, List<StructureEdits.Cell> cells, String sep) {
    String s = model.text();
    Map<XNode, List<StructureEdits.Cell>> byRow = new IdentityHashMap<>();
    List<XNode> order = new ArrayList<>();
    for (StructureEdits.Cell c : cells) {
      XNode el = model.byPath(c.path());
      if (el == null) return StructureEdits.Result.fail("The element no longer exists.");
      if (!byRow.containsKey(el)) order.add(el);
      byRow.computeIfAbsent(el, k -> new ArrayList<>()).add(c);
    }
    List<ValueEdits.TextEdit> edits = new ArrayList<>();
    for (XNode row : order) {
      List<String> added = new ArrayList<>();
      Set<String> typed = row.parent() != null && "array".equals(row.parent().jsonType()) ? typedKeys(row.parent()) : Set.of();
      for (StructureEdits.Cell c : byRow.get(row)) {
        if (c.key().equals("#text")) {
          if (!isScalar(row)) return StructureEdits.Result.fail("Only a single value can be set here.");
          int v = valueStart(s, row);
          edits.add(new ValueEdits.TextEdit(v, row.end() - v, token(c.value(), !"string".equals(row.jsonType()))));
          continue;
        }
        if (!"object".equals(row.jsonType())) return StructureEdits.Result.fail("Only objects have named values.");
        XNode m = null;
        for (XNode ch : row.children()) {
          if (ch.tag().equals(c.key())) {
            m = ch;
            break;
          }
        }
        if (m != null) {
          if (!isScalar(m)) {
            return StructureEdits.Result.fail("\"" + c.key() + "\" is an " + m.jsonType() + "; edit its " + ("array".equals(m.jsonType()) ? "items" : "members") + " instead.");
          }
          int v = valueStart(s, m);
          edits.add(new ValueEdits.TextEdit(v, m.end() - v, token(c.value(), !"string".equals(m.jsonType()))));
        }
        else added.add(quote(c.key()) + sep + token(c.value(), typed.contains(c.key())));
      }
      if (!added.isEmpty()) edits.add(appendEntries(s, model, row, added));
    }
    return new StructureEdits.Result(sorted(edits), null);
  }
}
