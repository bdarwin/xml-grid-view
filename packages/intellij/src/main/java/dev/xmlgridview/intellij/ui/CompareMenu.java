package dev.xmlgridview.intellij.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The "Compare…" menu: what changes are shown against (last commit, a branch/tag/commit, another file, the opened text). */
final class CompareMenu {
  private static final String OTHER_REF = "Other…";

  private CompareMenu() {
  }

  static void show(@NotNull Project project, @NotNull JComponent anchor, @NotNull ChangeBaseline baseline, @NotNull Consumer<String> onError) {
    ChangeBaseline.Source current = baseline.source();
    DefaultActionGroup g = new DefaultActionGroup();
    g.add(item("Last Commit", current instanceof ChangeBaseline.Head, () -> baseline.compareWith(new ChangeBaseline.Head(), onError)));
    g.add(item("Branch, Tag or Commit…", current instanceof ChangeBaseline.Ref, () -> chooseRef(project, anchor, baseline, onError)));
    g.add(item("Another File…", current instanceof ChangeBaseline.OtherFile, () -> {
      FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor(), project, null,
                             vf -> baseline.compareWith(new ChangeBaseline.OtherFile(vf), onError));
    }));
    g.add(item("When the File Was Opened", current instanceof ChangeBaseline.Opened, () -> baseline.compareWith(new ChangeBaseline.Opened(), onError)));
    ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup("Compare With", g, com.intellij.ide.DataManager.getInstance().getDataContext(anchor),
                                                                          JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false);
    popup.show(RelativePoint.getSouthWestOf(anchor));
  }

  private static DumbAwareAction item(String text, boolean current, Runnable run) {
    Icon icon = current ? AllIcons.Actions.Checked : null;
    return new DumbAwareAction(text, null, icon) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        run.run();
      }

      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
      }
    };
  }

  /** Branches, tags and remotes (most recent first), or any ref typed in. */
  private static void chooseRef(Project project, JComponent anchor, ChangeBaseline baseline, Consumer<String> onError) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<String> refs = new ArrayList<>(baseline.refs());
      ApplicationManager.getApplication().invokeLater(() -> {
        Consumer<String> use = ref -> {
          if (ref != null && !ref.isBlank()) baseline.compareWith(new ChangeBaseline.Ref(ref.trim()), onError);
        };
        Runnable typeIn = () -> use.accept(Messages.showInputDialog(project, "Branch, tag or commit (e.g. main, v1.2, HEAD~1):", "Compare With", null));
        if (refs.isEmpty()) {
          typeIn.run();
          return;
        }
        refs.add(0, OTHER_REF);
        JBPopupFactory.getInstance().createPopupChooserBuilder(refs)
          .setTitle("Compare with Branch, Tag or Commit")
          .setNamerForFiltering(s -> s)
          .setItemChosenCallback(ref -> {
            if (OTHER_REF.equals(ref)) typeIn.run();
            else use.accept(ref);
          })
          .createPopup()
          .show(RelativePoint.getSouthWestOf(anchor));
      }, ModalityState.any());
    });
  }
}
