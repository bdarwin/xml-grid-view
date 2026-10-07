package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An element of the immutable node model. Fields mirror the canonical tree
 * format in fixtures/SCHEMA.md. Instances are created by {@link XmlModelBuilder}
 * and never change after the model is built.
 */
public final class XNode {
  private final int id;
  private final String tag;
  private final String ns;
  private final int start;
  private final int end;
  private final List<XAttr> attrs;
  private final Map<String, String> nsDecls;
  private final String text;
  private final int textStart;
  private final @Nullable XNode parent;
  private final int index;
  private final String path;
  private final List<XNode> children = new ArrayList<>();
  private final List<XNode> childrenView = Collections.unmodifiableList(children);

  XNode(int id, String tag, String ns, int start, int end, List<XAttr> attrs, Map<String, String> nsDecls,
        String text, int textStart, @Nullable XNode parent, int index) {
    this.id = id;
    this.tag = tag;
    this.ns = ns;
    this.start = start;
    this.end = end;
    this.attrs = List.copyOf(attrs);
    this.nsDecls = Collections.unmodifiableMap(nsDecls);
    this.text = text;
    this.textStart = textStart;
    this.parent = parent;
    this.index = index;
    this.path = parent == null ? Integer.toString(index) : parent.path + "/" + index;
  }

  void addChild(XNode child) {
    children.add(child);
  }

  /** Document-order index, also the index into {@link XmlDocumentModel#elements()}. */
  public int id() { return id; }

  /** Qualified name as written. */
  public @NotNull String tag() { return tag; }

  /** Resolved namespace URI, or "". */
  public @NotNull String ns() { return ns; }

  public int start() { return start; }

  public int end() { return end; }

  /** Attributes in source order, excluding namespace declarations. */
  public @NotNull List<XAttr> attrs() { return attrs; }

  /** Namespace declarations on this element: prefix (or "" for default) to URI, in source order. */
  public @NotNull Map<String, String> nsDecls() { return nsDecls; }

  /** Element text: direct text/CDATA concatenated, XML-whitespace trimmed. */
  public @NotNull String text() { return text; }

  /** Offset of the first non-blank direct text, or -1. */
  public int textStart() { return textStart; }

  public @Nullable XNode parent() { return parent; }

  /** Index among the parent's element children (or among top-level elements). */
  public int index() { return index; }

  /** Child-index path key, e.g. "0/2/1". */
  public @NotNull String path() { return path; }

  /** Element children. */
  public @NotNull List<XNode> children() { return childrenView; }

  /** No element children and no attributes. */
  public boolean isLeaf() { return children.isEmpty() && attrs.isEmpty(); }

  public @Nullable XAttr attr(String name) {
    for (XAttr a : attrs) if (a.name().equals(name)) return a;
    return null;
  }

  @Override
  public String toString() {
    return "<" + tag + "> " + path;
  }
}
