package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.RandomAccess;
import java.util.List;
import java.util.function.Predicate;

/**
 * One row of the Flat view: an element, or one of its attributes listed right
 * after it one level deeper. Matches the canonical {@code <name>.flat.json}
 * format in fixtures/SCHEMA.md.
 */
public record FlatRow(int depth, @NotNull Kind kind, @NotNull XNode node, int attrIndex, @Nullable XMisc misc) {
  public FlatRow(int depth, @NotNull Kind kind, @NotNull XNode node, int attrIndex) {
    this(depth, kind, node, attrIndex, null);
  }

  /** ELEMENT and ATTR rows; MISC rows are comments, PIs and the DOCTYPE ({@link #misc()}; {@code node} is the parent, or the root at top level). */
  public enum Kind {
    ELEMENT("element"), ATTR("attr"), MISC("misc");

    private final String id;

    Kind(String id) { this.id = id; }

    public String id() { return id; }
  }

  /** The fixture kind: element, attr, comment, pi or doctype. */
  public @NotNull String kindId() {
    return kind == Kind.MISC ? misc.kind().id() : kind.id();
  }

  /** {@code tag} for elements, {@code @name} for attributes, {@code #comment} / PI target / {@code DOCTYPE}. */
  public @NotNull String name() {
    return switch (kind) {
      case ELEMENT -> node.tag();
      case ATTR -> "@" + attr().name();
      case MISC -> misc.name();
    };
  }

  /** Element text (trimmed, interior whitespace kept), the attribute value, or the comment / PI / DOCTYPE text. */
  public @NotNull String value() {
    return switch (kind) {
      case ELEMENT -> node.text();
      case ATTR -> attr().value();
      case MISC -> misc.value();
    };
  }

  /** Path for the fixtures: the element's (the owner's for attributes, the parent's for MISC; "" at top level). */
  public @NotNull String pathKey() {
    return kind == Kind.MISC && misc.parent() == null ? "" : node.path();
  }

  public @NotNull XAttr attr() {
    if (kind != Kind.ATTR) throw new IllegalStateException("not an attribute row");
    return node.attrs().get(attrIndex);
  }

  /** Source offset to navigate to: the element start tag, or the attribute. */
  public int offset() {
    return switch (kind) {
      case ELEMENT -> node.start();
      case ATTR -> attr().start();
      case MISC -> misc.start();
    };
  }

  /** True when the row has rows below it that collapsing would hide. */
  public boolean expandable() {
    return kind == Kind.ELEMENT && (!node.attrs().isEmpty() || !node.children().isEmpty());
  }

  /** Stable key across refreshes: element path, plus {@code @name} for attributes. */
  public @NotNull String key() {
    return switch (kind) {
      case ELEMENT -> node.path();
      case ATTR -> node.path() + "@" + attr().name();
      case MISC -> pathKey() + "!" + misc.before() + misc.kind().id() + misc.name();
    };
  }

  /** Value rows (attributes and leaf elements) get the lighter band tint; element rows with children the stronger one. */
  public boolean isValueRow() {
    return kind != Kind.ELEMENT || node.children().isEmpty();
  }

  /**
   * Depth of the Flat view's colour sections: the first level with more than one element
   * under a chain of single children (each book under catalog, or under catalog/books).
   */
  public static int sectionLevel(@NotNull XmlDocumentModel model) {
    int level = 1;
    for (XNode r = model.root(); r != null && r.children().size() == 1; r = r.children().get(0)) level++;
    return level;
  }

  /** Colour band of a node: 0 above the section level, else 1 or 2 alternating by section. */
  public static int band(@NotNull XNode node, int sectionLevel) {
    int depth = node.depth();
    if (depth < sectionLevel) return 0;
    XNode s = node;
    for (int d = depth; d > sectionLevel; d--) s = s.parent();
    return s.index() % 2 + 1;
  }

  /** Index of the row for {@code node} (attrIndex -1) or one of its attributes, or -1. */
  public static int indexOf(@NotNull List<FlatRow> rows, @NotNull XNode node, int attrIndex) {
    if (rows instanceof Rows packed) return packed.indexOf(node.id(), attrIndex);
    for (int i = 0; i < rows.size(); i++) {
      FlatRow r = rows.get(i);
      if (r.node == node && r.attrIndex == attrIndex) return i;
    }
    return -1;
  }

  /** All rows, fully expanded, in document order. */
  public static @NotNull List<FlatRow> build(@NotNull XmlDocumentModel model) {
    return build(model, n -> false, null);
  }

  /**
   * Visible rows: elements for which {@code collapsed} is true keep their own
   * row but hide their attributes and descendants. When {@code only} is
   * non-null, only those elements are shown (all expanded); their attributes
   * are shown too.
   */
  public static @NotNull List<FlatRow> build(@NotNull XmlDocumentModel model, @NotNull Predicate<XNode> collapsed,
                                             @Nullable Predicate<XNode> only) {
    Rows out = new Rows(model);
    XNode root = model.root();
    if (root == null) return out;
    // Comments, PIs and DOCTYPE are shown among the elements, except in a filtered view.
    boolean withMisc = only == null && !model.misc().isEmpty();
    java.util.IdentityHashMap<XMisc, Integer> miscIndex = new java.util.IdentityHashMap<>();
    if (withMisc) for (int i = 0; i < model.misc().size(); i++) miscIndex.put(model.misc().get(i), i);
    // Iterative pre-order walk: documents can nest deeply. Items: element id, or -(misc index) - 1.
    ArrayDeque<Integer> todo = new ArrayDeque<>();
    pushChildren(todo, List.of(root), withMisc ? model.miscOf(null) : List.of(), miscIndex);
    while (!todo.isEmpty()) {
      int item = todo.pop();
      if (item < 0) {
        out.add(-item - 1, MISC_ROW);
        continue;
      }
      XNode n = model.elements().get(item);
      if (only != null && !only.test(n)) continue;
      out.add(n.id(), -1);
      if (only == null && collapsed.test(n)) continue;
      for (int i = 0; i < n.attrs().size(); i++) out.add(n.id(), i);
      pushChildren(todo, n.children(), withMisc ? model.miscOf(n) : List.of(), miscIndex);
    }
    out.trim();
    return out;
  }

  /** Attribute-index marker of a MISC row; its id field is the index into {@link XmlDocumentModel#misc()}. */
  private static final int MISC_ROW = -2;

  /** Pushes children (with their comments etc. in between) so they pop in document order. */
  private static void pushChildren(ArrayDeque<Integer> todo, List<XNode> kids, List<XMisc> misc, java.util.Map<XMisc, Integer> miscIndex) {
    int m = misc.size() - 1;
    for (int i = kids.size(); i >= 0; i--) {
      // Misc entries with before == i come right before child i (or at the end for i == size).
      while (m >= 0 && misc.get(m).before() >= i) todo.push(-miscIndex.get(misc.get(m--)) - 1);
      if (i > 0) todo.push(kids.get(i - 1).id());
    }
  }

  /**
   * Rows packed as (element id, attribute index) pairs, 8 bytes each; the row records are
   * created on access. Large documents have tens of millions of rows.
   */
  private static final class Rows extends AbstractList<FlatRow> implements RandomAccess {
    private final List<XNode> elements;
    private final List<XMisc> misc;
    private final @Nullable XNode root;
    private long[] packed = new long[16];
    private int size;

    Rows(XmlDocumentModel model) {
      this.elements = model.elements();
      this.misc = model.misc();
      this.root = model.root();
    }

    void add(int id, int attrIndex) {
      if (size == packed.length) packed = Arrays.copyOf(packed, size + (size >> 1));
      packed[size++] = ((long)id << 32) | (attrIndex & 0xFFFFFFFFL);
    }

    void trim() {
      if (packed.length - size > 1024) packed = Arrays.copyOf(packed, size);
    }

    @Override
    public FlatRow get(int i) {
      if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i);
      long p = packed[i];
      int attr = (int)p;
      if (attr == MISC_ROW) {
        XMisc m = misc.get((int)(p >>> 32));
        XNode parent = m.parent();
        return new FlatRow(parent == null ? 0 : parent.depth() + 1, Kind.MISC, parent == null ? root : parent, -1, m);
      }
      XNode n = elements.get((int)(p >>> 32));
      return attr < 0 ? new FlatRow(n.depth(), Kind.ELEMENT, n, -1) : new FlatRow(n.depth() + 1, Kind.ATTR, n, attr);
    }

    int indexOf(int id, int attrIndex) {
      long want = ((long)id << 32) | (attrIndex & 0xFFFFFFFFL);
      for (int i = 0; i < size; i++) if (packed[i] == want) return i;
      return -1;
    }

    @Override
    public int size() { return size; }
  }
}
