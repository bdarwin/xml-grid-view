package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A comment, processing instruction or DOCTYPE, shown read-only among the elements in the tree
 * and Flat view. {@code before} is the number of element siblings that precede it (in
 * {@code parent}, or among top-level elements when {@code parent} is null).
 */
public record XMisc(@NotNull Kind kind, @NotNull String name, @NotNull String value, int start, int end, @Nullable XNode parent, int before) {
  public enum Kind {
    COMMENT("comment"), PI("pi"), DOCTYPE("doctype");

    private final String id;

    Kind(String id) { this.id = id; }

    public String id() { return id; }
  }

  /** How it is written, shortened: {@code <!-- text -->}, {@code <?target data?>}, {@code <!DOCTYPE …>}. */
  public @NotNull String display() {
    String v = value.replaceAll("\\s+", " ");
    if (v.length() > 200) v = v.substring(0, 199) + "…";
    return switch (kind) {
      case COMMENT -> "<!-- " + v + " -->";
      case PI -> "<?" + name + (v.isEmpty() ? "" : " " + v) + "?>";
      case DOCTYPE -> "<!DOCTYPE " + v + ">";
    };
  }
}
