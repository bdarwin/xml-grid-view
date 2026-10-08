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

  static void install(@NotNull JBTable table, @NotNull Disposable parent) {
    DefaultCellEditor editor = new DefaultCellEditor(new JBTextField());
    editor.setClickCountToStart(Integer.MAX_VALUE);
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

  /** True while {@code c} is a table with an active cell editor (keyboard actions should then stay out of the way). */
  static boolean isEditing(Component c) {
    return c instanceof javax.swing.JTable t && t.isEditing();
  }
}
