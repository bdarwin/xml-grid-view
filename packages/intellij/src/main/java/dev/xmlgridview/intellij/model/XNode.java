package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

/**
 * An element of the immutable node model. Fields mirror the canonical tree
 * format in fixtures/SCHEMA.md. Instances are created by {@link XmlFastParser}
 * and never change after the model is built.
 *
 * <p>Kept small, since large documents have millions of elements: text that appears
 * verbatim in the source is read from it on demand, and the path is computed on demand.
 */
public final class XNode {
  private static final XNode[] NO_CHILDREN = new XNode[0];

  private final int id;
  private final String tag;
  private final String ns;
  private final int start;
  private int end;
  private final List<XAttr> attrs;
  private final Map<String, String> nsDecls;
  /** The element text, or null when it is {@code src[textStart, textEnd)} (or empty when textStart is -1). */
  private @Nullable String text;
  private int textStart = -1;
  private int textEnd;
  private final String src;
  private final @Nullable XNode parent;
  private final int index;
  private XNode[] children = NO_CHILDREN;
  /** JSON documents: index into {@link #JSON_TYPES} plus one; 0 for XML elements. */
  private byte json;

  /** JSON value types, as in the fixtures. */
  public static final String[] JSON_TYPES = {"object", "array", "string", "number", "boolean", "null"};

  XNode(int id, String tag, String ns, int start, List<XAttr> attrs, Map<String, String> nsDecls,
        String src, @Nullable XNode parent, int index) {
    this.id = id;
    this.tag = tag;
    this.ns = ns;
    this.start = start;
    this.end = -1;
    this.attrs = attrs.isEmpty() ? List.of() : List.copyOf(attrs);
    this.nsDecls = nsDecls.isEmpty() ? Map.of() : Collections.unmodifiableMap(nsDecls);
    this.src = src;
    this.parent = parent;
    this.index = index;
  }

  /**
   * Completes an element once its end tag (or the end of input) is reached; used by the streaming parser.
   * {@code text} null means the text is the source range {@code [textStart, textEnd)}.
   */
  void finish(int end, @Nullable String text, int textStart, int textEnd, XNode @Nullable [] children) {
    this.end = end;
    this.text = text;
    this.textStart = textStart;
    this.textEnd = textEnd;
    if (children != null) this.children = children;
  }

  void setJsonType(int type) {
    this.json = (byte)(type + 1);
  }

  /** In a JSON document: object, array, string, number, boolean or null; null for XML elements. */
  public @Nullable String jsonType() {
    return json == 0 ? null : JSON_TYPES[json - 1];
  }

  /** How the element is labelled: its tag, or {@code [i]} for an item of a JSON array. */
  public @NotNull String displayName() {
    return parent != null && "array".equals(parent.jsonType()) ? "[" + index + "]" : tag;
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
  public @NotNull String text() {
    if (text != null) return text;
    return textStart < 0 ? "" : src.substring(textStart, textEnd);
  }

  /** Whether {@link #text()} is non-empty, without building it. */
  public boolean hasText() {
    return textStart >= 0;
  }

  /** Offset of the first non-blank direct text, or -1. */
  public int textStart() { return textStart; }

  public @Nullable XNode parent() { return parent; }

  /** Index among the parent's element children (or among top-level elements). */
  public int index() { return index; }

  /** Number of ancestors (0 for the root). */
  public int depth() {
    int d = 0;
    for (XNode a = parent; a != null; a = a.parent) d++;
    return d;
  }

  /** Child-index path key, e.g. "0/2/1". */
  public @NotNull String path() {
    if (parent == null) return Integer.toString(index);
    int[] idx = new int[depth() + 1];
    int i = idx.length;
    for (XNode a = this; a != null; a = a.parent) idx[--i] = a.index;
    StringBuilder sb = new StringBuilder(idx.length * 4);
    for (int k = 0; k < idx.length; k++) {
      if (k > 0) sb.append('/');
      sb.append(idx[k]);
    }
    return sb.toString();
  }

  /** Element children. */
  public @NotNull List<XNode> children() {
    return children.length == 0 ? List.of() : new ChildList(children);
  }

  public int childCount() { return children.length; }

  public @NotNull XNode child(int i) { return children[i]; }

  /** No element children and no attributes. */
  public boolean isLeaf() { return children.length == 0 && attrs.isEmpty(); }

  public @Nullable XAttr attr(String name) {
    for (XAttr a : attrs) if (a.name().equals(name)) return a;
    return null;
  }

  @Override
  public String toString() {
    return "<" + tag + "> " + path();
  }

  private static final class ChildList extends AbstractList<XNode> implements RandomAccess {
    private final XNode[] a;

    ChildList(XNode[] a) {
      this.a = a;
    }

    @Override
    public XNode get(int i) { return a[i]; }

    @Override
    public int size() { return a.length; }
  }
}
