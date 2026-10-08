package dev.xmlgridview.intellij.ui;

import com.intellij.util.ui.JBUI;
import com.intellij.util.Alarm;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.DocumentAdapter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.treeView.AbstractTreeStructure;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.tree.AsyncTreeModel;
import com.intellij.ui.tree.StructureTreeModel;
import com.intellij.ui.tree.TreeVisitor;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.tree.TreeUtil;
import dev.xmlgridview.intellij.model.InspectTarget;
import dev.xmlgridview.intellij.model.XAttr;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Element tree. Children are created lazily by the async model, which keeps
 * large documents responsive. Node label: tag, key attributes (id/name/key)
 * and element-child count.
 */
final class XmlTreeView extends JPanel {
  private static final String[] KEY_ATTRS = {"id", "name", "key"};
  private static final int MAX_RESTORED_EXPANSIONS = 2_000;

  private final Structure structure = new Structure();
  private final StructureTreeModel<Structure> structureModel;
  private final Tree tree;
  private final Supplier<FindState> findState;
  private boolean suppressSelectionEvents;
  private final SearchTextField filterField = new SearchTextField(false);
  private final Alarm filterAlarm;
  /** Nodes kept by the Find bar's "show only matches", or null. */
  private @Nullable Collection<XNode> findFilter;
  /** Nodes matching the filter box, or null when it is empty. */
  private @Nullable Collection<XNode> boxFilter;
  private @Nullable Consumer<InspectTarget> inspector;

  XmlTreeView(@NotNull Disposable parent, @NotNull Supplier<FindState> findState,
              @NotNull Consumer<XNode> onSelect, @NotNull Consumer<XNode> onNavigate) {
    super(new BorderLayout());
    this.findState = findState;
    structureModel = new StructureTreeModel<>(structure, parent);
    tree = new Tree(new AsyncTreeModel(structureModel, parent));
    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    tree.setCellRenderer(new Renderer());
    tree.getEmptyText().setText("No XML elements");
    TreeSpeedSearch.installOn(tree, false, path -> {
      XNode n = nodeOf(path);
      return n == null ? "" : label(n);
    });
    tree.addTreeSelectionListener(e -> {
      if (suppressSelectionEvents) return;
      XNode n = nodeOf(e.getNewLeadSelectionPath());
      if (n != null) onSelect.accept(n);
    });
    AnAction navigate = new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        XNode n = selected();
        if (n != null) onNavigate.accept(n);
      }
    };
    navigate.registerCustomShortcutSet(new CustomShortcutSet(new com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), null),
                                                             new com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), null)), tree, parent);
    InspectAction inspect = InspectAction.install(tree, parent, () -> {
      XNode n = selected();
      return n == null ? null : InspectTarget.ofNode(n);
    }, t -> {
      if (inspector != null) inspector.accept(t);
    });
    DefaultActionGroup menu = new DefaultActionGroup();
    menu.add(inspect);
    menu.add(new DumbAwareAction("Go to Source", null, AllIcons.Actions.EditSource) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        XNode n = selected();
        if (n != null) onNavigate.accept(n);
      }
    });
    PopupHandler.installPopupMenu(tree, menu, "XmlGridView.Tree");
    add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER);

    // "Filter nodes": keeps elements whose tag, attribute names/values or text contain the text.
    filterAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent);
    filterField.getTextEditor().getEmptyText().setText("Filter nodes (tag, attribute, text)");
    filterField.addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
        scheduleBoxFilter();
      }
    });
    filterField.setBorder(JBUI.Borders.empty(2, 4));
    add(filterField, BorderLayout.NORTH);
  }

  private void scheduleBoxFilter() {
    String q = filterField.getText().trim().toLowerCase(java.util.Locale.ROOT);
    filterAlarm.cancelAllRequests();
    filterAlarm.addRequest(() -> {
      XmlDocumentModel m = structure.model;
      List<XNode> hits = null;
      if (!q.isEmpty() && m != null && m.root() != null) {
        hits = new ArrayList<>();
        java.util.ArrayDeque<XNode> stack = new java.util.ArrayDeque<>();
        stack.push(m.root());
        while (!stack.isEmpty()) {
          XNode n = stack.pop();
          if (matchesBox(n, q)) hits.add(n);
          for (int i = n.children().size() - 1; i >= 0; i--) stack.push(n.children().get(i));
        }
      }
      List<XNode> result = hits;
      ApplicationManager.getApplication().invokeLater(() -> {
        boxFilter = result;
        applyFilters().whenComplete((x, err) -> ApplicationManager.getApplication().invokeLater(() -> {
          if (result != null && !result.isEmpty()) {
            expandPaths(result.stream().limit(500).map(n -> n.parent() == null ? n.path() : n.parent().path()).toList());
          }
        }));
      });
    }, 200);
  }

  private static boolean matchesBox(XNode n, String q) {
    if (n.tag().toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
    for (XAttr a : n.attrs()) {
      if (a.name().toLowerCase(java.util.Locale.ROOT).contains(q) || a.value().toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
    }
    return n.text().toLowerCase(java.util.Locale.ROOT).contains(q);
  }

  /** Visible nodes: the filter box and Find's "show only matches", combined (both must match). */
  private CompletableFuture<?> applyFilters() {
    Set<XNode> box = closeOverAncestors(boxFilter);
    Set<XNode> find = closeOverAncestors(findFilter);
    Set<XNode> visible;
    if (box == null) visible = find;
    else if (find == null) visible = box;
    else {
      visible = new HashSet<>(box);
      visible.retainAll(find);
    }
    if (visible == null && structure.visible == null) return CompletableFuture.completedFuture(null);
    structure.visible = visible;
    return structureModel.invalidateAsync();
  }

  private static @Nullable Set<XNode> closeOverAncestors(@Nullable Collection<XNode> nodes) {
    if (nodes == null) return null;
    Set<XNode> out = new HashSet<>();
    for (XNode n : nodes) {
      for (XNode a = n; a != null && out.add(a); a = a.parent()) {
        // the node and its ancestors
      }
    }
    return out;
  }

  void setInspector(@NotNull Consumer<InspectTarget> inspector) {
    this.inspector = inspector;
  }

  JComponent focusComponent() {
    return tree;
  }

  Tree tree() {
    return tree;
  }

  /** Replaces the model; resolves when the tree has reloaded. */
  CompletableFuture<?> setModel(@Nullable XmlDocumentModel model) {
    structure.model = model;
    structure.visible = null;
    findFilter = null;
    boxFilter = null;
    CompletableFuture<?> f = structureModel.invalidateAsync();
    // Re-apply the filter box to the new model.
    if (!filterField.getText().isBlank()) f.whenComplete((x, err) -> ApplicationManager.getApplication().invokeLater(this::scheduleBoxFilter));
    return f;
  }

  /** Find's "show only matches": restricts the tree to {@code nodes} and their ancestors (null = off). */
  CompletableFuture<?> setFilter(@Nullable Collection<XNode> nodes) {
    findFilter = nodes;
    return applyFilters();
  }

  @Nullable XNode selected() {
    return nodeOf(tree.getSelectionPath());
  }

  /** Path keys of expanded nodes. */
  List<String> expandedPaths() {
    List<String> out = new ArrayList<>();
    for (TreePath p : TreeUtil.collectExpandedPaths(tree)) {
      XNode n = nodeOf(p);
      if (n != null) out.add(n.path());
    }
    return out;
  }

  /** Expands the nodes at the given path keys (where they still exist). */
  Promise<?> expandPaths(Collection<String> paths) {
    List<String> sorted = new ArrayList<>(paths);
    sorted.sort(Comparator.comparingInt(String::length));
    if (sorted.size() > MAX_RESTORED_EXPANSIONS) sorted = sorted.subList(0, MAX_RESTORED_EXPANSIONS);
    if (sorted.isEmpty()) return Promises.resolvedPromise();
    return TreeUtil.promiseExpand(tree, sorted.stream().map(XmlTreeView::visitor));
  }

  /** Expands the first level so the root's children are visible. */
  Promise<?> expandRoot() {
    return TreeUtil.promiseExpand(tree, 1);
  }

  /** Selects and reveals the node at {@code path}; selection listeners are not notified when {@code silent}. */
  Promise<TreePath> select(String path, boolean silent) {
    Promise<TreePath> p = TreeUtil.promiseMakeVisible(tree, visitor(path));
    return p.onSuccess(tp -> {
      boolean old = suppressSelectionEvents;
      suppressSelectionEvents = silent;
      try {
        tree.setSelectionPath(tp);
        tree.scrollPathToVisible(tp);
      }
      finally {
        suppressSelectionEvents = old;
      }
    });
  }

  void repaintTree() {
    tree.repaint();
  }

  static TreeVisitor visitor(String target) {
    return path -> {
      Object last = TreeUtil.getLastUserObject(path);
      Object element = last instanceof NodeDescriptor<?> d ? d.getElement() : last;
      if (!(element instanceof XNode n)) return TreeVisitor.Action.CONTINUE;
      String k = n.path();
      if (k.equals(target)) return TreeVisitor.Action.INTERRUPT;
      if (target.startsWith(k + "/")) return TreeVisitor.Action.CONTINUE;
      return TreeVisitor.Action.SKIP_CHILDREN;
    };
  }

  static @Nullable XNode nodeOf(@Nullable TreePath path) {
    if (path == null) return null;
    Object last = TreeUtil.getLastUserObject(path);
    Object element = last instanceof NodeDescriptor<?> d ? d.getElement() : last;
    return element instanceof XNode n ? n : null;
  }

  static String keyAttrs(XNode n) {
    StringBuilder sb = new StringBuilder();
    for (String k : KEY_ATTRS) {
      XAttr a = n.attr(k);
      if (a != null) {
        if (!sb.isEmpty()) sb.append(' ');
        sb.append(k).append('=').append(a.value());
      }
    }
    return sb.toString();
  }

  static String label(XNode n) {
    String keys = keyAttrs(n);
    return keys.isEmpty() ? n.tag() : n.tag() + " " + keys;
  }

  private final class Renderer extends ColoredTreeCellRenderer {
    @Override
    public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
                                      boolean leaf, int row, boolean hasFocus) {
      XNode n = nodeOf(new TreePath(value));
      if (n == null) return;
      FindState fs = findState.get();
      boolean hit = fs.matchedNodes().contains(n);
      SimpleTextAttributes tagAttrs = selected && tree.hasFocus()
                                      ? new SimpleTextAttributes(hit ? SimpleTextAttributes.STYLE_BOLD : SimpleTextAttributes.STYLE_PLAIN, null)
                                      : XmlColors.tagAttrs(hit ? SimpleTextAttributes.STYLE_BOLD : SimpleTextAttributes.STYLE_PLAIN);
      if (n == fs.currentNode()) {
        tagAttrs = new SimpleTextAttributes(tagAttrs.getStyle() | SimpleTextAttributes.STYLE_SEARCH_MATCH, null);
      }
      Highlight.append(this, n.tag(), tagAttrs, fs.targets().names() ? fs.matcher() : null);
      for (String k : KEY_ATTRS) {
        XAttr a = n.attr(k);
        if (a == null) continue;
        boolean plain = selected && tree.hasFocus();
        append(" " + k, plain ? SimpleTextAttributes.REGULAR_ATTRIBUTES : XmlColors.attrNameAttrs(SimpleTextAttributes.STYLE_PLAIN));
        append("=", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        Highlight.append(this, a.value(), plain ? SimpleTextAttributes.REGULAR_ATTRIBUTES : XmlColors.attrValueAttrs(SimpleTextAttributes.STYLE_PLAIN),
                         fs.targets().attrValues() ? fs.matcher() : null);
      }
      if (!n.children().isEmpty()) append("  (" + n.children().size() + ")", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      else if (!n.text().isEmpty()) {
        String t = n.text().length() > 60 ? n.text().substring(0, 59) + "…" : n.text();
        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
        Highlight.append(this, t.replace('\n', ' '), SimpleTextAttributes.GRAYED_ATTRIBUTES, fs.targets().text() ? fs.matcher() : null);
      }
      setToolTipText(n.path());
    }
  }

  /** Tree structure over the immutable model; thread-safe because the model never changes. */
  private static final class Structure extends AbstractTreeStructure {
    private static final Object ROOT = new Object();
    volatile @Nullable XmlDocumentModel model;
    volatile @Nullable Set<XNode> visible;

    @Override
    public @NotNull Object getRootElement() {
      return ROOT;
    }

    @Override
    public Object @NotNull [] getChildElements(@NotNull Object element) {
      XmlDocumentModel m = model;
      if (m == null) return new Object[0];
      List<XNode> kids;
      if (element == ROOT) kids = m.root() == null ? List.of() : List.of(m.root());
      else if (element instanceof XNode n) kids = n.children();
      else return new Object[0];
      Set<XNode> v = visible;
      if (v == null) return kids.toArray();
      return kids.stream().filter(v::contains).toArray();
    }

    @Override
    public @Nullable Object getParentElement(@NotNull Object element) {
      if (element instanceof XNode n) return n.parent() == null ? ROOT : n.parent();
      return null;
    }

    @Override
    public @NotNull NodeDescriptor<?> createDescriptor(@NotNull Object element, @Nullable NodeDescriptor parent) {
      return new Descriptor(element, parent);
    }

    @Override
    public boolean isAlwaysLeaf(@NotNull Object element) {
      return element instanceof XNode n && n.children().isEmpty();
    }

    @Override
    public void commit() {
    }

    @Override
    public boolean hasSomethingToCommit() {
      return false;
    }
  }

  private static final class Descriptor extends NodeDescriptor<Object> {
    private final Object element;

    Descriptor(Object element, @Nullable NodeDescriptor<?> parent) {
      super(null, parent);
      this.element = element;
      if (element instanceof XNode n) myName = n.tag();
    }

    @Override
    public boolean update() {
      return false;
    }

    @Override
    public Object getElement() {
      return element;
    }
  }

  /** Applies the filter box synchronously (tests). Returns the visible nodes' paths, or null when unfiltered. */
  @org.jetbrains.annotations.TestOnly
  @Nullable Set<String> applyBoxFilterForTest(String query) {
    String q = query.trim().toLowerCase(java.util.Locale.ROOT);
    XmlDocumentModel m = structure.model;
    List<XNode> hits = null;
    if (!q.isEmpty() && m != null && m.root() != null) {
      hits = new ArrayList<>();
      java.util.ArrayDeque<XNode> stack = new java.util.ArrayDeque<>();
      stack.push(m.root());
      while (!stack.isEmpty()) {
        XNode n = stack.pop();
        if (matchesBox(n, q)) hits.add(n);
        n.children().forEach(stack::push);
      }
    }
    boxFilter = hits;
    applyFilters();
    Set<XNode> v = structure.visible;
    if (v == null) return null;
    Set<String> out = new java.util.TreeSet<>();
    for (XNode n : v) out.add(n.path());
    return out;
  }
}
