package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * One row of the Flat view: an element, or one of its attributes listed right
 * after it one level deeper. Matches the canonical {@code <name>.flat.json}
 * format in fixtures/SCHEMA.md.
 */
public record FlatRow(int depth, @NotNull Kind kind, @NotNull XNode node, int attrIndex) {
  public enum Kind {
    ELEMENT("element"), ATTR("attr");

    private final String id;

    Kind(String id) { this.id = id; }

    public String id() { return id; }
  }

  /** {@code tag} for elements, {@code @name} for attributes. */
  public @NotNull String name() {
    return kind == Kind.ELEMENT ? node.tag() : "@" + attr().name();
  }

  /** Element text (trimmed, interior whitespace kept) or the attribute value. */
  public @NotNull String value() {
    return kind == Kind.ELEMENT ? node.text() : attr().value();
  }

  public @NotNull XAttr attr() {
    if (kind != Kind.ATTR) throw new IllegalStateException("not an attribute row");
    return node.attrs().get(attrIndex);
  }

  /** Source offset to navigate to: the element start tag, or the attribute. */
  public int offset() {
    return kind == Kind.ELEMENT ? node.start() : attr().start();
  }

  /** True when the row has rows below it that collapsing would hide. */
  public boolean expandable() {
    return kind == Kind.ELEMENT && (!node.attrs().isEmpty() || !node.children().isEmpty());
  }

  /** Stable key across refreshes: element path, plus {@code @name} for attributes. */
  public @NotNull String key() {
    return kind == Kind.ELEMENT ? node.path() : node.path() + "@" + attr().name();
  }

  /** All rows, fully expanded, in document order. */
  public static @NotNull List<FlatRow> build(@NotNull XmlDocumentModel model) {
    return build(model, n -> false, null);
  }

  /**
   * Visible rows: elements for which {@code collapsed} is true keep their own
   * row but hide their attributes and descendants. When {@code only} is
   * non-null, only those elements are shown (all expanded); their attributes
   * are shown too.
   */
  public static @NotNull List<FlatRow> build(@NotNull XmlDocumentModel model, @NotNull Predicate<XNode> collapsed,
                                             @Nullable Predicate<XNode> only) {
    List<FlatRow> out = new ArrayList<>();
    XNode root = model.root();
    if (root != null) visit(root, 0, collapsed, only, out);
    return out;
  }

  private static void visit(XNode n, int depth, Predicate<XNode> collapsed, @Nullable Predicate<XNode> only, List<FlatRow> out) {
    if (only != null && !only.test(n)) return;
    out.add(new FlatRow(depth, Kind.ELEMENT, n, -1));
    if (only == null && collapsed.test(n)) return;
    for (int i = 0; i < n.attrs().size(); i++) out.add(new FlatRow(depth + 1, Kind.ATTR, n, i));
    for (XNode c : n.children()) visit(c, depth + 1, collapsed, only, out);
  }
}
