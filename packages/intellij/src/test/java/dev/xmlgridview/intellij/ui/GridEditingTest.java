package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.editor.XmlGridEditorProvider;
import dev.xmlgridview.intellij.editor.XmlGridViewerEditor;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import java.awt.Point;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayDeque;
import java.util.Deque;

/** Spreadsheet-style grid edits from the grid menu: exact document changes, selection after the edit, one undo step. */
public class GridEditingTest extends BasePlatformTestCase {
  private static final String XML = """
    <catalog>
      <book id="b1">
        <title>One</title>
        <price>1</price>
      </book>
      <book id="b2">
        <title>Two</title>
        <price>2</price>
      </book>
      <book id="b3">
        <title>Three</title>
        <price>3</price>
      </book>
    </catalog>
    """;

  private XmlGridViewerEditor editor;
  private VirtualFile file;
  private final Deque<String> answers = new ArrayDeque<>();

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    TestDialogManager.setTestInputDialog(message -> answers.poll());
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      TestDialogManager.setTestInputDialog(null);
      if (editor != null) Disposer.dispose(editor);
    }
    finally {
      super.tearDown();
    }
  }

  private XmlGridPanel open() {
    file = myFixture.configureByText("catalog.xml", XML).getVirtualFile();
    editor = (XmlGridViewerEditor)new XmlGridEditorProvider().createEditor(getProject(), file);
    XmlGridPanel p = editor.getPanel();
    p.apply(ReadAction.compute(() -> XmlModelBuilder.build(getProject(), XML)));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    p.showGridForTest(p.model().root(), "book");
    return p;
  }

  private String docText() {
    return FileDocumentManager.getInstance().getDocument(file).getText();
  }

  private void run(XmlGridPanel p, String action, String... inputs) throws Exception {
    AnAction a = p.gridEditActionForTest(action);
    assertNotNull(action, a);
    AnActionEvent e = TestActionEvent.createTestEvent(a);
    a.update(e);
    assertTrue(action + " is enabled", e.getPresentation().isEnabledAndVisible());
    answers.addAll(java.util.List.of(inputs));
    a.actionPerformed(TestActionEvent.createTestEvent(a));
    PlatformTestUtil.waitWithEventsDispatching("model rebuilt", () -> p.model().text().equals(docText()), 30);
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
  }

  public void testInsertMoveAndDeleteRows() throws Exception {
    XmlGridPanel p = open();
    GridView g = p.gridForTest();
    p.selectGridCellForTest(0, 1);
    run(p, "Insert Row Below");
    assertTrue(docText().contains("</book>\n  <book id=\"\">\n    <title/>\n    <price/>\n  </book>\n  <book id=\"b2\">"));
    assertEquals("the new row is selected", new Point(1, 1), g.leadCell());

    // b1, (new), b2, b3: move b1 down past the new row.
    p.selectGridCellForTest(0, 0);
    run(p, "Move Row Down");
    assertTrue(docText().indexOf("id=\"\"") < docText().indexOf("id=\"b1\""));
    assertEquals("the moved row stays selected", new Point(0, 1), g.leadCell());

    p.selectGridCellForTest(0, 0);
    g.table().changeSelection(1, 0, false, true);
    run(p, "Delete Rows");
    assertFalse(docText().contains("id=\"\"") || docText().contains("b1"));
    assertTrue(docText().contains("b2") && docText().contains("b3"));
  }

  public void testAddAndDeleteColumns() throws Exception {
    XmlGridPanel p = open();
    p.selectGridCellForTest(0, 0);
    run(p, "Add Column", "@year");
    assertEquals(3, docText().split("year=\"\"", -1).length - 1);
    p.selectGridCellForTest(0, 0);
    run(p, "Add Column", "author");
    assertTrue(docText().contains("    <price>1</price>\n    <author/>\n  </book>"));

    int price = p.gridForTest().table().getColumnModel().getColumnIndex("price");
    p.selectGridCellForTest(0, price);
    run(p, "Delete Column");
    assertFalse(docText().contains("<price>"));
  }

  public void testPasteBlockAddsRowsInOneStep() throws Exception {
    XmlGridPanel p = open();
    CopyPasteManager.getInstance().setContents(new StringSelection("title\tprice\r\nUno\t10\r\n\"Dos, \"\"two\"\"\"\t20\r\nCuatro\t40\r\n"));
    p.selectGridCellForTest(1, 1);
    run(p, "Paste");
    String d = docText();
    assertTrue(d, d.contains("<book id=\"b2\">\n    <title>Uno</title>\n    <price>10</price>"));
    assertTrue(d, d.contains("<title>Dos, \"two\"</title>\n    <price>20</price>"));
    assertTrue(d, d.contains("  </book>\n  <book>\n    <title>Cuatro</title>\n    <price>40</price>\n  </book>\n</catalog>"));
    myFixture.performEditorAction(IdeActions.ACTION_UNDO);
    assertEquals("one undo step", XML, docText());
  }

  public void testClearCellsAndExport() throws Exception {
    XmlGridPanel p = open();
    p.selectGridCellForTest(0, 1);
    p.gridForTest().table().changeSelection(1, 2, false, true);
    run(p, "Clear Cells");
    assertTrue(docText().contains("<book id=\"b1\">\n    <title></title>\n    <price></price>"));
    assertTrue(docText().contains("<book id=\"b2\">\n    <title></title>\n    <price></price>"));
    assertEquals("@id,title,price\r\nb1,,\r\nb2,,\r\nb3,Three,3\r\n", p.exportText("csv"));
    assertTrue(p.exportText("json").contains("\"title\": \"Three\""));
  }
}
