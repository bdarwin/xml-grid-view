# XML Grid View for IntelliJ

This plugin shows a read-only XML tree and grid next to the text editor. It is
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
2. Use the **Editor / Split / Viewer** toggle in the editor's top-right toolbar
   to show the grid.

| Area | Behavior |
| --- | --- |
| Tree | Each node shows the tag, key attributes (`id`, `name`, `key`) and the number of element children. Type to speed-search. **Enter**/**F4** jumps to the element in the text editor. |
| Grid | Shows the selected element's children for one tag group; pick the group in the combo box when there is more than one. Click a header to sort. Right-click a header to filter: text and a distinct-values checklist capped at 1,000 values. Filters combine with AND. A funnel icon marks filtered columns, and **Clear filters** resets them. Rows are numbered, and the grid supports multi-cell selection and **Copy** as TSV (context menu: *Copy with Header*). Double-click a `{tag ×n}` cell, or press **Alt+Down**, to drill down. **Enter**/**F4** jumps to the source. |
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
  DOM, mapped back by child-index path), column filters, and the canonical JSON
  serializer used by the tests.
- `editor/`: the `FileEditorProvider`, which wraps the text editor in a
  `TextEditorWithPreview`, and the viewer `FileEditor`.
- `ui/`: the panel (`OnePixelSplitter`, banners), the tree
  (`StructureTreeModel` + `AsyncTreeModel`), the grid (`JBTable`), the find bar
  and the filter popup.

## Known limitations

- Error locations come from the JDK SAX parser, because PSI recovers silently.
  Columns can differ from the VS Code view (saxes). Golden tests compare only
  the line.
- While the document is malformed, the last valid model is shown. Its offsets
  can be slightly stale until the XML is valid again.
- PSI is built without the IDE's file-size limit, so loading a very large file
  ("Load anyway") needs a lot of memory.
