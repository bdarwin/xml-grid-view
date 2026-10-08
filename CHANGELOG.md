# Changelog

## 0.1.3

VS Code gets the same improvements as IntelliJ 0.1.2:

- Double-clicking a value opens the value inspector, in Grid cells and Flat
  values.
  - Double-clicking a Flat name or a row number goes to the source.
  - Double-clicking a `{tag ×n}` cell drills down.
- Flat: the Name column is measured to fit the visible names. It refits on
  expand/collapse; double-click the column edge to refit after resizing.
- Grid: a **Filter nodes** box above the tree. It matches tag names, attribute
  names and values, and text, keeps matches and their ancestors, and combines
  with Find's "show only matches".
- Value inspector: the JSON Tree and Grid tabs have a wrapped pane showing the
  selected item's full value.
- More colour, from your VS Code theme:
  - Tags (headers and tree), attribute names and attribute values are
    coloured.
  - The tree preview colours attribute names and values.
  - Grid and Flat rows have subtle stripes.

## 0.1.2

- IntelliJ: double-clicking a value opens the value inspector.
  - It opens one window per value, and another double-click brings that
    window to the front instead of opening a second.
  - Double-clicking a name goes to the source, and double-clicking a
    `{tag ×n}` cell drills down.
- IntelliJ Flat: the Name column fits its content. It refits on
  expand/collapse until you resize it; double-click the header to refit.
- IntelliJ Grid: a **Filter nodes** box above the tree. It matches tag names,
  attribute names and values, and text, and keeps matching nodes and their
  ancestors.
- IntelliJ value inspector: long text values wrap.
  - Grid cells wrap, up to six lines.
  - The Tree and Grid tabs have a wrapped pane showing the selected value in
    full.
- IntelliJ: more colour. Tags, attribute names and attribute values use your
  scheme's XML colours, with distinct fallbacks when a theme leaves them close
  to plain text. Rows have subtle stripes that keep the grid lines visible.

## 0.1.1

- First marketplace release for VS Code, Open VSX and JetBrains.
- MIT license, icons, and marketplace metadata.
- New **Flat** view: the whole document as an outline sheet with Name and
  Value columns.
  - One row per element; attributes appear as `@name` rows under their element.
  - Rows collapse and expand, with Expand all / Collapse all.
  - Copy as indented TSV.
  - Enter/F4 goes to the source.
  - Find, including XPath.
  - In VS Code it's a **Grid | Flat** switch; in IntelliJ it's a third bottom
    tab (**Text | Grid | Flat**).
- Flat view selects cells like a spreadsheet.
  - Drag or Shift-click any range of rows across the Name and/or Value
    columns, or click row numbers for whole rows.
  - Ctrl/Cmd+C copies exactly the selected cells.
- New **value inspector** for long or structured values.
  - Open it with **Shift+Enter** on a Grid cell, Flat value or tree node, or
    with the **⤢** button on long values.
  - Plain text shows in full, with wrapping, search and copy.
  - JSON is detected automatically, with **Tree / Grid / Text** tabs:
    - Tree: collapse/expand and search.
    - Grid: drill down through nested objects and arrays, with a breadcrumb,
      sort, filters and copy.
    - Text: pretty-printed.
  - Numbers keep their exact literal, so 20-digit IDs don't get rounded.
  - Broken JSON shows the parse error and its location.
- IntelliJ: visible grid lines in the Grid and Flat tables, and row numbers in
  Flat.
- IntelliJ: verified against 2024.3 through 2026.2, with no deprecated API
  usages.
- IntelliJ: the viewer is now a **Grid** tab at the bottom of the editor
  (**Text | Grid**), replacing the Editor / Split / Viewer toggle.

## 0.1.0

- First release: a read-only XML tree and grid, with search, XPath, live
  updates and a banner for malformed XML.
- VS Code extension, built on a shared web view.
- Native Swing plugin for IntelliJ Platform 2024.3+.
