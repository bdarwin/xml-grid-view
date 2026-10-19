package dev.xmlgridview.intellij;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.SingleRootFileViewProvider;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.editor.XmlFlatEditorProvider;
import dev.xmlgridview.intellij.editor.XmlFlatViewerEditor;
import dev.xmlgridview.intellij.editor.XmlGridEditorProvider;
import dev.xmlgridview.intellij.editor.XmlGridViewerEditor;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.ui.FlatPanel;
import dev.xmlgridview.intellij.ui.XmlGridPanel;

/** Model loading: one shared build per document version, and files over IntelliJ's content-loading limit. */
public class LargeFilesTest extends BasePlatformTestCase {
  private XmlGridViewerEditor grid;
  private XmlFlatViewerEditor flat;

  @Override
  protected void tearDown() throws Exception {
    try {
      if (grid != null) Disposer.dispose(grid);
      if (flat != null) Disposer.dispose(flat);
    }
    finally {
      super.tearDown();
    }
  }

  private static void waitFor(String what, java.util.function.BooleanSupplier cond) throws Exception {
    PlatformTestUtil.waitWithEventsDispatching(what, cond::getAsBoolean, 60);
  }

  public void testGridAndFlatShareOneModel() throws Exception {
    VirtualFile file = myFixture.configureByText("shared.xml", "<r><a>1</a><a>2</a></r>").getVirtualFile();
    grid = (XmlGridViewerEditor)new XmlGridEditorProvider().createEditor(getProject(), file);
    flat = (XmlFlatViewerEditor)new XmlFlatEditorProvider().createEditor(getProject(), file);
    XmlGridPanel g = grid.getPanel();
    FlatPanel f = flat.getPanel();
    g.refresh();
    f.refresh();
    waitFor("both views loaded", () -> g.model() != null && f.modelForTest() != null);
    assertSame("one build serves both tabs", g.model(), f.modelForTest());
  }

  public void testFileOverTheContentLimitIsShownInFullAndReadOnly() throws Exception {
    int books = 130_000;
    StringBuilder sb = new StringBuilder(books * 260 + 40).append("<catalog>\n");
    for (int i = 0; i < books; i++) {
      sb.append("  <book id=\"bk").append(i).append("\"><title>Title ").append(i)
        .append("</title><author>Some Author Name</author><price>12.50</price><genre>Fantasy</genre>")
        .append("<description>Lorem ipsum dolor sit amet, consectetur adipiscing elit.</description></book>\n");
    }
    sb.append("</catalog>\n");
    VirtualFile file = myFixture.getTempDirFixture().createFile("big.xml", sb.toString());
    assertTrue("test file exceeds the limit", SingleRootFileViewProvider.isTooLargeForContentLoading(file));
    assertTrue("IntelliJ only loads a preview",
               FileDocumentManager.getInstance().getDocument(file).getTextLength() < sb.length());

    grid = (XmlGridViewerEditor)new XmlGridEditorProvider().createEditor(getProject(), file);
    XmlGridPanel g = grid.getPanel();
    g.refresh();
    waitFor("large file loaded", () -> g.model() != null);
    XmlDocumentModel m = g.model();
    assertFalse("the whole file was parsed, not the truncated preview", m.hasErrors());
    assertEquals(books, m.root().children().size());
    assertTrue(g.isReadOnlyLargeFileForTest());
    assertFalse("no editing without a full document", g.isGridCellEditableForTest(0, 1));
  }
}
