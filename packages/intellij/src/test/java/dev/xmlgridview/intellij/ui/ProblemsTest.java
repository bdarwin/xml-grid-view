package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.editor.XmlGridEditorProvider;
import dev.xmlgridview.intellij.editor.XmlGridViewerEditor;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import java.util.List;

/** Problems the IDE reports (validation), mapped to tree nodes, grid cells and rows, and navigation between them. */
public class ProblemsTest extends BasePlatformTestCase {
  private static final String XML = """
    <!DOCTYPE catalog [
      <!ELEMENT catalog (book*)>
      <!ELEMENT book (title, price)>
      <!ATTLIST book id CDATA #REQUIRED format (hardcover|ebook) #IMPLIED>
      <!ELEMENT title (#PCDATA)>
      <!ELEMENT price (#PCDATA)>
    ]>
    <catalog>
      <book id="b1" format="hardcover">
        <title>One</title>
        <price>1</price>
      </book>
      <book id="b2" format="paperback">
        <title>Two</title>
      </book>
      <book id="b3">
        <title>Three</title>
        <price>3</price>
        <isbn>123</isbn>
      </book>
    </catalog>
    """;

  private XmlGridViewerEditor editor;

  @Override
  protected void tearDown() throws Exception {
    try {
      if (editor != null) Disposer.dispose(editor);
    }
    finally {
      super.tearDown();
    }
  }

  private XmlGridPanel open() {
    VirtualFile file = myFixture.configureByText("catalog.xml", XML).getVirtualFile();
    editor = (XmlGridViewerEditor)new XmlGridEditorProvider().createEditor(getProject(), file);
    XmlGridPanel p = editor.getPanel();
    p.apply(ReadAction.compute(() -> XmlModelBuilder.build(getProject(), XML)));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    p.showGridForTest(p.model().root(), "book");
    return p;
  }

  private static void waitForSelection(XmlGridPanel p, String path) {
    PlatformTestUtil.waitWithEventsDispatching(() -> "selected " + path + ", was " + p.selectedTreePathForTest(),
                                               () -> path.equals(p.selectedTreePathForTest()), 10);
  }

  private static int at(String s) {
    return XML.indexOf(s);
  }

  public void testProblemsMapToCellsRowsAndTree() {
    XmlGridPanel p = open();
    XmlDocumentModel m = p.model();
    DocumentProblems problems = p.problemsForTest();
    problems.set(m, List.of(
      new DocumentProblems.Problem(at("\"paperback\""), at("\"paperback\"") + 11, true, "Bad format"),
      new DocumentProblems.Problem(at("<isbn>") + 1, at("<isbn>") + 5, true, "isbn not allowed"),
      new DocumentProblems.Problem(at("<book id=\"b2\"") + 1, at("<book id=\"b2\"") + 5, false, "price missing")));
    GridView g = p.gridForTest();
    int format = g.gridTable().columnIndex("@format");
    int isbn = g.gridTable().columnIndex("isbn");
    assertEquals("Bad format", g.cellProblemsForTest(1, format).get(0).message());
    assertEquals("isbn not allowed", g.cellProblemsForTest(2, isbn).get(0).message());
    assertEquals("price missing", g.rowProblemsForTest(1).get(0).message());
    assertTrue(g.rowProblemsForTest(0).isEmpty());
    assertTrue("catalog contains problems", problems.within(m.root()));
    assertEquals("2 errors, 1 warning", p.problemsLinkTextForTest());

    // Next problem: in document order; each is shown as a row of its parent's grid.
    p.stepProblem(1);
    waitForSelection(p, "0/1");
    p.stepProblem(1);
    p.stepProblem(1);
    waitForSelection(p, "0/2/2");
    assertEquals("the isbn element is shown as a row of the b3 grid", "isbn", p.gridForTest().gridTable().group());
    p.stepProblem(1);
    waitForSelection(p, "0/1");
  }

  public void testRealValidationFromTheIde() {
    XmlGridPanel p = open();
    myFixture.doHighlighting();
    p.problemsForTest().refresh(p.model());
    List<DocumentProblems.Located> found = p.problemsForTest().ordered();
    assertFalse("the IDE's DTD validation found problems", found.isEmpty());
    assertTrue(found.stream().anyMatch(l -> l.node().tag().equals("isbn") || l.node().tag().equals("book")));
    assertFalse(p.problemsLinkTextForTest().isEmpty());
  }
}
