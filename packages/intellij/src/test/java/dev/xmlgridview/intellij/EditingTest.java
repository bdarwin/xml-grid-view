package dev.xmlgridview.intellij;

import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
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
import dev.xmlgridview.intellij.model.ValueEdits;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;
import dev.xmlgridview.intellij.ui.FlatPanel;
import dev.xmlgridview.intellij.ui.XmlGridPanel;

import java.util.ArrayList;
import java.util.List;

/** Value editing through the Grid and Flat tables: exact document changes, undo, and what is editable. */
public class EditingTest extends BasePlatformTestCase {
  private static final String XML = """
    <catalog>
      <book id="b1" lang="en"><title>The Cat</title><price>10</price><tags><t>a</t></tags></book>
      <book id="b2" lang="fr"><title>Le Chat</title><price>12</price><tags><t>b</t></tags></book>
    </catalog>
    """;

  private final List<FileEditor> editors = new ArrayList<>();
  private VirtualFile file;

  @Override
  protected void tearDown() throws Exception {
    try {
      for (FileEditor e : editors) Disposer.dispose(e);
    }
    finally {
      super.tearDown();
    }
  }

  private XmlDocumentModel build(String text) {
    return ReadAction.compute(() -> XmlModelBuilder.build(getProject(), text));
  }

  private String docText() {
    return FileDocumentManager.getInstance().getDocument(file).getText();
  }

  private XmlGridPanel openGrid() {
    file = myFixture.configureByText("catalog.xml", XML).getVirtualFile();
    FileEditor fe = new XmlGridEditorProvider().createEditor(getProject(), file);
    editors.add(fe);
    XmlGridPanel panel = ((XmlGridViewerEditor)fe).getPanel();
    panel.apply(build(XML));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    panel.showGridForTest(panel.model().root(), "book");
    return panel;
  }

  private FlatPanel openFlat() {
    file = myFixture.configureByText("catalog.xml", XML).getVirtualFile();
    FileEditor fe = new XmlFlatEditorProvider().createEditor(getProject(), file);
    editors.add(fe);
    FlatPanel panel = ((XmlFlatViewerEditor)fe).getPanel();
    panel.apply(build(XML));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    return panel;
  }

  public void testGridAttributeEditChangesDocumentExactlyAndIsUndoable() {
    XmlGridPanel panel = openGrid();
    int lang = panel.gridColumnForTest("@lang");
    assertTrue(panel.isGridCellEditableForTest(1, lang));
    ValueEdits.TextEdit expected = ValueEdits.compute(build(XML), ValueEdits.Target.attr("0/1", "lang"), "d\"e").edit();
    List<String> errors = new ArrayList<>();
    panel.editGridCellForTest(1, lang, "d\"e", errors::add);
    assertEquals(List.of(), errors);
    assertEquals(expected.applyTo(XML), docText());
    assertTrue(docText().contains("lang=\"d&quot;e\""));

    myFixture.performEditorAction(IdeActions.ACTION_UNDO);
    assertEquals("undo restores the original text", XML, docText());
  }

  public void testGridLeafEditAndNonEditableCells() {
    XmlGridPanel panel = openGrid();
    int title = panel.gridColumnForTest("title");
    int tags = panel.gridColumnForTest("tags");
    assertTrue("leaf cell is editable", panel.isGridCellEditableForTest(0, title));
    assertFalse("complex cell is not editable", panel.isGridCellEditableForTest(0, tags));
    panel.editGridCellForTest(0, title, "A & B", e -> fail(e));
    assertTrue(docText().contains("<title>A &amp; B</title>"));
  }

  public void testMixedContentTextIsNotEditable() {
    XmlDocumentModel m = build("<doc><p>Hello <b>bold</b> world</p><p>plain</p></doc>");
    var t = m.table(m.root(), "p");
    int text = t.columnIndex("#text");
    int b = t.columnIndex("b");
    assertNull("#text of an element with element children", ValueEdits.gridTarget(t, 0, text));
    assertNotNull("#text of a leaf row", ValueEdits.gridTarget(t, 1, text));
    assertNotNull("leaf child", ValueEdits.gridTarget(t, 0, b));
    assertNull("absent child is not editable (that would be adding)", ValueEdits.gridTarget(t, 1, b));
  }

  public void testFlatLeafValueEditAndEditability() {
    FlatPanel panel = openFlat();
    List<FlatRow> rows = panel.visibleRows();
    int titleRow = -1;
    int bookRow = -1;
    int langRow = -1;
    for (int i = 0; i < rows.size(); i++) {
      FlatRow r = rows.get(i);
      if (titleRow < 0 && r.name().equals("title")) titleRow = i;
      if (bookRow < 0 && r.name().equals("book")) bookRow = i;
      if (langRow < 0 && r.name().equals("@lang")) langRow = i;
    }
    assertTrue("leaf value editable", panel.isValueEditableForTest(titleRow));
    assertTrue("attribute value editable", panel.isValueEditableForTest(langRow));
    assertFalse("element with children is not editable", panel.isValueEditableForTest(bookRow));

    String expected = ValueEdits.compute(build(XML), ValueEdits.Target.text("0/0/0"), "Le <Chat>").edit().applyTo(XML);
    panel.editValueForTest(titleRow, "Le <Chat>", e -> fail(e));
    assertEquals(expected, docText());
  }

  public void testUnchangedValueWritesNothing() {
    FlatPanel panel = openFlat();
    long stamp = FileDocumentManager.getInstance().getDocument(file).getModificationStamp();
    int row = -1;
    List<FlatRow> rows = panel.visibleRows();
    for (int i = 0; i < rows.size(); i++) if (rows.get(i).name().equals("price")) { row = i; break; }
    panel.editValueForTest(row, "10", e -> fail(e));
    assertEquals(stamp, FileDocumentManager.getInstance().getDocument(file).getModificationStamp());
  }

  public void testStaleModelIsRefused() {
    FlatPanel panel = openFlat();
    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () ->
      FileDocumentManager.getInstance().getDocument(file).insertString(0, "<!-- changed -->"));
    int row = -1;
    List<FlatRow> rows = panel.visibleRows();
    for (int i = 0; i < rows.size(); i++) if (rows.get(i).name().equals("price")) { row = i; break; }
    assertFalse("stale model: not editable", panel.isValueEditableForTest(row));
  }
}
