package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * A per-column filter: a case-insensitive "contains" text and/or a set of
 * allowed cell values. A row passes a column's filter when both parts accept it.
 *
 * @param text    substring to look for, or "" for none
 * @param allowed allowed display values, or null to allow every value
 */
public record ColumnFilter(String text, @Nullable Set<String> allowed) {
  public boolean isActive() {
    return !text.isEmpty() || allowed != null;
  }

  public boolean accepts(String cellText) {
    if (!text.isEmpty() && !cellText.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) return false;
    return allowed == null || allowed.contains(cellText);
  }
}
