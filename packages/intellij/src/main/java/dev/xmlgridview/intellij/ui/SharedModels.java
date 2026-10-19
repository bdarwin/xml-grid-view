package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * One model build per document version, shared by the Grid and Flat tabs of the same file.
 * Builds run on a pooled thread without a read action (the parser works on an immutable
 * snapshot of the text). A newer version cancels a build still running for an older one.
 * Finished models are only weakly referenced here; the views hold them.
 */
final class SharedModels {
  private static final Logger LOG = Logger.getInstance(SharedModels.class);
  private static final Key<Entry> KEY = Key.create("XmlGridView.sharedModel");

  private SharedModels() {
  }

  /** A build in progress or finished. */
  static final class Build {
    final long stamp;
    final CompletableFuture<XmlDocumentModel> future = new CompletableFuture<>();
    final ProgressIndicatorBase indicator = new ProgressIndicatorBase();
    volatile double fraction;

    Build(long stamp) {
      this.stamp = stamp;
    }
  }

  private record Entry(long stamp, Build running, WeakReference<XmlDocumentModel> done) {
  }

  /**
   * The model for version {@code stamp} of {@code holder} (a Document, or a VirtualFile for files
   * too large for a Document): joins a running build, reuses a finished one, or starts a new one
   * that reads the text from {@code text} on the pooled thread.
   */
  static @NotNull Build request(@NotNull UserDataHolder holder, long stamp, @NotNull Supplier<CharSequence> text) {
    synchronized (holder) {
      Entry e = holder.getUserData(KEY);
      if (e != null && e.stamp == stamp) {
        if (e.running != null && !e.running.future.isCancelled()) return e.running;
        XmlDocumentModel m = e.done == null ? null : e.done.get();
        if (m != null) {
          Build b = new Build(stamp);
          b.fraction = 1;
          b.future.complete(m);
          return b;
        }
      }
      if (e != null && e.running != null && !e.running.future.isDone()) e.running.indicator.cancel();
      Build b = new Build(stamp);
      holder.putUserData(KEY, new Entry(stamp, b, null));
      AppExecutorUtil.getAppExecutorService().execute(() -> run(holder, b, text));
      return b;
    }
  }

  private static void run(UserDataHolder holder, Build b, Supplier<CharSequence> text) {
    try {
      XmlDocumentModel m = ProgressManager.getInstance().runProcess(
        () -> XmlModelBuilder.build(text.get(), f -> b.fraction = f), b.indicator);
      synchronized (holder) {
        Entry e = holder.getUserData(KEY);
        if (e != null && e.running == b) holder.putUserData(KEY, new Entry(b.stamp, null, new WeakReference<>(m)));
      }
      b.future.complete(m);
    }
    catch (ProcessCanceledException e) {
      b.future.cancel(false);
    }
    catch (Throwable t) {
      LOG.warn("Building the XML model failed", t);
      b.future.completeExceptionally(t);
    }
  }
}
