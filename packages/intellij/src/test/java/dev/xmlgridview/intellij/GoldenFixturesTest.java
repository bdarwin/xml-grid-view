package dev.xmlgridview.intellij;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.model.CanonicalJson;
import dev.xmlgridview.intellij.model.SearchOptions;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs every shared golden fixture (fixtures/cases) against the Java model and
 * compares with the expected JSON written by the TypeScript reference.
 */
public class GoldenFixturesTest extends BasePlatformTestCase {

  static Path casesDir() {
    String dir = System.getProperty("xmlgridview.fixtures");
    assertNotNull("system property xmlgridview.fixtures is not set", dir);
    return Path.of(dir, "cases");
  }

  static List<Path> cases() throws IOException {
    try (Stream<Path> s = Files.list(casesDir())) {
      return s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
    }
  }

  private XmlDocumentModel model(String text) {
    return ReadAction.compute(() -> XmlModelBuilder.build(getProject(), text));
  }

  public void testAllFixtures() throws IOException {
    List<Path> cases = cases();
    assertFalse("no fixture cases found", cases.isEmpty());
    List<String> failures = new ArrayList<>();
    int checks = 0;
    for (Path xml : cases) {
      String name = xml.getFileName().toString().replaceFirst("\\.xml$", "");
      String text = Files.readString(xml, StandardCharsets.UTF_8);
      XmlDocumentModel model = model(text);
      Path base = xml.getParent();

      if (name.startsWith("malformed-")) {
        JsonObject expected = read(base.resolve(name + ".errors.json")).getAsJsonObject();
        checks++;
        if (!model.hasErrors()) {
          failures.add(name + ": expected errors, got none");
          continue;
        }
        int line = expected.getAsJsonObject("firstError").get("line").getAsInt();
        if (model.errors().get(0).line() != line) {
          failures.add(name + ": first error line " + model.errors().get(0).line() + " (" + model.errors().get(0).message()
                       + "), expected " + line);
        }
        continue;
      }

      if (model.hasErrors()) failures.add(name + ": unexpected error " + model.errors().get(0));
      checks += compare(failures, name + ".tree.json", read(base.resolve(name + ".tree.json")),
                        parse(CanonicalJson.write(CanonicalJson.tree(model))));
      checks += compare(failures, name + ".grids.json", read(base.resolve(name + ".grids.json")),
                        parse(CanonicalJson.write(CanonicalJson.grids(model))));

      Path searchFile = base.resolve(name + ".search.json");
      if (!Files.exists(searchFile)) continue;
      JsonObject search = read(searchFile).getAsJsonObject();
      int i = 0;
      for (JsonElement ce : search.getAsJsonArray("cases")) {
        JsonObject c = ce.getAsJsonObject();
        JsonObject o = c.getAsJsonObject("options");
        SearchOptions options = new SearchOptions(o.get("caseSensitive").getAsBoolean(), o.get("wholeWord").getAsBoolean(),
                                                  o.get("regex").getAsBoolean());
        JsonObject grid = c.has("grid") ? c.getAsJsonObject("grid") : null;
        Object actual = CanonicalJson.searchCase(model, c.get("query").getAsString(), options, c.get("scope").getAsString(),
                                                 c.get("target").getAsString(),
                                                 grid == null ? null : grid.get("path").getAsString(),
                                                 grid == null ? null : grid.get("group").getAsString());
        checks += compare(failures, name + ".search.json cases[" + i++ + "] " + c.get("query"), c.get("expected"),
                          parse(CanonicalJson.write(actual)));
      }
      for (JsonElement xe : search.getAsJsonArray("xpath")) {
        JsonObject x = xe.getAsJsonObject();
        String expr = x.get("expr").getAsString();
        checks += compare(failures, name + ".search.json xpath " + expr, x.get("expected"),
                          parse(CanonicalJson.write(CanonicalJson.xpathCase(model, expr))));
      }
    }
    assertTrue("only " + checks + " checks ran", checks > 50);
    if (!failures.isEmpty()) fail(failures.size() + " fixture mismatch(es):\n" + String.join("\n\n", failures));
  }

  private static int compare(List<String> failures, String what, JsonElement expected, JsonElement actual) {
    if (!jsonEquals(expected, actual)) {
      failures.add(what + "\n  expected: " + abbreviate(expected) + "\n  actual:   " + abbreviate(actual)
                   + "\n  first difference: " + firstDiff("$", expected, actual));
    }
    return 1;
  }

  /** Structural equality; numbers compare numerically (3 == 3.0). */
  static boolean jsonEquals(JsonElement a, JsonElement b) {
    return firstDiff("$", a, b) == null;
  }

  static String firstDiff(String at, JsonElement a, JsonElement b) {
    if (a == null || a.isJsonNull()) return b == null || b.isJsonNull() ? null : at + ": expected null, got " + b;
    if (b == null || b.isJsonNull()) return at + ": expected " + a + ", got null";
    if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
      var pa = a.getAsJsonPrimitive();
      var pb = b.getAsJsonPrimitive();
      if (pa.isNumber() && pb.isNumber()) {
        return Double.compare(pa.getAsDouble(), pb.getAsDouble()) == 0 ? null : at + ": " + pa + " != " + pb;
      }
      return pa.equals(pb) ? null : at + ": " + pa + " != " + pb;
    }
    if (a.isJsonArray() && b.isJsonArray()) {
      JsonArray x = a.getAsJsonArray();
      JsonArray y = b.getAsJsonArray();
      for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
        String d = firstDiff(at + "[" + i + "]", x.get(i), y.get(i));
        if (d != null) return d;
      }
      return x.size() == y.size() ? null : at + ": length " + x.size() + " != " + y.size();
    }
    if (a.isJsonObject() && b.isJsonObject()) {
      JsonObject x = a.getAsJsonObject();
      JsonObject y = b.getAsJsonObject();
      for (String k : x.keySet()) {
        if (!y.has(k)) return at + "." + k + ": missing";
        String d = firstDiff(at + "." + k, x.get(k), y.get(k));
        if (d != null) return d;
      }
      for (String k : y.keySet()) if (!x.has(k)) return at + "." + k + ": unexpected";
      return null;
    }
    return at + ": type mismatch " + a + " vs " + b;
  }

  private static String abbreviate(JsonElement e) {
    String s = String.valueOf(e);
    return s.length() > 400 ? s.substring(0, 400) + "…" : s;
  }

  private static JsonElement read(Path p) throws IOException {
    return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
  }

  private static JsonElement parse(String s) {
    return JsonParser.parseString(s);
  }
}
