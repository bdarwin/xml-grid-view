package dev.xmlgridview.intellij.ui;

import dev.xmlgridview.intellij.model.GridTable;
import org.jetbrains.annotations.NotNull;

import java.text.NumberFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Count, sum, average, min and max of the selected cells, like a spreadsheet's status bar. Same rules
 * as selectionStats in packages/webview/src/gridView.ts: non-empty cells count, plain numbers (no
 * thousands separators) are summed, drill-down cells are never numbers.
 */
record SelectionStats(int cells, int count, int numbers, double sum, double min, double max) {
  private static final Pattern NUMBER = Pattern.compile("\\s*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?\\s*");

  static @NotNull SelectionStats of(@NotNull GridTable t, @NotNull List<int[]> cells) {
    int count = 0;
    int numbers = 0;
    double sum = 0;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    for (int[] rc : cells) {
      Object v = t.cell(rc[0], rc[1]);
      if (v == null || "".equals(v)) continue;
      count++;
      if (!(v instanceof String s) || !NUMBER.matcher(s).matches()) continue;
      double x = Double.parseDouble(s.trim());
      if (!Double.isFinite(x)) continue;
      numbers++;
      sum += x;
      min = Math.min(min, x);
      max = Math.max(max, x);
    }
    return new SelectionStats(cells.size(), count, numbers, sum, min, max);
  }

  /** A stat for display: grouped digits, at most 6 decimals (floating-point noise rounded away). */
  static @NotNull String format(double x) {
    NumberFormat f = NumberFormat.getNumberInstance();
    f.setMaximumFractionDigits(6);
    return f.format(x);
  }

  /** E.g. "3 cells · Count 3 · Sum 6 · Avg 2 · Min 1 · Max 3". */
  @NotNull String text() {
    NumberFormat n = NumberFormat.getIntegerInstance();
    StringBuilder b = new StringBuilder(n.format(cells)).append(" cells · Count ").append(n.format(count));
    if (numbers > 0) {
      b.append(" · Sum ").append(format(sum)).append(" · Avg ").append(format(sum / numbers))
        .append(" · Min ").append(format(min)).append(" · Max ").append(format(max));
    }
    return b.toString();
  }
}
