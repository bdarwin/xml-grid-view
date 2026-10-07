package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Column filters keyed by column key, combined with AND. */
public final class GridFilter {
  public static final int DISTINCT_LIMIT = 1_000;

  private final Map<String, ColumnFilter> filters = new LinkedHashMap<>();

  public void set(@NotNull String columnKey, @Nullable ColumnFilter f) {
    if (f == null || !f.isActive()) filters.remove(columnKey);
    else filters.put(columnKey, f);
  }

  public @Nullable ColumnFilter get(String columnKey) {
    return filters.get(columnKey);
  }

  public void clear() {
    filters.clear();
  }

  public boolean isEmpty() {
    return filters.isEmpty();
  }

  public Map<String, ColumnFilter> asMap() {
    return Collections.unmodifiableMap(filters);
  }

  /** Whether row {@code row} of {@code table} passes every filter whose column exists in the table. */
  public boolean accepts(@NotNull GridTable table, int row) {
    for (Map.Entry<String, ColumnFilter> e : filters.entrySet()) {
      int col = table.columnIndex(e.getKey());
      if (col < 0) continue;
      if (!e.getValue().accepts(table.cellText(row, col))) return false;
    }
    return true;
  }

  /** Model indices of rows that pass. */
  public List<Integer> apply(@NotNull GridTable table) {
    List<Integer> out = new ArrayList<>();
    for (int r = 0; r < table.rowCount(); r++) if (accepts(table, r)) out.add(r);
    return out;
  }

  /** Distinct display values of a column in first-seen order, capped at {@link #DISTINCT_LIMIT}. */
  public static DistinctValues distinctValues(@NotNull GridTable table, int col) {
    Set<String> out = new LinkedHashSet<>();
    boolean capped = false;
    for (int r = 0; r < table.rowCount(); r++) {
      out.add(table.cellText(r, col));
      if (out.size() >= DISTINCT_LIMIT) {
        capped = r < table.rowCount() - 1;
        break;
      }
    }
    return new DistinctValues(new ArrayList<>(out), capped);
  }

  public record DistinctValues(List<String> values, boolean capped) {
  }
}
