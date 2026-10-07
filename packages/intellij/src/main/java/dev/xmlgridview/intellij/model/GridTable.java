package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Child elements of {@code element} sharing the tag {@code group}, as rows.
 * Cells are {@code null} (absent), a String (value) or an Integer (complex cell count).
 */
public final class GridTable {
  private final XNode element;
  private final @Nullable String group;
  private final List<GroupInfo> groups;
  private final List<GridColumn> columns;
  private final List<XNode> rows;
  private final Object[][] cells;

  GridTable(XNode element, @Nullable String group, List<GroupInfo> groups, List<GridColumn> columns,
            List<XNode> rows, Object[][] cells) {
    this.element = element;
    this.group = group;
    this.groups = List.copyOf(groups);
    this.columns = List.copyOf(columns);
    this.rows = List.copyOf(rows);
    this.cells = cells;
  }

  public @NotNull XNode element() { return element; }

  /** Row tag, or null for the "self" view of an element without element children. */
  public @Nullable String group() { return group; }

  public @NotNull List<GroupInfo> groups() { return groups; }

  public @NotNull List<GridColumn> columns() { return columns; }

  public @NotNull List<XNode> rows() { return rows; }

  public int rowCount() { return rows.size(); }

  public int columnCount() { return columns.size(); }

  public @Nullable Object cell(int row, int col) { return cells[row][col]; }

  /** Display text: values as-is, complex cells as "{tag ×n}", absent as "". */
  public @NotNull String cellText(int row, int col) {
    Object v = cells[row][col];
    if (v == null) return "";
    if (v instanceof Integer n) return complexLabel(columns.get(col).label(), n);
    return (String) v;
  }

  /** The string search matches against: the value, or the column tag for complex cells. */
  public @Nullable String searchText(int row, int col) {
    Object v = cells[row][col];
    if (v == null) return null;
    return v instanceof Integer ? columns.get(col).label() : (String) v;
  }

  public int columnIndex(String key) {
    for (int i = 0; i < columns.size(); i++) if (columns.get(i).key().equals(key)) return i;
    return -1;
  }

  /** Source offset for a cell (attribute, first child with the tag, or first text), else the row element. */
  public int cellOffset(int row, int col) {
    XNode r = rows.get(row);
    if (col < 0 || col >= columns.size()) return r.start();
    GridColumn c = columns.get(col);
    switch (c.kind()) {
      case ATTR -> {
        XAttr a = r.attr(c.label().substring(1));
        if (a != null) return a.start();
      }
      case LEAF, COMPLEX -> {
        for (XNode ch : r.children()) if (ch.tag().equals(c.label())) return ch.start();
      }
      case TEXT -> {
        if (r.textStart() >= 0) return r.textStart();
      }
    }
    return r.start();
  }

  public static String complexLabel(String tag, int count) {
    return "{" + tag + " ×" + count + "}";
  }
}
