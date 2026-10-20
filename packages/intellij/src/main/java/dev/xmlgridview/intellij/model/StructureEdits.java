package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural edits (Java port of computeStructureEdit in the TS core): delete, insert, duplicate
 * and rename elements, add and delete attributes. Each operation becomes a few text replacements
 * in descending offset order, applied as one undoable edit. Formatting is kept. See
 * fixtures/SCHEMA.md, "Structural edits".
 */
public final class StructureEdits {
  private StructureEdits() {
  }

  public enum Kind {
    DELETE_ELEMENT, DELETE_ATTR, ADD_ATTR, RENAME_ELEMENT, INSERT_ELEMENT, DUPLICATE_ELEMENT,
    NEW_ROW, MOVE_ELEMENT, DELETE_ELEMENTS, ADD_COLUMN, DELETE_COLUMN, SET_CELLS, INSERT_ROWS
  }

  public enum Where { BEFORE, AFTER, CHILD }

  /** A grid cell to set: the row element, a column key ({@code @attr}, a child name or {@code #text}) and the value. */
  public record Cell(@NotNull String path, @NotNull String key, @NotNull String value) {
  }

  /**
   * An operation on the element at {@code path}; the other fields as the kind needs. Grid operations
   * take the rows as the children of {@code path} named {@code group}.
   */
  public record Op(@NotNull Kind kind, @NotNull String path, @Nullable String name, @Nullable String value, @Nullable Where where,
                   boolean up, boolean sameName, @NotNull List<String> paths, @Nullable String group, @NotNull List<Cell> cells,
                   @NotNull List<Map<String, String>> rows) {
    private Op(Kind kind, String path, @Nullable String name, @Nullable String value, @Nullable Where where) {
      this(kind, path, name, value, where, false, false, List.of(), null, List.of(), List.of());
    }

    public static Op deleteElement(String path) { return new Op(Kind.DELETE_ELEMENT, path, null, null, null); }

    public static Op deleteAttr(String path, String name) { return new Op(Kind.DELETE_ATTR, path, name, null, null); }

    public static Op addAttr(String path, String name, String value) { return new Op(Kind.ADD_ATTR, path, name, value, null); }

    public static Op rename(String path, String name) { return new Op(Kind.RENAME_ELEMENT, path, name, null, null); }

    public static Op insert(String path, Where where, String name) { return new Op(Kind.INSERT_ELEMENT, path, name, null, where); }

    public static Op duplicate(String path) { return new Op(Kind.DUPLICATE_ELEMENT, path, null, null, null); }

    /** A copy of the element with every value emptied, right after it. */
    public static Op newRow(String path) { return new Op(Kind.NEW_ROW, path, null, null, null); }

    /** Swaps the element with its previous or next element sibling (of the same name when {@code sameName}). */
    public static Op move(String path, boolean up, boolean sameName) {
      return new Op(Kind.MOVE_ELEMENT, path, null, null, null, up, sameName, List.of(), null, List.of(), List.of());
    }

    public static Op deleteElements(List<String> paths) {
      return new Op(Kind.DELETE_ELEMENTS, "", null, null, null, false, false, List.copyOf(paths), null, List.of(), List.of());
    }

    /** Adds an attribute ({@code @name}) or empty child element to every row that lacks it. */
    public static Op addColumn(String path, String group, String name) {
      return new Op(Kind.ADD_COLUMN, path, name, null, null, false, false, List.of(), group, List.of(), List.of());
    }

    public static Op deleteColumn(String path, String group, String key) {
      return new Op(Kind.DELETE_COLUMN, path, key, null, null, false, false, List.of(), group, List.of(), List.of());
    }

    public static Op setCells(List<Cell> cells) {
      return new Op(Kind.SET_CELLS, "", null, null, null, false, false, List.of(), null, List.copyOf(cells), List.of());
    }

    /** New rows (column key to value, in column order) after the last row, or as children when there are none. */
    public static Op insertRows(String path, String group, List<Map<String, String>> rows) {
      return new Op(Kind.INSERT_ROWS, path, null, null, null, false, false, List.of(), group, List.of(), List.copyOf(rows));
    }
  }

  /** The edits, or a human-readable reason the operation was refused. */
  public record Result(@Nullable List<ValueEdits.TextEdit> edits, @Nullable String error) {
    static Result ok(ValueEdits.TextEdit... e) {
      return new Result(List.of(e), null);
    }

    static Result fail(String message) {
      return new Result(null, message);
    }

    public boolean isOk() {
      return edits != null;
    }
  }

  private static final String NAME_START =
    "A-Za-z_\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u02FF\\u0370-\\u037D\\u037F-\\u1FFF\\u200C\\u200D\\u2070-\\u218F\\u2C00-\\u2FEF\\u3001-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFFD";
  private static final String NC_NAME = "[" + NAME_START + "][" + NAME_START + "\\-.0-9\\u00B7\\u0300-\\u036F\\u203F\\u2040]*";
  private static final Pattern QNAME = Pattern.compile("(" + NC_NAME + ":)?" + NC_NAME);

  /** A qualified name: an XML NCName, optionally {@code prefix:} another NCName. */
  public static boolean isQName(@NotNull String name) {
    return QNAME.matcher(name).matches();
  }

  private static boolean isWs(char c) {
    return c == ' ' || c == '\t';
  }

  private static int lineStart(String s, int offset) {
    return Math.max(s.lastIndexOf('\n', offset - 1), s.lastIndexOf('\r', offset - 1)) + 1;
  }

  /** The indentation before {@code offset} when only spaces/tabs precede it on its line, else null. */
  private static @Nullable String ownLineIndent(String s, int offset) {
    int ls = lineStart(s, offset);
    for (int i = ls; i < offset; i++) if (!isWs(s.charAt(i))) return null;
    return s.substring(ls, offset);
  }

  /** Offset just past the line break following {@code offset} when only spaces/tabs come first, else -1. */
  private static int restOfLineEnd(String s, int offset) {
    int i = offset;
    while (i < s.length() && isWs(s.charAt(i))) i++;
    if (s.startsWith("\r\n", i)) return i + 2;
    if (i < s.length() && (s.charAt(i) == '\n' || s.charAt(i) == '\r')) return i + 1;
    if (i == s.length()) return i;
    return -1;
  }

  private static String newline(String s) {
    return s.contains("\r\n") ? "\r\n" : "\n";
  }

  private static boolean isXmlWs(char c) {
    return TextUtil.isXmlWhitespace(c);
  }

  private static String indentUnit(String s, XmlDocumentModel model) {
    for (XNode el : model.elements()) {
      XNode parent = el.parent();
      if (parent == null) continue;
      String a = ownLineIndent(s, parent.start());
      String b = ownLineIndent(s, el.start());
      if (a != null && b != null && b.length() > a.length() && b.startsWith(a)) return b.substring(a.length());
    }
    return "  ";
  }

  private static boolean prefixInScope(XNode el, String prefix) {
    if (prefix.equals("xml")) return true;
    for (XNode e = el; e != null; e = e.parent()) if (e.nsDecls().containsKey(prefix)) return true;
    return false;
  }

  private static @Nullable String checkName(String name, XNode el, String what) {
    if (!isQName(name)) return "\"" + name + "\" is not a valid XML " + what + " name.";
    int colon = name.indexOf(':');
    if (colon > 0 && !prefixInScope(el, name.substring(0, colon))) return "The prefix \"" + name.substring(0, colon) + "\" is not declared here.";
    return null;
  }

  private static ValueEdits.TextEdit insertAfter(String s, XNode after, String text) {
    String indent = ownLineIndent(s, after.start());
    if (indent == null) return new ValueEdits.TextEdit(after.end(), 0, text);
    return new ValueEdits.TextEdit(after.end(), 0, newline(s) + indent + text);
  }

  public static @NotNull Result compute(@NotNull XmlDocumentModel model, @NotNull Op op) {
    String s = model.text();
    if (model.hasErrors()) return Result.fail("The document is not well-formed; fix it before editing.");
    if (op.kind() == Kind.DELETE_ELEMENTS) return deleteElements(model, op.paths());
    if (op.kind() == Kind.SET_CELLS) return setCells(model, op.cells());
    XNode el = model.byPath(op.path());
    if (el == null) return Result.fail("The element no longer exists.");
    boolean isRoot = el.parent() == null;
    int openEnd = ValueEdits.startTagEnd(s, el);
    if (openEnd < 0) return Result.fail("Could not locate the start tag.");
    int close = openEnd == el.end() ? -1 : s.lastIndexOf("</", el.end() - 1);

    switch (op.kind()) {
      case DELETE_ELEMENT -> {
        if (isRoot) return Result.fail("The root element can't be deleted.");
        String indent = ownLineIndent(s, el.start());
        int end = restOfLineEnd(s, el.end());
        if (indent != null && end >= 0) {
          int start = el.start() - indent.length();
          return Result.ok(new ValueEdits.TextEdit(start, end - start, ""));
        }
        return Result.ok(new ValueEdits.TextEdit(el.start(), el.end() - el.start(), ""));
      }
      case DELETE_ATTR -> {
        XAttr attr = el.attr(op.name());
        if (attr == null) return Result.fail("Attribute " + op.name() + " does not exist on <" + el.tag() + ">.");
        int start = attr.start();
        while (start > 0 && isXmlWs(s.charAt(start - 1))) start--;
        return Result.ok(new ValueEdits.TextEdit(start, attr.end() - start, ""));
      }
      case ADD_ATTR -> {
        String name = op.name();
        String bad = checkName(name, el, "attribute");
        if (bad != null) return Result.fail(bad);
        if (name.equals("xmlns") || name.startsWith("xmlns:")) return Result.fail("Namespace declarations can't be added here.");
        if (el.attr(name) != null) return Result.fail("<" + el.tag() + "> already has an attribute " + name + ".");
        int at = openEnd - 1;
        if (s.charAt(at - 1) == '/') at--;
        while (isXmlWs(s.charAt(at - 1))) at--;
        String value = op.value() == null ? "" : op.value();
        return Result.ok(new ValueEdits.TextEdit(at, 0, " " + name + "=\"" + ValueEdits.escapeAttr(value, '"') + "\""));
      }
      case RENAME_ELEMENT -> {
        String bad = checkName(op.name(), el, "element");
        if (bad != null) return Result.fail(bad);
        if (op.name().equals(el.tag())) return new Result(List.of(), null);
        List<ValueEdits.TextEdit> edits = new ArrayList<>(2);
        if (close >= 0) edits.add(new ValueEdits.TextEdit(close + 2, el.tag().length(), op.name()));
        edits.add(new ValueEdits.TextEdit(el.start() + 1, el.tag().length(), op.name()));
        return new Result(List.copyOf(edits), null);
      }
      case DUPLICATE_ELEMENT -> {
        if (isRoot) return Result.fail("The root element can't be duplicated (a document has one root).");
        return Result.ok(insertAfter(s, el, s.substring(el.start(), el.end())));
      }
      case INSERT_ELEMENT -> {
        Where where = op.where() == null ? Where.CHILD : op.where();
        XNode scope = where == Where.CHILD || el.parent() == null ? el : el.parent();
        String bad = checkName(op.name(), scope, "element");
        if (bad != null) return Result.fail(bad);
        String tag = "<" + op.name() + "/>";
        if (where != Where.CHILD) {
          if (isRoot) return Result.fail("Elements can't be added next to the root (a document has one root).");
          if (where == Where.AFTER) return Result.ok(insertAfter(s, el, tag));
          String indent = ownLineIndent(s, el.start());
          return Result.ok(new ValueEdits.TextEdit(el.start(), 0, indent == null ? tag : tag + newline(s) + indent));
        }
        String nl = newline(s);
        String indent = ownLineIndent(s, el.start());
        String childIndent = indent == null ? null : indent + indentUnit(s, model);
        if (el.childCount() > 0) return Result.ok(insertAfter(s, el.child(el.childCount() - 1), tag));
        String inner = childIndent == null ? tag : nl + childIndent + tag + nl + indent;
        if (close < 0) {
          int slash = openEnd - 2;
          return Result.ok(new ValueEdits.TextEdit(slash, 2, ">" + inner + "</" + el.tag() + ">"));
        }
        String content = s.substring(openEnd, close);
        if (!content.chars().allMatch(c -> isXmlWs((char)c))) return Result.fail("<" + el.tag() + "> has text; adding an element would mix text and elements.");
        return Result.ok(new ValueEdits.TextEdit(openEnd, close - openEnd, inner));
      }
      case NEW_ROW -> {
        if (isRoot) return Result.fail("The root element can't be repeated (a document has one root).");
        String indent = ownLineIndent(s, el.start());
        return Result.ok(insertAfter(s, el, blankCopy(s, el, indent == null ? "" : indent)));
      }
      case MOVE_ELEMENT -> {
        if (isRoot) return Result.fail("The root element can't be moved.");
        List<XNode> sibs = new ArrayList<>();
        for (XNode c : el.parent().children()) if (!op.sameName() || c.tag().equals(el.tag())) sibs.add(c);
        int i = sibs.indexOf(el);
        int j = op.up() ? i - 1 : i + 1;
        if (j < 0 || j >= sibs.size()) return Result.fail("<" + el.tag() + "> is already the " + (op.up() ? "first" : "last") + ".");
        XNode other = sibs.get(j);
        XNode a = other.start() < el.start() ? other : el;
        XNode b = a == other ? el : other;
        return new Result(List.of(new ValueEdits.TextEdit(b.start(), b.end() - b.start(), s.substring(a.start(), a.end())),
                                  new ValueEdits.TextEdit(a.start(), a.end() - a.start(), s.substring(b.start(), b.end()))), null);
      }
      case ADD_COLUMN -> {
        List<XNode> rows = rowsOf(el, op.group());
        if (rows.isEmpty()) return Result.fail("<" + el.tag() + "> has no <" + op.group() + "> children.");
        boolean isAttr = op.name().startsWith("@");
        String name = isAttr ? op.name().substring(1) : op.name();
        List<ValueEdits.TextEdit> edits = new ArrayList<>();
        for (XNode row : rows) {
          boolean has = isAttr ? row.attr(name) != null : hasChild(row, name);
          if (has) continue;
          Result r = compute(model, isAttr ? Op.addAttr(row.path(), name, "") : Op.insert(row.path(), Where.CHILD, name));
          if (!r.isOk()) return r;
          edits.addAll(r.edits());
        }
        if (edits.isEmpty()) return Result.fail("Every <" + op.group() + "> already has " + (isAttr ? "an attribute " : "a child ") + name + ".");
        return new Result(sorted(edits), null);
      }
      case DELETE_COLUMN -> {
        String key = op.name();
        if (key.equals("#text")) return Result.fail("The text column can't be deleted; clear its cells instead.");
        List<ValueEdits.TextEdit> edits = new ArrayList<>();
        for (XNode row : rowsOf(el, op.group())) {
          if (key.startsWith("@")) {
            if (row.attr(key.substring(1)) == null) continue;
            Result r = compute(model, Op.deleteAttr(row.path(), key.substring(1)));
            if (!r.isOk()) return r;
            edits.addAll(r.edits());
          }
          else {
            for (XNode c : row.children()) {
              if (!c.tag().equals(key)) continue;
              Result r = compute(model, Op.deleteElement(c.path()));
              if (!r.isOk()) return r;
              edits.addAll(r.edits());
            }
          }
        }
        if (edits.isEmpty()) return Result.fail("No <" + op.group() + "> has " + key + ".");
        return new Result(sorted(edits), null);
      }
      case INSERT_ROWS -> {
        List<XNode> rows = rowsOf(el, op.group());
        if (op.rows().isEmpty()) return new Result(List.of(), null);
        if (!isQName(op.group())) return Result.fail("\"" + op.group() + "\" is not a valid XML element name.");
        for (Map<String, String> row : op.rows()) {
          for (String key : row.keySet()) {
            if (key.equals("#text")) continue;
            String bad = checkName(key.startsWith("@") ? key.substring(1) : key, el, key.startsWith("@") ? "attribute" : "element");
            if (bad != null) return Result.fail(bad);
          }
        }
        String nl = newline(s);
        if (!rows.isEmpty()) {
          XNode template = rows.get(rows.size() - 1);
          String indent = ownLineIndent(s, template.start());
          String childIndent = template.childCount() > 0 ? ownLineIndent(s, template.child(0).start())
                                                         : indent == null ? null : indent + indentUnit(s, model);
          return Result.ok(insertAfter(s, template, joinRows(op, childIndent, indent, nl)));
        }
        String indent = ownLineIndent(s, el.start());
        String rowIndent = el.childCount() > 0 ? ownLineIndent(s, el.child(el.childCount() - 1).start())
                                               : indent == null ? null : indent + indentUnit(s, model);
        String childIndent = rowIndent == null ? null : rowIndent + indentUnit(s, model);
        return appendChildren(model, el, joinRows(op, childIndent, rowIndent, nl));
      }
      case DELETE_ELEMENTS, SET_CELLS -> throw new IllegalStateException();
    }
    throw new IllegalStateException(op.kind().toString());
  }

  private static List<XNode> rowsOf(XNode parent, @Nullable String group) {
    List<XNode> out = new ArrayList<>();
    for (XNode c : parent.children()) if (c.tag().equals(group)) out.add(c);
    return out;
  }

  private static boolean hasChild(XNode el, String name) {
    for (XNode c : el.children()) if (c.tag().equals(name)) return true;
    return false;
  }

  /** Descending offset order, as hosts apply them. */
  private static List<ValueEdits.TextEdit> sorted(List<ValueEdits.TextEdit> edits) {
    List<ValueEdits.TextEdit> out = new ArrayList<>(edits);
    out.sort(Comparator.comparingInt(ValueEdits.TextEdit::offset).reversed());
    return List.copyOf(out);
  }

  private static String joinRows(Op op, @Nullable String childIndent, @Nullable String indent, String nl) {
    StringBuilder b = new StringBuilder();
    for (Map<String, String> r : op.rows()) {
      if (!b.isEmpty() && indent != null) b.append(nl).append(indent);
      b.append(buildRow(op.group(), r, childIndent, indent, nl));
    }
    return b.toString();
  }

  private static String leaf(String key, String value) {
    return value.isEmpty() ? "<" + key + "/>" : "<" + key + ">" + ValueEdits.escapeText(value) + "</" + key + ">";
  }

  /** A row element with the given values: attributes in the start tag, child values as leaf children, {@code #text} as its text. */
  private static String buildRow(String name, Map<String, String> values, @Nullable String childIndent, @Nullable String indent, String nl) {
    StringBuilder attrs = new StringBuilder();
    List<String> children = new ArrayList<>();
    String text = "";
    for (Map.Entry<String, String> e : values.entrySet()) {
      String key = e.getKey();
      if (key.startsWith("@")) attrs.append(' ').append(key.substring(1)).append("=\"").append(ValueEdits.escapeAttr(e.getValue(), '"')).append('"');
      else if (key.equals("#text")) text = ValueEdits.escapeText(e.getValue());
      else children.add(leaf(key, e.getValue()));
    }
    if (!children.isEmpty()) {
      StringBuilder inner = new StringBuilder();
      if (childIndent == null || indent == null) children.forEach(inner::append);
      else {
        for (String c : children) inner.append(nl).append(childIndent).append(c);
        inner.append(nl).append(indent);
      }
      return "<" + name + attrs + ">" + inner + "</" + name + ">";
    }
    return text.isEmpty() ? "<" + name + attrs + "/>" : "<" + name + attrs + ">" + text + "</" + name + ">";
  }

  /** Offset of {@code </} of the end tag, or -1 for an empty-element tag. */
  private static int closeStart(String s, XNode el) {
    int openEnd = ValueEdits.startTagEnd(s, el);
    return openEnd == el.end() ? -1 : s.lastIndexOf("</", el.end() - 1);
  }

  /**
   * A copy of {@code el} with every value emptied: attribute names kept with "" values, text dropped,
   * element children kept (one of each name) and emptied the same way, in the original line layout.
   */
  private static String blankCopy(String s, XNode el, String indent) {
    StringBuilder tag = new StringBuilder("<").append(el.tag());
    for (Map.Entry<String, String> ns : el.nsDecls().entrySet()) {
      tag.append(' ').append(ns.getKey().isEmpty() ? "xmlns" : "xmlns:" + ns.getKey()).append("=\"").append(ValueEdits.escapeAttr(ns.getValue(), '"')).append('"');
    }
    for (XAttr a : el.attrs()) tag.append(' ').append(a.name()).append("=\"\"");
    Set<String> seen = new HashSet<>();
    List<XNode> kids = new ArrayList<>();
    for (XNode c : el.children()) if (seen.add(c.tag())) kids.add(c);
    if (kids.isEmpty()) return tag + "/>";
    String nl = newline(s);
    StringBuilder inner = new StringBuilder();
    for (XNode c : kids) {
      String ci = ownLineIndent(s, c.start());
      if (ci == null) inner.append(blankCopy(s, c, indent));
      else inner.append(nl).append(ci).append(blankCopy(s, c, ci));
    }
    int close = closeStart(s, el);
    if (close >= 0 && ownLineIndent(s, close) != null) inner.append(nl).append(indent);
    return tag + ">" + inner + "</" + el.tag() + ">";
  }

  /** Inserts {@code text} (one or more elements) as the last children of {@code el}. */
  private static Result appendChildren(XmlDocumentModel model, XNode el, String text) {
    String s = model.text();
    String nl = newline(s);
    String indent = ownLineIndent(s, el.start());
    String childIndent = indent == null ? null : indent + indentUnit(s, model);
    if (el.childCount() > 0) return Result.ok(insertAfter(s, el.child(el.childCount() - 1), text));
    String inner = childIndent == null ? text : nl + childIndent + text + nl + indent;
    int openEnd = ValueEdits.startTagEnd(s, el);
    int close = closeStart(s, el);
    if (close < 0) return Result.ok(new ValueEdits.TextEdit(openEnd - 2, 2, ">" + inner + "</" + el.tag() + ">"));
    String content = s.substring(openEnd, close);
    if (!content.chars().allMatch(c -> isXmlWs((char)c))) return Result.fail("<" + el.tag() + "> has text; adding an element would mix text and elements.");
    return Result.ok(new ValueEdits.TextEdit(openEnd, close - openEnd, inner));
  }

  /** Deletes several elements (descendants of another listed element are covered by it). */
  private static Result deleteElements(XmlDocumentModel model, List<String> paths) {
    List<XNode> els = new ArrayList<>();
    for (String p : paths) {
      XNode el = model.byPath(p);
      if (el == null) return Result.fail("The element no longer exists.");
      if (!els.contains(el)) els.add(el);
    }
    Set<XNode> listed = new HashSet<>(els);
    List<ValueEdits.TextEdit> edits = new ArrayList<>();
    for (XNode el : els) {
      boolean covered = false;
      for (XNode a = el.parent(); a != null; a = a.parent()) if (listed.contains(a)) covered = true;
      if (covered) continue;
      Result r = compute(model, Op.deleteElement(el.path()));
      if (!r.isOk()) return r;
      edits.addAll(r.edits());
    }
    return new Result(sorted(edits), null);
  }

  /**
   * Sets grid cells: existing attributes and leaf children get value edits; missing ones are added
   * (attributes at the end of the start tag, children as last children), one insertion per element.
   */
  private static Result setCells(XmlDocumentModel model, List<Cell> cells) {
    String s = model.text();
    Map<XNode, List<Cell>> byRow = new LinkedHashMap<>();
    for (Cell c : cells) {
      XNode el = model.byPath(c.path());
      if (el == null) return Result.fail("The element no longer exists.");
      byRow.computeIfAbsent(el, k -> new ArrayList<>()).add(c);
    }
    List<ValueEdits.TextEdit> edits = new ArrayList<>();
    for (Map.Entry<XNode, List<Cell>> entry : byRow.entrySet()) {
      XNode el = entry.getKey();
      String path = el.path();
      StringBuilder newAttrs = new StringBuilder();
      List<String> newKids = new ArrayList<>();
      for (Cell c : entry.getValue()) {
        String key = c.key();
        if (key.startsWith("@")) {
          String name = key.substring(1);
          if (el.attr(name) != null) {
            ValueEdits.Result r = ValueEdits.compute(model, ValueEdits.Target.attr(path, name), c.value());
            if (!r.isOk()) return Result.fail(r.error());
            edits.add(r.edit());
          }
          else {
            String bad = checkName(name, el, "attribute");
            if (bad != null) return Result.fail(bad);
            newAttrs.append(' ').append(name).append("=\"").append(ValueEdits.escapeAttr(c.value(), '"')).append('"');
          }
        }
        else if (key.equals("#text")) {
          ValueEdits.Result r = ValueEdits.compute(model, ValueEdits.Target.text(path), c.value());
          if (!r.isOk()) return Result.fail(r.error());
          edits.add(r.edit());
        }
        else {
          XNode child = null;
          for (XNode ch : el.children()) {
            if (ch.tag().equals(key)) {
              child = ch;
              break;
            }
          }
          if (child != null) {
            ValueEdits.Result r = ValueEdits.compute(model, ValueEdits.Target.text(child.path()), c.value());
            if (!r.isOk()) return Result.fail(r.error());
            edits.add(r.edit());
          }
          else {
            String bad = checkName(key, el, "element");
            if (bad != null) return Result.fail(bad);
            newKids.add(leaf(key, c.value()));
          }
        }
      }
      int openEnd = ValueEdits.startTagEnd(s, el);
      int at = openEnd - 1;
      if (s.charAt(at - 1) == '/') at--;
      while (isXmlWs(s.charAt(at - 1))) at--;
      if (!newKids.isEmpty()) {
        String nl = newline(s);
        String indent = ownLineIndent(s, el.start());
        String childIndent = indent == null ? null : indent + indentUnit(s, model);
        if (el.childCount() > 0) {
          XNode last = el.child(el.childCount() - 1);
          String ci = ownLineIndent(s, last.start());
          StringBuilder t = new StringBuilder();
          for (String k : newKids) t.append(ci == null ? "" : nl + ci).append(k);
          edits.add(new ValueEdits.TextEdit(last.end(), 0, t.toString()));
        }
        else {
          StringBuilder inner = new StringBuilder();
          if (childIndent == null) newKids.forEach(inner::append);
          else {
            for (String k : newKids) inner.append(nl).append(childIndent).append(k);
            inner.append(nl).append(indent);
          }
          int close = closeStart(s, el);
          if (close < 0) {
            int slash = openEnd - 2;
            edits.add(new ValueEdits.TextEdit(at, openEnd - at, newAttrs + s.substring(at, slash) + ">" + inner + "</" + el.tag() + ">"));
            newAttrs.setLength(0);
          }
          else {
            String content = s.substring(openEnd, close);
            if (!content.chars().allMatch(ch -> isXmlWs((char)ch))) {
              return Result.fail("<" + el.tag() + "> has text; adding an element would mix text and elements.");
            }
            edits.add(new ValueEdits.TextEdit(openEnd, close - openEnd, inner.toString()));
          }
        }
      }
      if (!newAttrs.isEmpty()) edits.add(new ValueEdits.TextEdit(at, 0, newAttrs.toString()));
    }
    return new Result(sorted(edits), null);
  }

  /** Applies edits given in any order. */
  public static @NotNull String apply(@NotNull String source, @NotNull List<ValueEdits.TextEdit> edits) {
    String out = source;
    List<ValueEdits.TextEdit> sorted = new ArrayList<>(edits);
    sorted.sort(Comparator.comparingInt(ValueEdits.TextEdit::offset).reversed());
    for (ValueEdits.TextEdit e : sorted) out = e.applyTo(out);
    return out;
  }

  /** Short description for the undo history. */
  public static @NotNull String label(@NotNull XmlDocumentModel model, @NotNull Op op) {
    XNode el = op.path().isEmpty() ? null : model.byPath(op.path());
    String tag = el == null ? "element" : "<" + el.tag() + ">";
    return switch (op.kind()) {
      case DELETE_ELEMENT -> "Delete " + tag;
      case DELETE_ATTR -> "Delete @" + op.name();
      case ADD_ATTR -> "Add @" + op.name();
      case RENAME_ELEMENT -> "Rename " + tag + " to <" + op.name() + ">";
      case INSERT_ELEMENT -> "Insert <" + op.name() + ">";
      case DUPLICATE_ELEMENT -> "Duplicate " + tag;
      case NEW_ROW -> "New " + tag;
      case MOVE_ELEMENT -> "Move " + tag + (op.up() ? " Up" : " Down");
      case DELETE_ELEMENTS -> op.paths().size() == 1 ? "Delete Row" : "Delete " + op.paths().size() + " Rows";
      case ADD_COLUMN -> "Add Column " + op.name();
      case DELETE_COLUMN -> "Delete Column " + op.name();
      case SET_CELLS -> op.cells().size() == 1 ? "Edit Cell" : "Edit " + op.cells().size() + " Cells";
      case INSERT_ROWS -> op.rows().size() == 1 ? "Insert Row" : "Insert " + op.rows().size() + " Rows";
    };
  }
}
