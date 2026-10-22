package dev.xmlgridview.intellij.ui;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
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
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The baseline that changes are highlighted against. By default the file as of the last commit when
 * it is under version control (the base revision IntelliJ shows changes against), otherwise the text
 * it had when the view was opened; Compare With switches to a branch, tag or commit (read with git),
 * another file, or the opened text. Re-read when the IDE's list of changes is updated (after a
 * commit, checkout or save) and when a compared file changes. The listener is called on the EDT.
 */
final class ChangeBaseline {
  static final String LAST_COMMIT = "the last commit";
  static final String OPENED = "when the file was opened";
  private static final Logger LOG = Logger.getInstance(ChangeBaseline.class);

  /** What to compare with. */
  sealed interface Source permits Head, Ref, OtherFile, Opened {
  }

  record Head() implements Source {
  }

  record Ref(@NotNull String ref) implements Source {
  }

  record OtherFile(@NotNull VirtualFile file) implements Source {
  }

  record Opened() implements Source {
  }

  interface Listener {
    /** A new baseline: its text, a label for tooltips, and whether the user chose it. */
    void baseline(@NotNull String text, @NotNull String label, boolean explicit);
  }

  private final Project project;
  private final @Nullable VirtualFile file;
  private final String openedText;
  private final Listener listener;
  private final Alarm alarm;
  private volatile Source source = new Head();
  private @Nullable String lastText;
  private @Nullable String lastLabel;
  private volatile boolean disposed;

  private ChangeBaseline(Project project, @Nullable VirtualFile file, String openedText, Disposable parent, Listener listener) {
    this.project = project;
    this.file = file;
    this.openedText = openedText;
    this.listener = listener;
    this.alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent);
    Disposer.register(parent, () -> disposed = true);
  }

  /** Starts watching; the first baseline arrives shortly (right away in tests). */
  static @NotNull ChangeBaseline start(@NotNull Project project, @Nullable VirtualFile file, @NotNull String openedText, @NotNull Disposable parent,
                                       @NotNull Listener listener) {
    ChangeBaseline b = new ChangeBaseline(project, file, openedText, parent, listener);
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
    // A compared file changing on disk.
    project.getMessageBus().connect(parent).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
      @Override
      public void after(@NotNull List<? extends VFileEvent> events) {
        if (b.source instanceof OtherFile o) {
          for (VFileEvent e : events) {
            if (o.file().equals(e.getFile())) {
              b.schedule();
              return;
            }
          }
        }
      }
    });
    if (ApplicationManager.getApplication().isUnitTestMode()) b.readAndDeliver(false);
    else b.schedule();
    return b;
  }

  @NotNull Source source() {
    return source;
  }

  /** Switches to {@code next}; {@code onError} gets a message (on the EDT) when it can't be read, and the baseline stays. */
  void compareWith(@NotNull Source next, @NotNull Consumer<String> onError) {
    Runnable work = () -> {
      try {
        Read r = read(next);
        deliver(() -> {
          source = next;
          lastText = null;
          send(r);
        });
      }
      catch (CantRead e) {
        deliver(() -> onError.accept(e.getMessage()));
      }
    };
    if (ApplicationManager.getApplication().isUnitTestMode()) work.run();
    else ApplicationManager.getApplication().executeOnPooledThread(work);
  }

  private void schedule() {
    if (disposed) return;
    alarm.cancelAllRequests();
    alarm.addRequest(() -> readAndDeliver(true), 200);
  }

  private void readAndDeliver(boolean keepOnError) {
    Source s = source;
    Read r;
    try {
      r = read(s);
    }
    catch (CantRead e) {
      if (keepOnError) return; // the branch or file went away: keep the last baseline
      r = new Read(openedText, OPENED);
    }
    Read result = r;
    deliver(() -> {
      if (source == s) send(result);
    });
  }

  private void send(Read r) {
    if (disposed || (Objects.equals(r.text(), lastText) && r.label().equals(lastLabel))) return;
    lastText = r.text();
    lastLabel = r.label();
    listener.baseline(r.text(), r.label(), !(source instanceof Head));
  }

  private void deliver(Runnable r) {
    if (ApplicationManager.getApplication().isDispatchThread()) r.run();
    else ApplicationManager.getApplication().invokeLater(() -> {
      if (!disposed) r.run();
    });
  }

  private record Read(@NotNull String text, @NotNull String label) {
  }

  private static final class CantRead extends Exception {
    CantRead(String message) {
      super(message);
    }
  }

  private Read read(Source s) throws CantRead {
    if (s instanceof Opened) return new Read(openedText, OPENED);
    if (s instanceof OtherFile o) {
      try {
        return new Read(LoadTextUtil.loadText(o.file()).toString(), o.file().getPresentableName());
      }
      catch (RuntimeException e) {
        throw new CantRead("Can't read " + o.file().getPresentableName() + ".");
      }
    }
    if (s instanceof Ref r) {
      String text = gitShow(r.ref());
      if (text == null) throw new CantRead("The file is not in " + r.ref() + ".");
      return new Read(text, r.ref());
    }
    String committed = null;
    try {
      committed = committed();
    }
    catch (LinkageError | RuntimeException e) {
      LOG.debug("Could not read the base revision", e);
    }
    return committed != null ? new Read(committed, LAST_COMMIT) : new Read(openedText, OPENED);
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

  /** Runs git in the file's directory; null when git is missing or fails (e.g. not a repository). */
  private @Nullable String git(String... args) {
    VirtualFile f = file;
    if (f == null || !f.isInLocalFileSystem() || f.getParent() == null) return null;
    List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.fsmonitor=false"));
    cmd.addAll(List.of(args));
    try {
      GeneralCommandLine line = new GeneralCommandLine(cmd).withWorkDirectory(f.getParent().getPath()).withCharset(StandardCharsets.UTF_8);
      ProcessOutput out = ExecUtil.execAndGetOutput(line, 30_000);
      return out.getExitCode() == 0 && !out.isTimeout() ? out.getStdout() : null;
    }
    catch (com.intellij.execution.ExecutionException e) {
      LOG.debug("git " + String.join(" ", args), e);
      return null;
    }
  }

  private @Nullable String gitShow(String ref) {
    String text = git("show", ref + ":./" + Objects.requireNonNull(file).getName());
    return text == null ? null : text.replace("\r\n", "\n");
  }

  /** Branches, tags and remote branches of the file's repository, most recent first (empty outside git). Not on the EDT. */
  @NotNull List<String> refs() {
    String out = git("for-each-ref", "--sort=-committerdate", "--format=%(refname:short)", "refs/heads", "refs/remotes", "refs/tags");
    if (out == null) return List.of();
    List<String> refs = new ArrayList<>();
    for (String r : out.split("\\R")) if (!r.isBlank() && !r.endsWith("/HEAD") && refs.size() < 200) refs.add(r.trim());
    return refs;
  }
}
