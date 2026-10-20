package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
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
import dev.xmlgridview.intellij.model.GridTable;
import dev.xmlgridview.intellij.model.ValueEdits;

import java.awt.datatransfer.StringSelection;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** JSON files in the Grid view: records as rows, typed value edits, members and items from the menus, paste. */
public class JsonDocumentTest extends BasePlatformTestCase {
  private static final String JSON = """
    [
      {"id": 1, "name": "Ada", "active": true},
      {"id": 2, "name": "Grace"}
    ]
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

  private String docText() {
    return FileDocumentManager.getInstance().getDocument(file).getText();
  }

  private XmlGridPanel open() {
    file = myFixture.configureByText("people.json", JSON).getVirtualFile();
    assertTrue(new XmlGridEditorProvider().accept(getProject(), file));
    editor = (XmlGridViewerEditor)new XmlGridEditorProvider().createEditor(getProject(), file);
    XmlGridPanel p = editor.getPanel();
    p.refresh();
    PlatformTestUtil.waitWithEventsDispatching("JSON model built", () -> p.model() != null && p.model().isJson(), 30);
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    return p;
  }

  private void rebuilt(XmlGridPanel p) {
    PlatformTestUtil.waitWithEventsDispatching("model rebuilt", () -> p.model().text().equals(docText()), 30);
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
  }

  public void testRecordsGridEditsAndPaste() {
    XmlGridPanel p = open();
    GridTable t = p.gridForTest().gridTable();
    assertEquals("the top-level array opens as a grid of its items", "item", t.group());
    assertEquals(List.of("id", "name", "active"), t.columns().stream().map(c -> c.key()).toList());
    assertEquals("Grace", t.cellText(1, 1));

    // Value edits keep types: numbers stay numbers, strings are quoted.
    ValueEdits.Result r = ValueEdits.compute(p.model(), ValueEdits.Target.text("0/0/0"), "10");
    assertTrue(r.isOk());

    p.selectGridCellForTest(1, 1);
    CopyPasteManager.getInstance().setContents(new StringSelection("Hopper\tfalse\nLin\ttrue\n"));
    AnAction paste = p.gridEditActionForTest("Paste");
    AnActionEvent e = TestActionEvent.createTestEvent(paste);
    paste.update(e);
    assertTrue(e.getPresentation().isEnabledAndVisible());
    paste.actionPerformed(TestActionEvent.createTestEvent(paste));
    assertEquals("""
      [
        {"id": 1, "name": "Ada", "active": true},
        {"id": 2, "name": "Hopper", "active": false},
        {"name": "Lin", "active": true}
      ]
      """, docText());
    rebuilt(p);

    // The element menu speaks JSON: "Add Member…" on an object, no attribute actions.
    p.selectTreePathForTest("0/0");
    PlatformTestUtil.waitWithEventsDispatching("row selected", () -> "0/0".equals(p.selectedTreePathForTest()), 10);
    AnAction add = p.structureActionForTest(true, "Insert Child Element");
    AnActionEvent ae = TestActionEvent.createTestEvent(add);
    add.update(ae);
    assertEquals("Add Member…", ae.getPresentation().getText());
    answers.add("email");
    add.actionPerformed(TestActionEvent.createTestEvent(add));
    assertTrue(docText(), docText().contains("{\"id\": 1, \"name\": \"Ada\", \"active\": true, \"email\": \"\"}"));
    AnAction attr = p.structureActionForTest(true, "Add Attribute");
    AnActionEvent at = TestActionEvent.createTestEvent(attr);
    attr.update(at);
    assertFalse("no attributes in JSON", at.getPresentation().isVisible());
  }
}
