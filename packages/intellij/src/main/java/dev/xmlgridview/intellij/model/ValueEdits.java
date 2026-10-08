package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Value edits: turns "set this attribute / this leaf element's text to V" into one minimal
 * text replacement on the source, applied by the host as a single undoable edit.
 * Port of packages/core/src/edits.ts; see fixtures/SCHEMA.md, "Value edits".
 */
public final class ValueEdits {
  private ValueEdits() {
  }

  /** What to edit: an attribute ({@code attrName} set) or an element's text ({@code attrName} null). */
  public record Target(@NotNull String path, @Nullable String attrName) {
    public static Target attr(@NotNull String path, @NotNull String name) {
      return new Target(path, name);
    }

    public static Target text(@NotNull String path) {
      return new Target(path, null);
    }

    public boolean isAttr() {
      return attrName != null;
    }
  }

  /** Replace {@code length} characters at {@code offset} (UTF-16 units) with {@code text}. */
  public record TextEdit(int offset, int length, @NotNull String text) {
    public @NotNull String applyTo(@NotNull String source) {
      return source.substring(0, offset) + text + source.substring(offset + length);
    }
  }

  /** Either an edit or a human-readable reason it was refused. */
  public record Result(@Nullable TextEdit edit, @Nullable String error) {
    static Result ok(TextEdit e) {
      return new Result(e, null);
    }

    static Result fail(String message) {
      return new Result(null, message);
    }

    public boolean isOk() {
      return edit != null;
    }
  }

  /** Escapes an attribute value for the given quote character; tab/LF/CR become character references. */
  public static @NotNull String escapeAttr(@NotNull String value, char quote) {
    StringBuilder out = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '"' -> out.append(quote == '"' ? "&quot;" : "\"");
        case '\'' -> out.append(quote == '\'' ? "&apos;" : "'");
        case '\t' -> out.append("&#9;");
        case '\n' -> out.append("&#10;");
        case '\r' -> out.append("&#13;");
        default -> out.append(ch);
      }
    }
    return out.toString();
  }

  /** Escapes element text: {@code &}, {@code <} and {@code >} (so {@code ]]>} never appears). */
  public static @NotNull String escapeText(@NotNull String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** Wraps a value in CDATA, splitting any {@code ]]>} across sections. */
  public static @NotNull String cdata(@NotNull String value) {
    return "<![CDATA[" + value.replace("]]>", "]]]]><![CDATA[>") + "]]>";
  }

  /** Whether an element's text can be edited (it has no element children). */
  public static boolean isTextEditable(@NotNull XNode el) {
    return el.children().isEmpty();
  }

  /** End offset of the start tag (just after its {@code >}), skipping quoted attribute values. */
  static int startTagEnd(@NotNull String source, @NotNull XNode el) {
    char quote = 0;
    for (int i = el.start() + 1; i < source.length(); i++) {
      char c = source.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0;
      }
      else if (c == '"' || c == '\'') {
        quote = c;
      }
      else if (c == '>') {
        return i + 1;
      }
    }
    return -1;
  }

  /**
   * Computes the replacement that sets a value. {@code model.text()} must be the source the model was
   * built from. Refuses anything that would need a structural change.
   */
  public static @NotNull Result compute(@NotNull XmlDocumentModel model, @NotNull Target target, @NotNull String value) {
    String source = model.text();
    XNode el = model.byPath(target.path());
    if (el == null) return Result.fail("The element no longer exists.");
    if (model.hasErrors()) return Result.fail("The document is not well-formed; fix it before editing values.");

    if (target.isAttr()) {
      XAttr attr = el.attr(target.attrName());
      if (attr == null) return Result.fail("Attribute " + target.attrName() + " does not exist on <" + el.tag() + ">.");
      String raw = source.substring(attr.start(), attr.end());
      int eq = raw.indexOf('=');
      char quote = raw.isEmpty() ? 0 : raw.charAt(raw.length() - 1);
      if (eq < 0 || (quote != '"' && quote != '\'')) return Result.fail("Could not locate the attribute value in the source.");
      int open = raw.indexOf(quote, eq);
      int offset = attr.start() + open + 1;
      int length = attr.end() - 1 - offset;
      return Result.ok(new TextEdit(offset, length, escapeAttr(value, quote)));
    }

    if (!isTextEditable(el)) return Result.fail("<" + el.tag() + "> has child elements; only leaf elements' text can be edited.");
    int openEnd = startTagEnd(source, el);
    if (openEnd < 0) return Result.fail("Could not locate the start tag.");
    boolean selfClosing = openEnd == el.end();
    if (selfClosing) {
      // <tag .../>  ->  <tag ...>value</tag>
      int slash = source.lastIndexOf('/', openEnd - 1);
      if (slash < el.start() || source.charAt(openEnd - 1) != '>') return Result.fail("Could not locate the empty-element tag.");
      if (value.isEmpty()) return Result.ok(new TextEdit(openEnd, 0, ""));
      return Result.ok(new TextEdit(slash, openEnd - slash, ">" + escapeText(value) + "</" + el.tag() + ">"));
    }
    int closeStart = source.lastIndexOf("</", el.end() - 1);
    if (closeStart < openEnd) return Result.fail("Could not locate the end tag.");
    String content = source.substring(openEnd, closeStart);
    if (content.contains("<!--") || content.contains("<?")) {
      return Result.fail("The value contains comments or processing instructions; edit it in the text editor.");
    }
    // Keep CDATA when the current value is a single CDATA section (ignoring surrounding whitespace).
    String t = content.strip();
    boolean isCdata = t.startsWith("<![CDATA[") && t.endsWith("]]>") && t.indexOf("<![CDATA[", 1) < 0;
    return Result.ok(new TextEdit(openEnd, closeStart - openEnd, isCdata ? cdata(value) : escapeText(value)));
  }

  /**
   * The edit target behind a grid cell, or null when the cell is not editable: attribute cells whose
   * attribute exists, leaf cells whose child exists, and #text cells of elements without element children.
   */
  public static @Nullable Target gridTarget(@NotNull GridTable t, int row, int col) {
    if (row < 0 || row >= t.rowCount() || col < 0 || col >= t.columnCount()) return null;
    XNode rowEl = t.rows().get(row);
    GridColumn c = t.columns().get(col);
    return switch (c.kind()) {
      case ATTR -> {
        String name = c.key().startsWith("@") ? c.key().substring(1) : c.key();
        yield rowEl.attr(name) == null ? null : Target.attr(rowEl.path(), name);
      }
      case LEAF -> {
        for (XNode child : rowEl.children()) {
          if (child.tag().equals(c.label())) yield isTextEditable(child) ? Target.text(child.path()) : null;
        }
        yield null;
      }
      case TEXT -> isTextEditable(rowEl) ? Target.text(rowEl.path()) : null;
      case COMPLEX -> null;
    };
  }

  /** The edit target behind a Flat row's value, or null: attribute rows and elements without element children. */
  public static @Nullable Target flatTarget(@NotNull FlatRow r) {
    if (r.kind() == FlatRow.Kind.ATTR) return Target.attr(r.node().path(), r.attr().name());
    return isTextEditable(r.node()) ? Target.text(r.node().path()) : null;
  }

  /** Short undo label, e.g. "Edit @id" or "Edit title". */
  public static @NotNull String label(@NotNull XmlDocumentModel model, @NotNull Target target) {
    if (target.isAttr()) return "Edit @" + target.attrName();
    XNode el = model.byPath(target.path());
    return "Edit " + (el == null ? "value" : el.tag());
  }
}
