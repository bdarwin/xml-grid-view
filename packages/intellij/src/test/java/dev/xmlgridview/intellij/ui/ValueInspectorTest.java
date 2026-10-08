package dev.xmlgridview.intellij.ui;

import java.util.ArrayList;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.application.ReadAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.model.FlatRow;
import dev.xmlgridview.intellij.model.GridTable;
import dev.xmlgridview.intellij.model.InspectTarget;
import dev.xmlgridview.intellij.model.Json;
import dev.xmlgridview.intellij.model.JsonTable;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import java.util.List;
import java.util.Map;

/** Value inspector: JSON model edge cases, inspector targets, and the Tree/Grid panels' behaviour. */
public class ValueInspectorTest extends BasePlatformTestCase {
  private static final String XML = """
    <catalog>
      <book id="bk101">
        <title>XML Guide</title>
        <description>{"tags": ["a", "b"], "rating": {"stars": 5}}</description>
      </book>
      <book id="bk102"><title>Rain</title><description>A long story
        over two lines.</description></book>
    </catalog>
    """;

  private XmlDocumentModel model() {
    return ReadAction.compute(() -> XmlModelBuilder.build(getProject(), XML));
  }

  // ---- JSON model ------------------------------------------------------------------------------

  public void testBigNumberLiteralKept() throws Exception {
    Object v = Json.parse("{\"id\": 12345678901234567890, \"p\": 1.50, \"e\": -0}");
    assertEquals("12345678901234567890", Json.primitiveText(((Map<?, ?>)v).get("id")));
    assertEquals("{\n  \"id\": 12345678901234567890,\n  \"p\": 1.50,\n  \"e\": -0\n}", Json.pretty(v));
  }

  public void testDuplicateKeysKeepFirstPositionLastValue() throws Exception {
    Map<?, ?> m = (Map<?, ?>)Json.parse("{\"a\": 1, \"b\": 2, \"a\": 3}");
    assertEquals(List.of("a", "b"), List.copyOf(m.keySet()));
    assertEquals("3", Json.primitiveText(m.get("a")));
  }

  public void testProtoAndPseudoColumnNamesArePlainKeys() throws Exception {
    Object v = Json.parse("[{\"__proto__\": 1, \"(value)\": 2}, 7]");
    JsonTable t = JsonTable.of(v);
    assertEquals(List.of("(value)", "__proto__", "(value)"), t.columns().stream().map(c -> c.key()).toList());
    assertEquals("2", t.cell(0, 2));
    assertEquals("7", t.cell(1, 0));
  }

  public void testTrailingCommaIsAnErrorWithLocation() {
    Json.Detection d = Json.detect("{\n  \"a\": 1,\n}");
    assertFalse(d.isJson());
    assertTrue(d.hasError());
    assertEquals(3, d.error().line());
    assertEquals(1, d.error().column());
    assertFalse(Json.detect("[1, 2] trailing").isJson());
    assertFalse(Json.detect("just text {x}").hasError());
  }

  public void testStringEscapingMatchesJsonStringify() {
    assertEquals("\"a\\nb\\u0001\\\"\\\\/é\"", Json.quote("a\nb\u0001\"\\/é"));
    assertEquals("\"\\ud800x\"", Json.quote("\ud800x"));
    assertEquals("\"😀\"", Json.quote("😀"));
  }

  // ---- Inspector targets -----------------------------------------------------------------------

  public void testGridCellTargets() {
    XmlDocumentModel m = model();
    GridTable t = m.table(m.root(), "book");
    InspectTarget attr = InspectTarget.ofGridCell(t, 0, t.columnIndex("@id"));
    assertEquals("catalog › book[1] › @id", attr.title());
    assertEquals("bk101", attr.text());
    InspectTarget desc = InspectTarget.ofGridCell(t, 0, t.columnIndex("description"));
    assertEquals("catalog › book[1] › description", desc.title());
    assertEquals(List.of("Tree", "Grid", "Text"), desc.tabs());
    InspectTarget story = InspectTarget.ofGridCell(t, 1, t.columnIndex("description"));
    assertEquals(List.of("Text"), story.tabs());
    assertTrue(story.text().contains("\n"));
    InspectTarget row = InspectTarget.ofGridCell(t, 1, -1);
    assertEquals("catalog › book[2]", row.title());
  }

  public void testFlatRowTargets() {
    List<FlatRow> rows = FlatRow.build(model());
    FlatRow idRow = rows.stream().filter(r -> r.kind() == FlatRow.Kind.ATTR).findFirst().orElseThrow();
    assertEquals("bk101", InspectTarget.ofFlatRow(idRow).text());
    FlatRow descRow = rows.stream().filter(r -> r.name().equals("description")).findFirst().orElseThrow();
    InspectTarget t = InspectTarget.ofFlatRow(descRow);
    assertTrue(t.detection().isJson());
    assertEquals("catalog › book[1] › description", t.title());
  }

  // ---- Panels ----------------------------------------------------------------------------------

  public void testTreeSearchFindsKeysAndValuesAndReveals() throws Exception {
    Object v = Json.parse("{\"tags\": [\"alpha\", \"beta\"], \"rating\": {\"stars\": 5, \"alphabet\": true}}");
    ValueInspector.JsonTreePanel panel = ValueInspector.treePanelForTest(v);
    panel.runSearch("alpha");
    assertEquals(2, panel.matchCount()); // "alpha" value and "alphabet" key
    panel.step(1);
    Object selected = panel.tree.getSelectionPath().getLastPathComponent();
    assertEquals(List.of("tags", 0), ((ValueInspector.JsonNode)selected).path);
    panel.step(1);
    assertEquals(List.of("rating", "alphabet"), ((ValueInspector.JsonNode)panel.tree.getSelectionPath().getLastPathComponent()).path);
  }

  public void testGridDrillsIntoNestedContainers() throws Exception {
    Object v = Json.parse("[{\"sku\": \"X\", \"lines\": [{\"q\": 1}, {\"q\": 2}]}, {\"sku\": \"Y\"}]");
    ValueInspector.JsonGridPanel panel = ValueInspector.gridPanelForTest(v);
    JsonTable top = panel.currentTable();
    int lines = top.columns().stream().map(c -> c.key()).toList().indexOf("lines");
    panel.drillForTest(0, lines);
    assertEquals(List.of(0, "lines"), panel.pathForTest());
    assertEquals(2, panel.currentTable().rowCount());
    assertEquals("2", panel.currentTable().cell(1, 0));
  }

  public void testSelectedValueShownWrappedInDetailPane() throws Exception {
    String story = "Once upon a time ".repeat(40).trim();
    Object v = Json.parse("{\"story\": \"" + story + "\", \"n\": 1}");
    ValueInspector.JsonTreePanel tree = ValueInspector.treePanelForTest(v);
    tree.runSearch("story");
    tree.step(1);
    assertEquals(story, ValueInspector.treeDetailForTest(tree));

    ValueInspector.JsonGridPanel grid = ValueInspector.gridPanelForTest(v);
    assertEquals(story, ValueInspector.gridDetailForTest(grid, 0, 1));
  }

  public void testLongGridValuesWrapIntoTallerRows() throws Exception {
    String story = "Once upon a time ".repeat(40).trim();
    Object v = Json.parse("{\"story\": \"" + story + "\", \"n\": 1}");
    ValueInspector.JsonGridPanel grid = ValueInspector.gridPanelForTest(v);
    int tall = ValueInspector.gridRowHeightForTest(grid, 0, JBUI.scale(400));
    int plain = ValueInspector.gridRowHeightForTest(grid, 1, JBUI.scale(400));
    assertTrue("long value wraps (" + tall + " > " + plain + ")", tall > plain);
  }

  public void testInspectorJsonSaveIsValidated() {
    List<String> saved = new ArrayList<>();
    InspectTarget target = new InspectTarget("x › meta", "{\"a\": 1}", dev.xmlgridview.intellij.model.ValueEdits.Target.text("0/0"));
    ValueInspector dialog = ValueInspector.createForTest(getProject(), target, (t, v) -> {
      saved.add(v);
      return null;
    });
    try {
      assertTrue(dialog.canEdit());
      dialog.startEditing();
      assertTrue(dialog.isEditingForTest());
      dialog.setEditTextForTest("{\"a\": 1,}");
      String error = dialog.save();
      assertNotNull("invalid JSON is rejected", error);
      assertTrue(error, error.contains("line 1"));
      assertEquals(List.of(), saved);
      dialog.setEditTextForTest("{\"a\": 2}");
      assertNull(dialog.save());
      assertEquals(List.of("{\"a\": 2}"), saved);
    }
    finally {
      com.intellij.openapi.util.Disposer.dispose(dialog.getDisposable());
    }
  }

  public void testInspectorCancelRestoresAndReadOnlyHasNoEdit() {
    InspectTarget target = new InspectTarget("x › note", "hello", dev.xmlgridview.intellij.model.ValueEdits.Target.text("0/0"));
    ValueInspector dialog = ValueInspector.createForTest(getProject(), target, (t, v) -> null);
    ValueInspector readOnly = ValueInspector.createForTest(getProject(), target, null);
    try {
      dialog.startEditing();
      dialog.setEditTextForTest("changed");
      dialog.doCancelAction(); // Escape while editing leaves edit mode, keeps the dialog
      assertFalse(dialog.isEditingForTest());
      assertFalse("no saver: no editing", readOnly.canEdit());
    }
    finally {
      com.intellij.openapi.util.Disposer.dispose(dialog.getDisposable());
      com.intellij.openapi.util.Disposer.dispose(readOnly.getDisposable());
    }
  }

  public void testDialogContentBuildsForJsonAndText() {
    XmlDocumentModel m = model();
    GridTable t = m.table(m.root(), "book");
    for (int row = 0; row < 2; row++) {
      InspectTarget target = InspectTarget.ofGridCell(t, row, t.columnIndex("description"));
      ValueInspector dialog = ValueInspector.createForTest(getProject(), target);
      try {
        assertEquals(target.tabs(), dialog.tabTitlesForTest());
      }
      finally {
        dialog.close(0);
      }
    }
    InspectTarget broken = new InspectTarget("x", "{\"a\": 1,}");
    ValueInspector dialog = ValueInspector.createForTest(getProject(), broken);
    try {
      assertTrue(dialog.hasErrorBannerForTest());
    }
    finally {
      dialog.close(0);
    }
  }
}
