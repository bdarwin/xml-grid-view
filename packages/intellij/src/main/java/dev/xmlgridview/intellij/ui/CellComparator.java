package dev.xmlgridview.intellij.ui;

import java.util.Comparator;

/** Sorts numbers numerically, other text case-insensitively; empty cells sort last when ascending. */
final class CellComparator implements Comparator<Object> {
  static final CellComparator INSTANCE = new CellComparator();

  @Override
  public int compare(Object o1, Object o2) {
    String a = String.valueOf(o1);
    String b = String.valueOf(o2);
    if (a.isEmpty() || b.isEmpty()) return Boolean.compare(a.isEmpty(), b.isEmpty());
    Double x = number(a);
    Double y = number(b);
    if (x != null && y != null) return Double.compare(x, y);
    if (x != null) return -1;
    if (y != null) return 1;
    int c = a.compareToIgnoreCase(b);
    return c != 0 ? c : a.compareTo(b);
  }

  private static Double number(String s) {
    char c = s.charAt(0);
    if (!(Character.isDigit(c) || c == '-' || c == '+' || c == '.')) return null;
    try {
      return Double.parseDouble(s.trim());
    }
    catch (NumberFormatException e) {
      return null;
    }
  }
}
