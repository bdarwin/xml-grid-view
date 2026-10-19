package dev.xmlgridview.intellij.model;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * Single-pass XML scanner that builds the element model directly from the text, with
 * UTF-16 source offsets. Much faster and lighter than going through PSI, so large files
 * load quickly. Well-formedness errors are reported separately by {@link XmlErrorScanner};
 * on malformed input this recovers as well as it can (unknown end tags are ignored, open
 * elements are closed at the end of the input).
 *
 * <p>Text follows XML rules: line ends are normalized to LF, attribute-value whitespace
 * becomes spaces, predefined entities and character references are decoded (unknown
 * references are kept as written), CDATA is literal, comments and PIs are skipped.
 */
final class XmlFastParser {
  private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
  /** Characters between cancellation checks and progress reports. */
  private static final int CHUNK = 1 << 20;

  private final String s;
  private final int n;
  private final @Nullable DoubleConsumer progress;
  private int pos;
  private int nextCheck = CHUNK;
  private final List<XNode> elements = new ArrayList<>();
  private final ArrayList<Frame> stack = new ArrayList<>();
  private @Nullable XNode root;

  private static final class Frame {
    final XNode node;
    /** Namespace declarations made on this element, or null. */
    final @Nullable Map<String, String> ns;
    /** Direct text so far; null until the first non-blank character (leading blanks are trimmed anyway). */
    @Nullable StringBuilder text;
    int textStart = -1;
    int children;

    Frame(XNode node, @Nullable Map<String, String> ns) {
      this.node = node;
      this.ns = ns;
    }
  }

  private XmlFastParser(String s, @Nullable DoubleConsumer progress) {
    this.s = s;
    this.n = s.length();
    this.progress = progress;
  }

  /** The root element (null for a document without elements) and all elements in document order. */
  record Result(@Nullable XNode root, List<XNode> elements) {
  }

  static @NotNull Result parse(@NotNull String text, @Nullable DoubleConsumer progress) {
    XmlFastParser p = new XmlFastParser(text, progress);
    p.run();
    return new Result(p.root, p.elements);
  }

  private void run() {
    while (pos < n) {
      if (pos >= nextCheck) {
        nextCheck = pos + CHUNK;
        ProgressManager.checkCanceled();
        if (progress != null) progress.accept((double)pos / n);
      }
      int lt = s.indexOf('<', pos);
      if (lt < 0) {
        text(pos, n);
        pos = n;
        break;
      }
      if (lt > pos) text(pos, lt);
      pos = lt;
      if (s.startsWith("<!--", pos)) {
        pos = skipPast("-->", pos + 4);
      }
      else if (s.startsWith("<![CDATA[", pos)) {
        int end = s.indexOf("]]>", pos + 9);
        int stop = end < 0 ? n : end;
        cdata(pos + 9, stop);
        pos = end < 0 ? n : end + 3;
      }
      else if (s.startsWith("<?", pos)) {
        pos = skipPast("?>", pos + 2);
      }
      else if (s.startsWith("<!", pos)) {
        skipDeclaration();
      }
      else if (s.startsWith("</", pos)) {
        endTag();
      }
      else {
        startTag();
      }
    }
    while (!stack.isEmpty()) close(stack.size() - 1, n);
    if (progress != null) progress.accept(1.0);
  }

  private int skipPast(String terminator, int from) {
    int i = s.indexOf(terminator, from);
    return i < 0 ? n : i + terminator.length();
  }

  /** {@code <!DOCTYPE ...>} and friends: skips to the matching '>', honouring quotes and an internal subset. */
  private void skipDeclaration() {
    int depth = 0;
    char quote = 0;
    for (int i = pos + 2; i < n; i++) {
      char c = s.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0;
      }
      else if (c == '"' || c == '\'') quote = c;
      else if (c == '[') depth++;
      else if (c == ']') depth--;
      else if (c == '>' && depth <= 0) {
        pos = i + 1;
        return;
      }
    }
    pos = n;
  }

  private static boolean isNameEnd(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/' || c == '>' || c == '=' || c == '<' || c == '"' || c == '\'';
  }

  private String readName() {
    int a = pos;
    while (pos < n && !isNameEnd(s.charAt(pos))) pos++;
    return s.substring(a, pos);
  }

  private void skipWs() {
    while (pos < n && TextUtil.isXmlWhitespace(s.charAt(pos))) pos++;
  }

  private void startTag() {
    int start = pos;
    pos++;
    String name = readName();
    if (name.isEmpty()) {
      // A stray '<': treat it as text.
      text(start, start + 1);
      return;
    }
    List<XAttr> attrs = new ArrayList<>(4);
    Map<String, String> nsDecls = null;
    boolean selfClosing = false;
    while (pos < n) {
      skipWs();
      if (pos >= n) break;
      char c = s.charAt(pos);
      if (c == '>') {
        pos++;
        break;
      }
      if (c == '/' && pos + 1 < n && s.charAt(pos + 1) == '>') {
        pos += 2;
        selfClosing = true;
        break;
      }
      if (c == '<') break; // unterminated start tag; let the main loop handle '<'
      int aStart = pos;
      String aName = readName();
      if (aName.isEmpty()) {
        pos++; // skip an unexpected character
        continue;
      }
      skipWs();
      if (pos >= n || s.charAt(pos) != '=') continue; // attribute without a value
      pos++;
      skipWs();
      if (pos >= n) break;
      char q = s.charAt(pos);
      String value;
      if (q == '"' || q == '\'') {
        int vEnd = s.indexOf(q, pos + 1);
        if (vEnd < 0) vEnd = n;
        value = attributeValue(pos + 1, vEnd);
        pos = Math.min(n, vEnd + 1);
      }
      else {
        int vStart = pos;
        while (pos < n && !isNameEnd(s.charAt(pos))) pos++;
        value = attributeValue(vStart, pos);
      }
      if (aName.equals("xmlns") || aName.startsWith("xmlns:")) {
        if (nsDecls == null) nsDecls = new LinkedHashMap<>();
        nsDecls.put(aName.equals("xmlns") ? "" : aName.substring(6), value);
      }
      else {
        attrs.add(new XAttr(aName, value, aStart, pos));
      }
    }

    Frame parentFrame = stack.isEmpty() ? null : stack.get(stack.size() - 1);
    if (parentFrame == null && root != null) {
      // A second top-level element (malformed): skip it like the PSI-based model did.
      if (!selfClosing) skipElement(name);
      return;
    }
    XNode parent = parentFrame == null ? null : parentFrame.node;
    int index = parentFrame == null ? 0 : parentFrame.children++;
    String ns = resolve(name, nsDecls);
    XNode node = new XNode(elements.size(), name, ns, start, -1, attrs,
                           nsDecls == null ? Map.of() : nsDecls, "", -1, parent, index);
    elements.add(node);
    if (parent != null) parent.addChild(node);
    else root = node;
    Frame f = new Frame(node, nsDecls);
    if (selfClosing) {
      node.finish(pos, "", -1);
    }
    else {
      stack.add(f);
    }
  }

  /** Skips a whole element subtree by tag-name nesting (used only for extra top-level elements). */
  private void skipElement(String name) {
    int depth = 1;
    while (pos < n && depth > 0) {
      int lt = s.indexOf('<', pos);
      if (lt < 0) {
        pos = n;
        return;
      }
      pos = lt + 1;
      if (s.startsWith("/", pos)) {
        pos++;
        if (readName().equals(name)) depth--;
      }
      else if (s.startsWith(name, pos) && pos + name.length() < n && isNameEnd(s.charAt(pos + name.length()))) {
        int gt = s.indexOf('>', pos);
        if (gt > 0 && s.charAt(gt - 1) != '/') depth++;
      }
    }
    pos = skipPast(">", pos);
  }

  private void endTag() {
    int tagStart = pos;
    pos += 2;
    String name = readName();
    pos = skipPast(">", pos);
    for (int i = stack.size() - 1; i >= 0; i--) {
      if (stack.get(i).node.tag().equals(name)) {
        // Elements left open inside it end where this end tag starts.
        while (stack.size() - 1 > i) close(stack.size() - 1, tagStart);
        close(i, pos);
        return;
      }
    }
    // An end tag with no matching open element is ignored.
  }

  private void close(int frameIndex, int end) {
    Frame f = stack.remove(frameIndex);
    String text = f.text == null ? "" : TextUtil.xmlTrim(f.text.toString());
    f.node.finish(end, text, text.isEmpty() ? -1 : f.textStart);
  }

  private String resolve(String qname, @Nullable Map<String, String> own) {
    int colon = qname.indexOf(':');
    String prefix = colon < 0 ? "" : qname.substring(0, colon);
    if (prefix.equals("xml")) return XML_NS;
    if (own != null && own.containsKey(prefix)) return own.get(prefix);
    for (int i = stack.size() - 1; i >= 0; i--) {
      Map<String, String> m = stack.get(i).ns;
      if (m != null && m.containsKey(prefix)) return m.get(prefix);
    }
    return "";
  }

  /** Character data between markup: line ends normalized, references decoded. */
  private void text(int a, int b) {
    if (stack.isEmpty()) return;
    Frame f = stack.get(stack.size() - 1);
    StringBuilder out = f.text;
    int i = a;
    if (out == null) {
      // Skip leading blanks: they are trimmed from the element text anyway.
      while (i < b && TextUtil.isXmlWhitespace(s.charAt(i))) i++;
      if (i == b) return;
    }
    while (i < b) {
      char c = s.charAt(i);
      if (c == '&') {
        int semi = s.indexOf(';', i + 1);
        String rep = semi > i + 1 && semi < b && semi - i <= 12 ? TextUtil.decodeReference(s.substring(i + 1, semi)) : null;
        if (rep != null) {
          if (f.text == null) {
            if (TextUtil.xmlTrim(rep).isEmpty()) {
              i = semi + 1;
              continue; // a leading blank reference is trimmed too
            }
            f.text = out = new StringBuilder();
            f.textStart = i;
          }
          out.append(rep);
          i = semi + 1;
          continue;
        }
      }
      if (f.text == null) {
        if (TextUtil.isXmlWhitespace(c)) {
          i++;
          continue;
        }
        f.text = out = new StringBuilder();
        f.textStart = i;
      }
      if (c == '\r') {
        out.append('\n');
        if (i + 1 < b && s.charAt(i + 1) == '\n') i++;
      }
      else {
        out.append(c);
      }
      i++;
    }
  }

  /** CDATA content: literal apart from line-end normalization. */
  private void cdata(int a, int b) {
    if (stack.isEmpty()) return;
    Frame f = stack.get(stack.size() - 1);
    int i = a;
    if (f.text == null) {
      while (i < b && TextUtil.isXmlWhitespace(s.charAt(i))) i++;
      if (i == b) return;
      f.text = new StringBuilder();
      f.textStart = i;
    }
    StringBuilder out = f.text;
    for (; i < b; i++) {
      char c = s.charAt(i);
      if (c == '\r') {
        out.append('\n');
        if (i + 1 < b && s.charAt(i + 1) == '\n') i++;
      }
      else {
        out.append(c);
      }
    }
  }

  /** Attribute-value normalization: line ends, then tab/LF to spaces; then references decoded. */
  private String attributeValue(int a, int b) {
    boolean plain = true;
    for (int i = a; i < b; i++) {
      char c = s.charAt(i);
      if (c == '&' || c == '\t' || c == '\n' || c == '\r') {
        plain = false;
        break;
      }
    }
    if (plain) return s.substring(a, b);
    StringBuilder sb = new StringBuilder(b - a);
    for (int i = a; i < b; i++) {
      char c = s.charAt(i);
      if (c == '\r') {
        sb.append(' ');
        if (i + 1 < b && s.charAt(i + 1) == '\n') i++;
      }
      else if (c == '\t' || c == '\n') sb.append(' ');
      else sb.append(c);
    }
    return TextUtil.decodeEntities(sb);
  }
}
