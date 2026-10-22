package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.editor.XmlFlatEditorProvider;
import dev.xmlgridview.intellij.editor.XmlFlatViewerEditor;
import dev.xmlgridview.intellij.editor.XmlGridEditorProvider;
import dev.xmlgridview.intellij.editor.XmlGridViewerEditor;
import dev.xmlgridview.intellij.model.FlatRow;
import dev.xmlgridview.intellij.model.GridTable;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import java.util.List;

/** Changes against a baseline (the last commit, or the opened text) in the tree, grid and Flat view, and stepping through them. */
public class ChangesTest extends BasePlatformTestCase {
  private static final String BASE = """
    <catalog>
      <book id="b1" format="hardcover">
        <title>One</title>
        <price>1</price>
      </book>
      <book id="b2" format="ebook">
        <title>Two</title>
        <price>2</price>
      </book>
      <book id="b3" format="ebook">
        <title>Three</title>
        <price>3</price>
      </book>
    </catalog>
    """;

  /** b1's format and b2's price changed, b3 removed, b4 added. */
  private static final String CURRENT = """
    <catalog>
      <book id="b1" format="paperback">
        <title>One</title>
        <price>1</price>
      </book>
      <book id="b2" format="ebook">
        <title>Two</title>
        <price>2.50</price>
      </book>
      <book id="b4" format="ebook">
        <title>Four</title>
        <price>4</price>
      </book>
    </catalog>
    """;

  private com.intellij.openapi.Disposable editor;

  @Override
  protected void tearDown() throws Exception {
    try {
      if (editor != null) Disposer.dispose(editor);
    }
    finally {
      super.tearDown();
    }
  }

  private XmlDocumentModel build(String text) {
    return ReadAction.compute(() -> XmlModelBuilder.build(getProject(), text));
  }

  private XmlGridPanel openGrid() {
    VirtualFile file = myFixture.configureByText("catalog.xml", CURRENT).getVirtualFile();
    XmlGridViewerEditor e = (XmlGridViewerEditor)new XmlGridEditorProvider().createEditor(getProject(), file);
    editor = e;
    XmlGridPanel p = e.getPanel();
    p.apply(build(CURRENT));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    p.showGridForTest(p.model().root(), "book");
    return p;
  }

  private static void waitForSelection(XmlGridPanel p, String path) {
    PlatformTestUtil.waitWithEventsDispatching(() -> "selected " + path + ", was " + p.selectedTreePathForTest(),
                                               () -> path.equals(p.selectedTreePathForTest()), 10);
  }

  public void testWithoutVcsTheBaselineIsTheOpenedText() {
    XmlGridPanel p = openGrid();
    assertTrue(p.changesForTest().active());
    assertEquals(ChangeBaseline.OPENED, p.changesForTest().label());
    assertEquals("hidden without changes", "", p.changesLinkTextForTest());
    assertTrue(p.changesForTest().stops().isEmpty());
  }

  public void testChangesInGridTreeAndNavigation() {
    XmlGridPanel p = openGrid();
    DocumentChanges changes = p.changesForTest();
    changes.setBaselineForTest(BASE, ChangeBaseline.LAST_COMMIT);
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    assertEquals("+1 ~2 −1", p.changesLinkTextForTest());

    XmlDocumentModel m = p.model();
    XNode b1 = m.byPath("0/0");
    XNode b4 = m.byPath("0/2");
    assertEquals(DocumentChanges.Kind.MODIFIED, changes.of(b1).kind());
    assertTrue(changes.of(b1).tooltip().contains("@format: was hardcover"));
    assertEquals(DocumentChanges.Kind.ADDED, changes.of(b4).kind());
    assertEquals("the new book's children are new too", DocumentChanges.Kind.ADDED, changes.of(m.byPath("0/2/0")).kind());
    assertTrue(changes.within(m.root()));

    // Grid: b1's @format cell, b2's price cell, the new row, and a line where b3 was (above b4).
    GridView g = p.gridForTest();
    GridTable t = g.gridTable();
    DocumentChanges.GridMarks marks = g.changeMarksForTest();
    DocumentChanges.Mark format = marks.cells().get(((long)0 << 32) | t.columnIndex("@format"));
    assertEquals(DocumentChanges.Kind.MODIFIED, format.kind());
    assertEquals("Was: hardcover", format.tooltip());
    assertEquals("Was: 2", marks.cells().get(((long)1 << 32) | t.columnIndex("price")).tooltip());
    assertEquals(DocumentChanges.Kind.ADDED, marks.rows().get(2).kind());
    assertEquals(DocumentChanges.Kind.MODIFIED, marks.rows().get(0).kind());
    assertTrue(marks.removedAbove().get(2).contains("book: b3 · ebook · Three · 3"));

    // Tree: the removed book is shown struck through where it was.
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    assertEquals(List.of("book id=b1", "book id=b2", "-book b3 · ebook · Three · 3", "book id=b4"),
                 p.treeForTest().childLabelsForTest(m.root()));

    // Next change, in document order: b1, b2's price, the gap where b3 was, b4.
    p.stepChange(1);
    waitForSelection(p, "0/0");
    p.stepChange(1);
    waitForSelection(p, "0/1/1");
    p.stepChange(1);
    assertTrue(p.lastChangeMessageForTest(), p.lastChangeMessageForTest().startsWith("Change 3/4: Removed book (b3"));
    assertEquals("book", p.gridForTest().gridTable().group());
    p.stepChange(1);
    waitForSelection(p, "0/2");
    p.stepChange(1);
    waitForSelection(p, "0/0");

    // Editing so the document matches the baseline again clears the marks.
    p.apply(build(BASE));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    assertEquals("hidden without changes", "", p.changesLinkTextForTest());
    assertTrue(p.treeForTest().childLabelsForTest(p.model().root()).stream().noneMatch(s -> s.startsWith("-")));

    // Off (no baseline): the link is hidden.
    changes.setBaselineForTest(null, "");
    assertEquals("", p.changesLinkTextForTest());
  }

  public void testFlatRows() {
    VirtualFile file = myFixture.configureByText("catalog.xml", CURRENT).getVirtualFile();
    FileEditor fe = new XmlFlatEditorProvider().createEditor(getProject(), file);
    editor = fe;
    FlatPanel panel = ((XmlFlatViewerEditor)fe).getPanel();
    panel.apply(build(CURRENT));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    panel.changesForTest().setBaselineForTest(BASE, ChangeBaseline.LAST_COMMIT);
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    FlatRow format = null;
    FlatRow price2 = null;
    FlatRow title4 = null;
    for (FlatRow r : panel.visibleRows()) {
      if (r.kind() == FlatRow.Kind.ATTR && r.attr().name().equals("format") && r.node().path().equals("0/0")) format = r;
      if (r.kind() == FlatRow.Kind.ELEMENT && r.node().path().equals("0/1/1")) price2 = r;
      if (r.kind() == FlatRow.Kind.ELEMENT && r.node().path().equals("0/2/0")) title4 = r;
    }
    assertNotNull(format);
    assertEquals(DocumentChanges.Kind.MODIFIED, panel.changeOf(format).kind());
    assertEquals(DocumentChanges.Kind.MODIFIED, panel.changeOf(price2).kind());
    assertTrue(panel.changeOf(price2).tooltip().contains("Was: 2"));
    assertEquals(DocumentChanges.Kind.ADDED, panel.changeOf(title4).kind());
    FlatRow id1 = panel.visibleRows().stream().filter(r -> r.kind() == FlatRow.Kind.ATTR && r.attr().name().equals("id") && r.node().path().equals("0/0")).findFirst().orElseThrow();
    assertNull("unchanged attribute", panel.changeOf(id1));
  }
}
