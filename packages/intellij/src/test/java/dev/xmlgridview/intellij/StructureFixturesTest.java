package dev.xmlgridview.intellij;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.model.StructureEdits;
import dev.xmlgridview.intellij.model.ValueEdits;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Runs the shared structural-edit fixtures (fixtures/structure) against the Java port of computeStructureEdit. */
public class StructureFixturesTest extends BasePlatformTestCase {
  private XmlDocumentModel model(String text) {
    return ReadAction.compute(() -> XmlModelBuilder.build(getProject(), text));
  }

  private static String path(com.google.gson.JsonArray segs) {
    StringBuilder path = new StringBuilder();
    for (JsonElement seg : segs) {
      if (!path.isEmpty()) path.append('/');
      path.append(seg.getAsInt());
    }
    return path.toString();
  }

  private static StructureEdits.Op op(JsonObject o) {
    String p = o.has("path") ? path(o.getAsJsonArray("path")) : "";
    String name = o.has("name") ? o.get("name").getAsString() : null;
    String group = o.has("group") ? o.get("group").getAsString() : null;
    return switch (o.get("kind").getAsString()) {
      case "deleteElement" -> StructureEdits.Op.deleteElement(p);
      case "deleteAttr" -> StructureEdits.Op.deleteAttr(p, name);
      case "addAttr" -> StructureEdits.Op.addAttr(p, name, o.get("value").getAsString());
      case "renameElement" -> StructureEdits.Op.rename(p, name);
      case "duplicateElement" -> StructureEdits.Op.duplicate(p);
      case "insertElement" -> StructureEdits.Op.insert(p, StructureEdits.Where.valueOf(o.get("where").getAsString().toUpperCase()), name);
      case "newRow" -> StructureEdits.Op.newRow(p);
      case "moveElement" -> StructureEdits.Op.move(p, o.get("direction").getAsString().equals("up"), o.get("sameName").getAsBoolean());
      case "deleteElements" -> {
        List<String> paths = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("paths")) paths.add(path(e.getAsJsonArray()));
        yield StructureEdits.Op.deleteElements(paths);
      }
      case "addColumn" -> StructureEdits.Op.addColumn(p, group, name);
      case "deleteColumn" -> StructureEdits.Op.deleteColumn(p, group, o.get("key").getAsString());
      case "setCells" -> {
        List<StructureEdits.Cell> cells = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("cells")) {
          JsonObject c = e.getAsJsonObject();
          cells.add(new StructureEdits.Cell(path(c.getAsJsonArray("path")), c.get("key").getAsString(), c.get("value").getAsString()));
        }
        yield StructureEdits.Op.setCells(cells);
      }
      case "insertRows" -> {
        List<java.util.Map<String, String>> rows = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("rows")) {
          java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
          for (var en : e.getAsJsonObject().entrySet()) row.put(en.getKey(), en.getValue().getAsString());
          rows.add(row);
        }
        yield StructureEdits.Op.insertRows(p, group, rows);
      }
      default -> throw new IllegalArgumentException(o.toString());
    };
  }

  public void testQNamesMatchTheTsCore() {
    for (String n : new String[]{"a", "_x", "book-2", "a.b", "m:thumb", "é", "名前", "x·y"}) assertTrue(n, StructureEdits.isQName(n));
    for (String n : new String[]{"", "1a", "-a", ".a", "a b", "a:b:c", ":a", "a:", "a/b", "a&b", "<a>"}) assertFalse(n, StructureEdits.isQName(n));
  }

  public void testStructureFixtures() throws IOException {
    Path dir = Path.of(System.getProperty("xmlgridview.fixtures"), "structure");
    List<Path> xmls;
    try (Stream<Path> s = Files.list(dir)) {
      xmls = s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
    }
    assertFalse("no structure fixtures found", xmls.isEmpty());
    List<String> failures = new ArrayList<>();
    int checks = 0;
    for (Path xml : xmls) {
      String name = xml.getFileName().toString().replaceFirst("\\.xml$", "");
      String text = Files.readString(xml, StandardCharsets.UTF_8);
      XmlDocumentModel model = model(text);
      JsonObject file = JsonParser.parseString(Files.readString(dir.resolve(name + ".ops.json"), StandardCharsets.UTF_8)).getAsJsonObject();
      for (JsonElement ce : file.getAsJsonArray("cases")) {
        checks++;
        JsonObject c = ce.getAsJsonObject();
        JsonObject expected = c.getAsJsonObject("expected");
        StructureEdits.Result r = StructureEdits.compute(model, op(c.getAsJsonObject("op")));
        String where = name + " " + c.get("op");
        if (expected.has("error")) {
          if (r.isOk()) failures.add(where + ": expected an error, got " + r.edits());
          continue;
        }
        if (!r.isOk()) {
          failures.add(where + ": unexpected error " + r.error());
          continue;
        }
        List<ValueEdits.TextEdit> want = new ArrayList<>();
        for (JsonElement ee : expected.getAsJsonArray("edits")) {
          JsonObject e = ee.getAsJsonObject();
          want.add(new ValueEdits.TextEdit(e.get("offset").getAsInt(), e.get("length").getAsInt(), e.get("text").getAsString()));
        }
        if (!want.equals(r.edits())) {
          failures.add(where + ": edits " + r.edits() + ", expected " + want);
          continue;
        }
        String result = StructureEdits.apply(text, r.edits());
        if (!result.equals(expected.get("result").getAsString())) failures.add(where + ": result differs");
        if (model(result).hasErrors()) failures.add(where + ": result is not well-formed");
      }
    }
    assertTrue("checked " + checks + " cases", checks > 30);
    if (!failures.isEmpty()) fail(failures.size() + " structure fixture mismatches:\n" + String.join("\n", failures));
  }
}
