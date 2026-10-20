package dev.xmlgridview.intellij.ui;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Errors and warnings the IDE has highlighted in the document (XML schema and DTD validation,
 * inspections), mapped to elements. They come from the document's highlighting, so they are as
 * current as the last analysis of the file (the Text tab analyses it while open). Refreshed
 * when the analysis finishes and when the model is rebuilt.
 */
final class DocumentProblems {
  record Problem(int offset, int end, boolean error, @NotNull String message) {
  }

  /** A problem and the element it belongs to. */
  record Located(@NotNull XNode node, @NotNull Problem problem) {
  }

  static final Color ERROR = JBColor.RED;
  static final Color WARNING = new JBColor(0xBF8803, 0xC9A23A);

  private final Project project;
  private final Document document;
  private final Runnable onChange;
  private @Nullable XmlDocumentModel model;
  private List<Located> ordered = List.of();
  private Map<XNode, List<Problem>> byNode = Map.of();
  private Set<XNode> within = Set.of();

  DocumentProblems(@NotNull Project project, @NotNull Document document, @NotNull Disposable parent, @NotNull Runnable onChange) {
    this.project = project;
    this.document = document;
    this.onChange = onChange;
    project.getMessageBus().connect(parent).subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, new DaemonCodeAnalyzer.DaemonListener() {
      @Override
      public void daemonFinished(@NotNull Collection<? extends FileEditor> fileEditors) {
        ApplicationManager.getApplication().invokeLater(() -> refresh(model), project.getDisposed());
      }
    });
  }

  /** Re-reads the highlights and maps them onto {@code m}'s elements. */
  void refresh(@Nullable XmlDocumentModel m) {
    model = m;
    List<Problem> found = new ArrayList<>();
    if (m != null && !project.isDisposed()) {
      int length = document.getTextLength();
      DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.WEAK_WARNING, 0, length, info -> {
        if (info.getDescription() != null && info.getSeverity().compareTo(HighlightSeverity.WARNING) >= 0) {
          found.add(new Problem(info.getStartOffset(), info.getEndOffset(), info.getSeverity().compareTo(HighlightSeverity.ERROR) >= 0,
                                info.getDescription()));
        }
        return true;
      });
    }
    set(m, found);
  }

  /** Replaces the problems (also used by tests). */
  void set(@Nullable XmlDocumentModel m, @NotNull List<Problem> problems) {
    model = m;
    List<Located> list = new ArrayList<>();
    Map<XNode, List<Problem>> map = new HashMap<>();
    Set<XNode> anc = new HashSet<>();
    if (m != null) {
      List<Problem> sorted = new ArrayList<>(problems);
      sorted.sort((a, b) -> Integer.compare(a.offset(), b.offset()));
      for (Problem p : sorted) {
        XNode n = m.elementAt(p.offset());
        if (n == null) continue;
        list.add(new Located(n, p));
        map.computeIfAbsent(n, k -> new ArrayList<>()).add(p);
        for (XNode a = n.parent(); a != null && anc.add(a); a = a.parent()) {
          // all ancestors
        }
      }
    }
    boolean changed = !list.equals(ordered);
    ordered = List.copyOf(list);
    byNode = map;
    within = anc;
    if (changed) onChange.run();
  }

  @NotNull List<Located> ordered() {
    return ordered;
  }

  int errorCount() {
    int n = 0;
    for (Located l : ordered) if (l.problem().error()) n++;
    return n;
  }

  @NotNull List<Problem> of(@NotNull XNode node) {
    return byNode.getOrDefault(node, List.of());
  }

  /** True when an element below {@code node} has a problem. */
  boolean within(@NotNull XNode node) {
    return within.contains(node);
  }

  static boolean anyError(@NotNull List<Problem> ps) {
    for (Problem p : ps) if (p.error()) return true;
    return false;
  }

  static @Nullable Color color(@NotNull List<Problem> ps) {
    return ps.isEmpty() ? null : anyError(ps) ? ERROR : WARNING;
  }

  static @Nullable String tooltip(@NotNull List<Problem> ps) {
    if (ps.isEmpty()) return null;
    StringBuilder b = new StringBuilder("<html>");
    for (Problem p : ps) {
      if (b.length() > 6) b.append("<br>");
      b.append(com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(p.message()));
    }
    return b.append("</html>").toString();
  }
}
