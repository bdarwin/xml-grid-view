package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a DOCTYPE declares for entity references. DTDs are never loaded, so:
 * <ul>
 *   <li>general entities declared in the internal subset ({@code <!ENTITY name "value">}) are decoded;</li>
 *   <li>when the DOCTYPE references an external DTD (SYSTEM/PUBLIC) or the internal subset uses
 *   parameter entities, other names may be declared there: the HTML 4 names ({@code &uuml;},
 *   {@code &nbsp;}, …) are decoded, as those DTDs declare them; other names are kept as written.</li>
 * </ul>
 * Same rules as the core's dtd.ts, checked by the shared fixtures.
 */
public final class DtdEntities {
  private static final String NAME = "[A-Za-z_:][-A-Za-z0-9_.:\\u00B7-\\uFFFF]*";
  private static final Pattern EXTERNAL_ID = Pattern.compile("^\\s*" + NAME + "\\s+(SYSTEM|PUBLIC)\\b");
  private static final Pattern ENTITY_DECL = Pattern.compile("<!ENTITY\\s+(" + NAME + ")\\s+(?:\"([^\"]*)\"|'([^']*)')\\s*>");
  private static final Pattern PE_REF = Pattern.compile("%" + NAME + ";");
  private static final Pattern REF = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[^\\s&;]+);");
  private static final Map<String, String> PREDEFINED = Map.of("amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'");

  private DtdEntities() {
  }

  /** Entity name → replacement text, from the text of a DOCTYPE (everything between {@code <!DOCTYPE} and the final {@code >}). */
  public static @NotNull Map<String, String> of(@NotNull String doctype) {
    int open = doctype.indexOf('[');
    int close = doctype.lastIndexOf(']');
    String subset = open < 0 ? "" : doctype.substring(open + 1, close > open ? close : doctype.length());
    boolean external = EXTERNAL_ID.matcher(open < 0 ? doctype : doctype.substring(0, open)).find() || PE_REF.matcher(subset).find();
    Map<String, String> declared = new HashMap<>();
    Matcher m = ENTITY_DECL.matcher(subset);
    while (m.find()) {
      String name = m.group(1);
      // The first declaration of a name wins.
      if (declared.containsKey(name) || PREDEFINED.containsKey(name)) continue;
      declared.put(name, decodeValue(m.group(2) != null ? m.group(2) : m.group(3), declared, external));
    }
    if (!external) return declared;
    Map<String, String> all = new HashMap<>(HtmlEntities.MAP);
    all.putAll(declared);
    return all;
  }

  /** Decodes character references and references to entities declared before this one (and, with an external DTD, the HTML names). */
  private static String decodeValue(String value, Map<String, String> declared, boolean external) {
    Matcher m = REF.matcher(value);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String ref = m.group(1);
      String rep;
      if (ref.startsWith("#")) {
        int cp;
        try {
          cp = ref.length() > 1 && (ref.charAt(1) == 'x' || ref.charAt(1) == 'X') ? Integer.parseInt(ref.substring(2), 16) : Integer.parseInt(ref.substring(1));
        } catch (NumberFormatException e) {
          cp = -1;
        }
        rep = cp > 0 && cp <= 0x10FFFF ? new String(Character.toChars(cp)) : m.group();
      }
      else if (PREDEFINED.containsKey(ref)) rep = PREDEFINED.get(ref);
      else if (declared.containsKey(ref)) rep = declared.get(ref);
      else if (external && HtmlEntities.MAP.containsKey(ref)) rep = HtmlEntities.MAP.get(ref);
      else rep = m.group();
      m.appendReplacement(sb, Matcher.quoteReplacement(rep));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
