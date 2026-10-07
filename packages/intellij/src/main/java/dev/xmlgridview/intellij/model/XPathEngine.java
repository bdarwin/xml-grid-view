package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathEvaluationResult;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathNodes;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * XPath 1.0 over a namespace-aware DOM built from the document text. Result
 * nodes are mapped back to model elements by child-index path.
 */
public final class XPathEngine {
  private static final Map<XmlDocumentModel, Object> DOM_CACHE = new WeakHashMap<>();

  private XPathEngine() {
  }

  public enum Kind {
    ELEMENT("element"), ATTRIBUTE("attribute"), TEXT("text");

    private final String id;

    Kind(String id) { this.id = id; }

    public String id() { return id; }
  }

  public record Item(XNode node, Kind kind, @Nullable String attrName, @Nullable String value) {
    public int offset() {
      if (kind == Kind.ATTRIBUTE && attrName != null) {
        XAttr a = node.attr(attrName);
        if (a != null) return a.start();
      }
      if (kind == Kind.TEXT && node.textStart() >= 0) return node.textStart();
      return node.start();
    }
  }

  /** Either {@code items}, a {@code scalar} (Double, String or Boolean) or an {@code error}. */
  public record Result(List<Item> items, @Nullable Object scalar, @Nullable String error, Map<String, String> namespaces) {
  }

  /**
   * Prefix bindings: every declared prefix (first declaration wins), plus the
   * first default namespace under {@code d} (or default, ns0, ns1, ns2).
   */
  public static Map<String, String> namespaceBindings(XmlDocumentModel model) {
    Map<String, String> ns = new LinkedHashMap<>();
    String defaultUri = null;
    for (XNode el : model.elements()) {
      for (Map.Entry<String, String> e : el.nsDecls().entrySet()) {
        if (e.getKey().isEmpty()) {
          if (defaultUri == null && !e.getValue().isEmpty()) defaultUri = e.getValue();
        }
        else ns.putIfAbsent(e.getKey(), e.getValue());
      }
    }
    if (defaultUri != null) {
      for (String alias : new String[]{"d", "default", "ns0", "ns1", "ns2"}) {
        if (!ns.containsKey(alias)) {
          ns.put(alias, defaultUri);
          break;
        }
      }
    }
    return ns;
  }

  public static @NotNull Result evaluate(@NotNull XmlDocumentModel model, @NotNull String expr) {
    Map<String, String> ns = namespaceBindings(model);
    if (expr.isBlank()) return new Result(List.of(), null, null, ns);

    Object dom;
    synchronized (DOM_CACHE) {
      dom = DOM_CACHE.get(model);
      if (dom == null) {
        dom = parse(model.text());
        DOM_CACHE.put(model, dom);
      }
    }
    if (dom instanceof String err) return new Result(List.of(), null, err, ns);

    XPathEvaluationResult<?> r;
    try {
      XPath xpath = XPathFactory.newInstance().newXPath();
      xpath.setNamespaceContext(new Context(ns));
      r = xpath.evaluateExpression(expr, dom, XPathEvaluationResult.class);
    } catch (Exception e) {
      return new Result(List.of(), null, message(e), ns);
    }
    switch (r.type()) {
      case NUMBER, STRING, BOOLEAN -> {
        return new Result(List.of(), r.value(), null, ns);
      }
      case NODESET, NODE -> {
        List<Item> items = new ArrayList<>();
        Object v = r.value();
        if (v instanceof XPathNodes nodes) {
          for (Node n : nodes) addItem(model, n, items);
        }
        else if (v instanceof Node n) addItem(model, n, items);
        return new Result(sort(items), null, null, ns);
      }
      default -> {
        return new Result(List.of(), r.value(), null, ns);
      }
    }
  }

  private static Object parse(String text) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setExpandEntityReferences(true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      DocumentBuilder b = f.newDocumentBuilder();
      b.setErrorHandler(new DefaultHandler());
      Document doc = b.parse(new InputSource(new StringReader(text)));
      return doc;
    } catch (Exception e) {
      return "Document is not well-formed: " + message(e);
    }
  }

  private static String message(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getMessage() == null) t = t.getCause();
    String m = t.getMessage();
    if (m == null && e.getCause() != null) m = e.getCause().getMessage();
    if (m == null) m = e.getClass().getSimpleName();
    int nl = m.indexOf('\n');
    return (nl >= 0 ? m.substring(0, nl) : m).replaceFirst("^javax\\.xml\\.transform\\.TransformerException: ", "");
  }

  private static void addItem(XmlDocumentModel model, Node n, List<Item> out) {
    Element owner;
    Kind kind;
    String attr = null;
    String value = null;
    switch (n.getNodeType()) {
      case Node.ELEMENT_NODE -> {
        owner = (Element)n;
        kind = Kind.ELEMENT;
      }
      case Node.ATTRIBUTE_NODE -> {
        owner = ((Attr)n).getOwnerElement();
        kind = Kind.ATTRIBUTE;
        attr = ((Attr)n).getName();
        value = truncate(((Attr)n).getValue());
      }
      case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
        Node p = n.getParentNode();
        owner = p instanceof Element e ? e : null;
        kind = Kind.TEXT;
        value = truncate(n.getNodeValue());
      }
      default -> {
        return;
      }
    }
    if (owner == null) return;
    XNode el = model.byPath(domPath(owner));
    if (el != null) out.add(new Item(el, kind, attr, value));
  }

  private static String domPath(Element el) {
    List<Integer> path = new ArrayList<>();
    Node e = el;
    while (e instanceof Element) {
      int i = 0;
      for (Node s = e.getPreviousSibling(); s != null; s = s.getPreviousSibling()) {
        if (s.getNodeType() == Node.ELEMENT_NODE) i++;
      }
      path.add(0, i);
      e = e.getParentNode();
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < path.size(); i++) {
      if (i > 0) sb.append('/');
      sb.append(path.get(i));
    }
    return sb.toString();
  }

  private static int attrIndex(Item i) {
    if (i.kind() != Kind.ATTRIBUTE) return 0;
    List<XAttr> attrs = i.node().attrs();
    for (int k = 0; k < attrs.size(); k++) if (attrs.get(k).name().equals(i.attrName())) return k;
    return -1;
  }

  /** Document order (element, then attributes in source order, then text), duplicates removed. */
  private static List<Item> sort(List<Item> items) {
    List<Item> sorted = new ArrayList<>(items);
    sorted.sort(Comparator.<Item>comparingInt(i -> i.node().id())
                  .thenComparingInt(i -> i.kind().ordinal())
                  .thenComparingInt(XPathEngine::attrIndex));
    List<Item> out = new ArrayList<>();
    for (Item it : sorted) {
      Item prev = out.isEmpty() ? null : out.get(out.size() - 1);
      if (prev != null && prev.node() == it.node() && prev.kind() == it.kind()
          && java.util.Objects.equals(prev.attrName(), it.attrName())) {
        continue;
      }
      out.add(it);
    }
    return out;
  }

  private static String truncate(String s) {
    String t = s.replaceAll("\\s+", " ").trim();
    return t.length() > 120 ? t.substring(0, 119) + "…" : t;
  }

  private record Context(Map<String, String> ns) implements NamespaceContext {
    @Override
    public String getNamespaceURI(String prefix) {
      if (prefix == null) throw new IllegalArgumentException("null prefix");
      if (prefix.isEmpty()) return XMLConstants.NULL_NS_URI;
      if (prefix.equals(XMLConstants.XML_NS_PREFIX)) return XMLConstants.XML_NS_URI;
      String uri = ns.get(prefix);
      if (uri == null) throw new IllegalArgumentException("Unbound namespace prefix: " + prefix);
      return uri;
    }

    @Override
    public String getPrefix(String namespaceURI) {
      for (Map.Entry<String, String> e : ns.entrySet()) if (e.getValue().equals(namespaceURI)) return e.getKey();
      return null;
    }

    @Override
    public Iterator<String> getPrefixes(String namespaceURI) {
      List<String> out = new ArrayList<>();
      for (Map.Entry<String, String> e : ns.entrySet()) if (e.getValue().equals(namespaceURI)) out.add(e.getKey());
      return out.iterator();
    }
  }
}
