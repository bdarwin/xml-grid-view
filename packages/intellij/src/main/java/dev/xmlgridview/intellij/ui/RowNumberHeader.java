package dev.xmlgridview.intellij.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.event.TableModelEvent;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;

/** Row header painting 1-based view row numbers aligned with the table rows. */
final class RowNumberHeader extends JComponent {
  private final JTable table;

  RowNumberHeader(JTable table) {
    this.table = table;
    setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
    setForeground(UIUtil.getContextHelpForeground());
    table.getModel().addTableModelListener(e -> {
      if (e.getType() != TableModelEvent.UPDATE || e.getFirstRow() == TableModelEvent.HEADER_ROW) revalidate();
      repaint();
    });
    table.getSelectionModel().addListSelectionListener(e -> repaint());
    if (table.getRowSorter() != null) table.getRowSorter().addRowSorterListener(e -> {
      revalidate();
      repaint();
    });
  }

  @Override
  public Dimension getPreferredSize() {
    FontMetrics fm = getFontMetrics(getFont());
    int digits = Math.max(2, String.valueOf(Math.max(1, table.getRowCount())).length());
    int width = fm.charWidth('0') * digits + JBUI.scale(12);
    return new Dimension(width, table.getPreferredSize().height);
  }

  @Override
  protected void paintComponent(Graphics g) {
    Rectangle clip = g.getClipBounds();
    g.setColor(UIUtil.getPanelBackground());
    g.fillRect(clip.x, clip.y, clip.width, clip.height);
    if (table.getRowCount() == 0) return;
    int first = Math.max(0, table.rowAtPoint(new java.awt.Point(0, clip.y)));
    int last = table.rowAtPoint(new java.awt.Point(0, clip.y + clip.height));
    if (last < 0) last = table.getRowCount() - 1;
    FontMetrics fm = g.getFontMetrics(getFont());
    g.setFont(getFont());
    int[] selected = table.getSelectedRows();
    for (int r = first; r <= last; r++) {
      Rectangle cell = table.getCellRect(r, 0, true);
      boolean sel = java.util.Arrays.binarySearch(selected, r) >= 0;
      g.setColor(sel ? UIUtil.getLabelForeground() : getForeground());
      String s = String.valueOf(r + 1);
      int x = getWidth() - fm.stringWidth(s) - JBUI.scale(6);
      int y = cell.y + (cell.height + fm.getAscent() - fm.getDescent()) / 2;
      g.drawString(s, x, y);
    }
    g.setColor(JBColor.border());
    g.drawLine(getWidth() - 1, clip.y, getWidth() - 1, clip.y + clip.height);
  }
}
