package dev.xmlgridview.intellij.ui;

import dev.xmlgridview.intellij.model.GridTable;
import org.jetbrains.annotations.Nullable;

import javax.swing.table.AbstractTableModel;

/** Swing model over an immutable {@link GridTable}. Values are display strings. */
final class GridTableModel extends AbstractTableModel {
  private @Nullable GridTable table;

  @Nullable GridTable table() {
    return table;
  }

  void setTable(@Nullable GridTable table) {
    this.table = table;
    fireTableStructureChanged();
  }

  @Override
  public int getRowCount() {
    return table == null ? 0 : table.rowCount();
  }

  @Override
  public int getColumnCount() {
    return table == null ? 0 : table.columnCount();
  }

  @Override
  public String getColumnName(int column) {
    return table == null ? "" : table.columns().get(column).label();
  }

  @Override
  public Object getValueAt(int row, int column) {
    return table == null ? "" : table.cellText(row, column);
  }

  @Override
  public Class<?> getColumnClass(int columnIndex) {
    return String.class;
  }
}
