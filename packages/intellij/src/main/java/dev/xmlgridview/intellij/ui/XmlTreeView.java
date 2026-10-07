package dev.xmlgridview.intellij.ui;

import com.intellij.ide.util.treeView.AbstractTreeStructure;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.tree.AsyncTreeModel;
import com.intellij.ui.tree.StructureTreeModel;
import com.intellij.ui.tree.TreeVisitor;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.tree.TreeUtil;
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
    add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER);
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
    return structureModel.invalidateAsync();
  }

  /** Restricts the tree to {@code nodes} and their ancestors, or shows everything when null. */
  CompletableFuture<?> setFilter(@Nullable Collection<XNode> nodes) {
    if (nodes == null) {
      if (structure.visible == null) return CompletableFuture.completedFuture(null);
      structure.visible = null;
    }
    else {
      Set<XNode> visible = new HashSet<>();
      for (XNode n : nodes) {
        for (XNode a = n; a != null && visible.add(a); a = a.parent()) {
          // add the node and its ancestors
        }
      }
      structure.visible = visible;
    }
    return structureModel.invalidateAsync();
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
      SimpleTextAttributes tagAttrs = hit ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES;
      if (n == fs.currentNode()) {
        tagAttrs = new SimpleTextAttributes(tagAttrs.getStyle() | SimpleTextAttributes.STYLE_SEARCH_MATCH, null);
      }
      Highlight.append(this, n.tag(), tagAttrs, fs.targets().names() ? fs.matcher() : null);
      for (String k : KEY_ATTRS) {
        XAttr a = n.attr(k);
        if (a == null) continue;
        append(" " + k + "=", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        Highlight.append(this, a.value(), SimpleTextAttributes.GRAYED_ATTRIBUTES, fs.targets().attrValues() ? fs.matcher() : null);
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
}
