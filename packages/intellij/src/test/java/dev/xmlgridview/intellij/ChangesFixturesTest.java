package dev.xmlgridview.intellij;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.model.DocumentDiff;
import dev.xmlgridview.intellij.model.JsonModelBuilder;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Runs the shared change fixtures (fixtures/changes) against the Java port of diffDocuments. */
public class ChangesFixturesTest extends BasePlatformTestCase {
  private XmlDocumentModel model(Path file) throws Exception {
    String text = Files.readString(file, StandardCharsets.UTF_8);
    XmlDocumentModel m = file.toString().endsWith(".json")
                         ? JsonModelBuilder.build(text, null)
                         : ReadAction.compute(() -> XmlModelBuilder.build(getProject(), text));
    assertFalse(file + " must be well-formed", m.hasErrors());
    return m;
  }

  private static JsonObject strings(Map<String, String> m) {
    JsonObject o = new JsonObject();
    m.forEach((k, v) -> o.add(k, v == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(v)));
    return o;
  }

  static JsonObject canonical(DocumentDiff.Changes d) {
    JsonObject out = new JsonObject();
    JsonArray added = new JsonArray();
    d.added().forEach(n -> added.add(n.path()));
    JsonArray modified = new JsonArray();
    for (DocumentDiff.Modified m : d.modified()) {
      JsonObject o = new JsonObject();
      o.addProperty("path", m.node().path());
      if (!m.attrs().isEmpty()) o.add("attrs", strings(m.attrs()));
      if (!m.removedAttrs().isEmpty()) o.add("removedAttrs", strings(m.removedAttrs()));
      if (m.text() != null) o.addProperty("text", m.text());
      if (m.type() != null) o.addProperty("type", m.type());
      modified.add(o);
    }
    JsonArray removed = new JsonArray();
    for (DocumentDiff.Removed r : d.removed()) {
      JsonObject o = new JsonObject();
      o.add("parent", r.parent() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(r.parent().path()));
      o.addProperty("before", r.before());
      o.addProperty("name", r.name());
      o.addProperty("preview", r.preview());
      removed.add(o);
    }
    out.add("added", added);
    out.add("modified", modified);
    out.add("removed", removed);
    return out;
  }

  public void testFixtures() throws Exception {
    Path dir = Path.of(System.getProperty("xmlgridview.fixtures"), "changes");
    List<Path> bases;
    try (Stream<Path> s = Files.list(dir)) {
      bases = s.filter(p -> p.getFileName().toString().matches(".*\\.base\\.(xml|json)$")).sorted().toList();
    }
    assertFalse("no change fixtures found", bases.isEmpty());
    for (Path base : bases) {
      String file = base.getFileName().toString();
      String name = file.replaceFirst("\\.base\\.(xml|json)$", "");
      String ext = file.substring(file.lastIndexOf('.'));
      DocumentDiff.Changes d = DocumentDiff.diff(model(base), model(dir.resolve(name + ext)));
      JsonElement expected = JsonParser.parseString(Files.readString(dir.resolve(name + ".changes.json"), StandardCharsets.UTF_8));
      assertEquals(name, expected, canonical(d));
    }
  }
}
