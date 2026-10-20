package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;

/**
 * An attribute with its decoded value and source span (UTF-16 offsets). Values that
 * appear verbatim in the source are not copied: they are read from the source text on
 * demand, which keeps large documents small in memory.
 */
public final class XAttr {
  private final String name;
  /** The decoded value, or null when it is {@code src[valueStart, valueEnd)}. */
  private final String value;
  private final String src;
  private final int valueStart;
  private final int valueEnd;
  private final int start;
  private final int end;

  XAttr(String name, String value, int start, int end) {
    this(name, value, null, 0, 0, start, end);
  }

  private XAttr(String name, String value, String src, int valueStart, int valueEnd, int start, int end) {
    this.name = name;
    this.value = value;
    this.src = src;
    this.valueStart = valueStart;
    this.valueEnd = valueEnd;
    this.start = start;
    this.end = end;
  }

  /** An attribute whose value is the verbatim source range {@code [valueStart, valueEnd)}. */
  static XAttr verbatim(String name, String src, int valueStart, int valueEnd, int start, int end) {
    return new XAttr(name, null, src, valueStart, valueEnd, start, end);
  }

  public @NotNull String name() { return name; }

  public @NotNull String value() { return value != null ? value : src.substring(valueStart, valueEnd); }

  public int start() { return start; }

  public int end() { return end; }

  @Override
  public String toString() {
    return name + "=\"" + value() + "\"";
  }
}
