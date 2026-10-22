package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultCellEditor;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.event.KeyEvent;

/**
 * Excel-style in-place editing for the Grid and Flat tables: F2 or typing starts editing the selected
 * cell, Enter commits, Escape cancels, Tab commits and moves right. Mouse clicks never start editing
 * (double-click opens the value inspector instead).
 */
final class CellEditing {
  private CellEditing() {
  }

  /** Values to offer while editing a cell (view coordinates), e.g. a schema's enumeration; empty for none. */
  interface Suggester {
    @NotNull java.util.List<String> suggestions(int viewRow, int viewCol);
  }

  static void install(@NotNull JBTable table, @NotNull Disposable parent) {
    install(table, parent, (r, c) -> java.util.List.of());
  }

  static void install(@NotNull JBTable table, @NotNull Disposable parent, @NotNull Suggester suggester) {
    SuggestingEditor editor = new SuggestingEditor(suggester);
    table.setDefaultEditor(Object.class, editor);
    table.setDefaultEditor(String.class, editor);
    table.setSurrendersFocusOnKeystroke(true);
    table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
    // F2 explicitly: the IDE keymap may bind it to other actions before JTable sees it.
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        startEditing(table);
      }

      @Override
      public void update(@NotNull AnActionEvent e) {
        int row = table.getSelectionModel().getLeadSelectionIndex();
        int col = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        e.getPresentation().setEnabled(!table.isEditing() && row >= 0 && col >= 0 && row < table.getRowCount()
                                       && col < table.getColumnCount() && table.isCellEditable(row, col));
      }

      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0)), table, parent);
  }

  /** Starts editing the lead cell (if editable) and focuses the editor. */
  static boolean startEditing(@NotNull JBTable table) {
    int row = table.getSelectionModel().getLeadSelectionIndex();
    int col = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
    if (row < 0 || col < 0 || row >= table.getRowCount() || col >= table.getColumnCount()) return false;
    if (!table.editCellAt(row, col)) return false;
    Component c = table.getEditorComponent();
    if (c != null) c.requestFocusInWindow();
    return true;
  }

  /**
   * A text field, or an editable combo box with the suggested values (its list opens when editing
   * starts) when there are any. Clicks never start editing.
   */
  static final class SuggestingEditor extends javax.swing.AbstractCellEditor implements javax.swing.table.TableCellEditor {
    private final Suggester suggester;
    private final DefaultCellEditor text = new DefaultCellEditor(new JBTextField());
    private final com.intellij.openapi.ui.ComboBox<String> combo = new com.intellij.openapi.ui.ComboBox<>();
    private final DefaultCellEditor list = new DefaultCellEditor(combo);
    private DefaultCellEditor active = text;
    /** The suggestions of the cell being edited (tests). */
    private java.util.List<String> current = java.util.List.of();

    SuggestingEditor(Suggester suggester) {
      this.suggester = suggester;
      combo.setEditable(true);
      for (DefaultCellEditor e : new DefaultCellEditor[]{text, list}) {
        e.setClickCountToStart(Integer.MAX_VALUE);
        e.addCellEditorListener(new javax.swing.event.CellEditorListener() {
          @Override
          public void editingStopped(javax.swing.event.ChangeEvent ev) {
            fireEditingStopped();
          }

          @Override
          public void editingCanceled(javax.swing.event.ChangeEvent ev) {
            fireEditingCanceled();
          }
        });
      }
    }

    @Override
    public Component getTableCellEditorComponent(javax.swing.JTable table, Object value, boolean isSelected, int row, int column) {
      current = suggester.suggestions(row, column);
      if (current.isEmpty()) {
        active = text;
        return text.getTableCellEditorComponent(table, value, isSelected, row, column);
      }
      active = list;
      combo.setModel(new javax.swing.DefaultComboBoxModel<>(current.toArray(String[]::new)));
      Component c = list.getTableCellEditorComponent(table, value, isSelected, row, column);
      combo.getEditor().setItem(value == null ? "" : value.toString());
      javax.swing.SwingUtilities.invokeLater(() -> {
        if (combo.isShowing()) combo.showPopup();
      });
      return c;
    }

    @Override
    public Object getCellEditorValue() {
      if (active == list) {
        Object typed = combo.getEditor().getItem();
        return typed == null ? "" : typed.toString();
      }
      return text.getCellEditorValue();
    }

    @Override
    public boolean isCellEditable(java.util.EventObject e) {
      return text.isCellEditable(e);
    }

    @Override
    public boolean stopCellEditing() {
      return active.stopCellEditing();
    }

    @Override
    public void cancelCellEditing() {
      active.cancelCellEditing();
    }

    @org.jetbrains.annotations.TestOnly
    java.util.List<String> suggestionsForTest() {
      return current;
    }
  }

  /** True while {@code c} is a table with an active cell editor (keyboard actions should then stay out of the way). */
  static boolean isEditing(Component c) {
    return c instanceof javax.swing.JTable t && t.isEditing();
  }
}
