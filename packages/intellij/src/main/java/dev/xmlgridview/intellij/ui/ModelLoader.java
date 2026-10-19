package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.SingleRootFileViewProvider;
import com.intellij.openapi.editor.Document;
import java.util.Objects;
import dev.xmlgridview.intellij.model.ValueEdits;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.util.Alarm;
import dev.xmlgridview.intellij.model.ParseError;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.event.HierarchyEvent;
import java.util.function.IntConsumer;

/**
 * Keeps an {@link XmlDocumentModel} in sync with a document for one view:
 * rebuilds lazily (only while {@code owner} is showing), debounced after edits,
 * on a pooled thread, sharing each build with the file's other tab (see
 * {@link SharedModels}). Slow builds show a progress banner. Malformed documents
 * keep the last good model and show a warning banner; very large files wait for
 * "Load anyway". Files too large for IntelliJ to load into a document (its
 * content-loading limit, 20 MB by default) are read from disk and shown read-only.
 */
final class ModelLoader implements Disposable {
  static final long LARGE_FILE_CHARS = 50L * 1024 * 1024;
  static final int DEBOUNCE_MS = 300;
  /** Builds finishing faster than this never show the progress banner. */
  static final int PROGRESS_DELAY_MS = 400;

  interface Client {
    /** Called on the EDT with the new model; {@code previous} is null for the first one. */
    void modelChanged(@Nullable XmlDocumentModel previous, @NotNull XmlDocumentModel model);
  }

  private final Project project;
  private final Document document;
  private final JComponent owner;
  private final IntConsumer navigator;
  private final Client client;
  private final Alarm refreshAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
  private final JPanel banners = new JPanel();

  private @Nullable XmlDocumentModel model;
  private boolean dirty = true;
  private boolean largeFileAccepted;
  private @Nullable EditorNotificationPanel errorBanner;
  private @Nullable EditorNotificationPanel largeBanner;
  private @Nullable EditorNotificationPanel progressBanner;
  private @Nullable javax.swing.Timer progressTimer;
  /** Set when the file exceeds IntelliJ's content-loading limit: the document is only a preview. */
  private final @Nullable VirtualFile largeFile;
  private int generation;
  private boolean disposed;

  ModelLoader(@NotNull Project project, @NotNull Document document, @NotNull JComponent owner,
              @NotNull IntConsumer navigator, @NotNull Client client, @NotNull Disposable parent) {
    this.project = project;
    this.document = document;
    this.owner = owner;
    this.navigator = navigator;
    this.client = client;
    Disposer.register(parent, this);
    banners.setLayout(new BoxLayout(banners, BoxLayout.Y_AXIS));
    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
    largeFile = file != null && SingleRootFileViewProvider.isTooLargeForContentLoading(file) ? file : null;
    if (largeFile != null) {
      showReadOnlyBanner();
      // No document events for the real content: watch the file instead.
      ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
        @Override
        public void after(@NotNull java.util.List<? extends @NotNull VFileEvent> events) {
          for (VFileEvent e : events) {
            if (largeFile.equals(e.getFile())) {
              dirty = true;
              if (owner.isShowing()) scheduleRefresh(DEBOUNCE_MS);
              return;
            }
          }
        }
      });
    }

    document.addDocumentListener(new DocumentListener() {
      @Override
      public void documentChanged(@NotNull DocumentEvent event) {
        dirty = true;
        if (owner.isShowing()) scheduleRefresh(DEBOUNCE_MS);
      }
    }, this);
    // Build lazily: only once the view is actually shown.
    owner.addHierarchyListener(e -> {
      if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && owner.isShowing() && dirty) scheduleRefresh(0);
    });
  }

  /** Banner container to place above the view. */
  JComponent banners() {
    return banners;
  }

  @Nullable XmlDocumentModel model() {
    return model;
  }

  private void scheduleRefresh(int delay) {
    refreshAlarm.cancelAllRequests();
    refreshAlarm.addRequest(this::refresh, delay);
  }

  /** Whether value editing is possible now: a writable document and an up-to-date, well-formed model. */
  boolean canEdit() {
    XmlDocumentModel m = model;
    return largeFile == null && m != null && !m.hasErrors() && document.isWritable() && m.text().contentEquals(document.getImmutableCharSequence());
  }

  /**
   * Applies a value edit to the document as one undoable command and refreshes immediately.
   * Returns null on success, or the reason the edit was refused.
   */
  @Nullable String applyValueEdit(@NotNull ValueEdits.Target target, @NotNull String value) {
    XmlDocumentModel m = model;
    if (m == null) return "The view is still loading.";
    if (largeFile != null) return "This file is too large for IntelliJ to edit, so the view is read-only.";
    if (!m.text().contentEquals(document.getImmutableCharSequence())) {
      return "The document changed since the view was last updated; try again in a moment.";
    }
    ValueEdits.Result r = ValueEdits.compute(m, target, value);
    if (!r.isOk()) return r.error();
    ValueEdits.TextEdit e = Objects.requireNonNull(r.edit());
    if (e.length() == 0 && e.text().isEmpty()) return null;
    if (!FileDocumentManager.getInstance().requestWriting(document, project)) return "The file is read-only.";
    WriteCommandAction.runWriteCommandAction(project, ValueEdits.label(m, target), null,
                                             () -> document.replaceString(e.offset(), e.offset() + e.length(), e.text()));
    // Our own edit: rebuild now rather than after the typing debounce.
    refreshAlarm.cancelAllRequests();
    refresh();
    return null;
  }

  /** Rebuilds the model on a pooled thread (shared with the file's other tab); newer requests supersede older ones. */
  void refresh() {
    long size = largeFile != null ? largeFile.getLength() : document.getTextLength();
    if (size > LARGE_FILE_CHARS && !largeFileAccepted) {
      showLargeFileBanner();
      return;
    }
    dirty = false;
    SharedModels.Build b;
    if (largeFile != null) {
      VirtualFile f = largeFile;
      b = SharedModels.request(f, f.getModificationStamp(), () -> loadWholeFile(f));
    }
    else {
      CharSequence text = document.getImmutableCharSequence();
      b = SharedModels.request(document, document.getModificationStamp(), () -> text);
    }
    int gen = ++generation;
    if (!b.future.isDone()) startProgress(b, gen);
    b.future.whenComplete((built, err) -> ApplicationManager.getApplication().invokeLater(() -> {
      if (disposed || gen != generation) return;
      stopProgress();
      if (built != null) apply(built);
      else if (err != null && !(err instanceof java.util.concurrent.CancellationException)) showLoadFailure(err);
    }, ModalityState.any()));
  }

  private static CharSequence loadWholeFile(VirtualFile f) {
    // contentsToByteArray() refuses files over the limit; streaming reads them from disk.
    try (java.io.InputStream in = f.getInputStream()) {
      return LoadTextUtil.getTextByBinaryPresentation(in.readAllBytes(), f);
    }
    catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private void startProgress(SharedModels.Build b, int gen) {
    stopProgress();
    javax.swing.Timer t = new javax.swing.Timer(200, null);
    t.setInitialDelay(PROGRESS_DELAY_MS);
    t.addActionListener(e -> {
      if (disposed || gen != generation || b.future.isDone()) {
        stopProgress();
        return;
      }
      if (progressBanner == null) {
        progressBanner = new EditorNotificationPanel(EditorNotificationPanel.Status.Info);
        banners.add(progressBanner);
        banners.revalidate();
      }
      progressBanner.setText("Loading XML… " + Math.round(b.fraction * 100) + "%");
    });
    progressTimer = t;
    t.start();
  }

  private void stopProgress() {
    if (progressTimer != null) {
      progressTimer.stop();
      progressTimer = null;
    }
    if (progressBanner != null) {
      banners.remove(progressBanner);
      progressBanner = null;
      banners.revalidate();
      banners.repaint();
    }
  }

  private void showLoadFailure(Throwable err) {
    hideErrorBanner();
    EditorNotificationPanel p = new EditorNotificationPanel(EditorNotificationPanel.Status.Error);
    Throwable cause = err.getCause() != null ? err.getCause() : err;
    p.setText("Could not load the XML view: " + StringUtil.trimLog(String.valueOf(cause.getMessage()), 200));
    p.createActionLabel("Retry", this::refresh);
    errorBanner = p;
    banners.add(p);
    banners.revalidate();
  }

  private void showReadOnlyBanner() {
    EditorNotificationPanel p = new EditorNotificationPanel(EditorNotificationPanel.Status.Info);
    p.setText("This file (" + StringUtil.formatFileSize(largeFile.getLength())
              + ") is larger than IntelliJ loads into an editor, so it is shown in full here but read-only.");
    banners.add(p);
  }

  /** Swaps in a newly built model. Malformed documents keep the last good model. */
  @TestOnly
  void apply(@NotNull XmlDocumentModel built) {
    if (built.hasErrors()) {
      showErrorBanner(built.errors().get(0), model != null);
      if (model != null) return;
    }
    else {
      hideErrorBanner();
    }
    XmlDocumentModel previous = model;
    model = built;
    client.modelChanged(previous, built);
  }

  private void showErrorBanner(ParseError e, boolean keptLastGood) {
    hideErrorBanner();
    EditorNotificationPanel p = new EditorNotificationPanel(EditorNotificationPanel.Status.Warning);
    p.setText("XML is not well-formed at line " + e.line() + ", column " + e.column() + ": "
              + StringUtil.trimLog(e.message(), 200) + (keptLastGood ? " Showing the last valid version." : ""));
    p.createActionLabel("Go to error", () -> navigator.accept(Math.min(e.offset(), document.getTextLength())));
    errorBanner = p;
    banners.add(p);
    banners.revalidate();
  }

  private void hideErrorBanner() {
    if (errorBanner != null) {
      banners.remove(errorBanner);
      errorBanner = null;
      banners.revalidate();
      banners.repaint();
    }
  }

  private void showLargeFileBanner() {
    if (largeBanner != null) return;
    EditorNotificationPanel p = new EditorNotificationPanel(EditorNotificationPanel.Status.Info);
    p.setText("This file is " + StringUtil.formatFileSize(largeFile != null ? largeFile.getLength() : document.getTextLength())
              + ". Building the view may take a while and use a lot of memory.");
    p.createActionLabel("Load anyway", () -> {
      largeFileAccepted = true;
      banners.remove(p);
      largeBanner = null;
      banners.revalidate();
      refresh();
    });
    largeBanner = p;
    banners.add(p);
    banners.revalidate();
  }

  boolean isShowingLargeFileNotice() {
    return largeBanner != null;
  }

  boolean isShowingErrorBanner() {
    return errorBanner != null;
  }

  boolean isReadOnlyLargeFile() {
    return largeFile != null;
  }

  @Override
  public void dispose() {
    disposed = true;
    stopProgress();
  }
}
