package dev.xmlgridview.intellij.model;

import com.intellij.lang.ASTNode;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlEntityRef;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the immutable node model from XML PSI. Must be called inside a read action.
 */
public final class XmlModelBuilder {
  private final List<XNode> elements = new ArrayList<>();

  private XmlModelBuilder() {
  }

  /**
   * Parses {@code text} into a non-physical XmlFile (no file-size limit, so large
   * files still get XML PSI) and builds the model, with errors from {@link XmlErrorScanner}.
   */
  public static @NotNull XmlDocumentModel build(@NotNull Project project, @NotNull CharSequence text) {
    String s = text.toString();
    PsiFile psi = PsiFileFactory.getInstance(project)
      .createFileFromText("xml-grid-view.xml", XMLLanguage.INSTANCE, s, false, false, true);
    return build((XmlFile)psi, s);
  }

  public static @NotNull XmlDocumentModel build(@NotNull XmlFile file, @NotNull String text) {
    XmlModelBuilder b = new XmlModelBuilder();
    XmlTag rootTag = file.getRootTag();
    XNode root = rootTag == null ? null : b.element(rootTag, null, 0);
    List<ParseError> errors = XmlErrorScanner.scan(text);
    return new XmlDocumentModel(root, b.elements, errors, text);
  }

  private XNode element(XmlTag tag, @Nullable XNode parent, int index) {
    List<XAttr> attrs = new ArrayList<>();
    Map<String, String> nsDecls = new LinkedHashMap<>();
    for (XmlAttribute a : tag.getAttributes()) {
      String value = attributeValue(a);
      if (a.isNamespaceDeclaration()) {
        String name = a.getName();
        nsDecls.put(name.equals("xmlns") ? "" : name.substring("xmlns:".length()), value);
        continue;
      }
      attrs.add(new XAttr(a.getName(), value, a.getTextRange().getStartOffset(), a.getTextRange().getEndOffset()));
    }

    // Collect direct text and child tags from the tag's content (between the start and end tag).
    StringBuilder text = new StringBuilder();
    int[] firstText = {-1};
    List<XmlTag> childTags = new ArrayList<>();
    boolean inContent = false;
    for (PsiElement child = tag.getFirstChild(); child != null; child = child.getNextSibling()) {
      IElementType type = child.getNode().getElementType();
      if (!inContent) {
        if (type == XmlTokenType.XML_TAG_END) inContent = true;
        else if (type == XmlTokenType.XML_EMPTY_ELEMENT_END) break;
        continue;
      }
      if (type == XmlTokenType.XML_END_TAG_START) break;
      if (child instanceof XmlTag t) childTags.add(t);
      else appendText(child, text, firstText);
    }

    String trimmed = TextUtil.xmlTrim(text.toString());
    XNode node = new XNode(elements.size(), tag.getName(), tag.getNamespace(),
                           tag.getTextRange().getStartOffset(), tag.getTextRange().getEndOffset(),
                           attrs, nsDecls, trimmed, trimmed.isEmpty() ? -1 : firstText[0], parent, index);
    elements.add(node);
    int i = 0;
    for (XmlTag t : childTags) node.addChild(element(t, node, i++));
    return node;
  }

  /** Raw value with literal whitespace normalized (XML attribute-value normalization), then references decoded. */
  private static String attributeValue(XmlAttribute a) {
    XmlAttributeValue v = a.getValueElement();
    if (v == null) return "";
    String raw = v.getValue();
    StringBuilder sb = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      sb.append(c == '\t' || c == '\n' || c == '\r' ? ' ' : c);
    }
    return TextUtil.decodeEntities(sb);
  }

  /** Appends the character data of a content node: text, whitespace, CDATA and references; skips comments/PIs. */
  private static void appendText(PsiElement e, StringBuilder out, int[] firstText) {
    if (e instanceof PsiComment || e instanceof XmlProcessingInstruction || e instanceof PsiErrorElement) return;
    if (e instanceof XmlEntityRef) {
      String t = e.getText();
      String rep = t.length() > 2 ? TextUtil.decodeReference(t.substring(1, t.length() - 1)) : null;
      append(out, rep != null ? rep : t, e, firstText);
      return;
    }
    ASTNode node = e.getNode();
    if (e.getFirstChild() == null) {
      IElementType type = node.getElementType();
      if (type == XmlTokenType.XML_CDATA_START || type == XmlTokenType.XML_CDATA_END) return;
      String t = e.getText();
      if (type == XmlTokenType.XML_CHAR_ENTITY_REF || type == XmlTokenType.XML_ENTITY_REF_TOKEN) {
        String rep = t.length() > 2 ? TextUtil.decodeReference(t.substring(1, t.length() - 1)) : null;
        if (rep != null) t = rep;
      }
      append(out, t, e, firstText);
      return;
    }
    for (PsiElement c = e.getFirstChild(); c != null; c = c.getNextSibling()) appendText(c, out, firstText);
  }

  private static void append(StringBuilder out, String t, PsiElement e, int[] firstText) {
    if (firstText[0] < 0 && !TextUtil.xmlTrim(t).isEmpty()) {
      int lead = 0;
      while (lead < t.length() && TextUtil.isXmlWhitespace(t.charAt(lead))) lead++;
      int start = e.getTextRange().getStartOffset();
      firstText[0] = start + Math.min(lead, e.getTextLength());
    }
    out.append(t);
  }
}
