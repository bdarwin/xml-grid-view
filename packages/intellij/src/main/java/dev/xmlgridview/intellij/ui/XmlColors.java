package dev.xmlgridview.intellij.ui;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.XmlHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;

/**
 * Colors for tags, attribute names and values, taken from the editor's XML colors so they follow
 * the user's theme. When a scheme leaves a color unset, or it is too close to plain text to tell
 * apart, a distinct theme-aware fallback is used. Looked up on each use, so scheme changes apply at once.
 */
final class XmlColors {
  private static final JBColor TAG_FALLBACK = JBColor.namedColor("XmlGridView.tagColor", new JBColor(0x0033B3, 0xE8BF6A));
  private static final JBColor ATTR_NAME_FALLBACK = JBColor.namedColor("XmlGridView.attrNameColor", new JBColor(0x871094, 0xC77DBB));
  private static final JBColor ATTR_VALUE_FALLBACK = JBColor.namedColor("XmlGridView.attrValueColor", new JBColor(0x067D17, 0x6A8759));

  private XmlColors() {
  }

  private static @Nullable Color fg(TextAttributesKey key) {
    TextAttributes a = EditorColorsManager.getInstance().getGlobalScheme().getAttributes(key);
    return a == null ? null : a.getForegroundColor();
  }

  /** The first scheme color that stands out from regular text, else the fallback. */
  private static @NotNull Color pick(@NotNull Color fallback, TextAttributesKey... keys) {
    Color text = UIUtil.getLabelForeground();
    for (TextAttributesKey k : keys) {
      Color c = fg(k);
      if (c != null && distance(c, text) > 140) return c;
    }
    return fallback;
  }

  private static int distance(Color a, Color b) {
    return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen()) + Math.abs(a.getBlue() - b.getBlue());
  }

  static @NotNull Color tag() {
    return pick(TAG_FALLBACK, XmlHighlighterColors.XML_TAG_NAME, DefaultLanguageHighlighterColors.MARKUP_TAG);
  }

  static @NotNull Color attrName() {
    return pick(ATTR_NAME_FALLBACK, XmlHighlighterColors.XML_ATTRIBUTE_NAME, DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE);
  }

  static @NotNull Color attrValue() {
    return pick(ATTR_VALUE_FALLBACK, XmlHighlighterColors.XML_ATTRIBUTE_VALUE, DefaultLanguageHighlighterColors.STRING);
  }

  static @NotNull SimpleTextAttributes tagAttrs(int style) {
    return new SimpleTextAttributes(style, tag());
  }

  static @NotNull SimpleTextAttributes attrNameAttrs(int style) {
    return new SimpleTextAttributes(style, attrName());
  }

  static @NotNull SimpleTextAttributes attrValueAttrs(int style) {
    return new SimpleTextAttributes(style, attrValue());
  }

  /** Alternating row background, visible on light and dark themes, that keeps grid lines (JBTable striping hides them). */
  static @NotNull Color stripe(int row, @NotNull Color base) {
    if (row % 2 == 0) return base;
    return ColorUtil.mix(base, UIUtil.getLabelForeground(), 0.045);
  }
}
