package dev.xmlgridview.intellij;

import com.intellij.openapi.application.ReadAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.model.ColumnFilter;
import dev.xmlgridview.intellij.model.GridFilter;
import dev.xmlgridview.intellij.model.GridTable;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import java.util.Set;

/** PSI-specific behaviour the golden fixtures depend on. */
public class ModelTest extends BasePlatformTestCase {
  private XmlDocumentModel build(String text) {
    return ReadAction.compute(() -> XmlModelBuilder.build(getProject(), text));
  }

  public void testCdataEntitiesAndCommentsInText() {
    String xml = "<r><a>x &amp; <![CDATA[<y>]]> &#65;<!-- c -->z<?pi?></a></r>";
    XNode a = build(xml).root().children().get(0);
    assertEquals("x & <y> Az", a.text());
    assertEquals(xml.indexOf("x &amp;"), a.textStart());
  }

  public void testNamespaceDeclarationsAreNotAttributes() {
    XNode r = build("<r xmlns='urn:d' xmlns:p='urn:p' p:a='1' b='2'/>").root();
    assertEquals(2, r.attrs().size());
    assertEquals("p:a", r.attrs().get(0).name());
    assertEquals("urn:d", r.nsDecls().get(""));
    assertEquals("urn:p", r.nsDecls().get("p"));
    assertEquals("urn:d", r.ns());
  }

  public void testEmptyElementOffsets() {
    String xml = "<r><e/><f></f></r>";
    XmlDocumentModel m = build(xml);
    XNode e = m.root().children().get(0);
    XNode f = m.root().children().get(1);
    assertEquals("<e/>", xml.substring(e.start(), e.end()));
    assertEquals("<f></f>", xml.substring(f.start(), f.end()));
  }

  public void testAttributeValueNormalizationAndEntities() {
    XNode r = build("<r a='x\ty&quot;&#9;z'/>").root();
    assertEquals("x y\"\tz", r.attrs().get(0).value());
  }

  public void testMalformedHasErrorsAndPartialTree() {
    XmlDocumentModel m = build("<r>\n  <a>1</a>\n  <b>\n</r>");
    assertTrue(m.hasErrors());
    assertEquals(4, m.errors().get(0).line());
    assertNotNull(m.root());
    assertEquals("a", m.root().children().get(0).tag());
  }

  /** CRLF can't be in the shared fixtures (they are LF-only), so this pins the TS core's output for it. */
  public void testCrlfMatchesTheTsCore() throws Exception {
    XmlDocumentModel m = build("<doc a=\"one\r\ntwo\rthree\">\r\n  <p>first\r\nsecond\rthird</p>\r\n  <r><![CDATA[x\r\ny]]></r>\r\n</doc>\r\n");
    String expected = "{\"tag\":\"doc\",\"ns\":\"\",\"path\":\"0\",\"start\":0,\"end\":89,\"attrs\":[{\"name\":\"a\",\"value\":\"one two three\",\"start\":5,\"end\":23}],\"text\":\"\",\"children\":[{\"tag\":\"p\",\"ns\":\"\",\"path\":\"0/0\",\"start\":28,\"end\":54,\"attrs\":[],\"text\":\"first\\nsecond\\nthird\",\"children\":[]},{\"tag\":\"r\",\"ns\":\"\",\"path\":\"0/1\",\"start\":58,\"end\":81,\"attrs\":[],\"text\":\"x\\ny\",\"children\":[]}]}";
    assertEquals(com.google.gson.JsonParser.parseString(expected),
                 com.google.gson.JsonParser.parseString(dev.xmlgridview.intellij.model.CanonicalJson.write(dev.xmlgridview.intellij.model.CanonicalJson.tree(m))));
  }

  public void testMalformedInputNeverThrows() {
    for (String s : new String[]{"<", "<a", "<a b", "<a b=", "<a b='", "<a><b></a>", "</x><a/>", "<a/><b/>", "<!DOCTYPE", "<a><![CDATA[x", "<a>&", "<<a>>", "<a x=y>t</a>"}) {
      XmlDocumentModel m = build(s);
      for (XNode n : m.elements()) assertTrue(s, n.end() >= n.start() && n.end() <= s.length());
    }
    XmlDocumentModel m = build("<r><a>1</a><b>2</r>");
    assertEquals(java.util.List.of("a", "b"), m.root().children().stream().map(XNode::tag).toList());
  }

  public void testFieldsGridIsTheDefaultWhenNoTagRepeats() {
    XmlDocumentModel m = build("<book id='b1'><title>T</title><author>A</author></book>");
    GridTable t = m.table(m.root(), null);
    assertEquals(XmlDocumentModel.SELF_GROUP, t.group());
    assertEquals(1, t.rowCount());
    assertEquals(java.util.List.of("@id", "title", "author"), t.columns().stream().map(c -> c.key()).toList());
    assertEquals("A", t.cell(0, 2));
    XmlDocumentModel order = build("<order><customer>C</customer><item>1</item><item>2</item></order>");
    assertEquals("item", order.table(order.root(), null).group());
    assertEquals(XmlDocumentModel.SELF_GROUP, order.table(order.root(), XmlDocumentModel.SELF_GROUP).group());
  }

  public void testFilterCombinationIsAnd() {
    XmlDocumentModel m = build("<r><i a='x' b='1'/><i a='x' b='2'/><i a='y' b='1'/><i a='xy' b='3'/></r>");
    GridTable t = m.table(m.root(), "i");
    GridFilter f = new GridFilter();
    f.set("@a", new ColumnFilter("x", null));
    assertEquals(3, f.apply(t).size());
    f.set("@b", new ColumnFilter("", Set.of("1")));
    assertEquals(java.util.List.of(0), f.apply(t));
    f.set("@b", new ColumnFilter("", Set.of("1", "3")));
    assertEquals(java.util.List.of(0, 3), f.apply(t));
    f.set("@missing", new ColumnFilter("zzz", null));
    assertEquals("filters on absent columns are ignored", java.util.List.of(0, 3), f.apply(t));
    f.set("@a", null);
    assertEquals(java.util.List.of(0, 2, 3), f.apply(t));
  }

  public void testDistinctValuesCap() {
    StringBuilder sb = new StringBuilder("<r>");
    for (int i = 0; i < 1500; i++) sb.append("<i v='").append(i).append("'/>");
    sb.append("</r>");
    XmlDocumentModel m = build(sb.toString());
    GridFilter.DistinctValues d = GridFilter.distinctValues(m.table(m.root(), "i"), 0);
    assertEquals(GridFilter.DISTINCT_LIMIT, d.values().size());
    assertTrue(d.capped());
  }
}
