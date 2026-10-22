# Changelog

## 0.5.0

- **JSON support:** `.json`, `.jsonc` and `.geojson` files open in the same
  tree, grid and Flat views. Arrays of objects are grids; values, members,
  items, rows and columns can be edited, with types kept and formatting
  (including comments and trailing commas) preserved.
- **Grid editing like a spreadsheet:** insert, move and delete rows, add and
  delete columns, clear cells with Delete, and paste a block copied from
  Excel or Google Sheets (rows past the end become new elements). Each is
  one undo step.
- **Export:** the grid's visible rows as CSV or JSON.
- **Problems:** schema, DTD and other validation errors that the editor
  reports are marked in the tree, grid and Flat view. A counter steps
  through them.
- **Comments, processing instructions and the DOCTYPE** are shown in the
  tree and the Flat view.
- **Changes since the last commit:** added, changed and removed elements
  and values are highlighted in the tree, grid and Flat view, compared with
  the last git commit (or with the text when the file was opened). Elements
  are compared rather than lines, so reformatting does not count.

## 0.4.0

- **Structural editing:** right-click an element in the tree, the Grid or
  Flat to insert a child, sibling or duplicate, rename it, add or delete
  attributes, or delete it. This works in both VS Code and IntelliJ.
- **Element summary in the Grid:** the selected element's own attributes,
  single child values and text now show above the grid of its repeated
  children, instead of being hidden behind the group dropdown. Double-click
  a value to edit it, right-click for the structure menu. Chips with counts
  replace the dropdown for switching between child groups and (fields).
- **Formatting kept:** each structural edit is one undo step. Indentation is
  copied from the surrounding lines and CRLF files stay CRLF.
- **Checks:** names are validated, including that prefixes are declared, and
  the new element is selected afterwards. Both implementations are checked
  against the same fixtures (`fixtures/structure`).
- **IntelliJ Flat view:** the indent and the inspect icon area are now painted
  with the row's section colour. They used to show white patches.
- **IntelliJ Grid:** striped rows stay striped behind the inspect icon.
- **Visual tests:** screenshot tests for the web view and painted style checks
  for the IntelliJ panels guard against layout and colour regressions.
- **Large-file notice:** sizes under 1 MB show as KB, not "0.0 MB".

## 0.3.1

Large files load much faster in IntelliJ.

- **New parser:** IntelliJ now uses its own streaming XML parser instead of
  the IDE's PSI. A 50 MB file builds in about 0.6 s instead of 6 s, with
  about a fifth of the memory. Shared fixtures keep the output identical to
  VS Code, now including DOCTYPE, references and CRLF handling.
- **No UI freeze:** opening Flat on a large file no longer blocks the IDE for
  seconds while every row is measured. At 50 MB this went from 4 s to under
  0.1 s.
- **One build per version:** the Grid and Flat tabs share one model per
  document version. Building no longer holds the IDE's read lock, so typing
  is never blocked.
- **Progress banner:** slow loads show "Loading XML… N%".
- **Files over IntelliJ's 20 MB limit:** these are read in full from disk and
  shown read-only, instead of a truncated preview with a "not well-formed"
  error. The view refreshes when the file changes on disk.
- **Load failures:** a failed load now shows an error banner with Retry.
- **500 MB files:** the IntelliJ model is about a quarter of its old size
  (94 instead of 346 bytes per element). Text and attribute values are read
  from the file on demand, not copied, and Flat rows are packed into arrays.
  A 500 MB file now loads in about 9 s and uses about 2.5 GB, where it used
  to run out of memory. Selecting a node in a huge file no longer scans
  every row.
- **Memory check:** IntelliJ asks before loading only when a file may not fit
  in the IDE's free memory, instead of above a fixed 50 MB. If memory still
  runs out, the error banner says how to raise it.

### VS Code

- **Files over 50 MB:** these used to fail with "The editor could not be
  opened", because VS Code doesn't give extensions such large files as text
  documents. *Open With XML Grid View* now opens them in a read-only view that
  reads the file from disk and reloads it when the file changes.
- **Size limit:** files over 150 MB (`xmlGridView.maxFileSizeMB`) show a
  notice instead of crashing the view.

## 0.3.0

- **Fields grid:** selecting an element whose children don't repeat, such as
  a `<book>` with `title`, `author` and `price`, now shows one row with all of
  them as columns, instead of a `#text` grid of the first child only. The tag
  dropdown has a new **(fields)** entry; elements with repeating children
  still open on the first repeating tag.
- **Flat view:**
  - **Tree:** the element tree now sits beside the sheet. Selecting a node
    scrolls to it, the tree follows the sheet's selection, and the tree's
    filter box filters the sheet.
  - **Section colours:** each record gets a colour band, alternating between
    two colours, instead of zebra stripes. Element rows are tinted more
    strongly than value rows.
  - **Copy:** copied names no longer include indentation.
- The VS Code extension is now also published to Open VSX, for Cursor,
  VSCodium, Windsurf and other VS Code-based editors.

## 0.2.2

- The VS Code extension is now **XML Grid View Plus**
  (`bdarwin.xml-grid-view-plus`), because the name "XML Grid View" is taken
  on the VS Code Marketplace. The IntelliJ plugin keeps its name. No
  functional changes.

## 0.2.1

- Updated the marketplace descriptions. They now cover the Flat view, the
  value inspector and value editing; earlier ones still said "read-only".

## 0.2.0

- **Edit values.** Edit attribute values and leaf-element text in the Grid,
  in Flat and in the value inspector, in VS Code and IntelliJ.
  - **Starting an edit:** F2 or typing starts it. Enter commits, Esc cancels,
    Tab and Shift+Tab move.
  - **Inspector Edit mode:** JSON is validated before saving, with a Format
    JSON button.
  - **Escaping and CDATA:** values are escaped automatically, and CDATA is
    preserved.
  - **One undoable edit:** each change goes through the editor as a single
    undoable edit and is never auto-saved.
  - **Same edits in both editors:** shared golden fixtures (`fixtures/edits`)
    make VS Code and IntelliJ produce byte-identical edits.
- **VS Code:** new `xmlGridView.readOnly` setting. Files on read-only file
  systems are never editable.

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
