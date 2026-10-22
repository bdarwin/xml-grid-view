package dev.xmlgridview.intellij.ui;

import dev.xmlgridview.intellij.model.ValueEdits;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CopyProvider;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.xmlgridview.intellij.model.ColumnFilter;
import dev.xmlgridview.intellij.model.GridColumn;
import dev.xmlgridview.intellij.model.GridFilter;
import dev.xmlgridview.intellij.model.GridMatch;
import dev.xmlgridview.intellij.model.GridTable;
import dev.xmlgridview.intellij.model.InspectTarget;
import dev.xmlgridview.intellij.model.SearchEngine;
import dev.xmlgridview.intellij.model.XNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The grid: JBTable with sorting, per-column filters (AND), a row-number
 * header, multi-cell selection, TSV copy and drill-down into complex cells.
 */
final class GridView extends JPanel implements UiDataProvider {
  interface Listener {
    void drillDown(@NotNull XNode row, @NotNull String tag);

    void navigate(int offset);

    void filtersChanged();

    /** Whether values can be edited now (writable document, current model). */
    default boolean canEdit() {
      return false;
    }

    /** Applies a value edit; returns null on success or the reason it was refused. */
    default @Nullable String applyEdit(@NotNull ValueEdits.Target target, @NotNull String value) {
      return "Editing is not available.";
    }
  }

  private static final int AUTO_FIT_SAMPLE = 500;

  private final GridTableModel model = new GridTableModel();
  /** Shows why an edit was refused; replaceable in tests. */
  private java.util.function.Consumer<String> editErrors = this::showEditError;
  private final JBTable table = new JBTable(model);
  private final TableRowSorter<GridTableModel> sorter = new TableRowSorter<>(model);
  private final GridFilter filter = new GridFilter();
  private final Listener listener;
  private final Supplier<FindState> findState;
  private boolean copyWithHeader;
  private @Nullable Consumer<InspectTarget> inspector;
  private DefaultActionGroup popupMenu;
  private @Nullable DocumentProblems problems;
  /** Problems by cell (model row << 32 | model column) and by model row, for the current table. */
  private Map<Long, List<DocumentProblems.Problem>> cellProblems = Map.of();
  private Map<Integer, List<DocumentProblems.Problem>> rowProblems = Map.of();
  private @Nullable DocumentChanges changes;
  private DocumentChanges.GridMarks changeMarks = DocumentChanges.GridMarks.NONE;
  private RowNumberHeader rowHeader;
  private final InspectAction inspectAction;

  GridView(@NotNull Disposable parent, @NotNull Supplier<FindState> findState, @NotNull Listener listener) {
    super(new BorderLayout());
    this.listener = listener;
    this.findState = findState;

    table.setRowSorter(sorter);
    table.setCellSelectionEnabled(true);
    table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    GridLines.apply(table);
    // JBTable otherwise renders every row to pick the row height after each model change
    // (seconds on large files); a sample of the first rows is enough.
    table.setMaxItemsForSizeCalculation(100);
    table.getEmptyText().setText("No rows");
    table.setDefaultRenderer(Object.class, new CellRenderer());
    table.setDefaultRenderer(String.class, new CellRenderer());
    CellEditing.install(table, parent);
    model.setEditHandler(new GridTableModel.EditHandler() {
      @Override
      public boolean isEditable(int row, int col) {
        GridTable t = model.table();
        return t != null && listener.canEdit() && ValueEdits.gridTarget(t, row, col) != null;
      }

      @Override
      public void commit(int row, int col, String value) {
        GridTable t = model.table();
        ValueEdits.Target target = t == null ? null : ValueEdits.gridTarget(t, row, col);
        if (target == null) return;
        String error = listener.applyEdit(target, value);
        if (error != null) editErrors.accept(error);
      }
    });
    sorter.setRowFilter(new RowFilter<>() {
      @Override
      public boolean include(Entry<? extends GridTableModel, ? extends Integer> entry) {
        GridTable t = model.table();
        return t == null || filter.accepts(t, entry.getIdentifier());
      }
    });

    JTableHeader header = table.getTableHeader();
    header.setReorderingAllowed(false);
    TableCellRenderer base = header.getDefaultRenderer();
    header.setDefaultRenderer((tbl, value, isSelected, hasFocus, row, column) -> {
      Component c = base.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
      if (c instanceof JLabel l) {
        GridTable t = model.table();
        String key = t == null || column < 0 ? null : t.columns().get(tbl.convertColumnIndexToModel(column)).key();
        boolean filtered = key != null && filter.get(key) != null;
        l.setIcon(filtered ? AllIcons.General.Filter : null);
        if (t != null && column >= 0) {
          GridColumn gc = t.columns().get(tbl.convertColumnIndexToModel(column));
          l.setForeground(gc.kind() == GridColumn.Kind.ATTR ? XmlColors.attrName() : gc.kind() == GridColumn.Kind.TEXT ? l.getForeground() : XmlColors.tag());
          l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD));
        }
        l.setHorizontalTextPosition(SwingConstants.LEADING);
        l.setToolTipText("Click to sort; right-click to filter");
      }
      return c;
    });
    header.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        if (e.isPopupTrigger()) showFilter(e);
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        if (e.isPopupTrigger()) showFilter(e);
      }

      private void showFilter(MouseEvent e) {
        int view = header.columnAtPoint(e.getPoint());
        if (view >= 0) openFilterPopup(table.convertColumnIndexToModel(view));
      }
    });

    table.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1 || e.getClickCount() != 1 || e.isShiftDown() || e.isControlDown() || e.isMetaDown() || inspector == null) return;
        int viewRow = table.rowAtPoint(e.getPoint());
        int viewCol = table.columnAtPoint(e.getPoint());
        GridTable t = model.table();
        if (t == null || viewRow < 0 || viewCol < 0) return;
        int row = table.convertRowIndexToModel(viewRow);
        int col = table.convertColumnIndexToModel(viewCol);
        if (t.columns().isEmpty() || t.columns().get(col).kind() == GridColumn.Kind.COMPLEX) return;
        if (!InspectAction.worthInspecting(t.cellText(row, col))) return;
        Rectangle cell = table.getCellRect(viewRow, viewCol, false);
        if (!InspectAction.hitsCellIcon(e.getX(), cell.x, cell.width)) return;
        table.changeSelection(viewRow, viewCol, false, false);
        InspectTarget target = InspectTarget.ofGridCell(t, row, col);
        if (target != null) inspector.accept(target);
        e.consume();
      }

      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() != 2 || e.getButton() != MouseEvent.BUTTON1) return;
        int viewRow = table.rowAtPoint(e.getPoint());
        int viewCol = table.columnAtPoint(e.getPoint());
        if (viewRow < 0 || viewCol < 0) return;
        onDoubleClick(viewRow, viewCol);
      }
    });

    AnAction navigate = new DumbAwareAction() {
      @Override
      public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(!table.isEditing());
      }

      @Override
      public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
      }

      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        int row = table.getSelectionModel().getLeadSelectionIndex();
        int col = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        if (row >= 0 && row < table.getRowCount()) activate(row, Math.max(0, col), false);
      }
    };
    navigate.registerCustomShortcutSet(new CustomShortcutSet(new com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), null),
                                                             new com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), null)), table, parent);
    AnAction drill = new DumbAwareAction() {
      @Override
      public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(!table.isEditing());
      }

      @Override
      public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
      }

      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        int row = table.getSelectionModel().getLeadSelectionIndex();
        int col = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        if (row >= 0 && col >= 0) activate(row, col, true);
      }
    };
    drill.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK)), table, parent);

    inspectAction = InspectAction.install(table, parent, this::inspectTarget, t -> {
      if (inspector != null) inspector.accept(t);
    });
    popupMenu = popupActions();
    PopupHandler.installPopupMenu(table, popupMenu, "XmlGridView.Grid");
    selectCellOnRightClick(table);

    JScrollPane scroll = ScrollPaneFactory.createScrollPane(table, true);
    rowHeader = new RowNumberHeader(table);
    rowHeader.setMarker(r -> {
      List<DocumentProblems.Problem> ps = rowProblems.get(r);
      return ps == null ? null : DocumentProblems.color(ps);
    });
    rowHeader.setChangeMarker(r -> {
      DocumentChanges.Mark m = changeMarks.rows().get(r);
      return m == null ? null : DocumentChanges.color(m.kind());
    });
    rowHeader.setGapMarkers(r -> changeMarks.removedAbove().containsKey(r),
                            r -> r == tableRows() - 1 && changeMarks.removedAbove().containsKey(r + 1));
    rowHeader.setTooltips(r -> {
      List<String> lines = new ArrayList<>();
      DocumentChanges.Mark m = changeMarks.rows().get(r);
      if (m != null) lines.add(m.tooltip().replaceAll("<br>", "\n").replaceAll("<[^>]+>", ""));
      String above = changeMarks.removedAbove().get(r);
      if (above != null) lines.add("Above: " + above);
      String below = r == tableRows() - 1 ? changeMarks.removedAbove().get(r + 1) : null;
      if (below != null) lines.add("Below: " + below);
      if (lines.isEmpty()) return null;
      return DocumentChanges.html(java.util.Arrays.asList(String.join("\n\n", lines).split("\n", -1)));
    });
    scroll.setRowHeaderView(rowHeader);
    add(scroll, BorderLayout.CENTER);
  }

  JBTable table() {
    return table;
  }

  private int tableRows() {
    GridTable t = model.table();
    return t == null ? 0 : t.rowCount();
  }

  void setChanges(@NotNull DocumentChanges changes) {
    this.changes = changes;
    recomputeChanges();
  }

  /** Maps the changes against the baseline onto the current table. */
  void recomputeChanges() {
    changeMarks = changes == null ? DocumentChanges.GridMarks.NONE : changes.grid(model.table());
    table.repaint();
    if (rowHeader != null) rowHeader.repaint();
  }

  @org.jetbrains.annotations.TestOnly
  DocumentChanges.GridMarks changeMarksForTest() {
    return changeMarks;
  }

  void setProblems(@NotNull DocumentProblems problems) {
    this.problems = problems;
    recomputeProblems();
  }

  /**
   * Maps the document's problems onto the table: a problem inside a row's child goes to that
   * child's column, one inside an attribute to its column, the rest to the row (number).
   */
  void recomputeProblems() {
    GridTable t = model.table();
    Map<Long, List<DocumentProblems.Problem>> cells = new java.util.HashMap<>();
    Map<Integer, List<DocumentProblems.Problem>> rows = new java.util.HashMap<>();
    if (t != null && problems != null && !problems.ordered().isEmpty()) {
      Map<XNode, Integer> rowOf = new java.util.IdentityHashMap<>();
      for (int r = 0; r < t.rowCount(); r++) rowOf.put(t.rows().get(r), r);
      for (DocumentProblems.Located l : problems.ordered()) {
        XNode child = null;
        XNode e = l.node();
        while (e != null && !rowOf.containsKey(e)) {
          child = e;
          e = e.parent();
        }
        if (e == null) continue;
        int r = rowOf.get(e);
        int col = -1;
        if (child != null) col = t.columnIndex(child.tag());
        else {
          for (int c = 0; c < t.columnCount() && col < 0; c++) {
            GridColumn gc = t.columns().get(c);
            if (gc.kind() != GridColumn.Kind.ATTR) continue;
            dev.xmlgridview.intellij.model.XAttr a = e.attr(gc.label().substring(1));
            if (a != null && l.problem().offset() >= a.start() && l.problem().offset() < a.end()) col = c;
          }
        }
        if (col >= 0) cells.computeIfAbsent(((long)r << 32) | col, k -> new ArrayList<>()).add(l.problem());
        else rows.computeIfAbsent(r, k -> new ArrayList<>()).add(l.problem());
      }
    }
    cellProblems = cells;
    rowProblems = rows;
    table.repaint();
    if (rowHeader != null) rowHeader.repaint();
  }

  @org.jetbrains.annotations.TestOnly
  List<DocumentProblems.Problem> cellProblemsForTest(int modelRow, int modelCol) {
    return cellProblems.getOrDefault(((long)modelRow << 32) | modelCol, List.of());
  }

  @org.jetbrains.annotations.TestOnly
  List<DocumentProblems.Problem> rowProblemsForTest(int modelRow) {
    return rowProblems.getOrDefault(modelRow, List.of());
  }

  JComponent focusComponent() {
    return table;
  }

  @Nullable GridTable gridTable() {
    return model.table();
  }

  GridFilter filter() {
    return filter;
  }

  void setCopyWithHeader(boolean v) {
    copyWithHeader = v;
  }

  /** Shows a table with the given sort keys and filters (by column key); unknown columns are ignored. */
  void setTable(@Nullable GridTable t, Map<String, SortOrder> sortKeys, Map<String, ColumnFilter> filters) {
    filter.clear();
    model.setTable(t);
    if (t != null) {
      filters.forEach((k, f) -> {
        if (t.columnIndex(k) >= 0) filter.set(k, f);
      });
      List<RowSorter.SortKey> keys = new ArrayList<>();
      sortKeys.forEach((k, order) -> {
        int i = t.columnIndex(k);
        if (i >= 0) keys.add(new RowSorter.SortKey(i, order));
      });
      for (int c = 0; c < t.columnCount(); c++) sorter.setComparator(c, CellComparator.INSTANCE);
      recomputeProblems();
      recomputeChanges();
      sorter.setSortKeys(keys);
      sorter.allRowsChanged();
      autoFit();
    }
    listener.filtersChanged();
  }

  /** Current sort keys by column key. */
  Map<String, SortOrder> sortKeys() {
    Map<String, SortOrder> out = new LinkedHashMap<>();
    GridTable t = model.table();
    if (t == null) return out;
    for (RowSorter.SortKey k : sorter.getSortKeys()) {
      if (k.getColumn() < t.columnCount() && k.getSortOrder() != SortOrder.UNSORTED) {
        out.put(t.columns().get(k.getColumn()).key(), k.getSortOrder());
      }
    }
    return out;
  }

  Map<String, ColumnFilter> filters() {
    return new LinkedHashMap<>(filter.asMap());
  }

  void setColumnFilter(String key, @Nullable ColumnFilter f) {
    filter.set(key, f);
    sorter.allRowsChanged();
    table.getTableHeader().repaint();
    listener.filtersChanged();
  }

  void clearFilters() {
    filter.clear();
    sorter.allRowsChanged();
    table.getTableHeader().repaint();
    listener.filtersChanged();
  }

  int visibleRowCount() {
    return table.getRowCount();
  }

  /** Model rows in view (sorted, filtered) order. */
  int[] viewModelRows() {
    int[] out = new int[table.getRowCount()];
    for (int i = 0; i < out.length; i++) out[i] = table.convertRowIndexToModel(i);
    return out;
  }

  /** The lead cell in view coordinates, or null when nothing is selected. */
  @Nullable java.awt.Point leadCell() {
    int row = table.getSelectionModel().getLeadSelectionIndex();
    int col = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
    if (row < 0 || row >= table.getRowCount()) return null;
    return new java.awt.Point(Math.max(0, Math.min(col, table.getColumnCount() - 1)), row);
  }

  /** Selected model rows (any selected cell), in view order. */
  int[] selectedModelRows() {
    int[] rows = table.getSelectedRows();
    for (int i = 0; i < rows.length; i++) rows[i] = table.convertRowIndexToModel(rows[i]);
    return rows;
  }

  /** Selected cells as {model row, model column} pairs, in view order. */
  List<int[]> selectedCells() {
    List<int[]> out = new ArrayList<>();
    for (int r : table.getSelectedRows()) {
      for (int c : table.getSelectedColumns()) {
        if (table.isCellSelected(r, c)) out.add(new int[]{table.convertRowIndexToModel(r), table.convertColumnIndexToModel(c)});
      }
    }
    return out;
  }

  /** Selects the cell of {@code node}'s row in model column {@code col}, if visible. */
  void selectRowCell(@NotNull XNode node, int col) {
    GridTable t = model.table();
    if (t == null) return;
    int modelRow = t.rows().indexOf(node);
    if (modelRow < 0 || t.columnCount() == 0) return;
    selectCell(new GridMatch(modelRow, Math.max(0, Math.min(col, t.columnCount() - 1))));
  }

  /** Selects the row showing {@code node}, if visible. */
  void selectRow(@NotNull XNode node) {
    GridTable t = model.table();
    if (t == null) return;
    int modelRow = t.rows().indexOf(node);
    if (modelRow < 0) return;
    int viewRow = sorter.convertRowIndexToView(modelRow);
    if (viewRow < 0) return;
    table.setRowSelectionInterval(viewRow, viewRow);
    table.setColumnSelectionInterval(0, Math.max(0, table.getColumnCount() - 1));
    table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
  }

  /** Selects one cell (model coordinates); returns false when it is filtered out. */
  boolean selectCell(@NotNull GridMatch m) {
    int viewRow = sorter.convertRowIndexToView(m.row());
    int viewCol = table.convertColumnIndexToView(m.col());
    if (viewRow < 0 || viewCol < 0) return false;
    table.changeSelection(viewRow, viewCol, false, false);
    Rectangle r = table.getCellRect(viewRow, viewCol, true);
    table.scrollRectToVisible(r);
    return true;
  }

  /** Grid matches ordered by their position in the current (sorted, filtered) view. */
  List<GridMatch> inViewOrder(List<GridMatch> matches) {
    List<GridMatch> out = new ArrayList<>();
    for (GridMatch m : matches) if (sorter.convertRowIndexToView(m.row()) >= 0) out.add(m);
    out.sort((a, b) -> {
      int c = Integer.compare(sorter.convertRowIndexToView(a.row()), sorter.convertRowIndexToView(b.row()));
      return c != 0 ? c : Integer.compare(table.convertColumnIndexToView(a.col()), table.convertColumnIndexToView(b.col()));
    });
    return out;
  }

  void autoFit() {
    int max = JBUI.scale(400);
    int min = JBUI.scale(40);
    int pad = JBUI.scale(12);
    JTableHeader header = table.getTableHeader();
    int rows = Math.min(table.getRowCount(), AUTO_FIT_SAMPLE);
    for (int c = 0; c < table.getColumnCount(); c++) {
      TableColumn col = table.getColumnModel().getColumn(c);
      Component h = header.getDefaultRenderer().getTableCellRendererComponent(table, col.getHeaderValue(), false, false, -1, c);
      int w = h.getPreferredSize().width;
      for (int r = 0; r < rows && w < max; r++) {
        Component cell = table.prepareRenderer(table.getCellRenderer(r, c), r, c);
        w = Math.max(w, cell.getPreferredSize().width);
      }
      col.setPreferredWidth(Math.max(min, Math.min(max, w + pad)));
    }
  }

  void setInspector(@NotNull Consumer<InspectTarget> inspector) {
    this.inspector = inspector;
  }

  /** Appends actions (e.g. structural edits) to the grid's context menu. */
  void addMenuActions(@NotNull DefaultActionGroup actions) {
    popupMenu.addAll(actions);
  }

  /** Right-clicking a cell outside the selection selects it first, so the menu acts on what was clicked. */
  static void selectCellOnRightClick(@NotNull JTable t) {
    t.addMouseListener(new java.awt.event.MouseAdapter() {
      @Override
      public void mousePressed(java.awt.event.MouseEvent e) {
        if (!javax.swing.SwingUtilities.isRightMouseButton(e)) return;
        int row = t.rowAtPoint(e.getPoint());
        int col = t.columnAtPoint(e.getPoint());
        if (row < 0 || col < 0 || t.isCellSelected(row, col)) return;
        t.changeSelection(row, col, false, false);
      }
    });
  }

  /**
   * The element the context menu acts on: for the lead cell, an attribute cell's row element and
   * attribute, a leaf cell's child element, otherwise the row element.
   */
  @Nullable StructureActions.Target structureTarget(@NotNull dev.xmlgridview.intellij.model.XmlDocumentModel m) {
    GridTable t = model.table();
    int viewRow = table.getSelectionModel().getLeadSelectionIndex();
    if (t == null || viewRow < 0 || viewRow >= table.getRowCount()) return null;
    XNode row = t.rows().get(table.convertRowIndexToModel(viewRow));
    int viewCol = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
    if (viewCol >= 0 && viewCol < table.getColumnCount()) {
      GridColumn c = t.columns().get(table.convertColumnIndexToModel(viewCol));
      if (c.kind() == GridColumn.Kind.ATTR && row.attr(c.label().substring(1)) != null) {
        return new StructureActions.Target(m, row, c.label().substring(1));
      }
      if (c.kind() == GridColumn.Kind.LEAF) {
        for (XNode ch : row.children()) if (ch.tag().equals(c.label())) return new StructureActions.Target(m, ch, null);
      }
    }
    return new StructureActions.Target(m, row, null);
  }

  /**
   * Inspector target for the lead cell. A whole-row selection (row number click)
   * targets the row element itself.
   */
  @Nullable InspectTarget inspectTarget() {
    GridTable t = model.table();
    int viewRow = table.getSelectionModel().getLeadSelectionIndex();
    if (t == null || viewRow < 0 || viewRow >= table.getRowCount()) return null;
    int row = table.convertRowIndexToModel(viewRow);
    int viewCol = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
    boolean wholeRow = table.getColumnCount() > 1 && table.getSelectedColumnCount() == table.getColumnCount();
    int col = wholeRow || viewCol < 0 ? -1 : table.convertColumnIndexToModel(viewCol);
    return InspectTarget.ofGridCell(t, row, col);
  }

  private void showEditError(String message) {
    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
      .createHtmlTextBalloonBuilder(com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(message),
                                    com.intellij.openapi.ui.MessageType.WARNING, null)
      .setFadeoutTime(6000)
      .createBalloon()
      .show(com.intellij.ui.awt.RelativePoint.getCenterOf(table), com.intellij.openapi.ui.popup.Balloon.Position.above);
  }

  void setEditErrorReporter(@NotNull java.util.function.Consumer<String> reporter) {
    editErrors = reporter;
  }

  /** Double-click: a value opens the inspector; drill cells drill down; empty cells go to the source. */
  void onDoubleClick(int viewRow, int viewCol) {
    GridTable t = model.table();
    if (t != null && inspector != null && !t.columns().isEmpty()) {
      int row = table.convertRowIndexToModel(viewRow);
      int col = table.convertColumnIndexToModel(viewCol);
      if (t.columns().get(col).kind() != GridColumn.Kind.COMPLEX && t.cell(row, col) != null) {
        InspectTarget target = InspectTarget.ofGridCell(t, row, col);
        if (target != null) inspector.accept(target);
        return;
      }
    }
    activate(viewRow, viewCol, true);
  }

  private void activate(int viewRow, int viewCol, boolean preferDrill) {
    GridTable t = model.table();
    if (t == null) return;
    int row = table.convertRowIndexToModel(viewRow);
    int col = table.convertColumnIndexToModel(viewCol);
    GridColumn column = t.columns().isEmpty() ? null : t.columns().get(col);
    if (preferDrill && column != null && column.kind() == GridColumn.Kind.COMPLEX && t.cell(row, col) != null) {
      listener.drillDown(t.rows().get(row), column.label());
    }
    else {
      listener.navigate(t.columns().isEmpty() ? t.rows().get(row).start() : t.cellOffset(row, col));
    }
  }

  void openFilterPopup(int modelCol) {
    GridTable t = model.table();
    if (t == null || modelCol < 0 || modelCol >= t.columnCount()) return;
    String key = t.columns().get(modelCol).key();
    int viewCol = table.convertColumnIndexToView(modelCol);
    Rectangle r = table.getTableHeader().getHeaderRect(viewCol);
    ColumnFilterPopup.show(table.getTableHeader(), r, t, modelCol, filter.get(key), f -> setColumnFilter(key, f));
  }

  /** Selected cells as TSV in view order; rows and columns of a rectangular or multi-interval selection. */
  String selectionAsTsv(boolean withHeader) {
    int[] rows = table.getSelectedRows();
    int[] cols = table.getSelectedColumns();
    if (rows.length == 0 || cols.length == 0) return "";
    StringBuilder sb = new StringBuilder();
    if (withHeader) {
      for (int i = 0; i < cols.length; i++) {
        if (i > 0) sb.append('\t');
        sb.append(clean(table.getColumnName(cols[i])));
      }
      sb.append('\n');
    }
    for (int r : rows) {
      for (int i = 0; i < cols.length; i++) {
        if (i > 0) sb.append('\t');
        Object v = table.getValueAt(r, cols[i]);
        sb.append(clean(v == null ? "" : v.toString()));
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  private static String clean(String s) {
    return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
  }

  void copy(boolean withHeader) {
    String tsv = selectionAsTsv(withHeader);
    if (!tsv.isEmpty()) CopyPasteManager.getInstance().setContents(new StringSelection(tsv));
  }

  private final CopyProvider copyProvider = new CopyProvider() {
    @Override
    public void performCopy(@NotNull DataContext dataContext) {
      copy(copyWithHeader);
    }

    @Override
    public boolean isCopyEnabled(@NotNull DataContext dataContext) {
      return table.getSelectedRowCount() > 0 && table.getSelectedColumnCount() > 0;
    }

    @Override
    public boolean isCopyVisible(@NotNull DataContext dataContext) {
      return true;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }
  };

  @Override
  public void uiDataSnapshot(@NotNull DataSink sink) {
    sink.set(PlatformDataKeys.COPY_PROVIDER, copyProvider);
  }

  private DefaultActionGroup popupActions() {
    DefaultActionGroup g = new DefaultActionGroup();
    g.add(new DumbAwareAction("Copy", null, AllIcons.Actions.Copy) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        copy(false);
      }
    });
    g.add(new DumbAwareAction("Copy with Header") {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        copy(true);
      }
    });
    g.add(Separator.getInstance());
    g.add(new DumbAwareAction("Filter Column…", null, AllIcons.General.Filter) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        int col = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        if (col >= 0) openFilterPopup(table.convertColumnIndexToModel(col));
      }
    });
    g.add(new DumbAwareAction("Clear All Filters") {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        clearFilters();
      }

      @Override
      public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(!filter.isEmpty());
      }

      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
      }
    });
    g.add(new DumbAwareAction("Auto-Fit Columns") {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        autoFit();
      }
    });
    g.add(Separator.getInstance());
    g.add(inspectAction);
    g.add(new DumbAwareAction("Go to Source", null, AllIcons.Actions.EditSource) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        int row = table.getSelectionModel().getLeadSelectionIndex();
        int col = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        if (row >= 0) activate(row, Math.max(0, col), false);
      }
    });
    return g;
  }

  private final class CellRenderer extends ColoredTableCellRenderer {
    @Override
    protected void customizeCellRenderer(@NotNull JTable tbl, @Nullable Object value, boolean selected, boolean hasFocus,
                                         int viewRow, int viewCol) {
      GridTable t = model.table();
      if (t == null || value == null) return;
      int row = tbl.convertRowIndexToModel(viewRow);
      int col = tbl.convertColumnIndexToModel(viewCol);
      GridColumn c = t.columns().get(col);
      String text = value.toString();
      FindState fs = findState.get();
      GridMatch here = new GridMatch(row, col);
      boolean current = here.equals(fs.currentCell());
      SimpleTextAttributes attrs = c.kind() == GridColumn.Kind.COMPLEX && t.cell(row, col) != null
                                   ? SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
                                   : c.kind() == GridColumn.Kind.ATTR && !selected
                                     ? XmlColors.attrValueAttrs(SimpleTextAttributes.STYLE_PLAIN)
                                     : SimpleTextAttributes.REGULAR_ATTRIBUTES;
      if (current) attrs = attrs.derive(SimpleTextAttributes.STYLE_BOLD, null, null, null);
      boolean enabled = SearchEngine.columnEnabled(c, fs.targets());
      if (fs.matcher() != null && enabled) {
        if (c.kind() == GridColumn.Kind.COMPLEX && t.cell(row, col) != null) {
          // Highlight the tag inside "{tag ×n}".
          append("{", attrs);
          Highlight.append(this, c.label(), attrs, fs.matcher());
          append(text.substring(c.label().length() + 1), attrs);
        }
        else {
          Highlight.append(this, text, attrs, fs.matcher());
        }
      }
      else {
        append(text, attrs);
      }
      boolean inspect = c.kind() != GridColumn.Kind.COMPLEX && InspectAction.worthInspecting(text);
      setIcon(inspect ? InspectAction.CELL_ICON : null);
      setIconOnTheRight(true);
      if (c.kind() == GridColumn.Kind.COMPLEX && t.cell(row, col) != null) setToolTipText("Double-click or Alt+Down to drill down");
      else setToolTipText(inspect ? "Double-click, Shift+Enter or click the icon to inspect the full value" : null);
      if (!selected) setBackground(XmlColors.stripe(viewRow, tbl.getBackground()));
      // Changes: a tint over the cell (the whole row for a new row) and what changed as tooltip.
      DocumentChanges.Mark change = changeMarks.cells().get(((long)row << 32) | col);
      DocumentChanges.Mark rowChange = changeMarks.rows().get(row);
      if (change == null && rowChange != null && rowChange.kind() == DocumentChanges.Kind.ADDED) change = rowChange;
      if (change != null) {
        setBackground(DocumentChanges.tint(DocumentChanges.color(change.kind()), selected ? tbl.getSelectionBackground() : getBackground()));
        String tip = change.tooltip();
        setToolTipText(tip.startsWith("<html>") ? tip : DocumentChanges.html(List.of(tip.split("\n", -1))));
      }
      boolean gapAbove = changeMarks.removedAbove().containsKey(row);
      boolean gapBelow = row == t.rowCount() - 1 && changeMarks.removedAbove().containsKey(row + 1);
      if (gapAbove || gapBelow) {
        setBorder(javax.swing.BorderFactory.createMatteBorder(gapAbove ? JBUI.scale(2) : 0, 0, gapBelow ? JBUI.scale(2) : 0, 0, DocumentChanges.removed()));
      }
      List<DocumentProblems.Problem> ps = cellProblems.get(((long)row << 32) | col);
      if (ps != null) {
        // A bar along the bottom of the cell, like the web view, and the messages as tooltip.
        setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, JBUI.scale(2), 0, DocumentProblems.color(ps)));
        setToolTipText(DocumentProblems.tooltip(ps));
      }
      // SimpleColoredComponent fills the icon area with the table's background; leave it unfilled
      // so striped rows stay striped behind the inspect icon.
      setTransparentIconBackground(true);
    }
  }
}
