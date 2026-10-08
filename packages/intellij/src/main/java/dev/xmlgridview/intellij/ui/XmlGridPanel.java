package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.breadcrumbs.Breadcrumbs;
import com.intellij.ui.components.breadcrumbs.Crumb;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import dev.xmlgridview.intellij.model.ColumnFilter;
import dev.xmlgridview.intellij.model.DocMatch;
import dev.xmlgridview.intellij.model.GridMatch;
import dev.xmlgridview.intellij.model.GridTable;
import dev.xmlgridview.intellij.model.GroupInfo;
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
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SortOrder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The XML Grid View: tree on the left, grid on the right, find bar and
 * banners on top. The model is rebuilt off the EDT after document changes
 * (debounced) and swapped in on the EDT, preserving view state by path.
 */
public final class XmlGridPanel extends JPanel implements Disposable {
  public static final long LARGE_FILE_CHARS = ModelLoader.LARGE_FILE_CHARS;
  static final int DEBOUNCE_MS = ModelLoader.DEBOUNCE_MS;

  private final IntConsumer navigator;
  private final Alarm searchAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
  private final ModelLoader loader;

  private final FindBar findBar;
  private final XmlTreeView tree;
  private final GridView grid;
  private final Breadcrumbs crumbs = new Breadcrumbs();
  private final ComboBox<GroupInfo> groupCombo = new ComboBox<>();
  private final JBLabel rowsLabel = new JBLabel();
  private final LinkLabel<Void> clearFilters = new LinkLabel<>("Clear filters", null);
  private final DefaultListModel<ResultEntry> resultsModel = new DefaultListModel<>();
  private final JBList<ResultEntry> resultsList = new JBList<>(resultsModel);
  private final JComponent resultsPanel;

  private @Nullable XmlDocumentModel model;
  private @Nullable XNode gridNode;
  private @Nullable String gridGroup;
  private FindState findState = FindState.NONE;
  private List<Object> matches = List.of();
  private boolean matchesTruncated;
  private int current = -1;
  private boolean updatingGroups;
  private int modelVersion;

  public XmlGridPanel(@NotNull Project project, @NotNull Document document, @NotNull IntConsumer navigator,
                      @NotNull Disposable parent) {
    super(new BorderLayout());
    this.navigator = navigator;
    Disposer.register(parent, this);
    loader = new ModelLoader(project, document, this, navigator, (previous, built) -> modelChanged(built), this);

    tree = new XmlTreeView(this, () -> findState, this::onTreeSelect, n -> navigator.accept(n.start()));
    grid = new GridView(this, () -> findState, new GridView.Listener() {
      @Override
      public void drillDown(@NotNull XNode row, @NotNull String tag) {
        showGrid(row, tag, Map.of(), Map.of());
        tree.select(row.path(), true);
      }

      @Override
      public void navigate(int offset) {
        navigator.accept(offset);
      }

      @Override
      public void filtersChanged() {
        updateRowsLabel();
        if (findBar.isVisible() && findBar.scope() == FindBar.Scope.GRID) scheduleSearch(0);
      }
    });
    tree.setInspector(t -> ValueInspector.show(project, t));
    grid.setInspector(t -> ValueInspector.show(project, t));
    findBar = new FindBar(this, new FindBar.Listener() {
      @Override
      public void queryChanged() {
        scheduleSearch(DEBOUNCE_MS);
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
        applyTreeFilter();
      }

      @Override
      public void toggleResults() {
        resultsPanel.setVisible(!resultsPanel.isVisible());
        updateResultsLabel();
        revalidate();
      }
    });
    findBar.setVisible(false);

    JPanel north = new JPanel();
    north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
    north.add(loader.banners());
    north.add(findBar);
    add(north, BorderLayout.NORTH);

    groupCombo.setRenderer(new TextListRenderer<GroupInfo>(g -> g.tag() + " (" + g.count() + ")"));
    groupCombo.addActionListener(e -> {
      if (updatingGroups || gridNode == null) return;
      GroupInfo g = (GroupInfo)groupCombo.getSelectedItem();
      if (g != null && !g.tag().equals(gridGroup)) showGrid(gridNode, g.tag(), Map.of(), Map.of());
    });
    crumbs.onSelect((crumb, event) -> {
      if (crumb instanceof NodeCrumb nc) tree.select(nc.node.path(), false);
    });
    clearFilters.setListener((l, d) -> grid.clearFilters(), null);
    rowsLabel.setForeground(UIUtil.getContextHelpForeground());

    JPanel gridHeader = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    gridHeader.setBorder(JBUI.Borders.empty(2, 4));
    gridHeader.add(crumbs, BorderLayout.CENTER);
    JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));
    headerRight.add(clearFilters);
    headerRight.add(rowsLabel);
    headerRight.add(groupCombo);
    gridHeader.add(headerRight, BorderLayout.EAST);

    JPanel right = new JPanel(new BorderLayout());
    right.add(gridHeader, BorderLayout.NORTH);
    right.add(grid, BorderLayout.CENTER);

    OnePixelSplitter splitter = new OnePixelSplitter(false, "XmlGridView.Splitter", 0.3f);
    splitter.setFirstComponent(tree);
    splitter.setSecondComponent(right);

    resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    resultsList.setCellRenderer(new ResultRenderer());
    resultsList.getEmptyText().setText("No results");
    resultsList.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        int i = resultsList.locationToIndex(e.getPoint());
        if (i >= 0) goTo(i);
      }
    });
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        int i = resultsList.getSelectedIndex();
        if (i >= 0) goTo(i);
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)), resultsList, this);
    resultsPanel = ScrollPaneFactory.createScrollPane(resultsList, true);
    resultsPanel.setBorder(JBUI.Borders.customLineTop(com.intellij.ui.JBColor.border()));
    resultsPanel.setPreferredSize(JBUI.size(200, 160));
    resultsPanel.setVisible(false);

    JPanel center = new JPanel(new BorderLayout());
    center.add(splitter, BorderLayout.CENTER);
    center.add(resultsPanel, BorderLayout.SOUTH);
    add(center, BorderLayout.CENTER);

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

    updateRowsLabel();
  }

  public JComponent getPreferredFocusedComponent() {
    return tree.focusComponent();
  }

  // ---- Model refresh -------------------------------------------------------------------------

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
    ViewState state = model == null ? null : captureState();
    model = built;
    modelVersion++;
    int version = modelVersion;
    tree.setModel(built).whenComplete((x, err) -> ApplicationManager.getApplication().invokeLater(() -> {
      if (version != modelVersion) return;
      if (state == null) {
        tree.expandRoot();
        if (built.root() != null) tree.select(built.root().path(), true);
      }
      else {
        tree.expandPaths(state.expanded).onProcessed(y -> {
          if (state.selected != null && built.byPath(state.selected) != null) tree.select(state.selected, true);
        });
      }
      if (findBar.isVisible()) scheduleSearch(0);
    }, ModalityState.any()));

    XNode target = state == null ? null : built.byPath(state.gridPath);
    if (target == null) target = built.root();
    if (target != null) {
      showGrid(target, state == null ? null : state.group,
               state == null ? Map.of() : state.sort, state == null ? Map.of() : state.filters);
      if (state != null && state.selectedRow != null) {
        XNode row = built.byPath(state.selectedRow);
        if (row != null) grid.selectRow(row);
      }
    }
    else {
      gridNode = null;
      grid.setTable(null, Map.of(), Map.of());
      crumbs.setCrumbs(List.of());
    }
  }

  private record ViewState(List<String> expanded, @Nullable String selected, @Nullable String gridPath,
                           @Nullable String group, Map<String, SortOrder> sort, Map<String, ColumnFilter> filters,
                           @Nullable String selectedRow) {
  }

  private ViewState captureState() {
    XNode sel = tree.selected();
    String selectedRow = null;
    GridTable t = grid.gridTable();
    int viewRow = grid.table().getSelectionModel().getLeadSelectionIndex();
    if (t != null && viewRow >= 0 && viewRow < grid.table().getRowCount()) {
      selectedRow = t.rows().get(grid.table().convertRowIndexToModel(viewRow)).path();
    }
    return new ViewState(tree.expandedPaths(), sel == null ? null : sel.path(), gridNode == null ? null : gridNode.path(),
                         gridGroup, grid.sortKeys(), grid.filters(), selectedRow);
  }

  @TestOnly
  public boolean isShowingLargeFileNotice() {
    return loader.isShowingLargeFileNotice();
  }

  @TestOnly
  public boolean isShowingErrorBanner() {
    return loader.isShowingErrorBanner();
  }

  // ---- Grid ----------------------------------------------------------------------------------

  private void onTreeSelect(XNode node) {
    showGrid(node, null, Map.of(), Map.of());
  }

  private void showGrid(@NotNull XNode node, @Nullable String group, Map<String, SortOrder> sort,
                        Map<String, ColumnFilter> filters) {
    XmlDocumentModel m = model;
    if (m == null) return;
    GridTable t = m.table(node, group);
    gridNode = node;
    gridGroup = t.group();
    grid.setTable(t, sort, filters);

    updatingGroups = true;
    try {
      groupCombo.removeAllItems();
      for (GroupInfo g : t.groups()) groupCombo.addItem(g);
      for (int i = 0; i < groupCombo.getItemCount(); i++) {
        if (groupCombo.getItemAt(i).tag().equals(t.group())) groupCombo.setSelectedIndex(i);
      }
      groupCombo.setVisible(t.groups().size() > 1);
    }
    finally {
      updatingGroups = false;
    }

    List<Crumb> list = new ArrayList<>();
    for (XNode n = node; n != null; n = n.parent()) list.add(0, new NodeCrumb(n));
    if (t.group() != null) list.add(new GroupCrumb(t.group()));
    crumbs.setCrumbs(list);
    updateRowsLabel();
    if (findBar.isVisible() && findBar.scope() == FindBar.Scope.GRID) scheduleSearch(0);
  }

  private void updateRowsLabel() {
    GridTable t = grid.gridTable();
    int total = t == null ? 0 : t.rowCount();
    int shown = grid.visibleRowCount();
    rowsLabel.setText(shown == total ? total + (total == 1 ? " row" : " rows") : shown + " of " + total + " rows");
    clearFilters.setVisible(!grid.filter().isEmpty());
  }

  private static final class NodeCrumb implements Crumb {
    final XNode node;

    NodeCrumb(XNode node) {
      this.node = node;
    }

    @Override
    public String getText() {
      XNode p = node.parent();
      if (p == null) return node.tag();
      int same = 0;
      int pos = 0;
      for (XNode s : p.children()) {
        if (s.tag().equals(node.tag())) {
          same++;
          if (s == node) pos = same;
        }
      }
      return same > 1 ? node.tag() + "[" + pos + "]" : node.tag();
    }

    @Override
    public String getTooltip() {
      return node.path();
    }
  }

  private record GroupCrumb(String tag) implements Crumb {
    @Override
    public String getText() {
      return "→ " + tag;
    }

    @Override
    public String getTooltip() {
      return "Rows: <" + tag + "> children";
    }
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
    resultsPanel.setVisible(false);
    searchAlarm.cancelAllRequests();
    setMatches(List.of(), false, FindState.NONE);
    findBar.setError("");
    findBar.setCounter("");
    tree.setFilter(null);
    revalidate();
    grid.focusComponent().requestFocusInWindow();
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
    if (findBar.scope() == FindBar.Scope.DOCUMENT) {
      ReadAction.nonBlocking(() -> SearchEngine.searchDocument(m, matcher, targets, SearchEngine.DEFAULT_LIMIT, () -> {
          ProgressManager.checkCanceled();
          return false;
        }))
        .coalesceBy(this, findBar)
        .expireWith(this)
        .finishOnUiThread(ModalityState.any(), r -> {
          Set<XNode> nodes = new LinkedHashSet<>();
          for (DocMatch d : r.matches()) nodes.add(d.node());
          setMatches(new ArrayList<>(r.matches()), r.truncated(),
                     new FindState(matcher, targets, nodes, Set.of(), null, null));
        })
        .submit(AppExecutorUtil.getAppExecutorService());
    }
    else {
      GridTable t = grid.gridTable();
      if (t == null) {
        setMatches(List.of(), false, FindState.NONE);
        return;
      }
      ReadAction.nonBlocking(() -> SearchEngine.searchGrid(t, matcher, targets))
        .coalesceBy(this, findBar)
        .expireWith(this)
        .finishOnUiThread(ModalityState.any(), cells -> {
          if (grid.gridTable() != t) return;
          List<GridMatch> ordered = grid.inViewOrder(cells);
          Set<XNode> rows = new LinkedHashSet<>();
          for (GridMatch c : ordered) rows.add(t.rows().get(c.row()));
          setMatches(new ArrayList<>(ordered), false, new FindState(matcher, targets, rows, new HashSet<>(ordered), null, null));
        })
        .submit(AppExecutorUtil.getAppExecutorService());
    }
  }

  private void applyXPath(XPathEngine.Result r) {
    if (r.error() != null) {
      findBar.setError(r.error());
      setMatches(List.of(), false, FindState.NONE);
      return;
    }
    if (r.scalar() != null) {
      setMatches(List.of(), false, FindState.NONE);
      findBar.setCounter("= " + formatScalar(r.scalar()));
      return;
    }
    Set<XNode> nodes = new LinkedHashSet<>();
    for (XPathEngine.Item i : r.items()) nodes.add(i.node());
    setMatches(new ArrayList<>(r.items()), false, new FindState(null, SearchTargets.ALL, nodes, Set.of(), null, null));
  }

  private static String formatScalar(Object v) {
    if (v instanceof Double d && d == Math.rint(d) && !d.isInfinite()) return Long.toString(d.longValue());
    return String.valueOf(v);
  }

  private void setMatches(List<Object> list, boolean truncated, FindState state) {
    matches = list;
    matchesTruncated = truncated;
    current = -1;
    findState = state;
    resultsModel.clear();
    if (list.size() <= 10_000) {
      for (Object o : list) resultsModel.addElement(entry(o));
    }
    updateCounter();
    updateResultsLabel();
    applyTreeFilter();
    tree.repaintTree();
    grid.table().repaint();
  }

  private void updateCounter() {
    if (matches.isEmpty()) {
      findBar.setCounter(findBar.query().isEmpty() ? "" : "0 results");
      return;
    }
    String total = matches.size() + (matchesTruncated ? "+" : "");
    findBar.setCounter(current < 0 ? total + " results" : (current + 1) + " of " + total);
  }

  private void updateResultsLabel() {
    findBar.setResultsLabel((resultsPanel.isVisible() ? "Hide results" : "Show results") + " (" + matches.size() + ")");
  }

  private void applyTreeFilter() {
    if (findBar.isVisible() && findBar.showOnlyMatches() && findState.active()) {
      Collection<XNode> nodes = findState.matchedNodes();
      tree.setFilter(nodes).whenComplete((x, err) -> ApplicationManager.getApplication().invokeLater(
        () -> tree.expandPaths(nodes.stream().limit(500).map(n -> n.parent() == null ? n.path() : n.parent().path()).toList()),
        ModalityState.any()));
    }
    else {
      tree.setFilter(null);
    }
  }

  private void step(int delta) {
    if (matches.isEmpty()) return;
    int n = matches.size();
    goTo(current < 0 ? (delta > 0 ? 0 : n - 1) : ((current + delta) % n + n) % n);
  }

  private void goTo(int index) {
    if (index < 0 || index >= matches.size()) return;
    current = index;
    Object o = matches.get(index);
    if (o instanceof GridMatch g) {
      findState = findState.withCurrent(null, g);
      grid.selectCell(g);
    }
    else {
      XNode node = o instanceof DocMatch d ? d.node() : ((XPathEngine.Item)o).node();
      findState = findState.withCurrent(node, null);
      revealNode(node);
    }
    if (index < resultsModel.size()) {
      resultsList.setSelectedIndex(index);
      resultsList.ensureIndexIsVisible(index);
    }
    updateCounter();
    tree.repaintTree();
    grid.table().repaint();
  }

  /** Shows the node as a row of its parent's grid and selects it in the tree. */
  private void revealNode(XNode node) {
    XNode parent = node.parent();
    if (parent != null) {
      if (parent != gridNode || !node.tag().equals(gridGroup)) showGrid(parent, node.tag(), Map.of(), Map.of());
      grid.selectRow(node);
    }
    else if (gridNode != node) {
      showGrid(node, null, Map.of(), Map.of());
    }
    tree.select(node.path(), true);
  }

  private record ResultEntry(Object match) {
  }

  private static ResultEntry entry(Object o) {
    return new ResultEntry(o);
  }

  private final class ResultRenderer extends ColoredListCellRenderer<ResultEntry> {
    @Override
    protected void customizeCellRenderer(@NotNull JList<? extends ResultEntry> list, ResultEntry value, int index,
                                         boolean selected, boolean hasFocus) {
      Object o = value.match();
      Matcher m = findState.matcher();
      if (o instanceof DocMatch d) {
        append(d.node().tag(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        append("  " + d.target().id(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        if (d.attrIndex() >= 0) append(" @" + d.node().attrs().get(d.attrIndex()).name(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
        Highlight.append(this, StringUtil.first(d.field().replace('\n', ' '), 200, true), SimpleTextAttributes.REGULAR_ATTRIBUTES, m);
        append("   " + d.node().path(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      }
      else if (o instanceof XPathEngine.Item i) {
        append(i.node().tag(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        if (i.attrName() != null) append(" @" + i.attrName(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        if (i.value() != null) append("  " + i.value(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
        append("   " + i.node().path(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      }
      else if (o instanceof GridMatch g) {
        GridTable t = grid.gridTable();
        if (t == null || g.row() >= t.rowCount()) return;
        append("Row " + (g.row() + 1), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        append("  " + t.columns().get(g.col()).label() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        Highlight.append(this, t.cellText(g.row(), g.col()), SimpleTextAttributes.REGULAR_ATTRIBUTES, m);
      }
    }
  }

  // ---- Test hooks ----------------------------------------------------------------------------

  @TestOnly
  public @Nullable XmlDocumentModel model() {
    return model;
  }

  @TestOnly
  public void setInspectorForTest(@NotNull java.util.function.Consumer<dev.xmlgridview.intellij.model.InspectTarget> opener) {
    tree.setInspector(opener);
    grid.setInspector(opener);
  }

  @TestOnly
  public @Nullable java.util.Set<String> treeFilterForTest(String query) {
    return tree.applyBoxFilterForTest(query);
  }

  /** Sends a real double-click to a grid cell (view coordinates). */
  @TestOnly
  public void doubleClickGridForTest(int viewRow, int viewCol) {
    com.intellij.ui.table.JBTable t = grid.table();
    t.setSize(com.intellij.util.ui.JBUI.scale(900), com.intellij.util.ui.JBUI.scale(600));
    t.doLayout();
    java.awt.Rectangle r = t.getCellRect(viewRow, viewCol, false);
    int x = r.x + r.width / 2;
    int y = r.y + r.height / 2;
    for (int n = 1; n <= 2; n++) {
      long time = System.currentTimeMillis();
      t.dispatchEvent(new java.awt.event.MouseEvent(t, java.awt.event.MouseEvent.MOUSE_PRESSED, time, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, x, y, n, false, java.awt.event.MouseEvent.BUTTON1));
      t.dispatchEvent(new java.awt.event.MouseEvent(t, java.awt.event.MouseEvent.MOUSE_RELEASED, time, 0, x, y, n, false, java.awt.event.MouseEvent.BUTTON1));
      t.dispatchEvent(new java.awt.event.MouseEvent(t, java.awt.event.MouseEvent.MOUSE_CLICKED, time, 0, x, y, n, false, java.awt.event.MouseEvent.BUTTON1));
    }
  }

  @TestOnly
  public void showGridForTest(@NotNull XNode node, @Nullable String group) {
    showGrid(node, group, Map.of(), Map.of());
  }

  @TestOnly
  public void navigateToNode(@NotNull XNode node) {
    navigator.accept(node.start());
  }

  @TestOnly
  public void setColumnFilterForTest(String key, @Nullable ColumnFilter f) {
    grid.setColumnFilter(key, f);
  }

  @TestOnly
  public int visibleGridRows() {
    return grid.visibleRowCount();
  }

  @TestOnly
  public Map<String, ColumnFilter> gridFilters() {
    return new LinkedHashMap<>(grid.filters());
  }

  @TestOnly
  public @Nullable String gridPath() {
    return gridNode == null ? null : gridNode.path();
  }

  @Override
  public void dispose() {
  }
}
