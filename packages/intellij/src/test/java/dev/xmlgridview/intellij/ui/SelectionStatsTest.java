package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.table.JBTable;
import dev.xmlgridview.intellij.editor.XmlGridEditorProvider;
import dev.xmlgridview.intellij.editor.XmlGridViewerEditor;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

/** Count, sum, average, min and max of the selected grid cells, shown above the grid. */
public class SelectionStatsTest extends BasePlatformTestCase {
  private static final String XML = """
    <catalog>
      <book id="b1"><title>One</title><price>0.1</price></book>
      <book id="b2"><title>Two</title><price>0.2</price></book>
      <book id="b3"><title>Three</title><price>3</price><note/></book>
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

  private static String f(double x) {
    return SelectionStats.format(x);
  }

  private static void select(JBTable t, int r0, int c0, int r1, int c1) {
    t.getSelectionModel().setSelectionInterval(r0, r1);
    t.getColumnModel().getSelectionModel().setSelectionInterval(c0, c1);
  }

  public void testStatsFollowTheSelection() {
    VirtualFile file = myFixture.configureByText("catalog.xml", XML).getVirtualFile();
    editor = (XmlGridViewerEditor)new XmlGridEditorProvider().createEditor(getProject(), file);
    XmlGridPanel p = editor.getPanel();
    p.apply(ReadAction.compute(() -> XmlModelBuilder.build(getProject(), XML)));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    p.showGridForTest(p.model().root(), "book");
    JBTable t = p.gridForTest().table();
    int title = t.convertColumnIndexToView(p.gridForTest().gridTable().columnIndex("title"));
    int price = t.convertColumnIndexToView(p.gridForTest().gridTable().columnIndex("price"));
    int note = t.convertColumnIndexToView(p.gridForTest().gridTable().columnIndex("note"));

    select(t, 0, price, 0, price);
    assertEquals("one cell: nothing", "", p.statsTextForTest());
    select(t, 0, price, 2, price);
    assertEquals("3 cells · Count 3 · Sum " + f(3.3) + " · Avg " + f(1.1) + " · Min " + f(0.1) + " · Max 3", p.statsTextForTest());
    select(t, 0, title, 2, title);
    assertEquals("3 cells · Count 3", p.statsTextForTest());
    // Empty cells are not counted: b1 and b2 have no note, b3's is empty.
    select(t, 0, note, 2, note);
    assertEquals("3 cells · Count 0", p.statsTextForTest());
  }
}
