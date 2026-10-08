package dev.xmlgridview.intellij.ui;

import dev.xmlgridview.intellij.model.GridTable;
import org.jetbrains.annotations.Nullable;

import javax.swing.table.AbstractTableModel;

/** Swing model over an immutable {@link GridTable}. Values are display strings. */
final class GridTableModel extends AbstractTableModel {
  /** Decides which cells are editable and commits edited values. */
  interface EditHandler {
    boolean isEditable(int row, int col);

    void commit(int row, int col, String value);
  }

  private @Nullable GridTable table;
  private @Nullable EditHandler editHandler;

  void setEditHandler(@Nullable EditHandler handler) {
    this.editHandler = handler;
  }

  @Override
  public boolean isCellEditable(int row, int column) {
    return table != null && editHandler != null && editHandler.isEditable(row, column);
  }

  @Override
  public void setValueAt(Object value, int row, int column) {
    if (editHandler == null || table == null) return;
    String v = value == null ? "" : value.toString();
    // Unchanged values are not written (no empty undo steps).
    if (v.equals(table.cellText(row, column))) return;
    editHandler.commit(row, column, v);
  }

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
