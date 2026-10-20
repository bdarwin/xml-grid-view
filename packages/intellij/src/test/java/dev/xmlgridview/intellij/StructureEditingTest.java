package dev.xmlgridview.intellij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.editor.XmlFlatEditorProvider;
import dev.xmlgridview.intellij.editor.XmlFlatViewerEditor;
import dev.xmlgridview.intellij.editor.XmlGridEditorProvider;
import dev.xmlgridview.intellij.editor.XmlGridViewerEditor;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;
import dev.xmlgridview.intellij.ui.FlatPanel;
import dev.xmlgridview.intellij.ui.XmlGridPanel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/** Structural edits from the context menus: exact document changes, undo, selection of the new element, read-only. */
public class StructureEditingTest extends BasePlatformTestCase {
  private static final String XML = """
    <catalog>
      <book id="b1">
        <title>The Cat</title>
      </book>
      <book id="b2">
        <title>Le Chat</title>
      </book>
    </catalog>
    """;

  private final List<FileEditor> editors = new ArrayList<>();
  private final Deque<String> answers = new ArrayDeque<>();
  private VirtualFile file;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    TestDialogManager.setTestInputDialog(message -> answers.poll());
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      TestDialogManager.setTestInputDialog(null);
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

  private static boolean enabled(AnAction a) {
    AnActionEvent e = TestActionEvent.createTestEvent(a);
    a.update(e);
    return e.getPresentation().isEnabledAndVisible();
  }

  private void run(AnAction a, String... inputs) {
    assertNotNull(a);
    assertTrue(a.getTemplateText() + " is enabled", enabled(a));
    answers.addAll(List.of(inputs));
    a.actionPerformed(TestActionEvent.createTestEvent(a));
  }

  private void waitForModel(Supplier<XmlDocumentModel> model) throws Exception {
    PlatformTestUtil.waitWithEventsDispatching("model rebuilt", () -> model.get() != null && model.get().text().equals(docText()), 30);
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
  }

  public void testInsertChildFromTheGridSelectsTheNewElementAndUndoes() throws Exception {
    XmlGridPanel panel = openGrid();
    panel.selectGridCellForTest(0, 0);
    run(panel.structureActionForTest(false, "Insert Child Element"), "author");
    assertEquals(XML.replace("<title>The Cat</title>\n", "<title>The Cat</title>\n    <author/>\n"), docText());
    waitForModel(panel::model);
    PlatformTestUtil.waitWithEventsDispatching("new element selected", () -> "0/0/1".equals(panel.selectedTreePathForTest()), 30);

    myFixture.performEditorAction(IdeActions.ACTION_UNDO);
    assertEquals("one undo step", XML, docText());
  }

  public void testRenameDuplicateAndDeleteFromTheGrid() throws Exception {
    XmlGridPanel panel = openGrid();
    panel.selectGridCellForTest(1, 0);
    run(panel.structureActionForTest(false, "Rename"), "magazine");
    assertTrue(docText().contains("<magazine id=\"b2\">") && docText().contains("</magazine>"));
    waitForModel(panel::model);

    panel.showGridForTest(panel.model().root(), "book");
    panel.selectGridCellForTest(0, 0);
    run(panel.structureActionForTest(false, "Duplicate"));
    assertEquals(2, docText().split("<book id=\"b1\">", -1).length - 1);
    waitForModel(panel::model);

    panel.showGridForTest(panel.model().root(), "magazine");
    panel.selectGridCellForTest(0, 0);
    run(panel.structureActionForTest(false, "Delete Element"));
    assertFalse(docText().contains("magazine"));
  }

  public void testAttributesFromTheFlatView() throws Exception {
    FlatPanel panel = openFlat();
    panel.selectRowsForTest(1, 1); // <book id="b1">
    run(panel.structureActionForTest("Add Attribute"), "lang", "en & fr");
    assertTrue(docText(), docText().contains("<book id=\"b1\" lang=\"en &amp; fr\">"));
    waitForModel(panel::modelForTest);

    panel.selectRowsForTest(2, 2); // @id of the first book
    AnAction delete = panel.structureActionForTest("Delete Attribute");
    run(delete);
    assertTrue(docText(), docText().contains("<book lang=\"en &amp; fr\">"));
  }

  public void testInvalidNameIsRefusedAndRootActionsAreDisabled() throws Exception {
    FlatPanel panel = openFlat();
    panel.selectRowsForTest(0, 0); // the root
    assertFalse("no delete for the root", enabled(panel.structureActionForTest("Delete Element")));
    assertFalse("no siblings for the root", enabled(panel.structureActionForTest("Insert Element After")));
    assertTrue(enabled(panel.structureActionForTest("Insert Child Element")));
    // A cancelled dialog changes nothing.
    TestDialogManager.setTestInputDialog(message -> null);
    run(panel.structureActionForTest("Rename"));
    assertEquals(XML, docText());
  }
}
