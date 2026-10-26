package dev.xmlgridview.intellij.model;

import java.util.Map;

/** Text normalization helpers shared by the model, matching the TS core. */
public final class TextUtil {
  private TextUtil() {
  }

  public static boolean isXmlWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }

  /** Trims XML whitespace (space, tab, CR, LF) only. */
  public static String xmlTrim(String s) {
    int a = 0;
    int b = s.length();
    while (a < b && isXmlWhitespace(s.charAt(a))) a++;
    while (b > a && isXmlWhitespace(s.charAt(b - 1))) b--;
    return a == 0 && b == s.length() ? s : s.substring(a, b);
  }

  /** Decodes predefined entity and character references; unknown references are kept verbatim. */
  public static String decodeEntities(CharSequence raw) {
    return decodeEntities(raw, Map.of());
  }

  /** Like {@link #decodeEntities(CharSequence)}, also decoding the names in {@code entities} (declared by the DOCTYPE). */
  public static String decodeEntities(CharSequence raw, Map<String, String> entities) {
    String s = raw.toString();
    int amp = s.indexOf('&');
    if (amp < 0) return s;
    StringBuilder sb = new StringBuilder(s.length());
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      int semi;
      if (c == '&' && (semi = s.indexOf(';', i + 1)) > i + 1 && semi - i <= maxReference(entities)) {
        String name = s.substring(i + 1, semi);
        String rep = decodeReference(name, entities);
        if (rep != null) {
          sb.append(rep);
          i = semi + 1;
          continue;
        }
      }
      sb.append(c);
      i++;
    }
    return sb.toString();
  }

  /** Longest reference ("&" to ";") worth decoding: predefined and character references are short, declared names may be long. */
  public static int maxReference(Map<String, String> entities) {
    return entities.isEmpty() ? 12 : 256;
  }

  /** {@link #decodeReference(String)}, then the names in {@code entities}. */
  public static String decodeReference(String name, Map<String, String> entities) {
    String rep = decodeReference(name);
    return rep != null || name.startsWith("#") ? rep : entities.get(name);
  }

  /** Decodes the inside of a reference ("amp", "#65", "#x41"), or null when unknown. */
  public static String decodeReference(String name) {
    switch (name) {
      case "amp": return "&";
      case "lt": return "<";
      case "gt": return ">";
      case "quot": return "\"";
      case "apos": return "'";
      default:
        if (name.startsWith("#")) {
          try {
            int cp = name.startsWith("#x") || name.startsWith("#X")
                     ? Integer.parseInt(name.substring(2), 16)
                     : Integer.parseInt(name.substring(1));
            return new String(Character.toChars(cp));
          } catch (IllegalArgumentException e) {
            return null;
          }
        }
        return null;
    }
  }

  /** Converts 1-based line/column to an offset in {@code text}, clamped to the text. */
  public static int offsetOf(CharSequence text, int line, int column) {
    int l = 1;
    int i = 0;
    int n = text.length();
    while (l < line && i < n) {
      if (text.charAt(i++) == '\n') l++;
    }
    return Math.max(0, Math.min(n, i + Math.max(0, column - 1)));
  }
}
