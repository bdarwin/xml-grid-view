package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListListener;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * The baseline that changes are highlighted against: the file as of the last commit when it is
 * under version control (the base revision IntelliJ shows changes against), otherwise the text it
 * had when the view was opened. Re-read when the IDE's list of changes is updated (after a commit,
 * checkout or save). The consumer is called on the EDT with the text and a label for tooltips.
 */
final class ChangeBaseline {
  static final String LAST_COMMIT = "the last commit";
  static final String OPENED = "when the file was opened";
  private static final Logger LOG = Logger.getInstance(ChangeBaseline.class);

  private final Project project;
  private final @Nullable VirtualFile file;
  private final String openedText;
  private final BiConsumer<String, String> consumer;
  private final Alarm alarm;
  private @Nullable String lastText;
  private @Nullable String lastLabel;
  private volatile boolean disposed;

  private ChangeBaseline(Project project, @Nullable VirtualFile file, String openedText, Disposable parent, BiConsumer<String, String> consumer) {
    this.project = project;
    this.file = file;
    this.openedText = openedText;
    this.consumer = consumer;
    this.alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent);
    Disposer.register(parent, () -> disposed = true);
  }

  /** Starts watching; the first baseline arrives shortly (the opened text right away in tests). */
  static void start(@NotNull Project project, @Nullable VirtualFile file, @NotNull String openedText, @NotNull Disposable parent,
                    @NotNull BiConsumer<String, String> consumer) {
    ChangeBaseline b = new ChangeBaseline(project, file, openedText, parent, consumer);
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      consumer.accept(openedText, OPENED);
      return;
    }
    try {
      ChangeListManager.getInstance(project).addChangeListListener(new ChangeListListener() {
        @Override
        public void changeListUpdateDone() {
          b.schedule();
        }
      }, parent);
    }
    catch (LinkageError e) {
      LOG.info("No version control support: changes are shown since the file was opened", e);
    }
    b.schedule();
  }

  private void schedule() {
    if (disposed) return;
    alarm.cancelAllRequests();
    alarm.addRequest(this::read, 200);
  }

  /** Reads the baseline (off the EDT: the base revision may come from the VCS) and hands it over if it changed. */
  private void read() {
    String text = null;
    String label = OPENED;
    try {
      String committed = committed();
      if (committed != null) {
        text = committed;
        label = LAST_COMMIT;
      }
    }
    catch (LinkageError | RuntimeException e) {
      LOG.debug("Could not read the base revision", e);
    }
    if (text == null) text = openedText;
    String t = text;
    String l = label;
    ApplicationManager.getApplication().invokeLater(() -> {
      if (disposed || (Objects.equals(t, lastText) && l.equals(lastLabel))) return;
      lastText = t;
      lastLabel = l;
      consumer.accept(t, l);
    });
  }

  /** The file's text in the last commit, or null when it is not under version control or not committed yet. */
  private @Nullable String committed() {
    VirtualFile f = file;
    if (f == null || !f.isInLocalFileSystem() || project.isDisposed()) return null;
    com.intellij.openapi.util.Computable<Boolean> versioned = () -> ProjectLevelVcsManager.getInstance(project).getVcsFor(f) != null;
    if (!ApplicationManager.getApplication().runReadAction(versioned)) return null;
    ChangeListManager clm = ChangeListManager.getInstance(project);
    if (clm.isUnversioned(f) || clm.isIgnoredFile(f)) return null;
    Change change = clm.getChange(f);
    if (change == null) {
      // Unchanged since the last commit: the saved file is the committed version.
      return LoadTextUtil.loadText(f).toString();
    }
    ContentRevision before = change.getBeforeRevision();
    if (before == null) return null; // added, not committed yet
    try {
      String content = before.getContent();
      return content == null ? null : content.replace("\r\n", "\n");
    }
    catch (com.intellij.openapi.vcs.VcsException e) {
      LOG.debug("Could not read the base revision of " + f.getPath(), e);
      return null;
    }
  }
}
