package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import dev.xmlgridview.intellij.model.GridColumn;
import dev.xmlgridview.intellij.model.GridTable;
import dev.xmlgridview.intellij.model.GroupInfo;
import dev.xmlgridview.intellij.model.InspectTarget;
import dev.xmlgridview.intellij.model.ValueEdits;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The selected element's own data above its grid: attributes, single leaf children and text (from
 * its one-row fields table), and chips for its child tag groups. Values are shown whole or not at
 * all on one line, the rest behind "+N more". Double-click edits a value in place, right-click
 * opens the structure menu for it. Mirrors the web view's Summary component.
 */
final class SummaryPanel extends JPanel {
  interface Listener {
    void chooseGroup(@NotNull String tag);

    boolean canEdit();

    /** Applies a value edit; returns null or why it was refused. */
    @Nullable String applyEdit(@NotNull ValueEdits.Target target, @NotNull String value);

    void inspect(@NotNull InspectTarget target);

    /** Values to offer while editing (e.g. a schema's enumeration). */
    default @NotNull java.util.List<String> suggestions(@NotNull ValueEdits.Target target) {
      return java.util.List.of();
    }
  }

  private static final int VALUE_CHARS = 80;

  private final Listener listener;
  private final JPanel values = new JPanel(new LineLayout());
  private final LinkLabel<Void> more = new LinkLabel<>("", null);
  private final JPanel valuesRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
  private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
  private @Nullable DefaultActionGroup menuActions;
  private @Nullable StructureActions.Target menuTarget;
  private @Nullable XmlDocumentModel model;
  private @Nullable GridTable fields;
  private @Nullable XNode shownElement;
  private boolean expanded;
  private int hidden;

  SummaryPanel(@NotNull Listener listener) {
    this.listener = listener;
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(3, 8)));
    values.setOpaque(false);
    more.setListener((l, d) -> {
      expanded = !expanded;
      values.revalidate();
      values.repaint();
      updateMore();
    }, null);
    valuesRow.setOpaque(false);
    valuesRow.add(values, BorderLayout.CENTER);
    valuesRow.add(more, BorderLayout.EAST);
    valuesRow.setAlignmentX(LEFT_ALIGNMENT);
    chips.setOpaque(false);
    chips.setAlignmentX(LEFT_ALIGNMENT);
    add(valuesRow);
    add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)));
    add(chips);
    setVisible(false);
  }

  /** Actions for the right-click menu; they act on {@link #menuTarget()}. */
  void setMenuActions(@NotNull DefaultActionGroup actions) {
    menuActions = actions;
  }

  @Nullable StructureActions.Target menuTarget() {
    return menuTarget;
  }

  /** Shows the summary for the grid {@code table} of {@code m}, or hides it. */
  void show(@Nullable XmlDocumentModel m, @Nullable GridTable table) {
    model = m;
    values.removeAll();
    chips.removeAll();
    fields = null;
    if (m == null || table == null || table.group() == null) {
      shownElement = null;
      setVisible(false);
      return;
    }
    if (table.element() != shownElement) expanded = false;
    shownElement = table.element();
    boolean selfView = XmlDocumentModel.SELF_GROUP.equals(table.group());
    GridTable f = selfView ? table : m.table(table.element(), XmlDocumentModel.SELF_GROUP);
    fields = f;
    Set<String> complex = new HashSet<>();
    List<Item> items = new ArrayList<>();
    for (int c = 0; c < f.columnCount(); c++) {
      GridColumn col = f.columns().get(c);
      if (col.kind() == GridColumn.Kind.COMPLEX) complex.add(col.label());
      else if (f.cell(0, c) instanceof String s && !col.label().equals(table.group())) items.add(new Item(c, col, s));
    }
    boolean showValues = !selfView && !items.isEmpty();
    if (showValues) for (Item it : items) values.add(it);
    valuesRow.setVisible(showValues);

    List<GroupInfo> chipGroups = new ArrayList<>();
    for (GroupInfo g : table.groups()) if (complex.contains(g.tag()) || g.tag().equals(table.group())) chipGroups.add(g);
    SimpleColoredComponent label = new SimpleColoredComponent();
    label.append("Rows", SimpleTextAttributes.GRAYED_ATTRIBUTES);
    label.setOpaque(false);
    label.setIpad(JBUI.insetsRight(2));
    chips.add(label);
    for (GroupInfo g : chipGroups) chips.add(new Chip(g.tag(), g.count(), g.tag().equals(table.group())));
    chips.add(new Chip(XmlDocumentModel.SELF_GROUP, 0, selfView));

    setVisible(showValues || !chipGroups.isEmpty());
    updateMore();
    revalidate();
    repaint();
  }

  private void updateMore() {
    more.setText(expanded ? "less" : "+" + hidden + " more");
    more.setVisible(expanded || hidden > 0);
  }

  private void openMenu(@NotNull MouseEvent e, @NotNull StructureActions.Target target) {
    if (menuActions == null) return;
    menuTarget = target;
    ActionPopupMenu popup = ActionManager.getInstance().createActionPopupMenu("XmlGridView.Summary", menuActions);
    popup.setTargetComponent(this);
    popup.getComponent().show(e.getComponent(), e.getX(), e.getY());
  }

  private void edit(@NotNull Item item, @NotNull ValueEdits.Target target) {
    java.util.List<String> suggested = listener.suggestions(target);
    if (!suggested.isEmpty()) {
      editWithSuggestions(item, target, suggested);
      return;
    }
    JBTextField field = new JBTextField(item.value);
    field.setColumns(Math.min(40, Math.max(12, item.value.length() + 2)));
    field.selectAll();
    JBPopup popup = JBPopupFactory.getInstance().createComponentPopupBuilder(field, field)
      .setRequestFocus(true)
      .setCancelOnClickOutside(true)
      .createPopup();
    field.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
          e.consume();
          String v = field.getText();
          popup.closeOk(null);
          if (!v.equals(item.value)) commit(target, v);
        }
        else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
          e.consume();
          popup.cancel();
        }
      }
    });
    popup.show(new com.intellij.ui.awt.RelativePoint(item, new java.awt.Point(0, 0)));
  }

  /** Editing a value that has suggestions: an editable combo box whose list opens right away. */
  private void editWithSuggestions(@NotNull Item item, @NotNull ValueEdits.Target target, @NotNull java.util.List<String> suggested) {
    com.intellij.openapi.ui.ComboBox<String> combo = new com.intellij.openapi.ui.ComboBox<>(suggested.toArray(String[]::new));
    combo.setEditable(true);
    combo.getEditor().setItem(item.value);
    JBPopup popup = JBPopupFactory.getInstance().createComponentPopupBuilder(combo, (javax.swing.JComponent)combo.getEditor().getEditorComponent())
      .setRequestFocus(true)
      .setCancelOnClickOutside(true)
      .createPopup();
    combo.addActionListener(e -> {
      if (!"comboBoxEdited".equals(e.getActionCommand()) && combo.isPopupVisible()) return;
      Object v = combo.getEditor().getItem();
      String value = v == null ? "" : v.toString();
      popup.closeOk(null);
      if (!value.equals(item.value)) commit(target, value);
    });
    lastSuggestions = suggested;
    popup.show(new com.intellij.ui.awt.RelativePoint(item, new java.awt.Point(0, 0)));
    javax.swing.SwingUtilities.invokeLater(() -> {
      if (combo.isShowing()) combo.showPopup();
    });
  }

  private java.util.List<String> lastSuggestions = java.util.List.of();

  @org.jetbrains.annotations.TestOnly
  java.util.List<String> lastSuggestionsForTest() {
    return lastSuggestions;
  }

  private void commit(@NotNull ValueEdits.Target target, @NotNull String value) {
    String error = listener.applyEdit(target, value);
    if (error != null) com.intellij.openapi.ui.Messages.showErrorDialog(this, error, "Can't Edit the XML");
  }

  /** One value: name and (possibly cut) value, an inspect icon for long or JSON values. */
  private final class Item extends SimpleColoredComponent {
    final int col;
    final GridColumn column;
    final String value;

    Item(int col, GridColumn column, String value) {
      this.col = col;
      this.column = column;
      this.value = value;
      setOpaque(false);
      setTransparentIconBackground(true);
      setIpad(JBUI.emptyInsets());
      Color nameColor = switch (column.kind()) {
        case ATTR -> XmlColors.attrName();
        case TEXT -> UIUtil.getContextHelpForeground();
        default -> XmlColors.tag();
      };
      append(column.kind() == GridColumn.Kind.TEXT ? "text" : column.label(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, nameColor));
      append("  ");
      String shown = value.replaceAll("\\s+", " ").strip();
      if (shown.length() > VALUE_CHARS) shown = shown.substring(0, VALUE_CHARS - 1) + "…";
      append(shown, column.kind() == GridColumn.Kind.ATTR
                    ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, XmlColors.attrValue())
                    : SimpleTextAttributes.REGULAR_ATTRIBUTES);
      boolean inspectable = InspectAction.worthInspecting(value);
      if (inspectable) {
        setIcon(InspectAction.CELL_ICON);
        setIconOnTheRight(true);
      }
      setToolTipText(column.label() + ": " + (value.length() > 1000 ? value.substring(0, 1000) + "…" : value));
      addMouseListener(new MouseAdapter() {
        @Override
        public void mousePressed(MouseEvent e) {
          if (e.isPopupTrigger()) popup(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
          if (e.isPopupTrigger()) popup(e);
        }

        @Override
        public void mouseClicked(MouseEvent e) {
          if (!SwingUtilities.isLeftMouseButton(e)) return;
          boolean onIcon = inspectable && e.getX() >= getWidth() - InspectAction.CELL_ICON.getIconWidth() - JBUI.scale(4);
          if (onIcon && e.getClickCount() == 1) inspect();
          else if (e.getClickCount() == 2) activate();
        }
      });
    }

    private void popup(MouseEvent e) {
      StructureActions.Target t = target();
      if (t != null) openMenu(e, t);
    }

    void activate() {
      ValueEdits.Target t = fields == null || !listener.canEdit() ? null : ValueEdits.gridTarget(fields, 0, col);
      if (t != null) edit(this, t);
      else inspect();
    }

    void inspect() {
      InspectTarget t = fields == null ? null : InspectTarget.ofGridCell(fields, 0, col);
      if (t != null) listener.inspect(t);
    }

    /** The element (and attribute) this value belongs to, for the structure menu. */
    @Nullable StructureActions.Target target() {
      if (model == null || fields == null) return null;
      XNode el = fields.rows().get(0);
      if (column.kind() == GridColumn.Kind.ATTR) return new StructureActions.Target(model, el, column.label().substring(1));
      if (column.kind() == GridColumn.Kind.LEAF) {
        for (XNode ch : el.children()) if (ch.tag().equals(column.label())) return new StructureActions.Target(model, ch, null);
      }
      return new StructureActions.Target(model, el, null);
    }
  }

  /** A rounded group button: tag and count, filled when it is the grid's group. */
  private final class Chip extends SimpleColoredComponent {
    final String tag;
    final boolean active;

    Chip(String tag, int count, boolean active) {
      this.tag = tag;
      this.active = active;
      setOpaque(false);
      setIpad(JBUI.insets(1, 8));
      Color fg = active ? UIUtil.getListSelectionForeground(true) : UIUtil.getLabelForeground();
      append(XmlDocumentModel.SELF_GROUP.equals(tag) ? "(fields)" : tag, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fg));
      if (count > 1) append(" " + count, new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, active ? fg : UIUtil.getContextHelpForeground()));
      setToolTipText(XmlDocumentModel.SELF_GROUP.equals(tag) ? "This element as a single row, every attribute and child as a column"
                                                             : "One row per <" + tag + "> child");
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      getAccessibleContext().setAccessibleName((XmlDocumentModel.SELF_GROUP.equals(tag) ? "fields" : tag) + (active ? ", selected" : ""));
      addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          if (SwingUtilities.isLeftMouseButton(e)) listener.chooseGroup(tag);
        }
      });
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D)g.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = getHeight();
        if (active) {
          g2.setColor(UIUtil.getListSelectionBackground(true));
          g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        }
        else {
          g2.setColor(JBColor.border());
          g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        }
      }
      finally {
        g2.dispose();
      }
      super.paintComponent(g);
    }
  }

  /**
   * Lays values out on one line, each at most min(340, 45% of the width); values that don't fit
   * whole get no space and are counted as hidden. Expanded, values wrap onto further lines.
   */
  private final class LineLayout implements LayoutManager {
    private final int gap = JBUI.scale(14);
    private final int vgap = JBUI.scale(3);

    @Override
    public void addLayoutComponent(String name, Component comp) {
    }

    @Override
    public void removeLayoutComponent(Component comp) {
    }

    private int cap(int width) {
      return Math.max(JBUI.scale(60), Math.min(JBUI.scale(340), width * 45 / 100));
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
      int width = parent.getWidth() > 0 ? parent.getWidth() : Integer.MAX_VALUE / 2;
      int rowH = 0;
      for (Component c : parent.getComponents()) rowH = Math.max(rowH, c.getPreferredSize().height);
      if (!expanded) return new Dimension(0, rowH);
      int x = 0;
      int lines = parent.getComponentCount() == 0 ? 0 : 1;
      for (Component c : parent.getComponents()) {
        int w = Math.min(c.getPreferredSize().width, cap(width));
        if (x > 0 && x + w > width) {
          lines++;
          x = 0;
        }
        x += w + gap;
      }
      return new Dimension(0, lines * rowH + Math.max(0, lines - 1) * vgap);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
      return preferredLayoutSize(parent);
    }

    @Override
    public void layoutContainer(Container parent) {
      int width = parent.getWidth();
      int x = 0;
      int y = 0;
      int rowH = 0;
      for (Component c : parent.getComponents()) rowH = Math.max(rowH, c.getPreferredSize().height);
      int n = 0;
      boolean full = false;
      for (Component c : parent.getComponents()) {
        int w = Math.min(c.getPreferredSize().width, cap(width));
        if (expanded) {
          if (x > 0 && x + w > width) {
            x = 0;
            y += rowH + vgap;
          }
        }
        else if (full || x + w > width) {
          full = true;
          c.setBounds(0, 0, 0, 0);
          continue;
        }
        c.setBounds(x, y, w, rowH);
        x += w + gap;
        n++;
      }
      int nowHidden = expanded ? 0 : parent.getComponentCount() - n;
      if (nowHidden != hidden) {
        hidden = nowHidden;
        SwingUtilities.invokeLater(SummaryPanel.this::updateMore);
      }
    }
  }

  // ---- Test hooks ------------------------------------------------------------------------------

  @TestOnly
  List<String> valueLabelsForTest() {
    List<String> out = new ArrayList<>();
    for (Component c : values.getComponents()) if (c instanceof Item it && c.getWidth() > 0) out.add(it.column.label());
    return out;
  }

  @TestOnly
  List<SimpleColoredComponent> chipComponentsForTest() {
    List<SimpleColoredComponent> out = new ArrayList<>();
    for (Component c : chips.getComponents()) if (c instanceof Chip ch) out.add(ch);
    return out;
  }

  @TestOnly
  List<SimpleColoredComponent> itemComponentsForTest() {
    List<SimpleColoredComponent> out = new ArrayList<>();
    for (Component c : values.getComponents()) if (c instanceof Item it) out.add(it);
    return out;
  }

  /** What editing the value labelled {@code label} would offer (tests; the popup itself needs a screen). */
  @TestOnly
  List<String> suggestionsForTest(@NotNull String label) {
    ValueEdits.Target t = editTargetForTest(label);
    return t == null ? List.of() : listener.suggestions(t);
  }

  @TestOnly
  int hiddenForTest() {
    return hidden;
  }

  @TestOnly
  List<String> chipsForTest() {
    List<String> out = new ArrayList<>();
    for (Component c : chips.getComponents()) if (c instanceof Chip ch) out.add((ch.active ? "*" : "") + ch.tag);
    return out;
  }

  @TestOnly
  void clickChipForTest(@NotNull String tag) {
    listener.chooseGroup(tag);
  }

  @TestOnly
  void toggleMoreForTest() {
    expanded = !expanded;
    values.revalidate();
    updateMore();
  }

  @TestOnly
  @Nullable StructureActions.Target targetForTest(@NotNull String label) {
    for (Component c : values.getComponents()) if (c instanceof Item it && it.column.label().equals(label)) return it.target();
    return null;
  }

  @TestOnly
  @Nullable ValueEdits.Target editTargetForTest(@NotNull String label) {
    for (Component c : values.getComponents()) {
      if (c instanceof Item it && it.column.label().equals(label)) return fields == null ? null : ValueEdits.gridTarget(fields, 0, it.col);
    }
    return null;
  }

  @TestOnly
  @Nullable String commitForTest(@NotNull String label, @NotNull String value) {
    ValueEdits.Target t = editTargetForTest(label);
    return t == null ? "not editable" : listener.applyEdit(t, value);
  }

  @TestOnly
  void setMenuTargetForTest(@Nullable StructureActions.Target t) {
    menuTarget = t;
  }
}
