package dev.xmlgridview.intellij.ui;

import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;

import javax.swing.JList;
import java.util.function.Function;

/** Combo-box renderer showing a text label per item (replaces the deprecated SimpleListCellRenderer.create). */
final class TextListRenderer<T> extends SimpleListCellRenderer<T> {
  private final Function<? super T, String> text;

  TextListRenderer(@NotNull Function<? super T, String> text) {
    this.text = text;
  }

  @Override
  public void customize(@NotNull JList<? extends T> list, T value, int index, boolean selected, boolean hasFocus) {
    setText(value == null ? "" : text.apply(value));
  }
}
