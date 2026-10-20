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
