package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
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
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.xmlgridview.intellij.model.ParseError;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;
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
 * off the EDT in a non-blocking read action. Malformed documents keep the last
 * good model and show a warning banner; very large files wait for "Load anyway".
 */
final class ModelLoader implements Disposable {
  static final long LARGE_FILE_CHARS = 50L * 1024 * 1024;
  static final int DEBOUNCE_MS = 300;

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

  ModelLoader(@NotNull Project project, @NotNull Document document, @NotNull JComponent owner,
              @NotNull IntConsumer navigator, @NotNull Client client, @NotNull Disposable parent) {
    this.project = project;
    this.document = document;
    this.owner = owner;
    this.navigator = navigator;
    this.client = client;
    Disposer.register(parent, this);
    banners.setLayout(new BoxLayout(banners, BoxLayout.Y_AXIS));

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
    return m != null && !m.hasErrors() && document.isWritable() && m.text().contentEquals(document.getImmutableCharSequence());
  }

  /**
   * Applies a value edit to the document as one undoable command and refreshes immediately.
   * Returns null on success, or the reason the edit was refused.
   */
  @Nullable String applyValueEdit(@NotNull ValueEdits.Target target, @NotNull String value) {
    XmlDocumentModel m = model;
    if (m == null) return "The view is still loading.";
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

  /** Rebuilds the model in a non-blocking read action; stale builds are cancelled by coalescing. */
  void refresh() {
    if (document.getTextLength() > LARGE_FILE_CHARS && !largeFileAccepted) {
      showLargeFileBanner();
      return;
    }
    dirty = false;
    CharSequence text = document.getImmutableCharSequence();
    ReadAction.nonBlocking(() -> XmlModelBuilder.build(project, text))
      .coalesceBy(this, document)
      .expireWith(this)
      .finishOnUiThread(ModalityState.any(), this::apply)
      .submit(AppExecutorUtil.getAppExecutorService());
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
    p.setText("This file is " + StringUtil.formatFileSize(document.getTextLength())
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

  @Override
  public void dispose() {
  }
}
