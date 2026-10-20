package dev.xmlgridview.intellij.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import dev.xmlgridview.intellij.model.StructureEdits;
import dev.xmlgridview.intellij.model.StructureEdits.Op;
import dev.xmlgridview.intellij.model.StructureEdits.Where;
import dev.xmlgridview.intellij.model.ValueEdits;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Context-menu actions for structural edits (insert, duplicate, rename, add/delete attribute,
 * delete), shared by the tree, Grid and Flat. Each action computes its edits with
 * {@link StructureEdits} and hands them to the view's loader as one undoable command.
 */
final class StructureActions {
  private StructureActions() {
  }

  /** What the menu acts on: an element, and the attribute under the cursor if any. */
  record Target(@NotNull XmlDocumentModel model, @NotNull XNode node, @Nullable String attr) {
  }

  interface Applier {
    boolean canEdit();

    /** Applies the edits as one undoable command; returns null or why it was refused. Selects {@code selectPath} once rebuilt. */
    @Nullable String apply(@NotNull List<ValueEdits.TextEdit> edits, @NotNull String label, @Nullable String selectPath);
  }

  /** Separator plus the structural actions, to append to a view's popup menu. */
  static @NotNull DefaultActionGroup create(@NotNull JComponent owner, @NotNull Supplier<@Nullable Target> target, @NotNull Applier applier) {
    DefaultActionGroup g = new DefaultActionGroup();
    g.add(Separator.getInstance());
    g.add(new EditAction("Insert Child Element…", AllIcons.General.Add, t -> true, owner, target, applier) {
      @Override
      @Nullable Planned plan(Target t) {
        String n = askName(owner, "New child of <" + t.node().tag() + ">", "Element name", "");
        return n == null ? null : new Planned(Op.insert(t.node().path(), Where.CHILD, n), childPath(t.node(), t.node().childCount()));
      }
    });
    g.add(new EditAction("Insert Element Before…", null, StructureActions::notRoot, owner, target, applier) {
      @Override
      @Nullable Planned plan(Target t) {
        String n = askName(owner, "New element before <" + t.node().tag() + ">", "Element name", "");
        return n == null ? null : new Planned(Op.insert(t.node().path(), Where.BEFORE, n), t.node().path());
      }
    });
    g.add(new EditAction("Insert Element After…", null, StructureActions::notRoot, owner, target, applier) {
      @Override
      @Nullable Planned plan(Target t) {
        String n = askName(owner, "New element after <" + t.node().tag() + ">", "Element name", "");
        return n == null ? null : new Planned(Op.insert(t.node().path(), Where.AFTER, n), nextSiblingPath(t.node()));
      }
    });
    g.add(new EditAction("Duplicate", AllIcons.Actions.Copy, StructureActions::notRoot, owner, target, applier) {
      @Override
      Planned plan(Target t) {
        return new Planned(Op.duplicate(t.node().path()), nextSiblingPath(t.node()));
      }
    });
    g.add(Separator.getInstance());
    g.add(new EditAction("Rename…", null, t -> true, owner, target, applier) {
      @Override
      @Nullable Planned plan(Target t) {
        String n = askName(owner, "Rename <" + t.node().tag() + ">", "New name", t.node().tag());
        return n == null ? null : new Planned(Op.rename(t.node().path(), n), t.node().path());
      }
    });
    g.add(new EditAction("Add Attribute…", null, t -> true, owner, target, applier) {
      @Override
      @Nullable Planned plan(Target t) {
        String n = askName(owner, "New attribute on <" + t.node().tag() + ">", "Attribute name", "");
        if (n == null) return null;
        String v = Messages.showInputDialog(owner, "Value of @" + n, "New Attribute on <" + t.node().tag() + ">", null);
        return v == null ? null : new Planned(Op.addAttr(t.node().path(), n, v), null);
      }
    });
    g.add(new EditAction("Delete Attribute", AllIcons.General.Remove, t -> t.attr() != null, owner, target, applier) {
      @Override
      public void update(@NotNull AnActionEvent e) {
        super.update(e);
        Target t = target.get();
        e.getPresentation().setVisible(t != null && t.attr() != null);
        if (t != null && t.attr() != null) e.getPresentation().setText("Delete Attribute @" + t.attr());
      }

      @Override
      Planned plan(Target t) {
        return new Planned(Op.deleteAttr(t.node().path(), t.attr()), null);
      }
    });
    g.add(Separator.getInstance());
    g.add(new EditAction("Delete Element", AllIcons.General.Remove, StructureActions::notRoot, owner, target, applier) {
      @Override
      public void update(@NotNull AnActionEvent e) {
        super.update(e);
        Target t = target.get();
        if (t != null) e.getPresentation().setText("Delete <" + t.node().tag() + ">");
      }

      @Override
      Planned plan(Target t) {
        XNode parent = t.node().parent();
        return new Planned(Op.deleteElement(t.node().path()), parent == null ? null : parent.path());
      }
    });
    return g;
  }

  /** The action whose (template) text starts with {@code text}, for tests. */
  static @Nullable com.intellij.openapi.actionSystem.AnAction find(@NotNull DefaultActionGroup g, @NotNull String text) {
    for (com.intellij.openapi.actionSystem.AnAction a : g.getChildActionsOrStubs()) {
      String t = a.getTemplateText();
      if (t != null && t.startsWith(text)) return a;
    }
    return null;
  }

  private static boolean notRoot(Target t) {
    return t.node().parent() != null;
  }

  private static String childPath(XNode parent, int index) {
    return parent.path() + "/" + index;
  }

  private static String nextSiblingPath(XNode node) {
    XNode parent = node.parent();
    return parent == null ? node.path() : childPath(parent, node.index() + 1);
  }

  private static @Nullable String askName(JComponent owner, String title, String message, String initial) {
    String n = Messages.showInputDialog(owner, message, title, null, initial, new InputValidatorEx() {
      @Override
      public @Nullable String getErrorText(@NotNull String input) {
        return StructureEdits.isQName(input.strip()) ? null : "Not a valid XML name.";
      }
    });
    return n == null ? null : n.strip();
  }

  /** An edit and the element to select afterwards. */
  record Planned(@NotNull Op op, @Nullable String selectPath) {
  }

  private abstract static class EditAction extends DumbAwareAction {
    private final Function<Target, Boolean> allowed;
    private final JComponent owner;
    private final Supplier<@Nullable Target> target;
    private final Applier applier;

    EditAction(String text, @Nullable Icon icon, Function<Target, Boolean> allowed, JComponent owner,
               Supplier<@Nullable Target> target, Applier applier) {
      super(text, null, icon);
      this.allowed = allowed;
      this.owner = owner;
      this.target = target;
      this.applier = applier;
    }

    /** Asks for any input and returns the operation, or null when cancelled. */
    abstract @Nullable Planned plan(Target t);

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Target t = target.get();
      e.getPresentation().setVisible(t != null);
      e.getPresentation().setEnabled(t != null && applier.canEdit() && allowed.apply(t));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Target t = target.get();
      if (t == null || !applier.canEdit()) return;
      Planned p = plan(t);
      if (p == null) return;
      StructureEdits.Result r = StructureEdits.compute(t.model(), p.op());
      String error = r.isOk() ? null : r.error();
      if (error == null && !r.edits().isEmpty()) error = applier.apply(r.edits(), StructureEdits.label(t.model(), p.op()), p.selectPath());
      if (error != null) Messages.showErrorDialog(owner, error, "Can't Edit the XML");
    }
  }
}
