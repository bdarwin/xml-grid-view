package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What changed between an earlier version of a document (the baseline, e.g. the last commit) and
 * the current one, as a tree diff: elements are matched by parent, name and content, so reformatting
 * is not a change and the result names elements rather than text ranges. Port of
 * {@code diffDocuments} in packages/core/src/changes.ts; both are checked by fixtures/changes.
 */
public final class DocumentDiff {
  /** A current element whose own attributes or text changed. */
  public record Modified(@NotNull XNode node,
                         /* Changed attributes by name: the old value, or null for a new attribute. */
                         @NotNull Map<String, String> attrs,
                         /* Attributes the element no longer has, with their old values. */
                         @NotNull Map<String, String> removedAttrs,
                         /* The old text when it changed, else null. */
                         @Nullable String text,
                         /* JSON: the old value type when it changed, else null. */
                         @Nullable String type) {
  }

  /** An element of the baseline that is gone. */
  public record Removed(/* Current parent, or null for a top-level element. */ @Nullable XNode parent,
                        /* Number of current element siblings before it. */ int before,
                        @NotNull String name,
                        @NotNull String preview) {
  }

  /** The changes, each list in document order of the current elements. */
  public record Changes(@NotNull List<XNode> added, @NotNull List<Modified> modified, @NotNull List<Removed> removed) {
    public boolean isEmpty() {
      return added.isEmpty() && modified.isEmpty() && removed.isEmpty();
    }
  }

  private static final long MAX_MATCH_COST = 4_000_000;
  private static final long MAX_PAIR_COST = 250_000;
  private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  private final int[] baseHash;
  private final int[] curHash;
  private final List<XNode> added = new ArrayList<>();
  private final List<Modified> modified = new ArrayList<>();
  private final List<Removed> removed = new ArrayList<>();

  private DocumentDiff(XmlDocumentModel base, XmlDocumentModel cur) {
    baseHash = subtreeHashes(base);
    curHash = subtreeHashes(cur);
  }

  /** Compares two parsed documents of the same format. */
  public static @NotNull Changes diff(@NotNull XmlDocumentModel base, @NotNull XmlDocumentModel cur) {
    DocumentDiff d = new DocumentDiff(base, cur);
    d.matchChildren(roots(base), roots(cur), null);
    d.added.sort((a, b) -> Integer.compare(a.id(), b.id()));
    d.modified.sort((a, b) -> Integer.compare(a.node().id(), b.node().id()));
    return new Changes(List.copyOf(d.added), List.copyOf(d.modified), List.copyOf(d.removed));
  }

  private static List<XNode> roots(XmlDocumentModel m) {
    List<XNode> out = new ArrayList<>();
    for (XNode e : m.elements()) if (e.parent() == null) out.add(e);
    return out;
  }

  private static int[] subtreeHashes(XmlDocumentModel m) {
    List<XNode> els = m.elements();
    int[] h = new int[els.size()];
    // Children have higher ids than their parent, so walking backwards sees them first.
    for (int i = els.size() - 1; i >= 0; i--) {
      XNode el = els.get(i);
      int x = hash(ownSignature(el));
      for (int k = 0; k < el.childCount(); k++) x = (x ^ h[el.child(k).id()]) * 0x01000193;
      h[i] = x;
    }
    return h;
  }

  private static String ownSignature(XNode el) {
    StringBuilder s = new StringBuilder(el.tag()).append('\0').append(Objects.toString(el.jsonType(), "")).append('\0').append(el.text());
    for (XAttr a : el.attrs()) s.append('\0').append(a.name()).append('=').append(a.value());
    return s.toString();
  }

  private static int hash(String s) {
    int h = 0x811c9dc5;
    for (int i = 0; i < s.length(); i++) h = (h ^ s.charAt(i)) * 0x01000193;
    return h;
  }

  /**
   * Matches the children of a base and a current element: identical subtrees first (longest common
   * subsequence of hashes), then, between those, same-named elements, which are compared recursively.
   */
  private void matchChildren(List<XNode> bs, List<XNode> cs, @Nullable XNode parent) {
    int[] a = new int[bs.size()];
    int[] b = new int[cs.size()];
    for (int i = 0; i < a.length; i++) a[i] = baseHash[bs.get(i).id()];
    for (int j = 0; j < b.length; j++) b[j] = curHash[cs.get(j).id()];
    List<int[]> same = lcs(a, b);
    same.add(new int[]{bs.size(), cs.size()});
    int bi = 0;
    int ci = 0;
    for (int[] p : same) {
      if (p[0] > bi || p[1] > ci) matchByName(bs.subList(bi, p[0]), cs.subList(ci, p[1]), parent, ci);
      bi = p[0] + 1;
      ci = p[1] + 1;
    }
  }

  private void matchByName(List<XNode> bs, List<XNode> cs, @Nullable XNode parent, int ciOffset) {
    List<int[]> pairs = bestPairs(bs, cs);
    pairs.add(new int[]{bs.size(), cs.size()});
    int bi = 0;
    int ci = 0;
    for (int[] p : pairs) {
      for (; bi < p[0]; bi++) removed.add(new Removed(parent, ciOffset + ci, bs.get(bi).tag(), removedPreview(bs.get(bi))));
      for (; ci < p[1]; ci++) added.add(cs.get(ci));
      if (p[0] < bs.size()) {
        compareElements(bs.get(p[0]), cs.get(p[1]));
        bi = p[0] + 1;
        ci = p[1] + 1;
      }
    }
  }

  /** Order-preserving pairs of same-named elements with the largest total similarity. */
  private List<int[]> bestPairs(List<XNode> bs, List<XNode> cs) {
    int n = bs.size();
    int m = cs.size();
    if (n == 0 || m == 0) return new ArrayList<>();
    if ((long)n * m > MAX_PAIR_COST) {
      int[] a = new int[n];
      int[] b = new int[m];
      for (int i = 0; i < n; i++) a[i] = bs.get(i).tag().hashCode();
      for (int j = 0; j < m; j++) b[j] = cs.get(j).tag().hashCode();
      return lcs(a, b);
    }
    List<Set<String>> bf = new ArrayList<>(n);
    List<Set<String>> cf = new ArrayList<>(m);
    for (XNode e : bs) bf.add(features(e, baseHash));
    for (XNode e : cs) cf.add(features(e, curHash));
    int w = m + 1;
    double[] dp = new double[(n + 1) * w];
    double[] pick = new double[n * m];
    for (int i = n - 1; i >= 0; i--) {
      for (int j = m - 1; j >= 0; j--) {
        double s = 0;
        String bk = identity(bs.get(i));
        String ck = identity(cs.get(j));
        if (bs.get(i).tag().equals(cs.get(j).tag()) && (bk == null || ck == null || bk.equals(ck))) {
          int common = 0;
          for (String f : cf.get(j)) if (bf.get(i).contains(f)) common++;
          s = 1 + common;
        }
        pick[i * m + j] = s;
        dp[i * w + j] = Math.max(Math.max(dp[(i + 1) * w + j], dp[i * w + j + 1]), s != 0 ? s + dp[(i + 1) * w + j + 1] : 0);
      }
    }
    List<int[]> pairs = new ArrayList<>();
    for (int i = 0, j = 0; i < n && j < m; ) {
      double s = pick[i * m + j];
      if (s != 0 && dp[i * w + j] == s + dp[(i + 1) * w + j + 1]) {
        pairs.add(new int[]{i, j});
        i++;
        j++;
      }
      else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) i++;
      else j++;
    }
    return pairs;
  }

  private static final String[] KEY_ATTRS = {"id", "key", "name"};
  private static final String[] KEY_CHILDREN = {"id", "key"};

  /**
   * What identifies a record: its id, key or name attribute, else the text of an id or key child
   * without children of its own. Records with different identities are never paired as edits.
   */
  private static @Nullable String identity(XNode el) {
    for (String k : KEY_ATTRS) {
      XAttr a = el.attr(k);
      if (a != null) return "@" + k + "=" + a.value();
    }
    for (String k : KEY_CHILDREN) {
      for (int i = 0; i < el.childCount(); i++) {
        XNode c = el.child(i);
        if (c.tag().equals(k) && c.childCount() == 0) return k + "=" + c.text();
      }
    }
    return null;
  }

  /** What an element is made of, for similarity: its attributes, its text and its children's hashes. */
  private static Set<String> features(XNode el, int[] h) {
    Set<String> f = new HashSet<>();
    for (XAttr a : el.attrs()) f.add("@" + a.name() + "=" + a.value());
    String t = el.text();
    if (!t.isEmpty()) f.add("#" + t);
    for (int k = 0; k < el.childCount(); k++) f.add(Integer.toString(h[el.child(k).id()]));
    return f;
  }

  private void compareElements(XNode b, XNode c) {
    if (baseHash[b.id()] == curHash[c.id()] && ownSignature(b).equals(ownSignature(c))) return;
    Map<String, String> attrs = new LinkedHashMap<>();
    Map<String, String> old = new LinkedHashMap<>();
    for (XAttr a : b.attrs()) old.put(a.name(), a.value());
    for (XAttr a : c.attrs()) {
      String was = old.remove(a.name());
      if (was == null) attrs.put(a.name(), null);
      else if (!was.equals(a.value())) attrs.put(a.name(), was);
    }
    String bt = b.text();
    boolean typeChanged = !Objects.equals(b.jsonType(), c.jsonType());
    String text = !bt.equals(c.text()) || typeChanged ? bt : null;
    String type = typeChanged ? b.jsonType() : null;
    if (!attrs.isEmpty() || !old.isEmpty() || text != null) {
      modified.add(new Modified(c, Collections.unmodifiableMap(attrs), Collections.unmodifiableMap(old), text, type));
    }
    matchChildren(b.children(), c.children(), c);
  }

  private static String removedPreview(XNode el) {
    List<String> parts = new ArrayList<>();
    for (XAttr a : el.attrs()) parts.add(a.value());
    if (!el.text().isEmpty()) parts.add(el.text());
    for (int k = 0; k < el.childCount(); k++) {
      XNode c = el.child(k);
      if (c.childCount() == 0 && parts.size() < 8) parts.add(c.text());
    }
    parts.removeIf(String::isEmpty);
    String s = SPACES.matcher(String.join(" · ", parts)).replaceAll(" ").trim();
    return s.length() > 80 ? s.substring(0, 79) + "…" : s;
  }

  /**
   * Index pairs of a longest common subsequence of {@code a} and {@code b}, in order: common prefix
   * and suffix directly, the middle by dynamic programming, or greedily when that would be too large.
   */
  static List<int[]> lcs(int[] a, int[] b) {
    List<int[]> out = new ArrayList<>();
    int s = 0;
    while (s < a.length && s < b.length && a[s] == b[s]) {
      out.add(new int[]{s, s});
      s++;
    }
    int ea = a.length;
    int eb = b.length;
    ArrayDeque<int[]> suf = new ArrayDeque<>();
    while (ea > s && eb > s && a[ea - 1] == b[eb - 1]) suf.push(new int[]{--ea, --eb});
    int n = ea - s;
    int m = eb - s;
    if (n > 0 && m > 0) {
      if ((long)n * m <= MAX_MATCH_COST) {
        int w = m + 1;
        int[] dp = new int[(n + 1) * w];
        for (int i = n - 1; i >= 0; i--) {
          for (int j = m - 1; j >= 0; j--) {
            dp[i * w + j] = a[s + i] == b[s + j] ? dp[(i + 1) * w + j + 1] + 1 : Math.max(dp[(i + 1) * w + j], dp[i * w + j + 1]);
          }
        }
        for (int i = 0, j = 0; i < n && j < m; ) {
          if (a[s + i] == b[s + j]) {
            out.add(new int[]{s + i, s + j});
            i++;
            j++;
          }
          else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) i++;
          else j++;
        }
      }
      else {
        Map<Integer, ArrayList<Integer>> next = new HashMap<>();
        for (int j = eb - 1; j >= s; j--) next.computeIfAbsent(b[j], k -> new ArrayList<>()).add(j);
        int j = s;
        for (int i = s; i < ea; i++) {
          ArrayList<Integer> l = next.get(a[i]);
          while (l != null && !l.isEmpty() && l.get(l.size() - 1) < j) l.remove(l.size() - 1);
          if (l != null && !l.isEmpty()) {
            j = l.remove(l.size() - 1);
            out.add(new int[]{i, j++});
          }
        }
      }
    }
    out.addAll(suf);
    return out;
  }
}
