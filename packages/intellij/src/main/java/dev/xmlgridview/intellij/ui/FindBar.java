package dev.xmlgridview.intellij.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import dev.xmlgridview.intellij.model.SearchOptions;
import dev.xmlgridview.intellij.model.SearchTargets;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Find bar: query field with history, case/words/regex toggles, scope, targets,
 * "n of m", previous/next, XPath mode, "show only matches" and a results toggle.
 */
final class FindBar extends JPanel {
  enum Mode { TEXT, XPATH }

  enum Scope {
    GRID("Current grid"), DOCUMENT("Whole document");

    final String label;

    Scope(String label) { this.label = label; }
  }

  interface Listener {
    /** The query or any option changed (callers debounce). */
    void queryChanged();

    void next();

    void previous();

    void close();

    void showOnlyMatchesChanged();

    void toggleResults();
  }

  private final SearchTextField field = new SearchTextField(true, "XmlGridView.Find.History");
  private final ComboBox<Mode> mode = new ComboBox<>(Mode.values());
  private final ComboBox<Scope> scope = new ComboBox<>(Scope.values());
  private final JBCheckBox names = new JBCheckBox("Names", true);
  private final JBCheckBox attrNames = new JBCheckBox("Attr names", true);
  private final JBCheckBox attrValues = new JBCheckBox("Attr values", true);
  private final JBCheckBox text = new JBCheckBox("Text", true);
  private final JBCheckBox onlyMatches = new JBCheckBox("Show only matches", false);
  private final JBLabel counter = new JBLabel();
  private final JBLabel error = new JBLabel();
  private final LinkLabel<Void> resultsLink = new LinkLabel<>("Results", null);
  private final ActionToolbar toggles;
  private boolean caseSensitive;
  private boolean wholeWord;
  private boolean regex;

  FindBar(@NotNull Disposable parent, @NotNull Listener listener) {
    super(new BorderLayout());
    setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(2, 4)));

    mode.setRenderer(SimpleListCellRenderer.create("", m -> m == Mode.TEXT ? "Text" : "XPath"));
    scope.setRenderer(SimpleListCellRenderer.create("", s -> s.label));
    scope.setSelectedItem(Scope.DOCUMENT);
    error.setForeground(NamedColorUtil.getErrorForeground());
    counter.setForeground(UIUtil.getContextHelpForeground());

    DefaultActionGroup group = new DefaultActionGroup();
    group.add(toggle("Match Case", AllIcons.Actions.MatchCase, () -> caseSensitive, v -> caseSensitive = v, listener));
    group.add(toggle("Words", AllIcons.Actions.Words, () -> wholeWord, v -> wholeWord = v, listener));
    group.add(toggle("Regex", AllIcons.Actions.Regex, () -> regex, v -> regex = v, listener));
    group.addSeparator();
    group.add(simple("Previous Occurrence (Shift+Enter)", AllIcons.Actions.PreviousOccurence, listener::previous));
    group.add(simple("Next Occurrence (Enter)", AllIcons.Actions.NextOccurence, listener::next));
    group.add(simple("Close (Escape)", AllIcons.Actions.Close, listener::close));
    toggles = ActionManager.getInstance().createActionToolbar("XmlGridView.Find", group, true);
    toggles.setTargetComponent(this);
    toggles.getComponent().setBorder(JBUI.Borders.empty());

    JPanel row1 = new JPanel(new BorderLayout(JBUI.scale(4), 0));
    row1.add(mode, BorderLayout.WEST);
    row1.add(field, BorderLayout.CENTER);
    JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
    right.add(toggles.getComponent());
    right.add(scope);
    right.add(counter);
    row1.add(right, BorderLayout.EAST);

    JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
    row2.add(names);
    row2.add(attrNames);
    row2.add(attrValues);
    row2.add(text);
    row2.add(onlyMatches);
    row2.add(resultsLink);
    row2.add(error);

    JPanel rows = new JPanel();
    rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
    rows.add(row1);
    rows.add(row2);
    add(rows, BorderLayout.CENTER);

    field.addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        listener.queryChanged();
      }
    });
    mode.addActionListener(e -> {
      updateModeControls();
      listener.queryChanged();
    });
    scope.addActionListener(e -> listener.queryChanged());
    for (JBCheckBox cb : new JBCheckBox[]{names, attrNames, attrValues, text}) cb.addActionListener(e -> listener.queryChanged());
    onlyMatches.addActionListener(e -> listener.showOnlyMatchesChanged());
    resultsLink.setListener((l, d) -> listener.toggleResults(), null);

    JComponent editor = field.getTextEditor();
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        field.addCurrentTextToHistory();
        listener.next();
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)), editor, parent);
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        field.addCurrentTextToHistory();
        listener.previous();
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)),
                                editor, parent);
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        listener.close();
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)), this, parent);
    updateModeControls();
  }

  private void updateModeControls() {
    boolean textMode = mode() == Mode.TEXT;
    scope.setEnabled(textMode);
    names.setEnabled(textMode);
    attrNames.setEnabled(textMode);
    attrValues.setEnabled(textMode);
    text.setEnabled(textMode);
    field.getTextEditor().getEmptyText().setText(textMode ? "Find" : "XPath, e.g. //book[@id='1']  (default namespace prefix: d)");
    toggles.updateActionsAsync();
  }

  void focusField() {
    field.getTextEditor().requestFocusInWindow();
    field.getTextEditor().selectAll();
  }

  JComponent focusComponent() {
    return field.getTextEditor();
  }

  String query() {
    return field.getText();
  }

  void setQuery(String q) {
    field.setText(q);
  }

  Mode mode() {
    return (Mode)mode.getSelectedItem();
  }

  void setMode(Mode m) {
    mode.setSelectedItem(m);
  }

  Scope scope() {
    return (Scope)scope.getSelectedItem();
  }

  void setScope(Scope s) {
    scope.setSelectedItem(s);
  }

  SearchOptions options() {
    return new SearchOptions(caseSensitive, wholeWord, regex);
  }

  SearchTargets targets() {
    return new SearchTargets(names.isSelected(), attrNames.isSelected(), attrValues.isSelected(), text.isSelected());
  }

  boolean showOnlyMatches() {
    return onlyMatches.isSelected();
  }

  void setCounter(String s) {
    counter.setText(s);
  }

  void setError(String s) {
    error.setText(s);
    error.setToolTipText(s.isEmpty() ? null : s);
  }

  void setResultsLabel(String s) {
    resultsLink.setText(s);
  }

  private static ToggleAction toggle(String name, javax.swing.Icon icon, java.util.function.BooleanSupplier get,
                                     java.util.function.Consumer<Boolean> set, Listener l) {
    return new DumbAwareToggleAction(name, null, icon) {
      @Override
      public boolean isSelected(@NotNull AnActionEvent e) {
        return get.getAsBoolean();
      }

      @Override
      public void setSelected(@NotNull AnActionEvent e, boolean state) {
        set.accept(state);
        l.queryChanged();
      }

      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
      }
    };
  }

  private static AnAction simple(String name, javax.swing.Icon icon, Runnable r) {
    return new DumbAwareAction(name, null, icon) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        r.run();
      }
    };
  }
}
