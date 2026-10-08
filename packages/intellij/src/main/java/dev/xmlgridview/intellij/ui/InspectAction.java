package dev.xmlgridview.intellij.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.util.ui.JBUI;
import dev.xmlgridview.intellij.model.InspectTarget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** "Inspect Value" (Shift+Enter): opens the value inspector for the selected cell, row or node. */
final class InspectAction extends DumbAwareAction {
  static final CustomShortcutSet SHORTCUT =
    new CustomShortcutSet(new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), null));

  /** Trailing cell icon marking values worth opening in the inspector. */
  static final Icon CELL_ICON = AllIcons.General.ExpandComponent;

  private final Supplier<@Nullable InspectTarget> target;
  private final Consumer<InspectTarget> open;
  private @Nullable JComponent component;

  InspectAction(@NotNull Supplier<@Nullable InspectTarget> target, @NotNull Consumer<InspectTarget> open) {
    super("Inspect Value", "Show the full value; JSON gets Tree, Grid and Text views", AllIcons.Actions.Show);
    this.target = target;
    this.open = open;
  }

  /** Creates the action and binds Shift+Enter on {@code component} (which also shows it in menus). */
  static InspectAction install(@NotNull JComponent component, @NotNull Disposable parent,
                               @NotNull Supplier<@Nullable InspectTarget> target, @NotNull Consumer<InspectTarget> open) {
    InspectAction a = new InspectAction(target, open);
    a.component = component;
    a.registerCustomShortcutSet(SHORTCUT, component, parent);
    return a;
  }

  /** Long, multi-line or JSON-looking values get the inline inspector icon. */
  static boolean worthInspecting(@Nullable String raw) {
    if (raw == null || raw.isEmpty()) return false;
    if (raw.length() > 80 || raw.indexOf('\n') >= 0) return true;
    String t = raw.strip();
    return t.startsWith("{") || t.startsWith("[");
  }

  /** True when a click at {@code x} hits the trailing icon of a cell spanning {@code cellX..cellX+cellWidth}. */
  static boolean hitsCellIcon(int x, int cellX, int cellWidth) {
    return x >= cellX + cellWidth - CELL_ICON.getIconWidth() - JBUI.scale(8);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    InspectTarget t = target.get();
    if (t != null) open.accept(t);
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    // While a cell is being edited, Shift+Enter belongs to the editor.
    e.getPresentation().setEnabled(!CellEditing.isEditing(component) && target.get() != null);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }
}
