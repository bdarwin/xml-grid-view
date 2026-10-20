package dev.xmlgridview.intellij.model;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Builds the immutable node model from XML text with {@link XmlFastParser} (no PSI, no read
 * action needed), and collects well-formedness errors with {@link XmlErrorScanner}.
 */
public final class XmlModelBuilder {
  private XmlModelBuilder() {
  }

  /** Kept for callers that have a project at hand; the project is not needed. */
  public static @NotNull XmlDocumentModel build(@SuppressWarnings("unused") @Nullable Project project, @NotNull CharSequence text) {
    return build(text, null);
  }

  /**
   * Parses {@code text}. {@code progress} (may be null) receives the parsed fraction, 0..1,
   * from the calling thread; cancellation is checked through the current progress indicator.
   */
  public static @NotNull XmlDocumentModel build(@NotNull CharSequence text, @Nullable DoubleConsumer progress) {
    String s = text.toString();
    XmlFastParser.Result r = XmlFastParser.parse(s, progress == null ? null : f -> progress.accept(f * 0.9));
    List<ParseError> errors = XmlErrorScanner.scan(s);
    if (progress != null) progress.accept(1.0);
    return new XmlDocumentModel(r.root(), r.elements(), errors, s, r.misc());
  }
}
