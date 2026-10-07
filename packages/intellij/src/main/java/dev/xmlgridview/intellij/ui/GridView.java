package dev.xmlgridview.intellij.ui;

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
  }

  private static final int AUTO_FIT_SAMPLE = 500;

  private final GridTableModel model = new GridTableModel();
  private final JBTable table = new JBTable(model);
  private final TableRowSorter<GridTableModel> sorter = new TableRowSorter<>(model);
  private final GridFilter filter = new GridFilter();
  private final Listener listener;
  private final Supplier<FindState> findState;
  private boolean copyWithHeader;

  GridView(@NotNull Disposable parent, @NotNull Supplier<FindState> findState, @NotNull Listener listener) {
    super(new BorderLayout());
    this.listener = listener;
    this.findState = findState;

    table.setRowSorter(sorter);
    table.setCellSelectionEnabled(true);
    table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.setShowGrid(true);
    table.setStriped(true);
    table.getEmptyText().setText("No rows");
    table.setDefaultRenderer(Object.class, new CellRenderer());
    table.setDefaultRenderer(String.class, new CellRenderer());
    table.setDefaultEditor(Object.class, null);
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
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() != 2 || e.getButton() != MouseEvent.BUTTON1) return;
        int viewRow = table.rowAtPoint(e.getPoint());
        int viewCol = table.columnAtPoint(e.getPoint());
        if (viewRow < 0 || viewCol < 0) return;
        activate(viewRow, viewCol, true);
      }
    });

    AnAction navigate = new DumbAwareAction() {
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
      public void actionPerformed(@NotNull AnActionEvent e) {
        int row = table.getSelectionModel().getLeadSelectionIndex();
        int col = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        if (row >= 0 && col >= 0) activate(row, col, true);
      }
    };
    drill.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK)), table, parent);

    PopupHandler.installPopupMenu(table, popupActions(), "XmlGridView.Grid");

    JScrollPane scroll = ScrollPaneFactory.createScrollPane(table, true);
    RowNumberHeader rowHeader = new RowNumberHeader(table);
    scroll.setRowHeaderView(rowHeader);
    add(scroll, BorderLayout.CENTER);
  }

  JBTable table() {
    return table;
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
      if (c.kind() == GridColumn.Kind.COMPLEX && t.cell(row, col) != null) setToolTipText("Double-click or Alt+Down to drill down");
      else setToolTipText(null);
    }
  }
}
