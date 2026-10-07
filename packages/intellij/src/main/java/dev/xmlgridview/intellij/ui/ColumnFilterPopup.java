package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import dev.xmlgridview.intellij.model.ColumnFilter;
import dev.xmlgridview.intellij.model.GridFilter;
import dev.xmlgridview.intellij.model.GridTable;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Popup with a "contains" text filter and a checklist of distinct values
 * (capped at {@link GridFilter#DISTINCT_LIMIT}).
 */
final class ColumnFilterPopup {
  private ColumnFilterPopup() {
  }

  static void show(JComponent owner, Rectangle anchor, GridTable table, int col, @Nullable ColumnFilter current,
                   Consumer<@Nullable ColumnFilter> apply) {
    GridFilter.DistinctValues distinct = GridFilter.distinctValues(table, col);
    List<String> values = distinct.values();

    JBTextField text = new JBTextField(current == null ? "" : current.text());
    text.getEmptyText().setText("Contains…");
    CheckBoxList<String> list = new CheckBoxList<>();
    JBTextField valueSearch = new JBTextField();
    valueSearch.getEmptyText().setText("Search values");

    Set<String> checked = new HashSet<>();
    if (current == null || current.allowed() == null) checked.addAll(values);
    else checked.addAll(current.allowed());
    Runnable fill = () -> {
      String q = valueSearch.getText().toLowerCase(Locale.ROOT);
      list.clear();
      for (String v : values) {
        if (!q.isEmpty() && !v.toLowerCase(Locale.ROOT).contains(q)) continue;
        list.addItem(v, v.isEmpty() ? "(empty)" : v, checked.contains(v));
      }
    };
    fill.run();
    list.setCheckBoxListListener((index, value) -> {
      String v = list.getItemAt(index);
      if (v == null) return;
      if (value) checked.add(v);
      else checked.remove(v);
    });
    valueSearch.getDocument().addDocumentListener(new com.intellij.ui.DocumentAdapter() {
      @Override
      protected void textChanged(DocumentEvent e) {
        fill.run();
      }
    });

    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    panel.setBorder(JBUI.Borders.empty(8));
    JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    top.add(label("Text filter (" + table.columns().get(col).label() + ")"));
    top.add(text);
    top.add(label("Values"));
    JPanel bulk = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
    bulk.add(new LinkLabel<>("Select all", null, (l, d) -> {
      checked.addAll(values);
      fill.run();
    }));
    bulk.add(new LinkLabel<>("Select none", null, (l, d) -> {
      checked.clear();
      fill.run();
    }));
    bulk.setAlignmentX(0f);
    top.add(bulk);
    top.add(valueSearch);
    panel.add(top, BorderLayout.NORTH);
    JScrollPane scroll = ScrollPaneFactory.createScrollPane(list);
    scroll.setPreferredSize(JBUI.size(260, 220));
    panel.add(scroll, BorderLayout.CENTER);

    JPanel bottom = new JPanel(new BorderLayout());
    if (distinct.capped()) {
      JBLabel note = new JBLabel("Showing the first " + GridFilter.DISTINCT_LIMIT + " distinct values");
      note.setForeground(UIUtil.getContextHelpForeground());
      note.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
      bottom.add(note, BorderLayout.NORTH);
    }
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
    JButton clear = new JButton("Clear");
    JButton ok = new JButton("Apply");
    buttons.add(clear);
    buttons.add(ok);
    bottom.add(buttons, BorderLayout.SOUTH);
    panel.add(bottom, BorderLayout.SOUTH);

    JBPopup popup = JBPopupFactory.getInstance()
      .createComponentPopupBuilder(panel, text)
      .setRequestFocus(true)
      .setFocusable(true)
      .setCancelOnClickOutside(true)
      .setMovable(true)
      .setResizable(true)
      .setTitle("Filter")
      .createPopup();
    ok.addActionListener(e -> {
      // Every listed value checked means no value filter. With a capped list, unchecking a value
      // restricts the column to the checked values, so values beyond the cap are hidden too.
      Set<String> allowed = checked.containsAll(values) ? null : new HashSet<>(checked);
      apply.accept(new ColumnFilter(text.getText().trim(), allowed));
      popup.closeOk(null);
    });
    clear.addActionListener(e -> {
      apply.accept(null);
      popup.closeOk(null);
    });
    text.addActionListener(e -> ok.doClick());
    popup.show(new RelativePoint(owner, new Point(anchor.x, anchor.y + anchor.height)));
  }

  private static JBLabel label(String s) {
    JBLabel l = new JBLabel(s);
    l.setBorder(JBUI.Borders.emptyTop(4));
    l.setAlignmentX(0f);
    return l;
  }
}
