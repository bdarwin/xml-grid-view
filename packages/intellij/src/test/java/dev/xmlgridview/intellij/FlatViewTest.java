package dev.xmlgridview.intellij;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.editor.XmlFlatEditorProvider;
import dev.xmlgridview.intellij.editor.XmlFlatViewerEditor;
import dev.xmlgridview.intellij.editor.XmlGridEditorProvider;
import dev.xmlgridview.intellij.model.FlatRow;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;
import dev.xmlgridview.intellij.ui.FlatPanel;

import java.util.List;

/** Light UI tests for the Flat tab: registration and order, rows, collapse, navigation, copy. */
public class FlatViewTest extends BasePlatformTestCase {
  private static final String XML = """
    <catalog>
      <book id="bk101">
        <author>Gambardella, Matthew</author>
        <description>An in-depth look
          with XML.</description>
        <items><it>Item1</it><it>Item2</it></items>
      </book>
    </catalog>
    """;

  private XmlFlatViewerEditor editor;
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

  private FlatPanel open(String text) {
    file = myFixture.configureByText("catalog.xml", text).getVirtualFile();
    FileEditor fe = new XmlFlatEditorProvider().createEditor(getProject(), file);
    assertInstanceOf(fe, XmlFlatViewerEditor.class);
    editor = (XmlFlatViewerEditor)fe;
    assertEquals("bottom tab label", "Flat", editor.getName());
    FlatPanel panel = editor.getPanel();
    panel.apply(build(text));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    return panel;
  }

  private XmlDocumentModel build(String text) {
    return ReadAction.compute(() -> XmlModelBuilder.build(getProject(), text));
  }

  private static List<String> names(List<FlatRow> rows) {
    return rows.stream().map(r -> "  ".repeat(r.depth()) + r.name()).toList();
  }

  public void testProviderRegisteredAfterGrid() {
    XmlFlatEditorProvider provider = new XmlFlatEditorProvider();
    assertEquals(FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR, provider.getPolicy());
    VirtualFile xml = myFixture.configureByText("a.xml", "<a/>").getVirtualFile();
    assertTrue(provider.accept(getProject(), xml));
    assertFalse(provider.accept(getProject(), myFixture.configureByText("a.txt", "<a/>").getVirtualFile()));

    // Bottom tabs read Text | Grid | Flat: the Grid provider comes before the Flat one.
    List<FileEditorProvider> providers = FileEditorProviderManager.getInstance().getProviderList(getProject(), xml);
    int grid = -1;
    int flat = -1;
    for (int i = 0; i < providers.size(); i++) {
      if (providers.get(i) instanceof XmlGridEditorProvider) grid = i;
      if (providers.get(i) instanceof XmlFlatEditorProvider) flat = i;
    }
    assertTrue("grid provider registered", grid >= 0);
    assertTrue("flat provider registered", flat >= 0);
    assertTrue("Grid before Flat: " + providers, grid < flat);
  }

  public void testRowsAreAnOutline() {
    FlatPanel panel = open(XML);
    assertEquals(List.of(
      "catalog",
      "  book",
      "    @id",
      "    author",
      "    description",
      "    items",
      "      it",
      "      it"), names(panel.visibleRows()));
    FlatRow id = panel.visibleRows().get(2);
    assertEquals(FlatRow.Kind.ATTR, id.kind());
    assertEquals("bk101", id.value());
    assertEquals("Item2", panel.visibleRows().get(7).value());
  }

  public void testCollapseHidesAttributesAndChildren() {
    FlatPanel panel = open(XML);
    panel.toggleForTest(1); // book
    assertEquals(List.of("catalog", "  book"), names(panel.visibleRows()));
    // Collapse state survives a refresh with an edited document.
    panel.apply(build(XML.replace("Item2", "Item3")));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    assertEquals(List.of("catalog", "  book"), names(panel.visibleRows()));
    panel.toggleForTest(1);
    assertEquals(8, panel.visibleRows().size());
    assertEquals("Item3", panel.visibleRows().get(7).value());
  }

  public void testNavigateMovesCaretToElementOrAttribute() {
    FlatPanel panel = open(XML);
    panel.navigateRowForTest(3); // author
    Editor text = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
    assertNotNull("navigation switches to the text editor", text);
    assertEquals(file, FileDocumentManager.getInstance().getFile(text.getDocument()));
    assertEquals(XML.indexOf("<author>"), text.getCaretModel().getOffset());

    panel.navigateRowForTest(2); // @id
    assertEquals(XML.indexOf("id=\"bk101\""), text.getCaretModel().getOffset());
  }

  public void testCopyAsIndentedTsv() {
    FlatPanel panel = open(XML);
    panel.selectRowsForTest(1, 4);
    assertEquals("  book\t\n"
                 + "    @id\tbk101\n"
                 + "    author\tGambardella, Matthew\n"
                 + "    description\tAn in-depth look with XML.", panel.copyForTest());
  }

  public void testCopyCellRangeLikeASpreadsheet() {
    FlatPanel panel = open(XML);
    panel.selectCellsForTest(3, 4, 1, 1); // Value column only
    assertEquals("Gambardella, Matthew\nAn in-depth look with XML.", panel.copyForTest());
    panel.selectCellsForTest(1, 2, 0, 0); // Name column only
    assertEquals("  book\n    @id", panel.copyForTest());
  }

  public void testInspectTargetForLeadRow() {
    FlatPanel panel = open(XML);
    panel.selectCellsForTest(2, 2, 1, 1); // @id value
    assertEquals("bk101", panel.inspectTargetForTest().text());
    assertTrue(panel.inspectTargetForTest().title().endsWith("› @id"));
  }

  public void testMalformedUpdateKeepsLastGoodModel() {
    FlatPanel panel = open(XML);
    XmlDocumentModel good = panel.model();
    panel.apply(build("<catalog><book>"));
    assertSame(good, panel.model());
    assertTrue(panel.isShowingErrorBanner());
    assertEquals(8, panel.visibleRows().size());
  }
}
