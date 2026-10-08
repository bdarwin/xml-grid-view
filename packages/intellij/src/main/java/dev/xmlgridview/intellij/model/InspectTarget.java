package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the value inspector shows: a title (the node path, e.g.
 * {@code catalog › book[1] › description}) and the node's full text: element
 * text or attribute value. Pure model code; the dialog lives in the UI package.
 */
public final class InspectTarget {
  public static final String TAB_TREE = "Tree";
  public static final String TAB_GRID = "Grid";
  public static final String TAB_TEXT = "Text";

  private final String title;
  private final String text;
  private final @Nullable ValueEdits.Target editTarget;
  private @Nullable Json.Detection detection;

  public InspectTarget(@NotNull String title, @NotNull String text) {
    this(title, text, null);
  }

  public InspectTarget(@NotNull String title, @NotNull String text, @Nullable ValueEdits.Target editTarget) {
    this.title = title;
    this.text = text;
    this.editTarget = editTarget;
  }

  /** What saving an edited value changes, or null when the value cannot be edited. */
  public @Nullable ValueEdits.Target editTarget() { return editTarget; }

  public @NotNull String title() { return title; }

  public @NotNull String text() { return text; }

  /** JSON detection, computed once. */
  public @NotNull Json.Detection detection() {
    if (detection == null) detection = Json.detect(text);
    return detection;
  }

  /** Tabs the inspector shows: Tree, Grid and Text for JSON; Text only otherwise. */
  public @NotNull List<String> tabs() {
    return detection().isJson() ? List.of(TAB_TREE, TAB_GRID, TAB_TEXT) : List.of(TAB_TEXT);
  }

  public static @NotNull InspectTarget ofNode(@NotNull XNode n) {
    return new InspectTarget(pathLabel(n), n.text(), ValueEdits.isTextEditable(n) ? ValueEdits.Target.text(n.path()) : null);
  }

  public static @NotNull InspectTarget ofAttr(@NotNull XNode n, @NotNull XAttr a) {
    return new InspectTarget(pathLabel(n) + " › @" + a.name(), a.value(), ValueEdits.Target.attr(n.path(), a.name()));
  }

  public static @NotNull InspectTarget ofFlatRow(@NotNull FlatRow r) {
    return r.kind() == FlatRow.Kind.ATTR ? ofAttr(r.node(), r.attr()) : ofNode(r.node());
  }

  /**
   * Target for a grid cell (model coordinates): attribute cells show the attribute
   * value, leaf cells the child element's text, {@code #text} and complex cells the
   * row element's text. A negative column means the whole row (its element).
   */
  public static @Nullable InspectTarget ofGridCell(@NotNull GridTable t, int row, int col) {
    if (row < 0 || row >= t.rowCount()) return null;
    XNode node = t.rows().get(row);
    if (col < 0 || t.columns().isEmpty()) return ofNode(node);
    GridColumn c = t.columns().get(col);
    switch (c.kind()) {
      case ATTR -> {
        XAttr a = node.attr(c.key().substring(1));
        return a == null ? null : ofAttr(node, a);
      }
      case LEAF -> {
        for (XNode child : node.children()) if (child.tag().equals(c.key())) return ofNode(child);
        return null;
      }
      default -> {
        return ofNode(node);
      }
    }
  }

  /** {@code catalog › book[2] › title}; the index is shown only when siblings share the tag. */
  public static @NotNull String pathLabel(@NotNull XNode n) {
    List<String> parts = new ArrayList<>();
    for (XNode e = n; e != null; e = e.parent()) {
      String label = e.tag();
      XNode p = e.parent();
      if (p != null) {
        int same = 0;
        int pos = 0;
        for (XNode s : p.children()) {
          if (s.tag().equals(e.tag())) {
            same++;
            if (s == e) pos = same;
          }
        }
        if (same > 1) label += "[" + pos + "]";
      }
      parts.add(label);
    }
    Collections.reverse(parts);
    return String.join(" › ", parts);
  }
}
