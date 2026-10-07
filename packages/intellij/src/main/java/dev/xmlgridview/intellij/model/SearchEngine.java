package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Document and grid search with the field order defined in fixtures/SCHEMA.md. */
public final class SearchEngine {
  public static final int DEFAULT_LIMIT = 100_000;

  private SearchEngine() {
  }

  public record DocResult(List<DocMatch> matches, boolean truncated) {
  }

  public static @NotNull DocResult searchDocument(@NotNull XmlDocumentModel model, @NotNull Matcher m,
                                                  @NotNull SearchTargets t, int limit,
                                                  @NotNull BooleanSupplier cancelled) {
    List<DocMatch> out = new ArrayList<>();
    List<XNode> els = model.elements();
    for (int i = 0; i < els.size(); i++) {
      if ((i & 0xfff) == 0 && cancelled.getAsBoolean()) break;
      XNode el = els.get(i);
      if (t.names() && m.test(el.tag())) {
        out.add(new DocMatch(el, MatchTarget.NAME, -1, el.tag()));
        if (out.size() >= limit) return new DocResult(out, true);
      }
      if (t.attrNames() || t.attrValues()) {
        List<XAttr> attrs = el.attrs();
        for (int a = 0; a < attrs.size(); a++) {
          XAttr attr = attrs.get(a);
          if (t.attrNames() && m.test(attr.name())) {
            out.add(new DocMatch(el, MatchTarget.ATTR_NAME, a, attr.name()));
            if (out.size() >= limit) return new DocResult(out, true);
          }
          if (t.attrValues() && m.test(attr.value())) {
            out.add(new DocMatch(el, MatchTarget.ATTR_VALUE, a, attr.value()));
            if (out.size() >= limit) return new DocResult(out, true);
          }
        }
      }
      if (t.text() && !el.text().isEmpty() && m.test(el.text())) {
        out.add(new DocMatch(el, MatchTarget.TEXT, -1, el.text()));
        if (out.size() >= limit) return new DocResult(out, true);
      }
    }
    return new DocResult(out, false);
  }

  public static @NotNull DocResult searchDocument(@NotNull XmlDocumentModel model, @NotNull Matcher m,
                                                  @NotNull SearchTargets t) {
    return searchDocument(model, m, t, DEFAULT_LIMIT, () -> false);
  }

  /** Whether a column takes part in grid search for the given targets. */
  public static boolean columnEnabled(GridColumn c, SearchTargets t) {
    return switch (c.kind()) {
      case ATTR -> t.attrValues();
      case COMPLEX -> t.names();
      case LEAF, TEXT -> t.text();
    };
  }

  /** Matching cells, row by row, column by column, in model order. */
  public static @NotNull List<GridMatch> searchGrid(@NotNull GridTable table, @NotNull Matcher m, @NotNull SearchTargets t) {
    List<GridMatch> out = new ArrayList<>();
    int ncols = table.columnCount();
    boolean[] enabled = new boolean[ncols];
    for (int c = 0; c < ncols; c++) enabled[c] = columnEnabled(table.columns().get(c), t);
    for (int r = 0; r < table.rowCount(); r++) {
      for (int c = 0; c < ncols; c++) {
        if (enabled[c] && m.test(table.searchText(r, c))) out.add(new GridMatch(r, c));
      }
    }
    return out;
  }
}
