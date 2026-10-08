package dev.xmlgridview.intellij.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;

import java.awt.Dimension;

/** Spreadsheet-style cell borders for the Grid and Flat tables. */
final class GridLines {
  /** Theme border color; Table.gridColor is often invisible against the New UI background. */
  static final JBColor COLOR = JBColor.namedColor("XmlGridView.gridColor", JBColor.border());

  private GridLines() {
  }

  static void apply(@NotNull JBTable table) {
    // Striping disables JBTable's grid, and zero intercell spacing leaves no room to paint it.
    table.setStriped(false);
    table.setShowGrid(true);
    table.setIntercellSpacing(new Dimension(1, 1));
    table.setGridColor(COLOR);
  }
}
