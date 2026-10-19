package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable parsed document: the element tree, errors and the source text it was built from. */
public final class XmlDocumentModel {
  private final @Nullable XNode root;
  private final List<XNode> elements;
  private final List<ParseError> errors;
  private final String text;
  private final Map<String, GridTable> tableCache = new ConcurrentHashMap<>();

  XmlDocumentModel(@Nullable XNode root, List<XNode> elements, List<ParseError> errors, String text) {
    this.root = root;
    this.elements = List.copyOf(elements);
    this.errors = List.copyOf(errors);
    this.text = text;
  }

  public @Nullable XNode root() { return root; }

  /** All elements in document order. */
  public @NotNull List<XNode> elements() { return elements; }

  public @NotNull List<ParseError> errors() { return errors; }

  public boolean hasErrors() { return !errors.isEmpty(); }

  public @NotNull String text() { return text; }

  /** Resolves a path key like "0/2/1"; null when it doesn't exist. */
  public @Nullable XNode byPath(@Nullable String path) {
    if (path == null || path.isEmpty() || root == null) return null;
    String[] parts = path.split("/");
    if (!parts[0].equals("0")) return null;
    XNode n = root;
    for (int i = 1; i < parts.length; i++) {
      int idx;
      try {
        idx = Integer.parseInt(parts[i]);
      } catch (NumberFormatException e) {
        return null;
      }
      if (idx < 0 || idx >= n.children().size()) return null;
      n = n.children().get(idx);
    }
    return n;
  }

  /** Distinct child tags of an element in order of first appearance, with counts. */
  public static @NotNull List<GroupInfo> groups(@NotNull XNode el) {
    Map<String, int[]> map = new LinkedHashMap<>();
    for (XNode c : el.children()) map.computeIfAbsent(c.tag(), k -> new int[1])[0]++;
    List<GroupInfo> out = new ArrayList<>(map.size());
    map.forEach((k, v) -> out.add(new GroupInfo(k, v[0])));
    return out;
  }

  /**
   * Pseudo group for the one-row "fields" grid of an element with element children
   * (attributes and children as columns). "." can never be a tag name.
   */
  public static final String SELF_GROUP = ".";

  /** The grid shown on selection: the first repeating tag, else the fields grid; null without children. */
  public static @Nullable String defaultGroup(@NotNull List<GroupInfo> groups) {
    if (groups.isEmpty()) return null;
    for (GroupInfo g : groups) if (g.count() > 1) return g.tag();
    return SELF_GROUP;
  }

  /**
   * The grid for an element and tag group (or SELF_GROUP). Unknown or null groups
   * fall back to defaultGroup(); an element without element children yields a
   * one-row table with group null.
   */
  public @NotNull GridTable table(@NotNull XNode el, @Nullable String group) {
    List<GroupInfo> groups = groups(el);
    String g = null;
    if (!groups.isEmpty() && SELF_GROUP.equals(group)) g = SELF_GROUP;
    for (GroupInfo gi : groups) if (gi.tag().equals(group)) g = gi.tag();
    if (g == null) g = defaultGroup(groups);
    String key = el.id() + "\u0000" + (g == null ? "" : g);
    String finalG = g;
    return tableCache.computeIfAbsent(key, k -> GridBuilder.build(el, finalG, groups));
  }
}
