package dev.xmlgridview.intellij.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.CopyProvider;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.FileTypes;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import dev.xmlgridview.intellij.model.GridColumn;
import dev.xmlgridview.intellij.model.InspectTarget;
import dev.xmlgridview.intellij.model.InvalidQueryException;
import dev.xmlgridview.intellij.model.Json;
import dev.xmlgridview.intellij.model.JsonTable;
import dev.xmlgridview.intellij.model.Matcher;
import dev.xmlgridview.intellij.model.SearchOptions;
import dev.xmlgridview.intellij.model.TextUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Value inspector: a non-modal, resizable dialog showing a node's full text.
 * JSON values get Tree, Grid and Text tabs; anything else a read-only text
 * editor (soft-wrapped, with the editor's own Find).
 */
public final class ValueInspector extends DialogWrapper {
  private static final String DIMENSION_KEY = "XmlGridView.ValueInspector";

  private final Project project;
  private final InspectTarget target;
  private @Nullable JBTabbedPane tabs;
  private @Nullable JComponent preferredFocus;
  private boolean errorBanner;

  public static void show(@NotNull Project project, @Nullable InspectTarget target) {
    if (target == null) return;
    new ValueInspector(project, target).show();
  }

  private ValueInspector(@NotNull Project project, @NotNull InspectTarget target) {
    super(project, true, IdeModalityType.MODELESS);
    this.project = project;
    this.target = target;
    setTitle("Value: " + target.title());
    setCancelButtonText("Close");
    init();
  }

  @Override
  protected @Nullable String getDimensionServiceKey() {
    return DIMENSION_KEY;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return preferredFocus;
  }

  @Override
  protected Action @NotNull [] createActions() {
    Action copy = new AbstractAction("Copy") {
      @Override
      public void actionPerformed(ActionEvent e) {
        Json.Detection d = target.detection();
        boolean pretty = d.isJson() && tabs != null && InspectTarget.TAB_TEXT.equals(tabs.getTitleAt(tabs.getSelectedIndex()));
        CopyPasteManager.getInstance().setContents(new StringSelection(pretty ? Objects.requireNonNull(d.pretty()) : target.text()));
      }
    };
    return new Action[]{copy, getCancelAction()};
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    JPanel root = new JPanel(new BorderLayout());
    root.setPreferredSize(JBUI.size(820, 560));
    Json.Detection d = target.detection();
    if (d.isJson()) {
      tabs = new JBTabbedPane();
      JsonTreePanel tree = new JsonTreePanel(Objects.requireNonNull(d.value()));
      tabs.addTab(InspectTarget.TAB_TREE, tree);
      tabs.addTab(InspectTarget.TAB_GRID, new JsonGridPanel(Objects.requireNonNull(d.value())));
      Editor text = createViewer(Objects.requireNonNull(d.pretty()), jsonFileType());
      tabs.addTab(InspectTarget.TAB_TEXT, text.getComponent());
      root.add(tabs, BorderLayout.CENTER);
      preferredFocus = tree.tree;
    }
    else {
      Editor editor = createViewer(target.text(), FileTypes.PLAIN_TEXT);
      Json.SyntaxError err = d.error();
      if (err != null) {
        EditorNotificationPanel banner = new EditorNotificationPanel(EditorNotificationPanel.Status.Warning);
        banner.setText("Looks like JSON but could not be parsed: " + err.message() + " (line " + err.line() + ", column " + err.column() + ")");
        int offset = leadingWhitespace(target.text()) + err.offset();
        banner.createActionLabel("Go to error", () -> {
          editor.getCaretModel().moveToOffset(Math.min(offset, editor.getDocument().getTextLength()));
          editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
          editor.getContentComponent().requestFocusInWindow();
        });
        root.add(banner, BorderLayout.NORTH);
        errorBanner = true;
      }
      root.add(editor.getComponent(), BorderLayout.CENTER);
      preferredFocus = editor.getContentComponent();
    }
    return root;
  }

  private static int leadingWhitespace(String s) {
    int i = 0;
    while (i < s.length() && TextUtil.isXmlWhitespace(s.charAt(i))) i++;
    return i;
  }

  private static FileType jsonFileType() {
    FileType t = FileTypeManager.getInstance().getFileTypeByExtension("json");
    return t instanceof UnknownFileType ? FileTypes.PLAIN_TEXT : t;
  }

  /** Read-only editor backed by a light virtual file, so the file type's highlighting and folding apply. */
  private Editor createViewer(String text, FileType type) {
    LightVirtualFile file = new LightVirtualFile("value." + type.getDefaultExtension(), type, text);
    file.setWritable(false);
    Document doc = Objects.requireNonNull(FileDocumentManager.getInstance().getDocument(file));
    Editor editor = EditorFactory.getInstance().createEditor(doc, project, file, true);
    editor.getSettings().setUseSoftWraps(true);
    editor.getSettings().setLineNumbersShown(true);
    editor.getSettings().setFoldingOutlineShown(true);
    Disposer.register(getDisposable(), () -> EditorFactory.getInstance().releaseEditor(editor));
    return editor;
  }

  private static SimpleTextAttributes colors(TextAttributesKey key, SimpleTextAttributes fallback) {
    EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
    TextAttributes ta = scheme.getAttributes(key);
    if (ta == null || ta.getForegroundColor() == null) return fallback;
    return new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ta.getForegroundColor());
  }

  // ---- Tree tab ------------------------------------------------------------------------------

  /** A JSON tree node; identity is its path, so expansion state survives model events. */
  static final class JsonNode {
    final @Nullable String key;
    final Object value;
    final List<Object> path;

    JsonNode(@Nullable String key, Object value, List<Object> path) {
      this.key = key;
      this.value = value;
      this.path = path;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof JsonNode n && n.path.equals(path);
    }

    @Override
    public int hashCode() {
      return path.hashCode();
    }

    @Override
    public String toString() {
      return (key == null ? "" : key + ": ") + (Json.isContainer(value) ? Json.containerLabel(value) : Json.primitiveText(value));
    }
  }

  /** Lazy TreeModel over the parsed JSON value. */
  static final class JsonTreeModel implements TreeModel {
    private final JsonNode root;

    JsonTreeModel(Object value) {
      root = new JsonNode(null, value, List.of());
    }

    @Override
    public Object getRoot() {
      return root;
    }

    @Override
    public Object getChild(Object parent, int index) {
      JsonNode p = (JsonNode)parent;
      List<Object> path = new ArrayList<>(p.path);
      if (p.value instanceof List<?> l) {
        path.add(index);
        return new JsonNode(String.valueOf(index), l.get(index), List.copyOf(path));
      }
      Map.Entry<?, ?> e = ((Map<?, ?>)p.value).entrySet().stream().skip(index).findFirst().orElseThrow();
      path.add(e.getKey());
      return new JsonNode((String)e.getKey(), e.getValue(), List.copyOf(path));
    }

    @Override
    public int getChildCount(Object parent) {
      Object v = ((JsonNode)parent).value;
      return Json.isContainer(v) ? Json.size(v) : 0;
    }

    @Override
    public boolean isLeaf(Object node) {
      return !Json.isContainer(((JsonNode)node).value);
    }

    @Override
    public void valueForPathChanged(TreePath path, Object newValue) {
    }

    @Override
    public int getIndexOfChild(Object parent, Object child) {
      JsonNode p = (JsonNode)parent;
      JsonNode c = (JsonNode)child;
      Object seg = c.path.get(c.path.size() - 1);
      if (p.value instanceof List<?>) return (Integer)seg;
      int i = 0;
      for (Object k : ((Map<?, ?>)p.value).keySet()) {
        if (k.equals(seg)) return i;
        i++;
      }
      return -1;
    }

    @Override
    public void addTreeModelListener(TreeModelListener l) {
    }

    @Override
    public void removeTreeModelListener(TreeModelListener l) {
    }
  }

  /** Tree with search (n of m, Enter/Shift+Enter, F3/Shift+F3), speed search and expand/collapse all. */
  static final class JsonTreePanel extends JPanel {
    final Tree tree;
    private final Object value;
    private final SearchTextField search = new SearchTextField(false);
    private final JBLabel counter = new JBLabel();
    private @Nullable Matcher matcher;
    private final List<List<Object>> matches = new ArrayList<>();
    private int current = -1;

    JsonTreePanel(Object value) {
      super(new BorderLayout());
      this.value = value;
      tree = new Tree(new JsonTreeModel(value));
      tree.setRootVisible(true);
      tree.setShowsRootHandles(true);
      tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
      tree.setCellRenderer(new Renderer());
      tree.expandRow(0);
      TreeSpeedSearch.installOn(tree, false, path -> path.getLastPathComponent().toString());

      DefaultActionGroup group = new DefaultActionGroup();
      group.add(new DumbAwareAction("Expand All", null, AllIcons.Actions.Expandall) {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
          TreeUtil.expandAll(tree);
        }
      });
      group.add(new DumbAwareAction("Collapse All", null, AllIcons.Actions.Collapseall) {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
          TreeUtil.collapseAll(tree, 0);
        }
      });
      ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("XmlGridView.JsonTree", group, true);
      toolbar.setTargetComponent(tree);

      JPanel north = new JPanel(new BorderLayout(JBUI.scale(6), 0));
      north.setBorder(JBUI.Borders.empty(4));
      search.getTextEditor().getEmptyText().setText("Search keys and values");
      north.add(search, BorderLayout.CENTER);
      JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
      right.add(counter);
      right.add(toolbar.getComponent());
      north.add(right, BorderLayout.EAST);
      add(north, BorderLayout.NORTH);
      add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER);

      search.addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent e) {
          runSearch(search.getText());
        }
      });
      search.getTextEditor().addKeyListener(new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent e) {
          if (e.getKeyCode() == KeyEvent.VK_ENTER) {
            step(e.isShiftDown() ? -1 : 1);
            e.consume();
          }
        }
      });
      DumbAwareAction next = new DumbAwareAction() {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
          step(1);
        }
      };
      next.registerCustomShortcutSet(new CustomShortcutSet(new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), null)), this);
      DumbAwareAction prev = new DumbAwareAction() {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
          step(-1);
        }
      };
      prev.registerCustomShortcutSet(new CustomShortcutSet(new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F3, KeyEvent.SHIFT_DOWN_MASK), null)), this);
    }

    void runSearch(String query) {
      matches.clear();
      current = -1;
      try {
        matcher = query.isEmpty() ? null : Matcher.create(query, SearchOptions.DEFAULT);
      }
      catch (InvalidQueryException e) {
        matcher = null;
      }
      if (matcher != null) collect(value, new ArrayList<>(), null);
      counter.setText(matcher == null ? "" : matches.isEmpty() ? "No results" : matches.size() + " results");
      tree.repaint();
    }

    private void collect(Object v, List<Object> path, @Nullable String key) {
      boolean hit = key != null && matcher.test(key) || !Json.isContainer(v) && matcher.test(Json.primitiveText(v));
      if (hit) matches.add(List.copyOf(path));
      if (v instanceof List<?> l) {
        for (int i = 0; i < l.size(); i++) {
          path.add(i);
          collect(l.get(i), path, null);
          path.remove(path.size() - 1);
        }
      }
      else if (v instanceof Map<?, ?> m) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
          path.add(e.getKey());
          collect(e.getValue(), path, (String)e.getKey());
          path.remove(path.size() - 1);
        }
      }
    }

    void step(int delta) {
      if (matches.isEmpty()) return;
      current = current < 0 ? (delta > 0 ? 0 : matches.size() - 1) : Math.floorMod(current + delta, matches.size());
      counter.setText((current + 1) + " of " + matches.size());
      reveal(matches.get(current));
    }

    /** Expands ancestors and selects the node at {@code path}. */
    void reveal(List<Object> path) {
      TreeModel m = tree.getModel();
      Object node = m.getRoot();
      TreePath tp = new TreePath(node);
      for (Object seg : path) {
        JsonNode parent = (JsonNode)node;
        int idx = m.getIndexOfChild(parent, new JsonNode(null, Json.NULL, append(parent.path, seg)));
        if (idx < 0) return;
        node = m.getChild(parent, idx);
        tp = tp.pathByAddingChild(node);
      }
      tree.expandPath(tp.getParentPath());
      tree.setSelectionPath(tp);
      tree.scrollPathToVisible(tp);
    }

    private static List<Object> append(List<Object> path, Object seg) {
      List<Object> p = new ArrayList<>(path);
      p.add(seg);
      return p;
    }

    @TestOnly
    int matchCount() {
      return matches.size();
    }

    private final class Renderer extends ColoredTreeCellRenderer {
      @Override
      public void customizeCellRenderer(@NotNull JTree t, Object node, boolean selected, boolean expanded, boolean leaf, int row,
                                        boolean hasFocus) {
        JsonNode n = (JsonNode)node;
        if (n.key != null) {
          SimpleTextAttributes keyAttrs = colors(DefaultLanguageHighlighterColors.INSTANCE_FIELD, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            .derive(SimpleTextAttributes.STYLE_BOLD, null, null, null);
          Highlight.append(this, n.key, keyAttrs, matcher);
          append(": ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        Object v = n.value;
        if (Json.isContainer(v)) {
          append(Json.containerLabel(v), SimpleTextAttributes.GRAYED_ATTRIBUTES);
          return;
        }
        SimpleTextAttributes attrs;
        String text = Json.primitiveText(v);
        if (v instanceof String) {
          attrs = colors(DefaultLanguageHighlighterColors.STRING, SimpleTextAttributes.REGULAR_ATTRIBUTES);
          text = text.replace('\n', '⏎');
        }
        else if (v instanceof Json.Num) attrs = colors(DefaultLanguageHighlighterColors.NUMBER, SimpleTextAttributes.REGULAR_ATTRIBUTES);
        else attrs = colors(DefaultLanguageHighlighterColors.KEYWORD, SimpleTextAttributes.REGULAR_ATTRIBUTES);
        Highlight.append(this, text.length() > 500 ? text.substring(0, 500) + "…" : text, attrs, matcher);
      }
    }
  }

  // ---- Grid tab ------------------------------------------------------------------------------

  /** JSON grid with breadcrumb drill-down, quick filter, sorting, grid lines, row numbers and TSV copy. */
  static final class JsonGridPanel extends JPanel implements UiDataProvider {
    private final Object root;
    private final List<Object> path = new ArrayList<>();
    private final JPanel breadcrumb = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0));
    private final SearchTextField filter = new SearchTextField(false);
    private final Model model = new Model();
    private final JBTable table = new JBTable(model);
    private final TableRowSorter<Model> sorter = new TableRowSorter<>(model);

    JsonGridPanel(Object root) {
      super(new BorderLayout());
      this.root = root;
      table.setRowSorter(sorter);
      table.setCellSelectionEnabled(true);
      table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
      table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
      table.getTableHeader().setReorderingAllowed(false);
      table.setDefaultRenderer(Object.class, new CellRenderer());
      table.setDefaultEditor(Object.class, null);
      GridLines.apply(table);
      sorter.setRowFilter(new RowFilter<>() {
        @Override
        public boolean include(Entry<? extends Model, ? extends Integer> entry) {
          String q = filter.getText().trim().toLowerCase();
          if (q.isEmpty() || model.table == null) return true;
          int r = entry.getIdentifier();
          for (int c = 0; c < model.table.columnCount(); c++) {
            if (model.table.cellText(r, c).toLowerCase().contains(q)) return true;
          }
          return false;
        }
      });
      filter.getTextEditor().getEmptyText().setText("Filter rows");
      filter.addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent e) {
          sorter.allRowsChanged();
        }
      });
      table.addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
            drill(table.rowAtPoint(e.getPoint()), table.columnAtPoint(e.getPoint()));
          }
        }
      });
      DumbAwareAction enter = new DumbAwareAction() {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
          drill(table.getSelectionModel().getLeadSelectionIndex(), table.getColumnModel().getSelectionModel().getLeadSelectionIndex());
        }
      };
      enter.registerCustomShortcutSet(new CustomShortcutSet(new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), null)), table);

      JPanel north = new JPanel();
      north.setLayout(new BoxLayout(north, BoxLayout.X_AXIS));
      north.setBorder(JBUI.Borders.empty(4));
      north.add(breadcrumb);
      north.add(Box.createHorizontalGlue());
      filter.setMaximumSize(JBUI.size(240, 30));
      north.add(filter);
      add(north, BorderLayout.NORTH);
      JScrollPane scroll = ScrollPaneFactory.createScrollPane(table, true);
      scroll.setRowHeaderView(new RowNumberHeader(table));
      add(scroll, BorderLayout.CENTER);
      show(List.of());
    }

    /** Shows the grid for the container at {@code newPath}. */
    void show(List<Object> newPath) {
      path.clear();
      path.addAll(newPath);
      Object node = Json.at(root, path);
      model.setTable(JsonTable.of(node));
      for (int c = 0; c < model.getColumnCount(); c++) sorter.setComparator(c, CellComparator.INSTANCE);
      sorter.setSortKeys(null);
      autoFit();
      breadcrumb.removeAll();
      breadcrumb.add(new ActionLink("$", (ActionEvent e) -> show(List.of())));
      for (int i = 0; i < path.size(); i++) {
        List<Object> prefix = List.copyOf(path.subList(0, i + 1));
        breadcrumb.add(new JBLabel("›"));
        Object seg = path.get(i);
        String label = seg instanceof Integer ? "[" + seg + "]" : String.valueOf(seg);
        if (i == path.size() - 1) breadcrumb.add(new JBLabel(label));
        else breadcrumb.add(new ActionLink(label, (ActionEvent e) -> show(prefix)));
      }
      breadcrumb.revalidate();
      breadcrumb.repaint();
    }

    private void drill(int viewRow, int viewCol) {
      if (viewRow < 0 || viewCol < 0 || model.table == null) return;
      int r = table.convertRowIndexToModel(viewRow);
      int c = table.convertColumnIndexToModel(viewCol);
      Object cell = model.table.cell(r, c);
      if (!(cell instanceof JsonTable.Drill)) return;
      List<Object> next = new ArrayList<>(path);
      next.add(model.table.rowSegments().get(r));
      String colKey = model.table.columns().get(c).key();
      Object node = Json.at(root, next);
      // Row items that are objects spread into key columns; descend into that key.
      if (node instanceof Map && !colKey.equals(JsonTable.VALUE_COLUMN) && !colKey.equals(JsonTable.KEY_COLUMN)) next.add(colKey);
      show(next);
    }

    private void autoFit() {
      int max = JBUI.scale(420);
      int min = JBUI.scale(50);
      for (int c = 0; c < table.getColumnCount(); c++) {
        int w = table.getTableHeader().getDefaultRenderer()
          .getTableCellRendererComponent(table, table.getColumnName(c), false, false, -1, c).getPreferredSize().width;
        for (int r = 0; r < Math.min(300, table.getRowCount()) && w < max; r++) {
          w = Math.max(w, table.prepareRenderer(table.getCellRenderer(r, c), r, c).getPreferredSize().width);
        }
        table.getColumnModel().getColumn(c).setPreferredWidth(Math.max(min, Math.min(max, w + JBUI.scale(12))));
      }
    }

    String selectionAsTsv() {
      if (model.table == null) return "";
      StringBuilder sb = new StringBuilder();
      int[] cols = table.getSelectedColumns();
      for (int vr : table.getSelectedRows()) {
        if (!sb.isEmpty()) sb.append('\n');
        int r = table.convertRowIndexToModel(vr);
        for (int i = 0; i < cols.length; i++) {
          if (i > 0) sb.append('\t');
          sb.append(model.table.cellText(r, table.convertColumnIndexToModel(cols[i])).replaceAll("\\s+", " "));
        }
      }
      return sb.toString();
    }

    @Override
    public void uiDataSnapshot(@NotNull DataSink sink) {
      sink.set(PlatformDataKeys.COPY_PROVIDER, new CopyProvider() {
        @Override
        public void performCopy(@NotNull DataContext dataContext) {
          CopyPasteManager.getInstance().setContents(new StringSelection(selectionAsTsv()));
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
      });
    }

    @TestOnly
    @Nullable JsonTable currentTable() {
      return model.table;
    }

    @TestOnly
    void drillForTest(int modelRow, int modelCol) {
      drill(table.convertRowIndexToView(modelRow), table.convertColumnIndexToView(modelCol));
    }

    @TestOnly
    List<Object> pathForTest() {
      return List.copyOf(path);
    }

    static final class Model extends AbstractTableModel {
      @Nullable JsonTable table;

      void setTable(JsonTable t) {
        table = t;
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
    }

    private final class CellRenderer extends ColoredTableCellRenderer {
      @Override
      protected void customizeCellRenderer(@NotNull JTable tbl, @Nullable Object value, boolean selected, boolean hasFocus, int row,
                                           int column) {
        if (model.table == null) return;
        int r = tbl.convertRowIndexToModel(row);
        int c = tbl.convertColumnIndexToModel(column);
        Object cell = model.table.cell(r, c);
        if (cell == null) return;
        GridColumn col = model.table.columns().get(c);
        if (cell instanceof JsonTable.Drill d) append(d.label(), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES);
        else if (col.key().equals(JsonTable.KEY_COLUMN)) append(cell.toString().replaceAll("\\s+", " "), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        else append(cell.toString().replaceAll("\\s+", " "));
      }
    }
  }

  // ---- Test hooks ----------------------------------------------------------------------------

  /** Builds the content panels without showing the dialog (for tests). */
  @TestOnly
  static ValueInspector createForTest(@NotNull Project project, @NotNull InspectTarget target) {
    return new ValueInspector(project, target);
  }

  @TestOnly
  List<String> tabTitlesForTest() {
    if (tabs == null) return List.of(InspectTarget.TAB_TEXT);
    List<String> out = new ArrayList<>();
    for (int i = 0; i < tabs.getTabCount(); i++) out.add(tabs.getTitleAt(i));
    return out;
  }

  @TestOnly
  boolean hasErrorBannerForTest() {
    return errorBanner;
  }

  @TestOnly
  static JsonTreePanel treePanelForTest(@NotNull Object json) {
    return new JsonTreePanel(json);
  }

  @TestOnly
  static JsonGridPanel gridPanelForTest(@NotNull Object json) {
    return new JsonGridPanel(json);
  }
}
