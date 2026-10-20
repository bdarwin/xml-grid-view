package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

  public enum Kind { DELETE_ELEMENT, DELETE_ATTR, ADD_ATTR, RENAME_ELEMENT, INSERT_ELEMENT, DUPLICATE_ELEMENT }

  public enum Where { BEFORE, AFTER, CHILD }

  /** An operation on the element at {@code path}; {@code name}, {@code value} and {@code where} as the kind needs. */
  public record Op(@NotNull Kind kind, @NotNull String path, @Nullable String name, @Nullable String value, @Nullable Where where) {
    public static Op deleteElement(String path) { return new Op(Kind.DELETE_ELEMENT, path, null, null, null); }

    public static Op deleteAttr(String path, String name) { return new Op(Kind.DELETE_ATTR, path, name, null, null); }

    public static Op addAttr(String path, String name, String value) { return new Op(Kind.ADD_ATTR, path, name, value, null); }

    public static Op rename(String path, String name) { return new Op(Kind.RENAME_ELEMENT, path, name, null, null); }

    public static Op insert(String path, Where where, String name) { return new Op(Kind.INSERT_ELEMENT, path, name, null, where); }

    public static Op duplicate(String path) { return new Op(Kind.DUPLICATE_ELEMENT, path, null, null, null); }
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
    XNode el = model.byPath(op.path());
    if (el == null) return Result.fail("The element no longer exists.");
    if (model.hasErrors()) return Result.fail("The document is not well-formed; fix it before editing.");
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
    }
    throw new IllegalStateException(op.kind().toString());
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
    XNode el = model.byPath(op.path());
    String tag = el == null ? "element" : "<" + el.tag() + ">";
    return switch (op.kind()) {
      case DELETE_ELEMENT -> "Delete " + tag;
      case DELETE_ATTR -> "Delete @" + op.name();
      case ADD_ATTR -> "Add @" + op.name();
      case RENAME_ELEMENT -> "Rename " + tag + " to <" + op.name() + ">";
      case INSERT_ELEMENT -> "Insert <" + op.name() + ">";
      case DUPLICATE_ELEMENT -> "Duplicate " + tag;
    };
  }
}
