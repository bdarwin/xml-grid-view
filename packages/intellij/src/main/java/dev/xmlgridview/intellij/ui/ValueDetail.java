package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.awt.Font;

/** Read-only, word-wrapped pane showing the full value of the selected tree node or grid cell. */
final class ValueDetail {
  private final JBTextArea area = new JBTextArea();

  ValueDetail() {
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setBorder(JBUI.Borders.empty(6, 8));
    var scheme = EditorColorsManager.getInstance().getGlobalScheme();
    area.setFont(new Font(scheme.getEditorFontName(), Font.PLAIN, scheme.getEditorFontSize()));
    area.getEmptyText().setText("Select an item to see its full value");
  }

  void show(@NotNull String text) {
    area.setText(text);
    area.setCaretPosition(0);
  }

  /** {@code main} on top, this pane below (resizable). */
  JComponent wrap(@NotNull JComponent main, @NotNull String proportionKey) {
    OnePixelSplitter split = new OnePixelSplitter(true, proportionKey, 0.72f);
    split.setFirstComponent(main);
    split.setSecondComponent(ScrollPaneFactory.createScrollPane(area, true));
    return split;
  }

  String textForTest() {
    return area.getText();
  }
}
