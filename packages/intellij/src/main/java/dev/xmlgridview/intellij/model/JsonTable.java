package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grid for a JSON value, following "JSON value inspector → Grids" in fixtures/SCHEMA.md:
 * <ul>
 *   <li>array: one row per item; object items contribute their keys as columns (union, first-seen order);
 *       primitive or array items go in a leading {@code (value)} column;</li>
 *   <li>object with two or more entries whose values are all objects (a "map"): {@code (key)} then the union of
 *       the values' keys;</li>
 *   <li>any other object: {@code (key)} and {@code (value)};</li>
 *   <li>primitive: a single {@code (value)} row.</li>
 * </ul>
 * Cells are {@code null} (absent), a String (primitive display text or row key) or a {@link Drill}.
 * Columns are positional, so a data key literally named "(value)" still gets its own column.
 */
public final class JsonTable {
  public static final String KEY_COLUMN = "(key)";
  public static final String VALUE_COLUMN = "(value)";

  /** A nested container cell: {@code kind} is "object" or "array"; {@code count} its keys or items. */
  public record Drill(@NotNull String kind, int count, @NotNull String label) {
  }

  private final List<GridColumn> columns;
  private final List<String> rowKeys;
  private final List<Object> rowSegments;
  private final Object[][] cells;

  private JsonTable(List<GridColumn> columns, List<String> rowKeys, List<Object> rowSegments, Object[][] cells) {
    this.columns = columns;
    this.rowKeys = rowKeys;
    this.rowSegments = rowSegments;
    this.cells = cells;
  }

  public @NotNull List<GridColumn> columns() { return columns; }

  /** Row keys: array indices or object keys, as strings. */
  public @NotNull List<String> rowKeys() { return rowKeys; }

  /** Child path segment per row: Integer for arrays, String for objects. */
  public @NotNull List<Object> rowSegments() { return rowSegments; }

  public int rowCount() { return rowKeys.size(); }

  public int columnCount() { return columns.size(); }

  public @Nullable Object cell(int row, int col) { return cells[row][col]; }

  /** Display text: drill cells show their label, absent cells "". */
  public @NotNull String cellText(int row, int col) {
    Object v = cells[row][col];
    return v == null ? "" : v instanceof Drill d ? d.label() : v.toString();
  }

  private record Row(String key, Object seg, Object item, boolean spread) {
  }

  public static @NotNull JsonTable of(@Nullable Object node) {
    List<Row> rows = new ArrayList<>();
    boolean hasKeyCol = false;
    if (node instanceof List<?> list) {
      for (int k = 0; k < list.size(); k++) rows.add(new Row(String.valueOf(k), k, list.get(k), list.get(k) instanceof Map));
    }
    else if (node instanceof Map<?, ?> map) {
      hasKeyCol = true;
      boolean asMap = map.size() >= 2 && map.values().stream().allMatch(v -> v instanceof Map);
      for (Map.Entry<?, ?> e : map.entrySet()) {
        String k = (String)e.getKey();
        rows.add(new Row(k, k, e.getValue(), asMap));
      }
    }
    else {
      rows.add(new Row("", "", node, false));
    }

    Set<String> keys = new LinkedHashSet<>();
    boolean hasValueCol = false;
    for (Row r : rows) {
      if (r.spread) {
        for (Object k : ((Map<?, ?>)r.item).keySet()) keys.add((String)k);
      }
      else hasValueCol = true;
    }

    List<String> keyOrder = new ArrayList<>(keys);
    int keyCol = hasKeyCol ? 0 : -1;
    int valueCol = hasValueCol ? (hasKeyCol ? 1 : 0) : -1;
    int first = (hasKeyCol ? 1 : 0) + (hasValueCol ? 1 : 0);
    int ncols = first + keyOrder.size();
    boolean[] complex = new boolean[ncols];
    Object[][] cells = new Object[rows.size()][ncols];
    Map<String, Integer> keyIndex = new HashMap<>();
    for (int k = 0; k < keyOrder.size(); k++) keyIndex.put(keyOrder.get(k), first + k);

    for (int r = 0; r < rows.size(); r++) {
      Row row = rows.get(r);
      if (keyCol >= 0) cells[r][keyCol] = row.key;
      if (row.spread) {
        for (Map.Entry<?, ?> e : ((Map<?, ?>)row.item).entrySet()) {
          int c = keyIndex.get((String)e.getKey());
          cells[r][c] = cellValue(e.getValue());
          if (cells[r][c] instanceof Drill) complex[c] = true;
        }
      }
      else {
        cells[r][valueCol] = cellValue(row.item);
        if (cells[r][valueCol] instanceof Drill) complex[valueCol] = true;
      }
    }

    List<GridColumn> columns = new ArrayList<>(ncols);
    for (int c = 0; c < ncols; c++) {
      String key = c == keyCol ? KEY_COLUMN : c == valueCol ? VALUE_COLUMN : keyOrder.get(c - first);
      columns.add(new GridColumn(key, key, complex[c] ? GridColumn.Kind.COMPLEX : GridColumn.Kind.LEAF));
    }
    List<String> rowKeys = new ArrayList<>(rows.size());
    List<Object> segments = new ArrayList<>(rows.size());
    for (Row r : rows) {
      rowKeys.add(r.key);
      segments.add(r.seg);
    }
    return new JsonTable(List.copyOf(columns), List.copyOf(rowKeys), List.copyOf(segments), cells);
  }

  private static Object cellValue(Object v) {
    if (Json.isContainer(v)) return new Drill(v instanceof List ? "array" : "object", Json.size(v), Json.containerLabel(v));
    return Json.primitiveText(v);
  }
}
