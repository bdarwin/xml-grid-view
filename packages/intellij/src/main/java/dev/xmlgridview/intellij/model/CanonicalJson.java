package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes the model to the canonical fixture JSON described in
 * fixtures/SCHEMA.md. Values are built as Map/List/String/Number/Boolean/null
 * and written with {@link #write(Object)}.
 */
public final class CanonicalJson {
  private CanonicalJson() {
  }

  public static @Nullable Map<String, Object> tree(@NotNull XmlDocumentModel model) {
    return model.root() == null ? null : node(model.root());
  }

  private static Map<String, Object> node(XNode n) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("tag", n.tag());
    m.put("ns", n.ns());
    m.put("path", n.path());
    m.put("start", n.start());
    m.put("end", n.end());
    List<Object> attrs = new ArrayList<>();
    for (XAttr a : n.attrs()) {
      Map<String, Object> am = new LinkedHashMap<>();
      am.put("name", a.name());
      am.put("value", a.value());
      am.put("start", a.start());
      am.put("end", a.end());
      attrs.add(am);
    }
    m.put("attrs", attrs);
    m.put("text", n.text());
    List<Object> children = new ArrayList<>();
    for (XNode c : n.children()) children.add(node(c));
    m.put("children", children);
    return m;
  }

  /**
   * Canonical form of the value inspector's JSON handling for one node text
   * ({@code fixtures/json/<name>.json}).
   */
  public static @NotNull Map<String, Object> jsonValue(@NotNull String text) {
    Json.Detection d = Json.detect(text);
    Map<String, Object> out = new LinkedHashMap<>();
    if (!d.isJson()) {
      out.put("kind", "text");
      out.put("hasError", d.hasError());
      return out;
    }
    out.put("kind", "json");
    out.put("pretty", d.pretty());
    Map<String, Object> grids = new LinkedHashMap<>();
    Json.forEachContainer(d.value(), (node, path) -> {
      JsonTable t = JsonTable.of(node);
      List<Object> cols = new ArrayList<>();
      for (GridColumn c : t.columns()) cols.add(map("key", c.key(), "kind", c.kind().id()));
      List<Object> rows = new ArrayList<>();
      for (int r = 0; r < t.rowCount(); r++) {
        List<Object> row = new ArrayList<>();
        for (int c = 0; c < t.columnCount(); c++) {
          Object v = t.cell(r, c);
          row.add(v instanceof JsonTable.Drill dr ? map("drill", dr.kind(), "count", dr.count()) : v);
        }
        rows.add(row);
      }
      grids.put(Json.pointer(path), map("columns", cols, "keys", new ArrayList<Object>(t.rowKeys()), "rows", rows));
    });
    out.put("grids", grids);
    return out;
  }

  public static @NotNull Map<String, Object> grids(@NotNull XmlDocumentModel model) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (XNode el : model.elements()) {
      if (el.children().isEmpty()) continue;
      List<GroupInfo> groups = XmlDocumentModel.groups(el);
      List<Object> gl = new ArrayList<>();
      Map<String, Object> grids = new LinkedHashMap<>();
      for (GroupInfo g : groups) {
        gl.add(map("tag", g.tag(), "count", g.count()));
        GridTable t = model.table(el, g.tag());
        List<Object> cols = new ArrayList<>();
        for (GridColumn c : t.columns()) cols.add(map("key", c.key(), "kind", c.kind().id()));
        List<Object> rows = new ArrayList<>();
        for (int r = 0; r < t.rowCount(); r++) {
          List<Object> row = new ArrayList<>();
          for (int c = 0; c < t.columnCount(); c++) {
            Object v = t.cell(r, c);
            row.add(v instanceof Integer n ? map("drill", t.columns().get(c).label(), "count", n) : v);
          }
          rows.add(row);
        }
        grids.put(g.tag(), map("columns", cols, "rows", rows));
      }
      out.put(el.path(), map("groups", gl, "grids", grids));
    }
    return out;
  }

  /** Flat view rows, fully expanded (fixtures {@code <name>.flat.json}). */
  public static @NotNull List<Object> flat(@NotNull XmlDocumentModel model) {
    List<Object> out = new ArrayList<>();
    for (FlatRow r : FlatRow.build(model)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("depth", r.depth());
      m.put("kind", r.kind().id());
      m.put("name", r.name());
      m.put("value", r.value());
      m.put("path", r.node().path());
      out.add(m);
    }
    return out;
  }

  /** Hits for a search case, or {@code {"error": true}} for an invalid query. */
  public static @NotNull Object searchCase(@NotNull XmlDocumentModel model, @NotNull String query,
                                           @NotNull SearchOptions options, @NotNull String scope,
                                           @NotNull String target, @Nullable String gridPath, @Nullable String gridGroup) {
    Matcher m;
    try {
      m = Matcher.create(query, options);
    } catch (InvalidQueryException e) {
      return map("error", true);
    }
    List<Object> out = new ArrayList<>();
    if (m == null) return out;
    SearchTargets targets = SearchTargets.of(target);
    if (scope.equals("document")) {
      for (DocMatch d : SearchEngine.searchDocument(model, m, targets).matches()) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("path", d.node().path());
        hit.put("target", d.target().id());
        hit.put("attr", d.attrIndex() >= 0 ? d.node().attrs().get(d.attrIndex()).name() : null);
        hit.put("ranges", ranges(m, d.field()));
        out.add(hit);
      }
      return out;
    }
    XNode owner = model.byPath(gridPath);
    if (owner == null) throw new IllegalArgumentException("No element at " + gridPath);
    GridTable t = model.table(owner, gridGroup);
    for (GridMatch g : SearchEngine.searchGrid(t, m, targets)) {
      Map<String, Object> hit = new LinkedHashMap<>();
      hit.put("row", g.row());
      hit.put("column", t.columns().get(g.col()).key());
      hit.put("ranges", ranges(m, t.searchText(g.row(), g.col())));
      out.add(hit);
    }
    return out;
  }

  public static @NotNull Object xpathCase(@NotNull XmlDocumentModel model, @NotNull String expr) {
    XPathEngine.Result r = XPathEngine.evaluate(model, expr);
    if (r.error() != null) return map("error", true);
    if (r.scalar() != null) return map("scalar", r.scalar());
    List<Object> nodes = new ArrayList<>();
    for (XPathEngine.Item i : r.items()) {
      Map<String, Object> n = new LinkedHashMap<>();
      n.put("path", i.node().path());
      n.put("kind", i.kind().id());
      n.put("attr", i.attrName());
      nodes.add(n);
    }
    return map("nodes", nodes);
  }

  public static @Nullable Map<String, Object> errors(@NotNull XmlDocumentModel model) {
    if (!model.hasErrors()) return null;
    ParseError e = model.errors().get(0);
    return map("hasErrors", true, "firstError", map("line", e.line(), "column", e.column()));
  }

  private static List<Object> ranges(Matcher m, String s) {
    List<Object> out = new ArrayList<>();
    for (int[] r : m.ranges(s)) out.add(List.of(r[0], r[1]));
    return out;
  }

  private static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String)kv[i], kv[i + 1]);
    return m;
  }

  /** Compact JSON text for a value tree. */
  public static @NotNull String write(@Nullable Object v) {
    StringBuilder sb = new StringBuilder();
    write(v, sb);
    return sb.toString();
  }

  private static void write(@Nullable Object v, StringBuilder sb) {
    if (v == null) {
      sb.append("null");
    }
    else if (v instanceof String s) {
      quote(s, sb);
    }
    else if (v instanceof Boolean b) {
      sb.append(b);
    }
    else if (v instanceof Double d) {
      if (d.isNaN() || d.isInfinite()) sb.append("null");
      else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.longValue());
      else sb.append(d);
    }
    else if (v instanceof Number n) {
      sb.append(n);
    }
    else if (v instanceof Map<?, ?> m) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) sb.append(',');
        first = false;
        quote(String.valueOf(e.getKey()), sb);
        sb.append(':');
        write(e.getValue(), sb);
      }
      sb.append('}');
    }
    else if (v instanceof List<?> l) {
      sb.append('[');
      for (int i = 0; i < l.size(); i++) {
        if (i > 0) sb.append(',');
        write(l.get(i), sb);
      }
      sb.append(']');
    }
    else {
      quote(v.toString(), sb);
    }
  }

  private static void quote(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) sb.append(String.format("\\u%04x", (int)c));
          else sb.append(c);
        }
      }
    }
    sb.append('"');
  }
}
