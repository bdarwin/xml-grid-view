package dev.xmlgridview.intellij.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.CopyProvider;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.table.JBTable;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.JBUI;
import dev.xmlgridview.intellij.model.DocMatch;
import dev.xmlgridview.intellij.model.FlatRow;
import dev.xmlgridview.intellij.model.InspectTarget;
import dev.xmlgridview.intellij.model.InvalidQueryException;
import dev.xmlgridview.intellij.model.Matcher;
import dev.xmlgridview.intellij.model.SearchEngine;
import dev.xmlgridview.intellij.model.SearchTargets;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XPathEngine;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The Flat view: an outline sheet of the whole document with a Name column
 * (indented by depth, expandable) and a Value column. Each element row is
 * followed by its attribute rows ({@code @name}) and then its children.
 */
public final class FlatPanel extends JPanel implements Disposable, UiDataProvider {
  private static final int NAME_COL = 0;
  private static final int VALUE_COL = 1;
  private static final int WIDTH_SAMPLE = 2000;

  private final IntConsumer navigator;
  private final Project project;
  private final InspectAction inspectAction;
  private boolean userSizedName;
  /** Opens the value inspector; replaceable in tests. */
  private java.util.function.Consumer<InspectTarget> inspectorOpener;
  private boolean fittingName;
  private final ModelLoader loader;
  private final Alarm searchAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
  private final FindBar findBar;
  private final RowsModel rowsModel = new RowsModel();
  private final JBTable table = new JBTable(rowsModel);
  /** Paths of collapsed elements; kept across refreshes. */
  private final Set<String> collapsed = new HashSet<>();

  private @Nullable XmlDocumentModel model;
  private FindState findState = FindState.NONE;
  /** Find hits as row keys (see {@link FlatRow#key()}), in document order. */
  private List<String> matchKeys = List.of();
  private boolean matchesTruncated;
  private int current = -1;

  public FlatPanel(@NotNull Project project, @NotNull Document document, @NotNull IntConsumer navigator,
                   @NotNull Disposable parent) {
    super(new BorderLayout());
    this.navigator = navigator;
    this.project = project;
    Disposer.register(parent, this);
    loader = new ModelLoader(project, document, this, navigator, (previous, built) -> modelChanged(built), this);

    findBar = new FindBar(this, new FindBar.Listener() {
      @Override
      public void queryChanged() {
        scheduleSearch(ModelLoader.DEBOUNCE_MS);
      }

      @Override
      public void next() {
        step(1);
      }

      @Override
      public void previous() {
        step(-1);
      }

      @Override
      public void close() {
        hideFind();
      }

      @Override
      public void showOnlyMatchesChanged() {
        rebuildRows(selectedKey());
      }

      @Override
      public void toggleResults() {
      }
    });
    // The flat sheet covers the whole document, so there is no grid scope or separate results list.
    findBar.setScope(FindBar.Scope.DOCUMENT);
    findBar.setScopeVisible(false);
    findBar.setResultsVisible(false);
    findBar.setVisible(false);

    JPanel north = new JPanel();
    north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
    north.add(loader.banners());
    north.add(findBar);
    add(north, BorderLayout.NORTH);

    // Spreadsheet-style rectangular cell selection (any rows x Name and/or Value).
    table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    table.setCellSelectionEnabled(true);
    // Columns are sized explicitly: Name fits its content, Value fills the rest (see fitColumns).
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    GridLines.apply(table);
    table.getEmptyText().setText("No XML elements");
    table.getTableHeader().setReorderingAllowed(false);
    // A manual drag of the Name column's edge stops automatic fitting.
    table.getTableHeader().addMouseListener(new MouseAdapter() {
      @Override
      public void mouseReleased(MouseEvent e) {
        javax.swing.table.TableColumn resized = table.getTableHeader().getResizingColumn();
        if (resized != null && !fittingName && resized.getModelIndex() == NAME_COL) userSizedName = true;
      }

      @Override
      public void mouseClicked(MouseEvent e) {
        // Double-click the header to fit again.
        if (e.getClickCount() == 2) {
          userSizedName = false;
          fitNameColumn();
        }
      }
    });
    table.setDefaultRenderer(Object.class, new Renderer());
    table.setDefaultEditor(Object.class, null);
    table.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1) return;
        if (e.getClickCount() == 1 && clickedInspectIcon(e)) return;
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0 && table.columnAtPoint(e.getPoint()) == NAME_COL && inTwisty(row, e.getX())) {
          toggle(row);
          e.consume();
        }
      }

      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() != 2 || e.getButton() != MouseEvent.BUTTON1) return;
        int row = table.rowAtPoint(e.getPoint());
        int viewCol = table.columnAtPoint(e.getPoint());
        if (row < 0 || viewCol < 0) return;
        if (table.convertColumnIndexToModel(viewCol) == VALUE_COL) {
          // Double-click a value: inspect it (one window per value).
          inspectorOpener.accept(InspectTarget.ofFlatRow(rowsModel.rows.get(row)));
        }
        else if (!inTwisty(row, e.getX())) {
          navigateRow(row);
        }
      }
    });

    action(() -> {
      int row = leadRow();
      if (row >= 0) navigateRow(row);
    }, new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), null),
           new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), null));
    action(this::left, new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), null));
    action(this::right, new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), null));

    inspectorOpener = t -> ValueInspector.show(this.project, t);
    inspectAction = InspectAction.install(table, this, this::inspectTarget, t -> inspectorOpener.accept(t));
    PopupHandler.installPopupMenu(table, popupActions(), "XmlGridView.Flat");
    JScrollPane scroll = ScrollPaneFactory.createScrollPane(table, true);
    scroll.setRowHeaderView(new RowNumberHeader(table));
    scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
      @Override
      public void componentResized(java.awt.event.ComponentEvent e) {
        fillValueColumn();
      }
    });
    // Dragging the Name edge also re-fills the Value column.
    table.getColumnModel().addColumnModelListener(new javax.swing.event.TableColumnModelListener() {
      @Override public void columnAdded(javax.swing.event.TableColumnModelEvent e) { }
      @Override public void columnRemoved(javax.swing.event.TableColumnModelEvent e) { }
      @Override public void columnMoved(javax.swing.event.TableColumnModelEvent e) { }
      @Override public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e) { }
      @Override
      public void columnMarginChanged(javax.swing.event.ChangeEvent e) {
        if (!fittingName && table.getTableHeader().getResizingColumn() != null
            && table.getTableHeader().getResizingColumn().getModelIndex() == NAME_COL) {
          javax.swing.SwingUtilities.invokeLater(FlatPanel.this::fillValueColumn);
        }
      }
    });
    add(scroll, BorderLayout.CENTER);

    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        showFind();
      }
    }.registerCustomShortcutSet(CommonShortcuts.getFind(), this, this);
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        if (!findBar.isVisible()) showFind();
        else step(1);
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0)), this, this);
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        if (!findBar.isVisible()) showFind();
        else step(-1);
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK)), this, this);
  }

  private void action(Runnable r, KeyboardShortcut... shortcuts) {
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        r.run();
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(shortcuts), table, this);
  }

  public JComponent getPreferredFocusedComponent() {
    return table;
  }

  // ---- Model ---------------------------------------------------------------------------------

  /** Rebuilds the model off the EDT (see {@link ModelLoader#refresh()}). */
  public void refresh() {
    loader.refresh();
  }

  /** Swaps in a newly built model. Malformed documents keep the last good model. */
  @TestOnly
  public void apply(@NotNull XmlDocumentModel built) {
    loader.apply(built);
  }

  private void modelChanged(@NotNull XmlDocumentModel built) {
    String selected = selectedKey();
    model = built;
    // Collapsed paths that no longer exist are dropped; the rest carry over.
    collapsed.removeIf(p -> built.byPath(p) == null);
    rebuildRows(selected);
    fitNameColumn();
    if (findBar.isVisible()) scheduleSearch(0);
  }

  /** Recomputes the visible rows (collapse state, "show only matches") and restores the selection by key. */
  private void rebuildRows(@Nullable String selectKey) {
    XmlDocumentModel m = model;
    List<FlatRow> rows;
    if (m == null) {
      rows = List.of();
    }
    else if (findBar.isVisible() && findBar.showOnlyMatches() && findState.active()) {
      Set<XNode> keep = new HashSet<>();
      for (XNode n : findState.matchedNodes()) {
        XNode a = n;
        while (a != null && keep.add(a)) a = a.parent();
      }
      rows = FlatRow.build(m, n -> false, keep::contains);
    }
    else {
      rows = FlatRow.build(m, n -> collapsed.contains(n.path()), null);
    }
    rowsModel.setRows(rows);
    fitNameColumn();
    if (selectKey != null) selectKey(selectKey);
  }

  /** Sizes the Name column to its visible content (sampled), unless the user has resized it. */
  private void fitNameColumn() {
    if (userSizedName) return;
    FontMetrics fm = table.getFontMetrics(table.getFont());
    FontMetrics bold = table.getFontMetrics(table.getFont().deriveFont(java.awt.Font.BOLD));
    int indent = indentWidth();
    int icon = AllIcons.General.ArrowDown.getIconWidth();
    int w = bold.stringWidth("Name");
    List<FlatRow> rows = rowsModel.rows;
    for (int i = 0; i < Math.min(rows.size(), WIDTH_SAMPLE); i++) {
      FlatRow r = rows.get(i);
      // Current find hits are bold, so measure with the bold font to be safe.
      w = Math.max(w, r.depth() * indent + icon + Math.max(fm.stringWidth(r.name()), bold.stringWidth(r.name())));
    }
    // Icon-text gap, cell insets and a little air.
    w = Math.min(w + JBUI.scale(28), Math.max(JBUI.scale(160), (int)(getWidth() * 0.6)));
    javax.swing.table.TableColumn col = table.getColumnModel().getColumn(table.convertColumnIndexToView(NAME_COL));
    fittingName = true;
    try {
      col.setPreferredWidth(w);
      col.setWidth(w);
    }
    finally {
      fittingName = false;
    }
    fillValueColumn();
  }

  /** The Value column takes the remaining viewport width (at least a readable minimum). */
  private void fillValueColumn() {
    javax.swing.table.TableColumn name = table.getColumnModel().getColumn(table.convertColumnIndexToView(NAME_COL));
    javax.swing.table.TableColumn value = table.getColumnModel().getColumn(table.convertColumnIndexToView(VALUE_COL));
    java.awt.Container viewport = table.getParent();
    int available = viewport != null ? viewport.getWidth() : getWidth();
    int w = Math.max(JBUI.scale(240), available - name.getWidth());
    value.setPreferredWidth(w);
    value.setWidth(w);
  }

  private static int indentWidth() {
    return JBUI.scale(16);
  }

  // ---- Rows, selection, expand/collapse ------------------------------------------------------

  private int leadRow() {
    int row = table.getSelectionModel().getLeadSelectionIndex();
    return row >= 0 && row < table.getRowCount() ? row : -1;
  }

  private @Nullable String selectedKey() {
    int row = leadRow();
    return row < 0 ? null : rowsModel.rows.get(row).key();
  }

  private boolean selectKey(String key) {
    List<FlatRow> rows = rowsModel.rows;
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).key().equals(key)) {
        selectRow(i);
        return true;
      }
    }
    return false;
  }

  private void selectRow(int row) {
    table.getSelectionModel().setSelectionInterval(row, row);
    table.getColumnModel().getSelectionModel().setSelectionInterval(0, table.getColumnCount() - 1);
    Rectangle r = table.getCellRect(row, 0, true);
    // Center the row like the editor does for navigation.
    Rectangle visible = table.getVisibleRect();
    r.y = Math.max(0, r.y - Math.max(0, (visible.height - r.height) / 2));
    r.height = visible.height;
    table.scrollRectToVisible(r);
  }

  private boolean isExpanded(FlatRow r) {
    return r.expandable() && !collapsed.contains(r.node().path());
  }

  private boolean inTwisty(int row, int x) {
    FlatRow r = rowsModel.rows.get(row);
    if (!r.expandable() || findState.active() && findBar.showOnlyMatches() && findBar.isVisible()) return false;
    Rectangle cell = table.getCellRect(row, NAME_COL, false);
    int start = cell.x + r.depth() * indentWidth();
    return x >= start && x < start + AllIcons.General.ArrowDown.getIconWidth() + JBUI.scale(8);
  }

  private void toggle(int row) {
    FlatRow r = rowsModel.rows.get(row);
    if (!r.expandable()) return;
    String path = r.node().path();
    if (!collapsed.remove(path)) collapsed.add(path);
    rebuildRows(r.key());
  }

  private int leadColumn() {
    int col = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
    return col < 0 ? NAME_COL : table.convertColumnIndexToModel(col);
  }

  /** Left: from Value to Name; in Name, collapse the row or go to its parent (like a tree). */
  private void left() {
    int row = leadRow();
    if (row < 0) return;
    if (leadColumn() == VALUE_COL) table.changeSelection(row, table.convertColumnIndexToView(NAME_COL), false, false);
    else collapseOrParent();
  }

  /** Right: in Name, expand a collapsed row first; otherwise move to the Value cell. */
  private void right() {
    int row = leadRow();
    if (row < 0) return;
    FlatRow r = rowsModel.rows.get(row);
    if (leadColumn() == NAME_COL && r.expandable() && !isExpanded(r)) toggle(row);
    else table.changeSelection(row, table.convertColumnIndexToView(VALUE_COL), false, false);
  }

  private void collapseOrParent() {
    int row = leadRow();
    if (row < 0) return;
    FlatRow r = rowsModel.rows.get(row);
    if (isExpanded(r)) {
      toggle(row);
    }
    else {
      XNode parent = r.kind() == FlatRow.Kind.ATTR ? r.node() : r.node().parent();
      if (parent != null) selectKey(parent.path());
    }
  }

  private void expandOrChild() {
    int row = leadRow();
    if (row < 0) return;
    FlatRow r = rowsModel.rows.get(row);
    if (r.expandable() && !isExpanded(r)) toggle(row);
    else if (r.expandable() && row + 1 < rowsModel.rows.size()) selectRow(row + 1);
  }

  private void navigateRow(int row) {
    navigator.accept(rowsModel.rows.get(row).offset());
  }

  // ---- Copy ----------------------------------------------------------------------------------

  /**
   * The selected cells as TSV, like copying a spreadsheet range: only the selected columns,
   * names indented two spaces per depth, values on one line.
   */
  String selectionAsTsv() {
    boolean name = false;
    boolean value = false;
    for (int col : table.getSelectedColumns()) {
      int m = table.convertColumnIndexToModel(col);
      if (m == NAME_COL) name = true;
      if (m == VALUE_COL) value = true;
    }
    if (!name && !value) return "";
    StringBuilder sb = new StringBuilder();
    for (int row : table.getSelectedRows()) {
      FlatRow r = rowsModel.rows.get(row);
      if (!sb.isEmpty()) sb.append('\n');
      if (name) sb.append("  ".repeat(r.depth())).append(r.name());
      if (name && value) sb.append('\t');
      if (value) sb.append(oneLine(r.value()));
    }
    return sb.toString();
  }

  private void copy() {
    String tsv = selectionAsTsv();
    if (!tsv.isEmpty()) CopyPasteManager.getInstance().setContents(new StringSelection(tsv));
  }

  private final CopyProvider copyProvider = new CopyProvider() {
    @Override
    public void performCopy(@NotNull DataContext dataContext) {
      copy();
    }

    @Override
    public boolean isCopyEnabled(@NotNull DataContext dataContext) {
      return table.getSelectedRowCount() > 0;
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
        copy();
      }
    });
    g.add(new DumbAwareAction("Expand All", null, AllIcons.Actions.Expandall) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        collapsed.clear();
        rebuildRows(selectedKey());
      }
    });
    g.add(new DumbAwareAction("Collapse All", null, AllIcons.Actions.Collapseall) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        XmlDocumentModel m = model;
        if (m == null) return;
        for (XNode n : m.elements()) if (!n.children().isEmpty() || !n.attrs().isEmpty()) collapsed.add(n.path());
        if (m.root() != null) collapsed.remove(m.root().path());
        rebuildRows(null);
      }
    });
    g.add(inspectAction);
    g.add(new DumbAwareAction("Go to Source", null, AllIcons.Actions.EditSource) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        int row = leadRow();
        if (row >= 0) navigateRow(row);
      }
    });
    return g;
  }

  /** Opens the inspector when the click hits a value cell's trailing icon. */
  private boolean clickedInspectIcon(MouseEvent e) {
    int row = table.rowAtPoint(e.getPoint());
    int viewCol = table.columnAtPoint(e.getPoint());
    if (row < 0 || viewCol < 0 || e.isShiftDown() || e.isControlDown() || e.isMetaDown()) return false;
    if (table.convertColumnIndexToModel(viewCol) != VALUE_COL) return false;
    FlatRow r = rowsModel.rows.get(row);
    if (!InspectAction.worthInspecting(r.value())) return false;
    Rectangle cell = table.getCellRect(row, viewCol, false);
    if (!InspectAction.hitsCellIcon(e.getX(), cell.x, cell.width)) return false;
    table.changeSelection(row, viewCol, false, false);
    inspectorOpener.accept(InspectTarget.ofFlatRow(r));
    e.consume();
    return true;
  }

  /** Inspector target for the lead row: the element's text or the attribute's value. */
  @Nullable InspectTarget inspectTarget() {
    int row = leadRow();
    return row < 0 ? null : InspectTarget.ofFlatRow(rowsModel.rows.get(row));
  }

  @TestOnly
  public @Nullable InspectTarget inspectTargetForTest() {
    return inspectTarget();
  }

  private static String oneLine(String s) {
    return s.replaceAll("\\s+", " ").trim();
  }

  // ---- Find ----------------------------------------------------------------------------------

  public void showFind() {
    findBar.setVisible(true);
    revalidate();
    findBar.focusField();
    scheduleSearch(0);
  }

  private void hideFind() {
    findBar.setVisible(false);
    searchAlarm.cancelAllRequests();
    setMatches(List.of(), false, FindState.NONE);
    findBar.setError("");
    findBar.setCounter("");
    revalidate();
    table.requestFocusInWindow();
  }

  private void scheduleSearch(int delay) {
    searchAlarm.cancelAllRequests();
    searchAlarm.addRequest(this::runSearch, delay);
  }

  private void runSearch() {
    XmlDocumentModel m = model;
    findBar.setError("");
    if (!findBar.isVisible() || m == null) return;
    String q = findBar.query();
    if (findBar.mode() == FindBar.Mode.XPATH) {
      if (q.isBlank()) {
        setMatches(List.of(), false, FindState.NONE);
        return;
      }
      ReadAction.nonBlocking(() -> XPathEngine.evaluate(m, q))
        .coalesceBy(this, findBar)
        .expireWith(this)
        .finishOnUiThread(ModalityState.any(), this::applyXPath)
        .submit(AppExecutorUtil.getAppExecutorService());
      return;
    }
    Matcher matcher;
    try {
      matcher = Matcher.create(q, findBar.options());
    }
    catch (InvalidQueryException e) {
      findBar.setError(e.getMessage());
      setMatches(List.of(), false, FindState.NONE);
      return;
    }
    if (matcher == null) {
      setMatches(List.of(), false, FindState.NONE);
      return;
    }
    SearchTargets targets = findBar.targets();
    ReadAction.nonBlocking(() -> SearchEngine.searchDocument(m, matcher, targets, SearchEngine.DEFAULT_LIMIT, () -> {
        ProgressManager.checkCanceled();
        return false;
      }))
      .coalesceBy(this, findBar)
      .expireWith(this)
      .finishOnUiThread(ModalityState.any(), r -> {
        Set<XNode> nodes = new LinkedHashSet<>();
        List<String> keys = new ArrayList<>(r.matches().size());
        for (DocMatch d : r.matches()) {
          nodes.add(d.node());
          keys.add(d.attrIndex() >= 0 ? d.node().path() + "@" + d.node().attrs().get(d.attrIndex()).name() : d.node().path());
        }
        setMatches(keys, r.truncated(), new FindState(matcher, targets, nodes, Set.of(), null, null));
      })
      .submit(AppExecutorUtil.getAppExecutorService());
  }

  private void applyXPath(XPathEngine.Result r) {
    if (r.error() != null) {
      findBar.setError(r.error());
      setMatches(List.of(), false, FindState.NONE);
      return;
    }
    if (r.scalar() != null) {
      setMatches(List.of(), false, FindState.NONE);
      Object v = r.scalar();
      findBar.setCounter("= " + (v instanceof Double d && d == Math.rint(d) && !d.isInfinite() ? Long.toString(d.longValue()) : v));
      return;
    }
    Set<XNode> nodes = new LinkedHashSet<>();
    List<String> keys = new ArrayList<>();
    for (XPathEngine.Item i : r.items()) {
      nodes.add(i.node());
      keys.add(i.attrName() != null ? i.node().path() + "@" + i.attrName() : i.node().path());
    }
    setMatches(keys, false, new FindState(null, SearchTargets.ALL, nodes, Set.of(), null, null));
  }

  private void setMatches(List<String> keys, boolean truncated, FindState state) {
    matchKeys = keys;
    matchesTruncated = truncated;
    current = -1;
    findState = state;
    updateCounter();
    if (findBar.showOnlyMatches()) rebuildRows(selectedKey());
    table.repaint();
  }

  private void updateCounter() {
    if (matchKeys.isEmpty()) {
      findBar.setCounter(findBar.query().isEmpty() ? "" : "0 results");
      return;
    }
    String total = matchKeys.size() + (matchesTruncated ? "+" : "");
    findBar.setCounter(current < 0 ? total + " results" : (current + 1) + " of " + total);
  }

  private void step(int delta) {
    if (matchKeys.isEmpty()) return;
    int n = matchKeys.size();
    goTo(current < 0 ? (delta > 0 ? 0 : n - 1) : ((current + delta) % n + n) % n);
  }

  private void goTo(int index) {
    XmlDocumentModel m = model;
    if (m == null || index < 0 || index >= matchKeys.size()) return;
    current = index;
    String key = matchKeys.get(index);
    int at = key.indexOf('@');
    XNode node = m.byPath(at < 0 ? key : key.substring(0, at));
    if (node != null) {
      // Reveal: expand the ancestors, and the element itself for an attribute hit.
      for (XNode a = at < 0 ? node.parent() : node; a != null; a = a.parent()) collapsed.remove(a.path());
      findState = findState.withCurrent(node, null);
      rebuildRows(null);
      selectKey(key);
    }
    updateCounter();
    table.repaint();
  }

  // ---- Rendering -----------------------------------------------------------------------------

  private final class Renderer extends ColoredTableCellRenderer {
    @Override
    protected void customizeCellRenderer(@NotNull JTable tbl, @Nullable Object value, boolean selected, boolean hasFocus,
                                         int row, int column) {
      FlatRow r = rowsModel.rows.get(row);
      FindState fs = findState;
      boolean isCurrent = fs.currentNode() == r.node() && currentKeyIs(r.key());
      boolean attr = r.kind() == FlatRow.Kind.ATTR;
      Matcher m = fs.matcher();
      if (column == NAME_COL) {
        setIconOnTheRight(false);
        setIpad(JBUI.insetsLeft(r.depth() * indentWidth()));
        Icon icon = AllIcons.General.ArrowDown;
        boolean filtering = findBar.isVisible() && findBar.showOnlyMatches() && fs.active();
        if (r.expandable() && !filtering) setIcon(isExpanded(r) ? AllIcons.General.ArrowDown : AllIcons.General.ArrowRight);
        else setIcon(EmptyIcon.create(icon));
        int style = isCurrent ? SimpleTextAttributes.STYLE_BOLD : SimpleTextAttributes.STYLE_PLAIN;
        SimpleTextAttributes base = selected ? new SimpleTextAttributes(style, null)
                                             : attr ? XmlColors.attrNameAttrs(style) : XmlColors.tagAttrs(style);
        boolean highlight = attr ? fs.targets().attrNames() : fs.targets().names();
        if (attr) {
          append("@", base);
          Highlight.append(this, r.attr().name(), base, highlight ? m : null);
        }
        else {
          Highlight.append(this, r.node().tag(), base, highlight ? m : null);
        }
        setToolTipText(r.node().path());
      }
      else {
        setIpad(JBUI.emptyInsets());
        int style = isCurrent ? SimpleTextAttributes.STYLE_BOLD : SimpleTextAttributes.STYLE_PLAIN;
        SimpleTextAttributes base = attr && !selected ? XmlColors.attrValueAttrs(style) : new SimpleTextAttributes(style, null);
        String raw = r.value();
        String shown = oneLine(raw);
        boolean highlight = attr ? fs.targets().attrValues() : fs.targets().text();
        Highlight.append(this, shown, base, highlight ? m : null);
        boolean inspect = InspectAction.worthInspecting(raw);
        setIcon(inspect ? InspectAction.CELL_ICON : null);
        setIconOnTheRight(true);
        setToolTipText(inspect ? "Double-click, Shift+Enter or click the icon to inspect the full value" : null);
      }
      if (!selected) setBackground(XmlColors.stripe(row, tbl.getBackground()));
    }

    private boolean currentKeyIs(String key) {
      return current >= 0 && current < matchKeys.size() && matchKeys.get(current).equals(key);
    }
  }

  private static final class RowsModel extends AbstractTableModel {
    private List<FlatRow> rows = List.of();

    void setRows(List<FlatRow> rows) {
      this.rows = rows;
      fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return 2;
    }

    @Override
    public String getColumnName(int column) {
      return column == NAME_COL ? "Name" : "Value";
    }

    @Override
    public Object getValueAt(int row, int column) {
      FlatRow r = rows.get(row);
      return column == NAME_COL ? r.name() : r.value();
    }
  }

  // ---- Test hooks ----------------------------------------------------------------------------

  @TestOnly
  public @Nullable XmlDocumentModel model() {
    return model;
  }

  @TestOnly
  public List<FlatRow> visibleRows() {
    return rowsModel.rows;
  }

  @TestOnly
  public void setInspectorOpenerForTest(java.util.function.Consumer<InspectTarget> opener) {
    inspectorOpener = opener;
  }

  /** Sends a real double-click to the cell (layout is forced so cell geometry is valid). */
  @TestOnly
  public void doubleClickForTest(int row, int modelCol) {
    table.setSize(JBUI.scale(900), JBUI.scale(600));
    table.doLayout();
    int viewCol = table.convertColumnIndexToView(modelCol);
    java.awt.Rectangle r = table.getCellRect(row, viewCol, false);
    int x = r.x + r.width / 2;
    int y = r.y + r.height / 2;
    for (int n = 1; n <= 2; n++) {
      long t = System.currentTimeMillis();
      table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_PRESSED, t, MouseEvent.BUTTON1_DOWN_MASK, x, y, n, false, MouseEvent.BUTTON1));
      table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED, t, 0, x, y, n, false, MouseEvent.BUTTON1));
      table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_CLICKED, t, 0, x, y, n, false, MouseEvent.BUTTON1));
    }
  }

  @TestOnly
  public int nameColumnWidthForTest() {
    return table.getColumnModel().getColumn(table.convertColumnIndexToView(NAME_COL)).getWidth();
  }

  @TestOnly
  public int nameContentWidthForTest() {
    java.awt.FontMetrics fm = table.getFontMetrics(table.getFont());
    int w = 0;
    for (FlatRow r : rowsModel.rows) w = Math.max(w, r.depth() * indentWidth() + AllIcons.General.ArrowDown.getIconWidth() + fm.stringWidth(r.name()));
    return w;
  }

  @TestOnly
  public void selectRowsForTest(int from, int to) {
    selectCellsForTest(from, to, NAME_COL, VALUE_COL);
  }

  @TestOnly
  public void selectCellsForTest(int fromRow, int toRow, int fromCol, int toCol) {
    table.getSelectionModel().setSelectionInterval(fromRow, toRow);
    table.getColumnModel().getSelectionModel().setSelectionInterval(fromCol, toCol);
  }

  @TestOnly
  public String copyForTest() {
    return selectionAsTsv();
  }

  @TestOnly
  public void navigateRowForTest(int row) {
    navigateRow(row);
  }

  @TestOnly
  public void toggleForTest(int row) {
    toggle(row);
  }

  @TestOnly
  public boolean isShowingErrorBanner() {
    return loader.isShowingErrorBanner();
  }

  @Override
  public void dispose() {
  }
}
