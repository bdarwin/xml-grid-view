package dev.xmlgridview.intellij.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Compiled search query; semantics match the TS core's createMatcher. */
public final class Matcher {
  private static final String WORD = "[\\p{L}\\p{N}_]";

  private final @Nullable String needle;
  private final @Nullable Pattern pattern;
  private final String query;

  private Matcher(@Nullable String needle, @Nullable Pattern pattern, String query) {
    this.needle = needle;
    this.pattern = pattern;
    this.query = query;
  }

  /** Returns null for an empty query. */
  public static @Nullable Matcher create(String query, SearchOptions o) throws InvalidQueryException {
    if (query.isEmpty()) return null;
    if (!o.regex() && !o.wholeWord() && !o.caseSensitive()) {
      return new Matcher(query.toLowerCase(Locale.ROOT), null, query);
    }
    String src = o.regex() ? query : Pattern.quote(query);
    if (o.wholeWord()) src = "(?<!" + WORD + ")(?:" + src + ")(?!" + WORD + ")";
    int flags = o.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    try {
      return new Matcher(null, Pattern.compile(src, flags), query);
    } catch (PatternSyntaxException e) {
      throw new InvalidQueryException(e.getDescription());
    }
  }

  public boolean test(@Nullable String s) {
    if (s == null) return false;
    if (needle != null) return s.toLowerCase(Locale.ROOT).contains(needle);
    java.util.regex.Matcher m = pattern.matcher(s);
    while (m.find()) if (m.end() > m.start()) return true;
    return false;
  }

  /** Non-overlapping [start, end) ranges, ignoring empty matches. */
  public List<int[]> ranges(@Nullable String s) {
    List<int[]> out = new ArrayList<>();
    if (s == null) return out;
    if (needle != null) {
      String lower = s.toLowerCase(Locale.ROOT);
      if (lower.length() != s.length()) {
        // Lowercasing changed the length; fall back to a case-insensitive pattern.
        regexRanges(Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), s, out);
        return out;
      }
      for (int i = lower.indexOf(needle); i >= 0; i = lower.indexOf(needle, i + needle.length())) {
        out.add(new int[]{i, i + needle.length()});
      }
      return out;
    }
    regexRanges(pattern, s, out);
    return out;
  }

  private static void regexRanges(Pattern p, String s, List<int[]> out) {
    java.util.regex.Matcher m = p.matcher(s);
    while (m.find()) if (m.end() > m.start()) out.add(new int[]{m.start(), m.end()});
  }
}
