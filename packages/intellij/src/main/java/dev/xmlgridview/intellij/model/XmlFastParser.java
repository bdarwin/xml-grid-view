package dev.xmlgridview.intellij.model;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
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
 * references are kept as written), CDATA is literal. Comments, PIs and the DOCTYPE are
 * recorded as {@link XMisc} entries.
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
  private final ArrayList<XNode> elements = new ArrayList<>();
  private final ArrayList<Frame> stack = new ArrayList<>();
  private @Nullable XNode root;
  private final ArrayList<XMisc> misc = new ArrayList<>();
  /** Top-level elements seen so far (more than one only in malformed documents). */
  private int topLevel;
  /** Tag and attribute names, shared: documents repeat a few names millions of times. */
  private final Map<String, String> names = new HashMap<>();

  private static final class Frame {
    final XNode node;
    /** Namespace declarations made on this element, or null. */
    final @Nullable Map<String, String> ns;
    /** Direct text so far; null until the first non-blank character (leading blanks are trimmed anyway). */
    @Nullable StringBuilder text;
    int textStart = -1;
    @Nullable ArrayList<XNode> children;

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
  record Result(@Nullable XNode root, List<XNode> elements, List<XMisc> misc) {
  }

  static @NotNull Result parse(@NotNull String text, @Nullable DoubleConsumer progress) {
    XmlFastParser p = new XmlFastParser(text, progress);
    p.run();
    p.elements.trimToSize();
    p.misc.trimToSize();
    return new Result(p.root, p.elements, p.misc);
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
        int start = pos;
        int close = s.indexOf("-->", pos + 4);
        pos = close < 0 ? n : close + 3;
        addMisc(XMisc.Kind.COMMENT, "#comment", s.substring(start + 4, close < 0 ? n : close), start);
      }
      else if (s.startsWith("<![CDATA[", pos)) {
        int end = s.indexOf("]]>", pos + 9);
        int stop = end < 0 ? n : end;
        cdata(pos + 9, stop);
        pos = end < 0 ? n : end + 3;
      }
      else if (s.startsWith("<?", pos)) {
        int start = pos;
        int close = s.indexOf("?>", pos + 2);
        pos = close < 0 ? n : close + 2;
        String body = s.substring(start + 2, close < 0 ? n : close);
        int t = 0;
        while (t < body.length() && !TextUtil.isXmlWhitespace(body.charAt(t))) t++;
        String target = body.substring(0, t);
        // The XML declaration is not a processing instruction.
        if (!(start == 0 && target.equals("xml"))) addMisc(XMisc.Kind.PI, target, body.substring(t), start);
      }
      else if (s.startsWith("<!", pos)) {
        int start = pos;
        skipDeclaration();
        if (s.startsWith("<!DOCTYPE", start)) addMisc(XMisc.Kind.DOCTYPE, "DOCTYPE", s.substring(start + 9, Math.max(start + 9, s.charAt(pos - 1) == '>' ? pos - 1 : pos)), start);
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

  /** Records a comment, PI or DOCTYPE ending at {@code pos}; line ends are normalized and the value trimmed. */
  private void addMisc(XMisc.Kind kind, String name, String raw, int start) {
    String v = raw.indexOf('\r') < 0 ? raw : raw.replace("\r\n", "\n").replace('\r', '\n');
    int a = 0;
    int b = v.length();
    while (a < b && TextUtil.isXmlWhitespace(v.charAt(a))) a++;
    while (b > a && TextUtil.isXmlWhitespace(v.charAt(b - 1))) b--;
    Frame top = stack.isEmpty() ? null : stack.get(stack.size() - 1);
    XNode parent = top == null ? null : top.node;
    int before = top == null ? topLevel : top.children == null ? 0 : top.children.size();
    misc.add(new XMisc(kind, name, v.substring(a, b), start, pos, parent, before));
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
    return name(a, pos);
  }

  private String name(int a, int b) {
    String n = s.substring(a, b);
    String shared = names.putIfAbsent(n, n);
    return shared != null ? shared : n;
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
      int vStart;
      int vEnd;
      if (q == '"' || q == '\'') {
        vStart = pos + 1;
        vEnd = s.indexOf(q, pos + 1);
        if (vEnd < 0) vEnd = n;
        pos = Math.min(n, vEnd + 1);
      }
      else {
        vStart = pos;
        while (pos < n && !isNameEnd(s.charAt(pos))) pos++;
        vEnd = pos;
      }
      if (aName.equals("xmlns") || aName.startsWith("xmlns:")) {
        if (nsDecls == null) nsDecls = new LinkedHashMap<>();
        nsDecls.put(aName.equals("xmlns") ? "" : aName.substring(6), attributeValue(vStart, vEnd));
      }
      else if (isPlain(vStart, vEnd)) {
        attrs.add(XAttr.verbatim(aName, s, vStart, vEnd, aStart, pos));
      }
      else {
        attrs.add(new XAttr(aName, attributeValue(vStart, vEnd), aStart, pos));
      }
    }

    Frame parentFrame = stack.isEmpty() ? null : stack.get(stack.size() - 1);
    if (parentFrame == null && root != null) {
      // A second top-level element (malformed): skip it like the PSI-based model did.
      if (!selfClosing) skipElement(name);
      return;
    }
    XNode parent = parentFrame == null ? null : parentFrame.node;
    int index = 0;
    if (parentFrame != null) {
      if (parentFrame.children == null) parentFrame.children = new ArrayList<>(4);
      index = parentFrame.children.size();
    }
    String ns = resolve(name, nsDecls);
    XNode node = new XNode(elements.size(), name, ns, start, attrs,
                           nsDecls == null ? Map.of() : nsDecls, s, parent, index);
    elements.add(node);
    if (parentFrame != null) parentFrame.children.add(node);
    else {
      root = node;
      topLevel++;
    }
    if (selfClosing) {
      node.finish(pos, null, -1, 0, null);
    }
    else {
      stack.add(new Frame(node, nsDecls));
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
    XNode[] children = f.children == null ? null : f.children.toArray(new XNode[0]);
    StringBuilder sb = f.text;
    int len = sb == null ? 0 : sb.length();
    while (len > 0 && TextUtil.isXmlWhitespace(sb.charAt(len - 1))) len--;
    if (len == 0) {
      f.node.finish(end, null, -1, 0, children);
      return;
    }
    // Text that appears verbatim in the source is not copied (see XNode#text()).
    int a = f.textStart;
    boolean verbatim = a + len <= n;
    for (int i = 0; verbatim && i < len; i++) verbatim = s.charAt(a + i) == sb.charAt(i);
    f.node.finish(end, verbatim ? null : sb.substring(0, len), a, a + len, children);
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

  private boolean isPlain(int a, int b) {
    for (int i = a; i < b; i++) {
      char c = s.charAt(i);
      if (c == '&' || c == '\t' || c == '\n' || c == '\r') return false;
    }
    return true;
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
