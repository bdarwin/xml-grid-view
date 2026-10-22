package dev.xmlgridview.intellij.ui;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.XmlAttributeDescriptor;
import dev.xmlgridview.intellij.model.ValueEdits;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The values the IDE knows are allowed for an attribute or element (an XSD enumeration, a DTD's
 * enumerated attribute type, xs:boolean), from the XML support's descriptors and the value's
 * references. Empty for JSON and when the document no longer matches the model.
 */
final class ValueSuggestions {
  private ValueSuggestions() {
  }

  static @NotNull List<String> of(@NotNull Project project, @NotNull Document document, @NotNull XmlDocumentModel model,
                                  @NotNull ValueEdits.Target target) {
    if (model.isJson() || model.hasErrors() || !model.text().contentEquals(document.getImmutableCharSequence())) return List.of();
    XNode el = model.byPath(target.path());
    if (el == null) return List.of();
    PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
    pdm.commitDocument(document);
    PsiFile file = pdm.getPsiFile(document);
    if (file == null) return List.of();
    PsiElement at = file.findElementAt(el.start() + 1);
    XmlTag tag = PsiTreeUtil.getParentOfType(at, XmlTag.class, false);
    if (tag == null || tag.getTextOffset() > el.start() + 1 + el.tag().length()) return List.of();
    Set<String> out = new LinkedHashSet<>();
    if (target.isAttr()) {
      XmlAttribute attr = tag.getAttribute(target.attrName());
      if (attr == null) return List.of();
      XmlAttributeDescriptor d = attr.getDescriptor();
      if (d != null && d.isEnumerated()) {
        String[] values = d.getEnumeratedValues();
        if (values != null) for (String v : values) out.add(v);
      }
      XmlAttributeValue value = attr.getValueElement();
      if (value != null) addVariants(value.getReferences(), out);
    }
    else {
      addVariants(tag.getReferences(), out);
      if (tag.getValue().getTextElements().length > 0) addVariants(tag.getValue().getTextElements()[0].getReferences(), out);
    }
    return new ArrayList<>(out);
  }

  /** Completion variants of references that offer fixed values (enumerations), not elements to resolve to. */
  private static void addVariants(PsiReference[] refs, Set<String> out) {
    for (PsiReference r : refs) {
      if (!r.getClass().getName().contains("Enumerat")) continue;
      for (Object v : r.getVariants()) {
        String s = v instanceof LookupElement l ? l.getLookupString()
                   : v instanceof PsiNamedElement n ? n.getName()
                   : v instanceof String str ? str : null;
        if (s != null && !s.isEmpty()) out.add(s);
      }
    }
  }
}
