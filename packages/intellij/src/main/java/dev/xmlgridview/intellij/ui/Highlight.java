package dev.xmlgridview.intellij.ui;

import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import dev.xmlgridview.intellij.model.Matcher;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Appends text to a SimpleColoredComponent, marking matches with SEARCH_MATCH attributes. */
final class Highlight {
  private Highlight() {
  }

  static void append(SimpleColoredComponent c, String text, SimpleTextAttributes base, @Nullable Matcher m) {
    if (m == null || text.isEmpty()) {
      c.append(text, base);
      return;
    }
    List<int[]> ranges = m.ranges(text);
    if (ranges.isEmpty()) {
      c.append(text, base);
      return;
    }
    SimpleTextAttributes match = new SimpleTextAttributes(
      base.getBgColor(), base.getFgColor(), base.getWaveColor(), base.getStyle() | SimpleTextAttributes.STYLE_SEARCH_MATCH);
    int at = 0;
    for (int[] r : ranges) {
      if (r[0] > at) c.append(text.substring(at, r[0]), base);
      c.append(text.substring(r[0], r[1]), match);
      at = r[1];
    }
    if (at < text.length()) c.append(text.substring(at), base);
  }
}
