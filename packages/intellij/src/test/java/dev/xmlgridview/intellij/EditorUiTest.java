package dev.xmlgridview.intellij;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.editor.XmlGridEditorProvider;
import dev.xmlgridview.intellij.editor.XmlGridViewerEditor;
import dev.xmlgridview.intellij.model.ColumnFilter;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;
import dev.xmlgridview.intellij.ui.XmlGridPanel;

import java.util.Set;

/** Light UI tests: provider registration, navigation offsets, filter combination, state preservation. */
public class EditorUiTest extends BasePlatformTestCase {
  private static final String XML = """
    <catalog>
      <book id="b1" lang="en"><title>The Cat</title><price>10</price></book>
      <book id="b2" lang="fr"><title>Le Chat</title><price>12</price></book>
      <book id="b3" lang="en"><title>Theory</title><price>40</price></book>
      <magazine id="m1"/>
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

  private XmlGridPanel open(String text) {
    file = myFixture.configureByText("catalog.xml", text).getVirtualFile();
    FileEditor fe = new XmlGridEditorProvider().createEditor(getProject(), file);
    assertInstanceOf(fe, XmlGridViewerEditor.class);
    editor = (XmlGridViewerEditor)fe;
    assertEquals("bottom tab label", "Grid", editor.getName());
    XmlGridPanel panel = editor.getPanel();
    panel.apply(build(text));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    return panel;
  }

  private XmlDocumentModel build(String text) {
    return ReadAction.compute(() -> XmlModelBuilder.build(getProject(), text));
  }

  public void testProviderIsRegisteredAndAcceptsXmlOnly() {
    boolean registered = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.getExtensionList().stream()
      .anyMatch(p -> p instanceof XmlGridEditorProvider);
    assertTrue("provider registered in plugin.xml", registered);
    XmlGridEditorProvider provider = new XmlGridEditorProvider();
    assertTrue(provider.accept(getProject(), myFixture.configureByText("a.xml", "<a/>").getVirtualFile()));
    assertFalse(provider.accept(getProject(), myFixture.configureByText("a.txt", "<a/>").getVirtualFile()));
    // A tab after the default text editor: bottom tabs "Text | Grid", Text selected by default.
    assertEquals(FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR, provider.getPolicy());
  }

  public void testNavigateMovesCaretToElementOffset() {
    XmlGridPanel panel = open(XML);
    XmlDocumentModel model = panel.model();
    assertNotNull(model);
    XNode book2 = model.byPath("0/1");
    assertNotNull(book2);
    panel.navigateToNode(book2);
    Editor textEditor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
    assertNotNull("navigation switches to the text editor", textEditor);
    assertEquals(file, FileDocumentManager.getInstance().getFile(textEditor.getDocument()));
    assertEquals(XML.indexOf("<book id=\"b2\""), textEditor.getCaretModel().getOffset());
    assertEquals(book2.start(), textEditor.getCaretModel().getOffset());
  }

  public void testDoubleClickGridValueOpensInspector() {
    XmlGridPanel panel = open(XML);
    panel.showGridForTest(panel.model().root(), "book");
    java.util.List<dev.xmlgridview.intellij.model.InspectTarget> opened = new java.util.ArrayList<>();
    panel.setInspectorForTest(opened::add);
    panel.doubleClickGridForTest(1, 2); // row b2, column "title"
    assertEquals("double-click a value opens exactly one inspector", 1, opened.size());
    assertEquals("Le Chat", opened.get(0).text());
  }

  public void testTreeFilterKeepsMatchesAndAncestors() {
    XmlGridPanel panel = open(XML);
    assertEquals(java.util.Set.of("0", "0/1", "0/1/0"), panel.treeFilterForTest("chat"));
    assertEquals("attribute values match too", java.util.Set.of("0", "0/0", "0/2"), panel.treeFilterForTest("en"));
    assertNull("empty filter shows everything", panel.treeFilterForTest(""));
  }

  public void testColumnFiltersCombineWithAnd() {
    XmlGridPanel panel = open(XML);
    XNode root = panel.model().root();
    panel.showGridForTest(root, "book");
    assertEquals(3, panel.visibleGridRows());
    panel.setColumnFilterForTest("@lang", new ColumnFilter("", Set.of("en")));
    assertEquals(2, panel.visibleGridRows());
    panel.setColumnFilterForTest("title", new ColumnFilter("the", null));
    assertEquals(2, panel.visibleGridRows());
    panel.setColumnFilterForTest("price", new ColumnFilter("", Set.of("40")));
    assertEquals(1, panel.visibleGridRows());
    panel.setColumnFilterForTest("@lang", null);
    assertEquals(1, panel.visibleGridRows());
    panel.setColumnFilterForTest("price", null);
    assertEquals("title contains 'the' (case-insensitive)", 2, panel.visibleGridRows());
  }

  public void testRefreshPreservesGridAndFilters() {
    XmlGridPanel panel = open(XML);
    panel.showGridForTest(panel.model().root(), "book");
    panel.setColumnFilterForTest("@lang", new ColumnFilter("", Set.of("en")));
    String changed = XML.replace("<magazine id=\"m1\"/>", "<magazine id=\"m1\"/><book id=\"b4\" lang=\"en\"/>");
    panel.apply(build(changed));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    assertEquals("0", panel.gridPath());
    assertEquals(Set.of("@lang"), panel.gridFilters().keySet());
    assertEquals(3, panel.visibleGridRows());
  }

  public void testAsyncRefreshFromDocument() throws Exception {
    XmlGridPanel panel = open(XML);
    panel.showGridForTest(panel.model().root(), "book");
    panel.setColumnFilterForTest("@lang", new ColumnFilter("", Set.of("en")));
    XmlDocumentModel before = panel.model();
    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
      var doc = FileDocumentManager.getInstance().getDocument(file);
      doc.insertString(doc.getText().indexOf("<magazine"), "<book id=\"b5\" lang=\"en\"/>");
    });
    panel.refresh();
    long deadline = System.currentTimeMillis() + 20_000;
    while (panel.model() == before && System.currentTimeMillis() < deadline) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
      Thread.sleep(20);
    }
    assertNotSame("model rebuilt off the EDT", before, panel.model());
    assertEquals(4, panel.model().table(panel.model().root(), "book").rowCount());
    assertEquals("filters preserved across refresh", 3, panel.visibleGridRows());
  }

  public void testMalformedUpdateKeepsLastGoodModel() {
    XmlGridPanel panel = open(XML);
    XmlDocumentModel good = panel.model();
    panel.apply(build("<catalog><book>"));
    assertSame(good, panel.model());
    assertTrue(panel.isShowingErrorBanner());
    panel.apply(build(XML));
    assertFalse(panel.isShowingErrorBanner());
  }
}
