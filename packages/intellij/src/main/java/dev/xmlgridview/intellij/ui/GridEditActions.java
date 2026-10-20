package dev.xmlgridview.intellij.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.ShortcutSet;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import dev.xmlgridview.intellij.model.GridColumn;
import dev.xmlgridview.intellij.model.GridTable;
import dev.xmlgridview.intellij.model.GridText;
import dev.xmlgridview.intellij.model.StructureEdits;
import dev.xmlgridview.intellij.model.StructureEdits.Op;
import dev.xmlgridview.intellij.model.ValueEdits;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Spreadsheet-style grid edits (insert, move and delete rows, add and delete columns, paste and
 * clear cells) for grids whose rows are the children of one element sharing a tag. Each action
 * computes {@link StructureEdits} operations and applies them as one undoable command. Mirrors
 * the grid menu of the web view.
 */
final class GridEditActions {
  private GridEditActions() {
  }

  interface Applier {
    boolean canEdit();

    /**
     * Applies the edits as one undoable command; returns null or why it was refused. Once rebuilt,
     * selects the row at {@code rowPath} (if not null) in model column {@code col}.
     */
    @Nullable String apply(@NotNull List<ValueEdits.TextEdit> edits, @NotNull String label, @Nullable String rowPath, int col);
  }

  /** What the actions work on: the model and the grid's table. */
  record Context(@NotNull XmlDocumentModel model, @NotNull GridTable table) {
    boolean isRowGrid() {
      return table.group() != null && !XmlDocumentModel.SELF_GROUP.equals(table.group());
    }
  }

  static @NotNull DefaultActionGroup create(@NotNull JComponent owner, @NotNull GridView grid, @NotNull Supplier<@Nullable XmlDocumentModel> model,
                                            @NotNull Applier applier, @NotNull Disposable parent) {
    Supplier<@Nullable Context> ctx = () -> {
      XmlDocumentModel m = model.get();
      GridTable t = grid.gridTable();
      return m == null || t == null ? null : new Context(m, t);
    };
    DefaultActionGroup g = new DefaultActionGroup();
    g.add(Separator.getInstance());
    g.add(new GridAction(grid, "Insert Row Below", AllIcons.General.Add, ctx, applier, true) {
      @Override
      void run(Context c, Point lead) {
        XNode row = rowAt(grid, c, lead.y);
        if (row == null) return;
        XNode parent = row.parent();
        String next = parent == null ? null : parent.path() + "/" + (row.index() + 1);
        apply(owner, c, applier, List.of(Op.newRow(row.path())), "New <" + c.table().group() + ">", next, modelCol(grid, lead));
      }
    });
    AnAction up = new GridAction(grid, "Move Row Up", AllIcons.Actions.MoveUp, ctx, applier, true) {
      @Override
      boolean enabled(Context c, Point lead) {
        XNode row = rowAt(grid, c, lead.y);
        return row != null && sameNameSibling(row, true) != null;
      }

      @Override
      void run(Context c, Point lead) {
        move(owner, grid, c, applier, lead, true);
      }
    };
    up.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)), grid.table(), parent);
    g.add(up);
    AnAction down = new GridAction(grid, "Move Row Down", AllIcons.Actions.MoveDown, ctx, applier, true) {
      @Override
      boolean enabled(Context c, Point lead) {
        XNode row = rowAt(grid, c, lead.y);
        return row != null && sameNameSibling(row, false) != null;
      }

      @Override
      void run(Context c, Point lead) {
        move(owner, grid, c, applier, lead, false);
      }
    };
    down.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)), grid.table(), parent);
    g.add(down);
    g.add(new GridAction(grid, "Delete Rows", AllIcons.General.Remove, ctx, applier, true) {
      @Override
      public void update(@NotNull AnActionEvent e) {
        super.update(e);
        int n = grid.selectedModelRows().length;
        e.getPresentation().setText(n <= 1 ? "Delete Row" : "Delete " + n + " Rows");
      }

      @Override
      void run(Context c, Point lead) {
        List<String> paths = new ArrayList<>();
        for (int r : grid.selectedModelRows()) paths.add(c.table().rows().get(r).path());
        if (paths.isEmpty()) return;
        apply(owner, c, applier, List.of(Op.deleteElements(paths)), paths.size() == 1 ? "Delete Row" : "Delete " + paths.size() + " Rows", null, 0);
      }
    });
    g.add(Separator.getInstance());
    AnAction paste = new GridAction(grid, "Paste", AllIcons.Actions.MenuPaste, ctx, applier, false) {
      @Override
      void run(Context c, Point lead) {
        String text = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        String error = text == null || text.isEmpty() ? "The clipboard is empty." : paste(owner, grid, c, applier, lead, text);
        if (error != null) Messages.showErrorDialog(owner, error, "Can't Paste");
      }
    };
    paste.registerCustomShortcutSet(shortcuts(IdeActions.ACTION_PASTE, KeyEvent.VK_V), grid.table(), parent);
    g.add(paste);
    AnAction clear = new GridAction(grid, "Clear Cells", null, ctx, applier, false) {
      @Override
      void run(Context c, Point lead) {
        clearCells(owner, grid, c, applier);
      }
    };
    clear.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)), grid.table(), parent);
    g.add(clear);
    g.add(new GridAction(grid, "Add Column…", null, ctx, applier, true) {
      @Override
      void run(Context c, Point lead) {
        String n = Messages.showInputDialog(owner, "Name (@name for an attribute)", "New Column for <" + c.table().group() + ">", null, "",
                                            new InputValidatorEx() {
                                              @Override
                                              public @Nullable String getErrorText(@NotNull String input) {
                                                String v = input.strip();
                                                return StructureEdits.isQName(v.startsWith("@") ? v.substring(1) : v) ? null : "Not a valid XML name.";
                                              }
                                            });
        if (n == null) return;
        n = n.strip();
        apply(owner, c, applier, List.of(Op.addColumn(c.table().element().path(), c.table().group(), n)), "Add Column " + n, null, 0);
      }
    });
    g.add(new GridAction(grid, "Delete Column", AllIcons.General.Remove, ctx, applier, true) {
      @Override
      boolean enabled(Context c, Point lead) {
        GridColumn col = column(grid, c, lead);
        return col != null && col.kind() != GridColumn.Kind.TEXT;
      }

      @Override
      public void update(@NotNull AnActionEvent e) {
        super.update(e);
        Context c = ctx.get();
        Point lead = grid.leadCell();
        GridColumn col = c == null || lead == null ? null : column(grid, c, lead);
        if (col != null) e.getPresentation().setText("Delete Column " + col.label());
      }

      @Override
      void run(Context c, Point lead) {
        GridColumn col = column(grid, c, lead);
        if (col == null) return;
        apply(owner, c, applier, List.of(Op.deleteColumn(c.table().element().path(), c.table().group(), col.key())), "Delete Column " + col.label(), null, 0);
      }
    });
    return g;
  }

  private static ShortcutSet shortcuts(String actionId, int fallbackKey) {
    AnAction a = ActionManager.getInstance().getAction(actionId);
    if (a != null && a.getShortcutSet().getShortcuts().length > 0) return a.getShortcutSet();
    int mod = System.getProperty("os.name", "").startsWith("Mac") ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    return new CustomShortcutSet(new KeyboardShortcut(KeyStroke.getKeyStroke(fallbackKey, mod), null));
  }

  private static @Nullable XNode rowAt(GridView grid, Context c, int viewRow) {
    int[] rows = grid.viewModelRows();
    return viewRow >= 0 && viewRow < rows.length ? c.table().rows().get(rows[viewRow]) : null;
  }

  private static int modelCol(GridView grid, Point lead) {
    return lead.x < 0 || lead.x >= grid.table().getColumnCount() ? 0 : grid.table().convertColumnIndexToModel(lead.x);
  }

  private static @Nullable GridColumn column(GridView grid, Context c, Point lead) {
    int col = modelCol(grid, lead);
    return col < c.table().columnCount() ? c.table().columns().get(col) : null;
  }

  /** The nearest sibling of {@code row} with the same tag before (up) or after it. */
  static @Nullable XNode sameNameSibling(XNode row, boolean up) {
    XNode parent = row.parent();
    if (parent == null) return null;
    XNode prev = null;
    boolean found = false;
    for (XNode c : parent.children()) {
      if (!c.tag().equals(row.tag())) continue;
      if (found) return c;
      if (c == row) {
        if (up) return prev;
        found = true;
      }
      prev = c;
    }
    return null;
  }

  private static void move(JComponent owner, GridView grid, Context c, Applier applier, Point lead, boolean up) {
    XNode row = rowAt(grid, c, lead.y);
    XNode other = row == null ? null : sameNameSibling(row, up);
    if (other == null) return;
    // After the swap the moved row sits where its sibling was.
    apply(owner, c, applier, List.of(Op.move(row.path(), up, true)), "Move <" + row.tag() + (up ? "> Up" : "> Down"), other.path(), modelCol(grid, lead));
  }

  private static void clearCells(JComponent owner, GridView grid, Context c, Applier applier) {
    List<StructureEdits.Cell> cells = new ArrayList<>();
    for (int[] rc : grid.selectedCells()) {
      GridColumn col = c.table().columns().get(rc[1]);
      if (!(c.table().cell(rc[0], rc[1]) instanceof String s) || s.isEmpty() || col.kind() == GridColumn.Kind.COMPLEX) continue;
      if (ValueEdits.gridTarget(c.table(), rc[0], rc[1]) == null) continue;
      cells.add(new StructureEdits.Cell(c.table().rows().get(rc[0]).path(), col.key(), ""));
    }
    if (!cells.isEmpty()) apply(owner, c, applier, List.of(Op.setCells(cells)), cells.size() == 1 ? "Clear Cell" : "Clear " + cells.size() + " Cells", null, 0);
  }

  /**
   * Pastes tab-separated text at the lead cell: values go into the cells right and down from it
   * (missing attributes and children are added), rows beyond the last become new rows. A header
   * row matching the column labels is skipped; nested-table columns are left alone.
   */
  static @Nullable String paste(JComponent owner, GridView grid, Context c, Applier applier, Point lead, String text) {
    List<List<String>> rows = GridText.parseTsv(text);
    if (rows.isEmpty()) return "The clipboard is empty.";
    GridTable t = c.table();
    int[] view = grid.viewModelRows();
    int startCol = lead.x;
    if (rows.size() > 1) {
      List<String> first = rows.get(0);
      boolean header = true;
      for (int j = 0; j < first.size(); j++) {
        int vc = startCol + j;
        if (vc >= grid.table().getColumnCount() || !first.get(j).equals(t.columns().get(grid.table().convertColumnIndexToModel(vc)).label())) {
          header = false;
          break;
        }
      }
      if (header) rows = rows.subList(1, rows.size());
    }
    List<StructureEdits.Cell> cells = new ArrayList<>();
    List<Map<String, String>> added = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      int v = lead.y + i;
      XNode row = v < view.length ? t.rows().get(view[v]) : null;
      if (row == null && !c.isRowGrid()) continue;
      Map<String, String> rec = new LinkedHashMap<>();
      List<String> values = rows.get(i);
      for (int j = 0; j < values.size(); j++) {
        int vc = startCol + j;
        if (vc >= grid.table().getColumnCount()) break;
        GridColumn col = t.columns().get(grid.table().convertColumnIndexToModel(vc));
        if (col.kind() == GridColumn.Kind.COMPLEX) continue;
        if (row != null) cells.add(new StructureEdits.Cell(row.path(), col.key(), values.get(j)));
        else rec.put(col.key(), values.get(j));
      }
      if (row == null && !rec.isEmpty()) added.add(rec);
    }
    List<Op> ops = new ArrayList<>();
    if (!cells.isEmpty()) ops.add(Op.setCells(cells));
    if (!added.isEmpty()) ops.add(Op.insertRows(t.element().path(), t.group(), added));
    if (ops.isEmpty()) return "Nothing to paste here.";
    return compute(c, applier, ops, rows.size() == 1 ? "Paste" : "Paste " + rows.size() + " Rows", null, 0);
  }

  private static void apply(JComponent owner, Context c, Applier applier, List<Op> ops, String label, @Nullable String rowPath, int col) {
    String error = compute(c, applier, ops, label, rowPath, col);
    if (error != null) Messages.showErrorDialog(owner, error, "Can't Edit the XML");
  }

  /** Computes the operations on the same model and applies their edits together; returns null or the error. */
  private static @Nullable String compute(Context c, Applier applier, List<Op> ops, String label, @Nullable String rowPath, int col) {
    if (!applier.canEdit()) return "This document can't be edited now.";
    List<ValueEdits.TextEdit> edits = new ArrayList<>();
    for (Op op : ops) {
      StructureEdits.Result r = StructureEdits.compute(c.model(), op);
      if (!r.isOk()) return r.error();
      edits.addAll(r.edits());
    }
    if (edits.isEmpty()) return null;
    edits.sort((a, b) -> Integer.compare(b.offset(), a.offset()));
    return applier.apply(edits, label, rowPath, col);
  }

  /** An action shown for row grids; {@code structural} ones need a row grid, the others any grid with rows. */
  private abstract static class GridAction extends DumbAwareAction {
    private final GridView grid;
    private final Supplier<@Nullable Context> ctx;
    private final Applier applier;
    private final boolean rowGridOnly;

    GridAction(GridView grid, String text, @Nullable javax.swing.Icon icon, Supplier<@Nullable Context> ctx, Applier applier, boolean rowGridOnly) {
      super(text, null, icon);
      this.grid = grid;
      this.ctx = ctx;
      this.applier = applier;
      this.rowGridOnly = rowGridOnly;
    }

    abstract void run(Context c, Point lead);

    boolean enabled(Context c, Point lead) {
      return true;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }

    private @Nullable Context usable() {
      Context c = ctx.get();
      return c == null || (rowGridOnly && !c.isRowGrid()) ? null : c;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Context c = usable();
      e.getPresentation().setVisible(c != null);
      Point lead = c == null ? null : grid.leadCell();
      e.getPresentation().setEnabled(c != null && lead != null && applier.canEdit() && enabled(c, lead));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Context c = usable();
      Point lead = grid.leadCell();
      if (c == null || lead == null || !applier.canEdit() || !enabled(c, lead)) return;
      run(c, lead);
    }
  }
}
