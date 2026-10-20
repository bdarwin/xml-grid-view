package dev.xmlgridview.intellij.model;

import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Text formats for grids: spreadsheet TSV in (paste) and CSV or JSON out (export). Mirrors gridView.ts. */
public final class GridText {
  private GridText() {
  }

  /**
   * Parses tab-separated text as copied from a spreadsheet: fields in double quotes may hold tabs,
   * line breaks and doubled quotes. A trailing line break doesn't add a row.
   */
  public static @NotNull List<List<String>> parseTsv(@NotNull String text) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    boolean atFieldStart = true;
    int i = 0;
    while (i < text.length()) {
      char ch = text.charAt(i);
      if (quoted) {
        if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          field.append('"');
          i += 2;
          continue;
        }
        if (ch == '"') quoted = false;
        else field.append(ch);
        i++;
        continue;
      }
      if (ch == '"' && atFieldStart) {
        quoted = true;
        atFieldStart = false;
        i++;
        continue;
      }
      if (ch == '\t') {
        row.add(field.toString());
        field.setLength(0);
        atFieldStart = true;
        i++;
        continue;
      }
      if (ch == '\r' || ch == '\n') {
        row.add(field.toString());
        rows.add(row);
        row = new ArrayList<>();
        field.setLength(0);
        atFieldStart = true;
        i += ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n' ? 2 : 1;
        continue;
      }
      field.append(ch);
      atFieldStart = false;
      i++;
    }
    if (!field.isEmpty() || !row.isEmpty()) {
      row.add(field.toString());
      rows.add(row);
    }
    return rows;
  }

  private static String csvField(String s) {
    return s.matches("(?s).*[\",\\r\\n].*") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
  }

  /** CSV (RFC 4180) of the given model rows and every column, with a header row. */
  public static @NotNull String toCsv(@NotNull GridTable t, @NotNull int[] rows) {
    StringBuilder b = new StringBuilder();
    for (int c = 0; c < t.columnCount(); c++) b.append(c > 0 ? "," : "").append(csvField(t.columns().get(c).label()));
    b.append("\r\n");
    for (int r : rows) {
      for (int c = 0; c < t.columnCount(); c++) b.append(c > 0 ? "," : "").append(csvField(t.cellText(r, c)));
      b.append("\r\n");
    }
    return b.toString();
  }

  /** The rows as JSON objects keyed by column label; nested-table cells are left out, numbers stay strings. */
  public static @NotNull String toJson(@NotNull GridTable t, @NotNull int[] rows) {
    List<Map<String, String>> out = new ArrayList<>();
    for (int r : rows) {
      Map<String, String> o = new LinkedHashMap<>();
      for (int c = 0; c < t.columnCount(); c++) if (t.cell(r, c) instanceof String s) o.put(t.columns().get(c).label(), s);
      out.add(o);
    }
    return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out) + "\n";
  }
}
