package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import dev.xmlgridview.intellij.model.DocumentDiff;
import dev.xmlgridview.intellij.model.GridTable;
import dev.xmlgridview.intellij.model.JsonModelBuilder;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What changed against a baseline (the last commit, or the text when the file was opened), mapped
 * onto the current model's elements: added (with their descendants) and modified elements, the
 * ancestors of changes, and removed elements by parent and by the element that now follows them.
 * The baseline comes from {@link ChangeBaseline}; the diff runs off the EDT and {@code onChange}
 * is called on the EDT when the marks change.
 */
final class DocumentChanges {
  enum Kind { ADDED, MODIFIED, REMOVED }

  record Mark(@NotNull Kind kind, @NotNull String tooltip) {
  }

  /** A place to go to with next/previous: a changed element, or a gap where elements were removed. */
  record Stop(@NotNull XNode node, @NotNull List<DocumentDiff.Removed> removed, @Nullable XNode parent) {
    boolean isRemoval() {
      return !removed.isEmpty();
    }
  }

  /** Gutter colours of the editor scheme (the colours IntelliJ uses for changed lines). */
  static Color added() {
    return gutter(EditorColors.ADDED_LINES_COLOR, new JBColor(0x6AAB73, 0x549159));
  }

  static Color modified() {
    return gutter(EditorColors.MODIFIED_LINES_COLOR, new JBColor(0x6C8FC9, 0x43698D));
  }

  /** Red, not the scheme's deleted-lines colour (grey in the light themes), so removals stand out. */
  static Color removed() {
    return new JBColor(0xE5534B, 0xD0675F);
  }

  private static Color gutter(com.intellij.openapi.editor.colors.ColorKey key, Color fallback) {
    Color c = EditorColorsManager.getInstance().getGlobalScheme().getColor(key);
    return c != null ? c : fallback;
  }

  /** The colour as a tint over {@code bg}, for cell backgrounds (gutter colours are already soft in light themes). */
  static Color tint(@NotNull Color c, @NotNull Color bg) {
    return ColorUtil.mix(bg, c, 0.5);
  }

  /** Text colour for a changed name: the colours IntelliJ uses for changed files. */
  static Color text(@NotNull Kind k) {
    return switch (k) {
      case ADDED -> com.intellij.openapi.vcs.FileStatus.ADDED.getColor() != null ? com.intellij.openapi.vcs.FileStatus.ADDED.getColor() : new JBColor(0x067D17, 0x73BD79);
      case MODIFIED -> com.intellij.openapi.vcs.FileStatus.MODIFIED.getColor() != null ? com.intellij.openapi.vcs.FileStatus.MODIFIED.getColor() : new JBColor(0x0032A0, 0x6897BB);
      case REMOVED -> removed();
    };
  }

  static Color color(@NotNull Kind k) {
    return switch (k) {
      case ADDED -> added();
      case MODIFIED -> modified();
      case REMOVED -> removed();
    };
  }

  private final Runnable onChange;
  private @Nullable XmlDocumentModel model;
  private @Nullable XmlDocumentModel baseModel;
  private @Nullable String label;
  private boolean explicit;
  private java.util.function.Consumer<String> onError = m -> {
  };

  /** Told (on the EDT) when a baseline the user chose can't be parsed. */
  void setErrorHandler(@NotNull java.util.function.Consumer<String> handler) {
    onError = handler;
  }
  private int generation;
  private Map<XNode, Mark> byNode = Map.of();
  private Map<XNode, DocumentDiff.Modified> details = Map.of();
  private Set<XNode> within = Set.of();
  /** Removed elements by current parent (the model's root key {@link #TOP} for top level). */
  private Map<Object, List<DocumentDiff.Removed>> removedByParent = Map.of();
  private Map<XNode, List<DocumentDiff.Removed>> removedBefore = Map.of();
  private List<Stop> stops = List.of();
  private int added;
  private int modifiedCount;
  private int removedCount;
  private boolean disposed;

  private static final Object TOP = new Object();

  DocumentChanges(@NotNull Disposable parent, @NotNull Runnable onChange) {
    this.onChange = onChange;
    Disposer.register(parent, () -> disposed = true);
  }

  /** Sets the baseline text ({@code null}: off) and what it is, e.g. "the last commit". */
  void setBaseline(@Nullable String text, @NotNull String label, boolean json) {
    setBaseline(text, label, json, false);
  }

  /** {@code explicit}: the user chose it (Compare With), so "no differences" is worth showing. */
  void setBaseline(@Nullable String text, @NotNull String label, boolean json, boolean explicit) {
    this.explicit = explicit;
    int gen = ++generation;
    if (text == null) {
      apply(gen, null, null, null);
      return;
    }
    run(() -> {
      XmlDocumentModel base = json ? JsonModelBuilder.build(text, null) : XmlModelBuilder.build(text, null);
      XmlDocumentModel cur = model;
      DocumentDiff.Changes c = base.hasErrors() || cur == null || cur.hasErrors() ? null : DocumentDiff.diff(base, cur);
      return () -> {
        if (base.hasErrors() && explicit && gen == generation) onError.accept(label + " is not well-formed.");
        apply(gen, base.hasErrors() ? null : base, label, c, cur);
      };
    });
  }

  /** The model was rebuilt: compares it with the baseline again. */
  void refresh(@Nullable XmlDocumentModel m) {
    model = m;
    int gen = ++generation;
    XmlDocumentModel base = baseModel;
    String l = label;
    if (base == null || m == null || m.hasErrors()) {
      apply(gen, base, l, null, m);
      return;
    }
    run(() -> {
      DocumentDiff.Changes c = DocumentDiff.diff(base, m);
      return () -> apply(gen, base, l, c, m);
    });
  }

  private void run(java.util.function.Supplier<Runnable> work) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      work.get().run();
      return;
    }
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      Runnable done = work.get();
      ApplicationManager.getApplication().invokeLater(() -> {
        if (!disposed) done.run();
      });
    });
  }

  private void apply(int gen, @Nullable XmlDocumentModel base, @Nullable String l, @Nullable DocumentDiff.Changes c) {
    apply(gen, base, l, c, model);
  }

  private void apply(int gen, @Nullable XmlDocumentModel base, @Nullable String l, @Nullable DocumentDiff.Changes c,
                     @Nullable XmlDocumentModel forModel) {
    if (gen != generation || forModel != model) return;
    baseModel = base;
    label = base == null ? null : l;
    Map<XNode, Mark> marks = new IdentityHashMap<>();
    Map<XNode, DocumentDiff.Modified> det = new IdentityHashMap<>();
    Set<XNode> anc = new HashSet<>();
    Map<Object, List<DocumentDiff.Removed>> byParent = new HashMap<>();
    Map<XNode, List<DocumentDiff.Removed>> before = new IdentityHashMap<>();
    // Stops by document position: an element at 2 * id, a gap before element k at 2 * k - 1.
    TreeMap<Integer, Stop> nav = new TreeMap<>();
    if (c != null && label != null && forModel != null) {
      List<XNode> els = forModel.elements();
      Mark addedMark = new Mark(Kind.ADDED, "Added since " + label);
      for (XNode n : c.added()) {
        for (int j = n.id(); j < els.size() && (j == n.id() || els.get(j).start() < n.end()); j++) marks.put(els.get(j), addedMark);
        markWithin(n, anc);
        nav.put(2 * n.id(), new Stop(n, List.of(), null));
      }
      for (DocumentDiff.Modified m : c.modified()) {
        det.put(m.node(), m);
        if (marks.containsKey(m.node())) continue;
        marks.put(m.node(), new Mark(Kind.MODIFIED, modifiedTooltip(m, label)));
        markWithin(m.node(), anc);
        nav.put(2 * m.node().id(), new Stop(m.node(), List.of(), null));
      }
      for (DocumentDiff.Removed r : c.removed()) {
        XNode p = r.parent();
        byParent.computeIfAbsent(p == null ? TOP : p, k -> new ArrayList<>()).add(r);
        XNode next = null;
        if (p != null && r.before() < p.childCount()) next = p.child(r.before());
        if (next != null) before.computeIfAbsent(next, k -> new ArrayList<>()).add(r);
        if (p != null) {
          anc.add(p);
          markWithin(p, anc);
        }
        int key;
        if (next != null) key = 2 * next.id() - 1;
        else {
          // After the parent's last descendant.
          int k = p == null ? els.size() : p.id() + 1;
          while (p != null && k < els.size() && els.get(k).start() < p.end()) k++;
          key = 2 * k - 1;
        }
        Stop s = nav.get(key);
        if (s != null) s.removed().add(r);
        else {
          List<DocumentDiff.Removed> items = new ArrayList<>();
          items.add(r);
          XNode at = next != null ? next : p != null ? p : forModel.root();
          if (at != null) nav.put(key, new Stop(at, items, p));
        }
      }
      added = c.added().size();
      modifiedCount = c.modified().size();
      removedCount = c.removed().size();
    }
    else {
      added = modifiedCount = removedCount = 0;
    }
    byNode = marks;
    details = det;
    within = anc;
    removedByParent = byParent;
    removedBefore = before;
    stops = List.copyOf(nav.values());
    onChange.run();
  }

  private static void markWithin(XNode n, Set<XNode> anc) {
    for (XNode a = n.parent(); a != null && anc.add(a); a = a.parent()) {
      // all ancestors
    }
  }

  /** Whether the user chose the baseline (Compare With). */
  boolean explicit() {
    return explicit;
  }

  /** Whether changes are shown (there is a usable baseline). */
  boolean active() {
    return label != null;
  }

  @Nullable String label() {
    return label;
  }

  int addedCount() {
    return added;
  }

  int modifiedCount() {
    return modifiedCount;
  }

  int removedCount() {
    return removedCount;
  }

  @NotNull List<Stop> stops() {
    return stops;
  }

  @Nullable Mark of(@NotNull XNode n) {
    return byNode.get(n);
  }

  @Nullable DocumentDiff.Modified details(@NotNull XNode n) {
    return details.get(n);
  }

  /** True when something below {@code n} changed. */
  boolean within(@NotNull XNode n) {
    return within.contains(n);
  }

  /** Removed children of {@code parent} (null: top level), in order. */
  @NotNull List<DocumentDiff.Removed> removedIn(@Nullable XNode parent) {
    return removedByParent.getOrDefault(parent == null ? TOP : parent, List.of());
  }

  /** Removed elements that {@code n} now directly follows. */
  @NotNull List<DocumentDiff.Removed> removedBefore(@NotNull XNode n) {
    return removedBefore.getOrDefault(n, List.of());
  }

  static String shortText(@NotNull String s) {
    String t = s.replaceAll("\\s+", " ").trim();
    if (t.isEmpty()) return "(empty)";
    return t.length() > 200 ? t.substring(0, 199) + "…" : t;
  }

  static String modifiedTooltip(@NotNull DocumentDiff.Modified m, @NotNull String label) {
    List<String> lines = new ArrayList<>();
    lines.add("Changed since " + label);
    m.attrs().forEach((name, was) -> lines.add(was == null ? "@" + name + ": new" : "@" + name + ": was " + shortText(was)));
    m.removedAttrs().forEach((name, was) -> lines.add("@" + name + ": removed (was " + shortText(was) + ")"));
    if (m.text() != null) lines.add(m.type() != null ? "Was: " + shortText(m.text()) + " (" + m.type() + ")" : "Was: " + shortText(m.text()));
    return html(lines);
  }

  static String removedTooltip(@NotNull List<DocumentDiff.Removed> rs, @NotNull String label) {
    List<String> lines = new ArrayList<>();
    lines.add("Removed since " + label + ":");
    for (DocumentDiff.Removed r : rs) lines.add(r.preview().isEmpty() ? r.name() : r.name() + ": " + r.preview());
    return html(lines);
  }

  static String html(List<String> lines) {
    StringBuilder b = new StringBuilder("<html>");
    for (int i = 0; i < lines.size(); i++) {
      if (i > 0) b.append("<br>");
      b.append(StringUtil.escapeXmlEntities(lines.get(i)));
    }
    return b.append("</html>").toString();
  }

  /** Changes on one grid: by cell (model row << 32 | model column), by model row, and rows removed above a model row. */
  record GridMarks(@NotNull Map<Long, Mark> cells, @NotNull Map<Integer, Mark> rows, @NotNull Map<Integer, String> removedAbove) {
    static final GridMarks NONE = new GridMarks(Map.of(), Map.of(), Map.of());
  }

  /**
   * A new row; a changed attribute or text in its column; a new, changed or removed child in the
   * child's column; and rows removed between the current ones (key = row count: below the last).
   * Mirrors gridChanges in packages/webview/src/changes.ts.
   */
  @NotNull GridMarks grid(@Nullable GridTable t) {
    if (t == null || label == null || (byNode.isEmpty() && removedByParent.isEmpty())) return GridMarks.NONE;
    Map<Long, Mark> cells = new HashMap<>();
    Map<Integer, Mark> rows = new HashMap<>();
    Map<Integer, String> removedAbove = new HashMap<>();
    String changed = "Changed since " + label;
    for (int r = 0; r < t.rowCount(); r++) {
      XNode e = t.rows().get(r);
      Mark own = byNode.get(e);
      if (own != null && own.kind() == Kind.ADDED) {
        rows.put(r, own);
        continue;
      }
      boolean any = false;
      DocumentDiff.Modified d = details.get(e);
      if (d != null) {
        for (Map.Entry<String, String> a : d.attrs().entrySet()) {
          any |= put(cells, t, r, "@" + a.getKey(), a.getValue() == null ? new Mark(Kind.ADDED, "New since " + label)
                                                                       : new Mark(Kind.MODIFIED, "Was: " + shortText(a.getValue())));
        }
        for (Map.Entry<String, String> a : d.removedAttrs().entrySet()) {
          any |= put(cells, t, r, "@" + a.getKey(), new Mark(Kind.REMOVED, "Removed since " + label + " (was " + shortText(a.getValue()) + ")"));
        }
        if (d.text() != null) any |= put(cells, t, r, "#text", new Mark(Kind.MODIFIED, "Was: " + shortText(d.text())));
        any = true;
      }
      for (int k = 0; k < e.childCount(); k++) {
        XNode ch = e.child(k);
        Mark m = byNode.get(ch);
        if (m != null && m.kind() == Kind.ADDED) any |= put(cells, t, r, ch.tag(), m);
        else if (m != null) {
          DocumentDiff.Modified cd = details.get(ch);
          boolean textOnly = cd != null && cd.text() != null && cd.attrs().isEmpty() && cd.removedAttrs().isEmpty();
          any |= put(cells, t, r, ch.tag(), new Mark(Kind.MODIFIED, textOnly ? "Was: " + shortText(cd.text()) : m.tooltip()));
        }
        else if (within.contains(ch)) any |= put(cells, t, r, ch.tag(), new Mark(Kind.MODIFIED, changed));
      }
      for (DocumentDiff.Removed rm : removedIn(e)) {
        any = true;
        int c = t.columnIndex(rm.name());
        if (c < 0) continue;
        long key = ((long)r << 32) | c;
        if (!cells.containsKey(key)) {
          cells.put(key, new Mark(t.cell(r, c) == null ? Kind.REMOVED : Kind.MODIFIED, removedTooltip(List.of(rm), label)));
        }
      }
      if (own != null || any || within.contains(e)) rows.put(r, new Mark(Kind.MODIFIED, own != null ? own.tooltip() : changed));
    }
    if (t.group() != null) {
      for (DocumentDiff.Removed rm : removedIn(t.element())) {
        if (!rm.name().equals(t.group())) continue;
        int r = 0;
        while (r < t.rowCount() && t.rows().get(r).index() < rm.before()) r++;
        String line = rm.preview().isEmpty() ? rm.name() : rm.name() + ": " + rm.preview();
        removedAbove.merge(r, "Removed since " + label + ":\n" + line, (a, b) -> a + "\n" + line);
      }
    }
    return new GridMarks(cells, rows, removedAbove);
  }

  private static boolean put(Map<Long, Mark> cells, GridTable t, int r, String key, Mark m) {
    int c = t.columnIndex(key);
    if (c >= 0) cells.putIfAbsent(((long)r << 32) | c, m);
    return true;
  }

  @TestOnly
  void setBaselineForTest(@Nullable String text, @NotNull String label) {
    setBaseline(text, label, model != null && model.isJson());
  }
}
