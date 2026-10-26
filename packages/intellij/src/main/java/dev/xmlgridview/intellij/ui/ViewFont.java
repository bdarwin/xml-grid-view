package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.awt.Font;

/**
 * Values (grid cells, Flat view, tree) are shown in the editor font, like the IDE's database
 * results grid: the font and size the user reads XML in, rather than the smaller UI font. Follows
 * changes to the editor font.
 */
final class ViewFont {
  private ViewFont() {
  }

  static @NotNull Font editor() {
    return EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN);
  }

  /** Sets {@code c}'s font to the editor font now and whenever the color scheme or its font changes. */
  static void apply(@NotNull JComponent c, @NotNull Disposable parent) {
    c.setFont(editor());
    ApplicationManager.getApplication().getMessageBus().connect(parent).subscribe(EditorColorsManager.TOPIC, (EditorColorsListener)scheme -> {
      c.setFont(editor());
      c.revalidate();
      c.repaint();
    });
  }
}
