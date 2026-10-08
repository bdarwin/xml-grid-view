# XML Grid View for IntelliJ

This plugin shows an XML tree and grid next to the text editor, with value editing. It is
native Swing: it doesn't use JCEF or the web view, and the Gradle build doesn't
need Node.

- Platform: IntelliJ Platform 2024.3+ (`sinceBuild` 243)
- Dependencies: `com.intellij.modules.platform` and `com.intellij.modules.xml`
- Java: 21. Gradle provisions the toolchain through the foojay resolver.

## Build, test, run

```sh
cd packages/intellij
./gradlew buildPlugin   # -> build/distributions/xml-grid-view-intellij-<version>.zip
./gradlew test          # golden fixtures + model + light UI tests
./gradlew runIde        # sandbox IDE with the plugin installed
```

The first build downloads IntelliJ IDEA Community 2024.3.6, which is large. To
install the plugin, go to **Settings → Plugins → ⚙ → Install Plugin from
Disk…** and pick the zip.

The tests read the shared golden fixtures from `../../fixtures`. To use another
directory, pass `-PfixturesDir=/path/to/fixtures`. The TypeScript core
generates the expected files; see `fixtures/SCHEMA.md`.

## Using it

1. Open any `*.xml` file. It opens in the text editor as usual.
2. Click the **Grid** or **Flat** tab at the bottom of the editor. The tabs
   read **Text | Grid | Flat**, with Text selected by default. **Enter**/**F4**
   in either view switches back to Text with the caret on the element. To see
   source and a view side by side, use the editor tab's **Split Right** and pick
   a different bottom tab in each half.

| Area | Behavior |
| --- | --- |
| Tree | Each node shows the tag, key attributes (`id`, `name`, `key`) and the number of element children. Type to speed-search. **Enter**/**F4** jumps to the element in the text editor. |
| Grid | Shows the selected element's children for one tag group; pick the group in the combo box when there is more than one. Click a header to sort. Right-click a header to filter: text and a distinct-values checklist capped at 1,000 values. Filters combine with AND. A funnel icon marks filtered columns, and **Clear filters** resets them. Rows are numbered, and the grid supports multi-cell selection and **Copy** as TSV (context menu: *Copy with Header*). Double-click a `{tag ×n}` cell, or press **Alt+Down**, to drill down. **Enter**/**F4** jumps to the source. |
| Flat | An outline sheet of the whole document with **Name** and **Value** columns, fully expanded at first. Each element row is followed by its attributes (`@name`, shown muted) and then its children, indented by depth. Click the arrow or press **Left**/**Right** to collapse or expand; the context menu has *Expand All* / *Collapse All*. Values are shown on one line, with the full text in the tooltip. Multi-row selection, and **Copy** as TSV (`name<TAB>value`, two spaces of indentation per level). **Enter**/**F4** or double-click jumps to the element or attribute in the source. Find works the same as in the Grid tab, always across the whole document; *Show only matches* filters the sheet to matching rows and their ancestors. |
| Value inspector | **Shift+Enter** (or *Inspect Value* in the context menu) on a Grid cell, a Flat row or a tree node opens a resizable, non-modal window with the full value; long, multi-line or JSON-looking values also show a trailing icon in Grid and Flat cells that opens it on click. Plain text appears in a read-only, soft-wrapped editor (the editor's own **Ctrl/Cmd+F** works there); text that looks like JSON but doesn't parse gets a banner with the error location and *Go to error*. JSON gets three tabs: **Tree** (collapsible; search keys and values with "n of m", **Enter**/**Shift+Enter** and **F3**/**Shift+F3**; speed search; *Expand All* / *Collapse All*), **Grid** (arrays of objects as rows, nested objects/arrays as `{ n keys }` / `[ n items ]` cells; double-click or **Enter** drills in, the `$ › …` breadcrumb goes back; sort, quick filter, multi-cell copy) and **Text** (pretty-printed, read-only editor with JSON highlighting, folding and Find). Number literals are shown exactly as written. *Copy* copies the raw value, or the pretty JSON on the Text tab. |
| Editing values | Attribute values and the text of leaf elements (no element children) can be edited in place, Excel-style. Select a Grid cell or a Flat value and press **F2** or just start typing. **Enter** commits, **Escape** cancels, **Tab**/**Shift+Tab** commit and move. Mouse clicks never start editing; double-click still opens the value inspector. Editable cells are attribute cells whose attribute exists, leaf cells whose child exists, and `#text` of elements without child elements; `{tag ×n}` cells and absent values are not editable. Each edit is one undoable command (*Edit @id*, *Edit title*) applied to the document, so **Undo/Redo**, the modified marker and saving work as usual. Values are escaped automatically, and CDATA is kept when the value was CDATA. Edits are refused with a message when the file is read-only, the view is out of date, or the value contains comments. In the value inspector, **Edit** switches the Text tab to editing; **Save** (or **Ctrl/Cmd+Enter**) writes the value back; **Escape** leaves edit mode. JSON values must parse before they can be saved (the error shows line and column). Read-only files get no editing affordances. |
| Find (**Ctrl/Cmd+F**) | Search history; case, words and regex toggles; scope (current grid or whole document); target checkboxes; "n of m" counter; **Enter**/**Shift+Enter** and **F3**/**Shift+F3** to step through matches; a collapsible results list; *Show only matches* to filter the tree. XPath mode reports errors inline; the default namespace is bound to prefix `d`. |
| Malformed XML | The view keeps the last valid model and shows a warning banner with the error location. *Go to error* jumps there. |
| Files over 50 MB | The view asks for **Load anyway** before building. Tree children are always loaded lazily. |

The model is built from XML PSI in a `ReadAction.nonBlocking` task. Updates are
debounced by 300 ms after document changes and are coalesced, so a newer edit
cancels a stale build. Tree expansion, selection, the grid's element and group,
sort order and filters are restored by element path after each refresh.

## Layout

- `model/`: pure Java, no Swing. Contains the immutable node model built from
  PSI, the grid builder, search, XPath (`javax.xml.xpath` on a namespace-aware
  DOM, mapped back by child-index path), column filters, the Flat rows
  (`FlatRow`), the value inspector's JSON support (`Json`: a strict parser that
  keeps number literals verbatim, detection and pretty printing; `JsonTable`: JSON
  grids; `InspectTarget`: what to inspect for a cell/row/node), and the canonical
  JSON serializer used by the tests (including `fixtures/json`).
- `editor/`: two `FileEditorProvider`s, both `PLACE_AFTER_DEFAULT_EDITOR`: "Grid"
  and "Flat" (ordered after Grid via `order="after xmlGridView.grid"` in
  plugin.xml), their viewer `FileEditor`s, and the shared navigation to the
  Text tab.
- `ui/`: `ModelLoader` (lazy, debounced, non-blocking model builds, plus the
  malformed and large-file banners) shared by both views; the Grid panel
  (`OnePixelSplitter`), the tree (`StructureTreeModel` + `AsyncTreeModel`), the
  grid (`JBTable`), the filter popup; the Flat panel (`JBTable` with an indented,
  collapsible Name column); and the find bar.

## Known limitations

- Error locations come from the JDK SAX parser, because PSI recovers silently.
  Columns can differ from the VS Code view (saxes). Golden tests compare only
  the line.
- While the document is malformed, the last valid model is shown. Its offsets
  can be slightly stale until the XML is valid again.
- PSI is built without the IDE's file-size limit, so loading a very large file
  ("Load anyway") needs a lot of memory.
