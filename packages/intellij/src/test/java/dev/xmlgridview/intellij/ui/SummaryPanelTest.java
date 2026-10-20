package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.editor.XmlGridEditorProvider;
import dev.xmlgridview.intellij.editor.XmlGridViewerEditor;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import java.awt.Component;
import java.awt.Container;
import java.util.List;

/** The element summary above the grid: which values and chips show, fitting, switching groups, editing, the menu. */
public class SummaryPanelTest extends BasePlatformTestCase {
  private static final String XML = """
    <catalog updated="2026-10-20">
      <book id="bk101" format="hardcover">
        <author>Gambardella, Matthew</author>
        <title>XML Developer's Guide</title>
        <genre>Computer</genre>
        <price currency="USD">44.95</price>
        <publish_date>2000-10-01</publish_date>
        <description>An in-depth look at creating applications with XML.</description>
        <tags><tag>xml</tag><tag>reference</tag></tags>
      </book>
      <book id="bk102" format="paperback">
        <author>Ralls, Kim</author>
        <title>Midnight Rain</title>
        <price currency="USD">5.95</price>
      </book>
      <note>Prices are list prices.</note>
    </catalog>
    """;

  private XmlGridViewerEditor editor;
  private VirtualFile file;

  @Override
  protected void tearDown() throws Exception {
    try {
      if (editor != null) Disposer.dispose(editor);
    }
    finally {
      super.tearDown();
    }
  }

  private XmlGridPanel open(int width) {
    file = myFixture.configureByText("catalog.xml", XML).getVirtualFile();
    editor = (XmlGridViewerEditor)new XmlGridEditorProvider().createEditor(getProject(), file);
    XmlGridPanel p = editor.getPanel();
    p.apply(ReadAction.compute(() -> XmlModelBuilder.build(getProject(), XML)));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    p.showGridForTest(p.model().root(), null);
    layout(p, width);
    return p;
  }

  private static void layout(Component c, int width) {
    for (int i = 0; i < 3; i++) {
      c.setSize(width, 600);
      doLayout(c);
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }
  }

  private static void doLayout(Component c) {
    c.doLayout();
    if (c instanceof Container k) for (Component ch : k.getComponents()) doLayout(ch);
  }

  private String docText() {
    return FileDocumentManager.getInstance().getDocument(file).getText();
  }

  public void testRootShowsOwnValuesAndGroupChips() {
    XmlGridPanel p = open(1000);
    SummaryPanel s = p.summaryForTest();
    assertTrue(s.isVisible());
    assertEquals(List.of("@updated", "note"), s.valueLabelsForTest());
    assertEquals(0, s.hiddenForTest());
    assertEquals(List.of("*book", XmlDocumentModel.SELF_GROUP), s.chipsForTest());
  }

  public void testFieldsViewKeepsOnlyChipsAndLeavesHaveNoSummary() {
    XmlGridPanel p = open(1000);
    SummaryPanel s = p.summaryForTest();
    s.clickChipForTest(XmlDocumentModel.SELF_GROUP);
    layout(p, 1000);
    assertEquals(List.of("book", "*" + XmlDocumentModel.SELF_GROUP), s.chipsForTest());
    assertEquals(List.of(), s.valueLabelsForTest());

    p.showGridForTest(p.model().byPath("0/2"), null); // <note>: a leaf, the grid is all there is
    assertFalse(s.isVisible());
  }

  public void testValuesThatDoNotFitAreBehindMore() {
    XmlGridPanel p = open(700);
    SummaryPanel s = p.summaryForTest();
    p.showGridForTest(p.model().byPath("0/0"), "price");
    layout(p, 700);
    List<String> shown = s.valueLabelsForTest();
    assertEquals("@id", shown.get(0));
    assertTrue("some values hidden (" + shown + ")", s.hiddenForTest() > 0);
    assertFalse("the grid's own group isn't repeated as a value", shown.contains("price"));
    s.toggleMoreForTest();
    layout(p, 700);
    assertEquals(0, s.hiddenForTest());
    assertEquals(List.of("@id", "@format", "author", "title", "genre", "publish_date", "description"), s.valueLabelsForTest());
    assertEquals(List.of("*price", "tags", XmlDocumentModel.SELF_GROUP), s.chipsForTest());
  }

  public void testEditValuesAndDeleteFromTheMenu() throws Exception {
    XmlGridPanel p = open(1000);
    SummaryPanel s = p.summaryForTest();
    assertNull(s.commitForTest("@updated", "2026-11-01"));
    assertTrue(docText().contains("<catalog updated=\"2026-11-01\">"));
    PlatformTestUtil.waitWithEventsDispatching("model rebuilt", () -> p.model().text().equals(docText()), 30);
    layout(p, 1000);

    assertNull(s.commitForTest("note", "Net prices."));
    assertTrue(docText().contains("<note>Net prices.</note>"));
    PlatformTestUtil.waitWithEventsDispatching("model rebuilt", () -> p.model().text().equals(docText()), 30);
    layout(p, 1000);

    StructureActions.Target t = s.targetForTest("note");
    assertNotNull(t);
    assertEquals("note", t.node().tag());
    s.setMenuTargetForTest(t);
    AnAction delete = p.summaryActionForTest("Delete Element");
    AnActionEvent e = TestActionEvent.createTestEvent(delete);
    delete.update(e);
    assertEquals("Delete <note>", e.getPresentation().getText());
    delete.actionPerformed(TestActionEvent.createTestEvent(delete));
    assertFalse(docText().contains("note"));
    PlatformTestUtil.waitWithEventsDispatching("model rebuilt", () -> p.model().text().equals(docText()), 30);
    layout(p, 1000);
    assertEquals(List.of("@updated"), s.valueLabelsForTest());
  }
}
