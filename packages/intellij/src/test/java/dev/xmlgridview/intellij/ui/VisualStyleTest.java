package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.UIUtil;
import dev.xmlgridview.intellij.editor.XmlFlatEditorProvider;
import dev.xmlgridview.intellij.editor.XmlFlatViewerEditor;
import dev.xmlgridview.intellij.editor.XmlGridEditorProvider;
import dev.xmlgridview.intellij.editor.XmlGridViewerEditor;
import dev.xmlgridview.intellij.model.FlatRow;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumn;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Visual checks of the Swing views. The panels are laid out and painted off screen (PNGs go to
 * build/reports/visual for a look), then checked against the style rules rather than pixel
 * baselines, since fonts differ between macOS and CI: section and stripe backgrounds as
 * painted, tag/attribute colours, headers and names that fit, and painting without errors.
 */
public class VisualStyleTest extends BasePlatformTestCase {
  private static final String XML = """
    <catalog updated="2026-10-20">
      <book id="bk101" format="hardcover">
        <meta>{"pages":350}</meta>
        <author>Gambardella, Matthew</author>
        <title>XML Developer's Guide</title>
        <price currency="USD">44.95</price>
        <tags><tag>xml</tag><tag>reference</tag></tags>
      </book>
      <book id="bk102" format="paperback">
        <meta>{"pages":120,"tags":["fantasy"]}</meta>
        <author>Ralls, Kim</author>
        <title>Midnight Rain</title>
        <price currency="USD">5.95</price>
        <tags><tag>fantasy</tag></tags>
      </book>
      <book id="bk103" format="ebook">
        <author>Corets, Eva</author>
        <title>Maeve Ascendant</title>
        <price currency="EUR">5.95</price>
      </book>
      <config><![CDATA[{"theme":"dark","pageSize":25}]]></config>
      <note>Prices are list prices.</note>
    </catalog>
    """;
  private static final int W = 1000;
  private static final int H = 600;

  private XmlGridViewerEditor gridEditor;
  private XmlFlatViewerEditor flatEditor;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    // Headless Linux runs on Metal, whose tree arrows can't paint without a component (they NPE);
    // the IDE's own look never uses them.
    javax.swing.UIManager.put("Tree.expandedIcon", com.intellij.icons.AllIcons.General.ArrowDown);
    javax.swing.UIManager.put("Tree.collapsedIcon", com.intellij.icons.AllIcons.General.ArrowRight);
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      if (gridEditor != null) Disposer.dispose(gridEditor);
      if (flatEditor != null) Disposer.dispose(flatEditor);
    }
    finally {
      super.tearDown();
    }
  }

  private XmlDocumentModel model() {
    return ReadAction.compute(() -> XmlModelBuilder.build(getProject(), XML));
  }

  private VirtualFile file() {
    return myFixture.configureByText("catalog.xml", XML).getVirtualFile();
  }

  private XmlGridPanel grid() {
    gridEditor = (XmlGridViewerEditor)new XmlGridEditorProvider().createEditor(getProject(), file());
    XmlGridPanel p = gridEditor.getPanel();
    p.apply(model());
    settle(p);
    return p;
  }

  private FlatPanel flat() {
    flatEditor = (XmlFlatViewerEditor)new XmlFlatEditorProvider().createEditor(getProject(), file());
    FlatPanel p = flatEditor.getPanel();
    p.apply(model());
    settle(p);
    return p;
  }

  /** Sizes and lays out the whole component tree, letting async tree models finish. */
  private static void settle(JComponent c) {
    for (int i = 0; i < 3; i++) {
      c.setSize(W, H);
      layout(c);
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }
  }

  private static void layout(Component c) {
    c.doLayout();
    if (c instanceof Container k) for (Component ch : k.getComponents()) layout(ch);
  }

  /** Paints {@code c} off screen and saves the PNG under build/reports/visual. */
  private static BufferedImage paint(JComponent c, String name) throws Exception {
    settle(c);
    BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    try {
      c.paint(g);
    }
    finally {
      g.dispose();
    }
    File dir = new File("build/reports/visual");
    dir.mkdirs();
    ImageIO.write(img, "png", new File(dir, name + ".png"));
    return img;
  }

  private static void assertNotBlank(BufferedImage img) {
    Set<Integer> colors = new HashSet<>();
    for (int y = 0; y < img.getHeight(); y += 3) for (int x = 0; x < img.getWidth(); x += 3) colors.add(img.getRGB(x, y));
    assertTrue("painted something (distinct colours: " + colors.size() + ")", colors.size() > 20);
  }

  /** The visible data table: the JBTable whose model has {@code columns} columns and the most rows. */
  private static JBTable table(JComponent root, int columns) {
    JBTable best = null;
    for (JBTable t : UIUtil.findComponentsOfType(root, JBTable.class)) {
      if (t.getColumnCount() == columns && (best == null || t.getRowCount() > best.getRowCount())) best = t;
    }
    assertNotNull("table with " + columns + " columns", best);
    return best;
  }

  private static JBTable widestTable(JComponent root) {
    JBTable best = null;
    for (JBTable t : UIUtil.findComponentsOfType(root, JBTable.class)) if (best == null || t.getColumnCount() > best.getColumnCount()) best = t;
    assertNotNull(best);
    return best;
  }

  /** The pixel colour near the right edge of a cell (away from text), in the painted image. */
  private static Color pixelInCell(BufferedImage img, JComponent root, JBTable t, int row, int col) {
    Rectangle r = SwingUtilities.convertRectangle(t, t.getCellRect(row, col, false), root);
    return new Color(img.getRGB(r.x + r.width - 3, r.y + r.height / 2));
  }

  private static Color firstFragmentColor(Component renderer) {
    SimpleColoredComponent.ColoredIterator it = ((SimpleColoredComponent)renderer).iterator();
    assertTrue("renderer has text", it.hasNext());
    it.next();
    SimpleTextAttributes a = it.getTextAttributes();
    return a.getFgColor();
  }

  public void testGridStripesAttributeColoursAndHeaders() throws Exception {
    XmlGridPanel p = grid();
    BufferedImage img = paint(p, "grid");
    assertNotBlank(img);
    JBTable t = widestTable(p);
    assertEquals("one row per book", 3, t.getRowCount());
    t.clearSelection();
    img = paint(p, "grid");

    // Alternate rows are striped, as painted: the whole top line of every cell, including icon areas
    // (meta has an inspect icon), where no glyphs are drawn.
    for (int row = 0; row < t.getRowCount(); row++) {
      Color expected = XmlColors.stripe(row, t.getBackground());
      for (int col = 0; col < t.getColumnCount(); col++) {
        Rectangle c = SwingUtilities.convertRectangle(t, t.getCellRect(row, col, false), p);
        for (int x = c.x + 1; x < Math.min(W, c.x + c.width - 1); x++) {
          assertEquals("row " + row + " column " + t.getColumnName(col) + " background at x=" + (x - c.x),
                       expected, new Color(img.getRGB(x, c.y + 1)));
        }
      }
    }
    // Attribute columns use the attribute-value colour; element columns plain text.
    int idCol = t.getColumnModel().getColumnIndex(t.getModel().getColumnName(0));
    assertTrue(t.getColumnName(idCol).startsWith("@"));
    assertEquals(XmlColors.attrValue(), firstFragmentColor(t.prepareRenderer(t.getCellRenderer(0, idCol), 0, idCol)));
    // Header labels fit their columns after auto-fit.
    for (int c = 0; c < t.getColumnCount(); c++) {
      TableColumn col = t.getColumnModel().getColumn(c);
      Component h = t.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(t, col.getHeaderValue(), false, false, -1, c);
      assertTrue("header '" + col.getHeaderValue() + "' fits (" + h.getPreferredSize().width + " > " + col.getWidth() + ")",
                 h.getPreferredSize().width <= col.getWidth() + 2);
    }
  }

  public void testElementSummaryAboveTheGrid() throws Exception {
    XmlGridPanel p = grid();
    BufferedImage img = paint(p, "grid-summary");
    SummaryPanel s = p.summaryForTest();
    assertTrue(s.isVisible() && s.getHeight() > 0);
    assertEquals(List.of("@updated", "config", "note"), s.valueLabelsForTest());
    // Value colours: attribute names like the grid's attribute headers, element names like tags.
    List<SimpleColoredComponent> items = s.itemComponentsForTest();
    assertEquals(XmlColors.attrName(), firstFragmentColor(items.get(0)));
    assertEquals(XmlColors.tag(), firstFragmentColor(items.get(1)));
    assertNull(items.get(0).getIcon());
    assertNotNull("JSON values get the inspect icon", items.get(1).getIcon());
    // Chips: the grid's group is filled with the selection colour, the others are outlined only.
    List<String> chips = s.chipsForTest();
    List<SimpleColoredComponent> chipViews = s.chipComponentsForTest();
    assertEquals(chips.size(), chipViews.size());
    for (int i = 0; i < chips.size(); i++) {
      Rectangle r = SwingUtilities.convertRectangle(chipViews.get(i).getParent(), chipViews.get(i).getBounds(), p);
      Color inside = new Color(img.getRGB(r.x + r.width / 2, r.y + 2));
      if (chips.get(i).startsWith("*")) assertEquals("active chip " + chips.get(i), UIUtil.getListSelectionBackground(true), inside);
      else assertEquals("chip " + chips.get(i), s.getBackground(), inside);
    }
  }

  /** The baseline for the change tests: bk101 was an ebook, bk102 cost 4.95, bk103 is new and a bk104 was removed. */
  private static final String BASELINE = XML.replace("id=\"bk101\" format=\"hardcover\"", "id=\"bk101\" format=\"ebook\"")
    .replace("<price currency=\"USD\">5.95</price>", "<price currency=\"USD\">4.95</price>")
    .replaceFirst("(?s)  <book id=\"bk103\".*?</book>\n", "")
    .replace("  <config>", "  <book id=\"bk104\" format=\"ebook\">\n    <author>Knorr, Stefan</author>\n    <title>Creepy Crawlies</title>\n  </book>\n  <config>");

  public void testChangesAreTintedInGridAndFlat() throws Exception {
    XmlGridPanel p = grid();
    p.changesForTest().setBaselineForTest(BASELINE, ChangeBaseline.LAST_COMMIT);
    settle(p);
    JBTable t = widestTable(p);
    t.clearSelection();
    BufferedImage img = paint(p, "grid-changes");
    assertEquals("+1 ~2 −1", p.changesLinkTextForTest());
    int format = t.convertColumnIndexToView(p.gridForTest().gridTable().columnIndex("@format"));
    int author = t.convertColumnIndexToView(p.gridForTest().gridTable().columnIndex("author"));
    assertEquals("changed cell is tinted", DocumentChanges.tint(DocumentChanges.modified(), XmlColors.stripe(0, t.getBackground())), pixelInCell(img, p, t, 0, format));
    assertEquals("unchanged cell is not", XmlColors.stripe(0, t.getBackground()), pixelInCell(img, p, t, 0, author));
    assertEquals("new row is tinted", DocumentChanges.tint(DocumentChanges.added(), XmlColors.stripe(2, t.getBackground())), pixelInCell(img, p, t, 2, author));
    // bk104 was where bk103 is now: a red line along the top of that row.
    Rectangle gap = SwingUtilities.convertRectangle(t, t.getCellRect(2, author, false), p);
    assertEquals(DocumentChanges.removed(), new Color(img.getRGB(gap.x + gap.width / 2, gap.y)));
    // The tree shows the removed book struck through, after the root is expanded.
    PlatformTestUtil.waitForPromise(p.treeForTest().select("0/0", true));
    settle(p);
    BufferedImage expanded = paint(p, "grid-changes");
    assertEquals(List.of("book id=bk101", "book id=bk102", "-book bk104 · ebook · Knorr, Stefan · Creepy Crawlies", "book id=bk103", "config", "note"),
                 p.treeForTest().childLabelsForTest(p.model().root()));
    assertNotBlank(expanded);

    FlatPanel f = flat();
    f.changesForTest().setBaselineForTest(BASELINE, ChangeBaseline.LAST_COMMIT);
    paint(f, "flat-changes");
  }

  public void testValuesUseTheEditorFont() throws Exception {
    java.awt.Font editor = ViewFont.editor();
    XmlGridPanel g = grid();
    paint(g, "grid");
    JBTable t = widestTable(g);
    assertFontFits("grid cell", t, t.prepareRenderer(t.getCellRenderer(0, 0), 0, 0), editor);
    com.intellij.ui.treeStructure.Tree tree = UIUtil.findComponentOfType(g, com.intellij.ui.treeStructure.Tree.class);
    assertNotNull(tree);
    assertEquals(editor, tree.getFont());
    Component node = tree.getCellRenderer().getTreeCellRendererComponent(tree, tree.getPathForRow(0).getLastPathComponent(), false, false, false, 0, false);
    assertEquals("tree node font", editor.getFamily(), node.getFont().getFamily());
    assertEquals(editor.getSize(), node.getFont().getSize());

    FlatPanel f = flat();
    JBTable ft = table(f, 2);
    paint(f, "flat");
    assertFontFits("Flat value", ft, ft.prepareRenderer(ft.getCellRenderer(0, 1), 0, 1), editor);
  }

  /** The renderer paints in the editor font, and rows are tall enough for it. */
  private static void assertFontFits(String what, JBTable t, Component c, java.awt.Font editor) {
    assertEquals(what + " font", editor.getFamily(), c.getFont().getFamily());
    assertEquals(what + " size", editor.getSize(), c.getFont().getSize());
    assertTrue(what + ": row height " + t.getRowHeight(0) + " fits the font", t.getRowHeight(0) >= t.getFontMetrics(editor).getHeight());
  }

  public void testSelectionStatsInTheHeader() throws Exception {
    XmlGridPanel p = grid();
    JBTable t = widestTable(p);
    int author = t.convertColumnIndexToView(p.gridForTest().gridTable().columnIndex("author"));
    t.getSelectionModel().setSelectionInterval(0, 2);
    t.getColumnModel().getSelectionModel().setSelectionInterval(author, author);
    assertNotBlank(paint(p, "grid-stats"));
    assertEquals("3 cells · Count 3", p.statsTextForTest());
  }

  public void testFlatSectionBandsAndNameColours() throws Exception {
    FlatPanel p = flat();
    JBTable t = table(p, 2);
    t.clearSelection();
    BufferedImage img = paint(p, "flat");
    assertNotBlank(img);
    XmlDocumentModel m = p.modelForTest();
    int level = FlatRow.sectionLevel(m);
    List<FlatRow> rows = p.visibleRows();
    Set<Color> seen = new HashSet<>();
    int checked = 0;
    for (int r = 0; r < rows.size(); r++) {
      Rectangle cell = SwingUtilities.convertRectangle(t, t.getCellRect(r, 1, false), p);
      if (cell.y + cell.height > H) break; // below the painted area
      checked++;
      FlatRow row = rows.get(r);
      Color expected = XmlColors.band(FlatRow.band(row.node(), level), row.isValueRow(), t.getBackground());
      for (int col = 0; col < 2; col++) {
        Rectangle c = SwingUtilities.convertRectangle(t, t.getCellRect(r, col, false), p);
        // The cell's whole top line (indent, icons, text area), where no glyphs are drawn.
        for (int x = c.x + 1; x < Math.min(W, c.x + c.width - 1); x++) {
          assertEquals("row " + r + " (" + row.name() + ") column " + col + " background at x=" + (x - c.x),
                       expected, new Color(img.getRGB(x, c.y + 1)));
        }
      }
      seen.add(expected);
      Color nameColor = firstFragmentColor(t.prepareRenderer(t.getCellRenderer(r, 0), r, 0));
      assertEquals("row " + r + " name colour", row.kind() == FlatRow.Kind.ATTR ? XmlColors.attrName() : XmlColors.tag(), nameColor);
    }
    assertTrue("checked the visible rows (" + checked + ")", checked >= 10);
    assertTrue("untinted, both bands, element and value tints (" + seen.size() + ")", seen.size() >= 5);
    assertTrue("names are not clipped", p.nameColumnWidthForTest() >= p.nameContentWidthForTest());
  }

  public void testCommentsPisAndDoctypeInFlat() throws Exception {
    String xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE c>\n<!-- top -->\n<c>\n  <!-- inside -->\n  <b id=\"1\"/>\n  <?pi data?>\n</c>\n";
    VirtualFile f = myFixture.configureByText("misc.xml", xml).getVirtualFile();
    flatEditor = (XmlFlatViewerEditor)new XmlFlatEditorProvider().createEditor(getProject(), f);
    FlatPanel p = flatEditor.getPanel();
    p.apply(ReadAction.compute(() -> XmlModelBuilder.build(getProject(), xml)));
    settle(p);
    List<FlatRow> rows = p.visibleRows();
    assertEquals(List.of("doctype", "comment", "element", "comment", "element", "attr", "pi"), rows.stream().map(FlatRow::kindId).toList());
    assertEquals(List.of(0, 0, 0, 1, 1, 2, 1), rows.stream().map(FlatRow::depth).toList());
    JBTable t = table(p, 2);
    t.clearSelection();
    assertNotBlank(paint(p, "flat-misc"));
    SimpleColoredComponent.ColoredIterator it = ((SimpleColoredComponent)t.prepareRenderer(t.getCellRenderer(1, 0), 1, 0)).iterator();
    it.next();
    assertEquals("comments are grey italic", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES, it.getTextAttributes());
  }

  public void testSelectionIsPaintedInFlat() throws Exception {
    FlatPanel p = flat();
    JBTable t = table(p, 2);
    p.selectRowsForTest(3, 3);
    BufferedImage img = paint(p, "flat-selected");
    Color sel = pixelInCell(img, p, t, 3, 1);
    Color plain = XmlColors.band(FlatRow.band(p.visibleRows().get(3).node(), FlatRow.sectionLevel(p.modelForTest())),
                                 p.visibleRows().get(3).isValueRow(), t.getBackground());
    assertFalse("selected row stands out", sel.equals(plain));
  }

  public void testOtherStatesPaint() throws Exception {
    XmlGridPanel g = grid();
    assertNotBlank(paint(g, "grid-root"));
    g.showGridForTest(g.model().byPath("0/0"), null);
    assertNotBlank(paint(g, "grid-fields"));
    g.showGridForTest(g.model().byPath("0/0/3"), "tag");
    assertNotBlank(paint(g, "grid-drill"));
    g.setColumnFilterForTest("@format", new dev.xmlgridview.intellij.model.ColumnFilter("e", null));
    assertNotBlank(paint(g, "grid-filtered"));

    FlatPanel f = flat();
    f.toggleForTest(0);
    assertNotBlank(paint(f, "flat-collapsed"));
    f.toggleForTest(0);
    f.selectInTreeForTest("0/2/1");
    assertNotBlank(paint(f, "flat-tree-select"));
  }
}
