package dev.xmlgridview.intellij.model;

public record SearchOptions(boolean caseSensitive, boolean wholeWord, boolean regex) {
  public static final SearchOptions DEFAULT = new SearchOptions(false, false, false);
}
