package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds grids following the column rules in fixtures/SCHEMA.md. */
final class GridBuilder {
  private GridBuilder() {
  }

  static GridTable build(XNode element, @Nullable String group, List<GroupInfo> groups) {
    List<XNode> rows = new ArrayList<>();
    if (group == null) rows.add(element);
    else for (XNode c : element.children()) if (c.tag().equals(group)) rows.add(c);

    Set<String> attrNames = new LinkedHashSet<>();
    Map<String, Boolean> childSimple = new LinkedHashMap<>();
    boolean hasText = false;
    for (XNode row : rows) {
      for (XAttr a : row.attrs()) attrNames.add(a.name());
      Set<String> seen = new HashSet<>();
      for (XNode c : row.children()) {
        Boolean simple = childSimple.get(c.tag());
        if (simple == null) simple = Boolean.TRUE;
        if (simple && (!c.isLeaf() || !seen.add(c.tag()))) simple = Boolean.FALSE;
        childSimple.put(c.tag(), simple);
      }
      if (!row.text().isEmpty()) hasText = true;
    }

    List<GridColumn> columns = new ArrayList<>();
    for (String a : attrNames) columns.add(new GridColumn("@" + a, "@" + a, GridColumn.Kind.ATTR));
    childSimple.forEach((tag, simple) ->
      columns.add(new GridColumn(tag, tag, simple ? GridColumn.Kind.LEAF : GridColumn.Kind.COMPLEX)));
    if (hasText) columns.add(new GridColumn(GridColumn.TEXT_KEY, GridColumn.TEXT_KEY, GridColumn.Kind.TEXT));

    Map<String, Integer> colIndex = new HashMap<>();
    for (int i = 0; i < columns.size(); i++) colIndex.put(columns.get(i).key(), i);

    Object[][] cells = new Object[rows.size()][columns.size()];
    for (int r = 0; r < rows.size(); r++) {
      XNode row = rows.get(r);
      Object[] line = cells[r];
      for (XAttr a : row.attrs()) line[colIndex.get("@" + a.name())] = a.value();
      for (XNode c : row.children()) {
        int ci = colIndex.get(c.tag());
        if (columns.get(ci).kind() == GridColumn.Kind.LEAF) line[ci] = c.text();
        else line[ci] = line[ci] == null ? 1 : (Integer) line[ci] + 1;
      }
      if (hasText && !row.text().isEmpty()) line[columns.size() - 1] = row.text();
    }
    return new GridTable(element, group, groups, columns, rows, cells);
  }
}
