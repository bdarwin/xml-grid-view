package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.table.JBTable;
import dev.xmlgridview.intellij.editor.XmlGridEditorProvider;
import dev.xmlgridview.intellij.editor.XmlGridViewerEditor;
import dev.xmlgridview.intellij.model.ValueEdits;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import java.util.List;

/** Allowed values from the IDE's XML support (XSD enumerations, DTD enumerated attributes) offered while editing. */
public class SuggestionsTest extends BasePlatformTestCase {
  private static final String XSD = """
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
      <xs:element name="catalog">
        <xs:complexType>
          <xs:sequence>
            <xs:element name="book" maxOccurs="unbounded">
              <xs:complexType>
                <xs:sequence>
                  <xs:element name="title" type="xs:string"/>
                  <xs:element name="status">
                    <xs:simpleType>
                      <xs:restriction base="xs:string">
                        <xs:enumeration value="draft"/>
                        <xs:enumeration value="published"/>
                        <xs:enumeration value="retired"/>
                      </xs:restriction>
                    </xs:simpleType>
                  </xs:element>
                </xs:sequence>
                <xs:attribute name="id" type="xs:string"/>
                <xs:attribute name="format">
                  <xs:simpleType>
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="hardcover"/>
                      <xs:enumeration value="paperback"/>
                      <xs:enumeration value="ebook"/>
                    </xs:restriction>
                  </xs:simpleType>
                </xs:attribute>
              </xs:complexType>
            </xs:element>
          </xs:sequence>
        </xs:complexType>
      </xs:element>
    </xs:schema>
    """;

  private static final String XML = """
    <catalog xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="catalog.xsd">
      <book id="b1" format="hardcover">
        <title>One</title>
        <status>draft</status>
      </book>
      <book id="b2" format="ebook">
        <title>Two</title>
        <status>published</status>
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

  private XmlDocumentModel build(String text) {
    return ReadAction.compute(() -> XmlModelBuilder.build(getProject(), text));
  }

  public void testXsdEnumerations() {
    myFixture.addFileToProject("catalog.xsd", XSD);
    VirtualFile file = myFixture.configureByText("catalog.xml", XML).getVirtualFile();
    XmlDocumentModel m = build(XML);
    com.intellij.openapi.editor.Document doc = myFixture.getEditor().getDocument();
    assertEquals(List.of("hardcover", "paperback", "ebook"), ValueSuggestions.of(getProject(), doc, m, new ValueEdits.Target("0/0", "format")));
    assertEquals(List.of("draft", "published", "retired"), ValueSuggestions.of(getProject(), doc, m, new ValueEdits.Target("0/0/1", null)));
    assertEquals("free text", List.of(), ValueSuggestions.of(getProject(), doc, m, new ValueEdits.Target("0/0", "id")));
    assertEquals("free text", List.of(), ValueSuggestions.of(getProject(), doc, m, new ValueEdits.Target("0/0/0", null)));

    // In the grid: editing the @format cell offers the values.
    editor = (XmlGridViewerEditor)new XmlGridEditorProvider().createEditor(getProject(), file);
    XmlGridPanel p = editor.getPanel();
    p.apply(build(XML));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    p.showGridForTest(p.model().root(), "book");
    JBTable t = p.gridForTest().table();
    int format = t.convertColumnIndexToView(p.gridForTest().gridTable().columnIndex("@format"));
    int title = t.convertColumnIndexToView(p.gridForTest().gridTable().columnIndex("title"));
    assertTrue(t.editCellAt(0, format));
    assertEquals(List.of("hardcover", "paperback", "ebook"), ((CellEditing.SuggestingEditor)t.getCellEditor()).suggestionsForTest());
    assertInstanceOf(t.getEditorComponent(), com.intellij.openapi.ui.ComboBox.class);
    paint(p, "grid-suggest");
    if (t.isEditing()) t.getCellEditor().cancelCellEditing();
    assertTrue(t.editCellAt(1, title));
    assertEquals(List.of(), ((CellEditing.SuggestingEditor)t.getCellEditor()).suggestionsForTest());
    t.getCellEditor().cancelCellEditing();
    // The element summary above a grid of one book's children (its own attributes) offers them too.
    p.showGridForTest(p.model().byPath("0/0"), "title");
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    assertEquals(List.of("hardcover", "paperback", "ebook"), p.summaryForTest().suggestionsForTest("@format"));
  }

  /** Paints the panel off screen to build/reports/visual for a look. */
  private static void paint(javax.swing.JComponent c, String name) {
    // No event dispatching here: focus moving away would end the cell editing being shown.
    c.setSize(900, 400);
    layout(c);
    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(900, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D g = img.createGraphics();
    c.paint(g);
    g.dispose();
    java.io.File dir = new java.io.File("build/reports/visual");
    dir.mkdirs();
    try {
      javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, name + ".png"));
    }
    catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void layout(java.awt.Component c) {
    c.doLayout();
    if (c instanceof java.awt.Container k) for (java.awt.Component ch : k.getComponents()) layout(ch);
  }

  public void testDtdEnumeratedAttribute() {
    String xml = """
      <!DOCTYPE list [
        <!ELEMENT list (item*)>
        <!ELEMENT item (#PCDATA)>
        <!ATTLIST item size (small|medium|large) #IMPLIED>
      ]>
      <list><item size="small">a</item></list>
      """;
    myFixture.configureByText("list.xml", xml);
    assertEquals(List.of("small", "medium", "large"),
                 ValueSuggestions.of(getProject(), myFixture.getEditor().getDocument(), build(xml), new ValueEdits.Target("0/0", "size")));
  }
}
