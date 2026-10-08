package dev.xmlgridview.intellij;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.xmlgridview.intellij.model.ValueEdits;
import dev.xmlgridview.intellij.model.XAttr;
import dev.xmlgridview.intellij.model.XNode;
import dev.xmlgridview.intellij.model.XmlDocumentModel;
import dev.xmlgridview.intellij.model.XmlModelBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Runs the shared value-edit fixtures (fixtures/edits) against the Java port of computeValueEdit. */
public class EditFixturesTest extends BasePlatformTestCase {

  private XmlDocumentModel model(String text) {
    return ReadAction.compute(() -> XmlModelBuilder.build(getProject(), text));
  }

  public void testEditFixtures() throws IOException {
    Path dir = Path.of(System.getProperty("xmlgridview.fixtures"), "edits");
    List<Path> xmls;
    try (Stream<Path> s = Files.list(dir)) {
      xmls = s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
    }
    assertFalse("no edit fixtures found", xmls.isEmpty());
    List<String> failures = new ArrayList<>();
    int checks = 0;
    for (Path xml : xmls) {
      String name = xml.getFileName().toString().replaceFirst("\\.xml$", "");
      String text = Files.readString(xml, StandardCharsets.UTF_8);
      XmlDocumentModel model = model(text);
      JsonObject file = JsonParser.parseString(Files.readString(dir.resolve(name + ".edits.json"), StandardCharsets.UTF_8)).getAsJsonObject();
      for (JsonElement ce : file.getAsJsonArray("cases")) {
        checks++;
        JsonObject c = ce.getAsJsonObject();
        JsonObject t = c.getAsJsonObject("target");
        StringBuilder path = new StringBuilder();
        for (JsonElement seg : t.getAsJsonArray("path")) {
          if (!path.isEmpty()) path.append('/');
          path.append(seg.getAsInt());
        }
        ValueEdits.Target target = t.get("kind").getAsString().equals("attr")
                                   ? ValueEdits.Target.attr(path.toString(), t.get("name").getAsString())
                                   : ValueEdits.Target.text(path.toString());
        String value = c.get("value").getAsString();
        JsonObject expected = c.getAsJsonObject("expected");
        ValueEdits.Result r = ValueEdits.compute(model, target, value);
        String where = name + " " + t + " = " + value;
        if (expected.has("error")) {
          if (r.isOk()) failures.add(where + ": expected an error, got " + r.edit());
          continue;
        }
        if (!r.isOk()) {
          failures.add(where + ": unexpected error " + r.error());
          continue;
        }
        JsonObject e = expected.getAsJsonObject("edit");
        ValueEdits.TextEdit want = new ValueEdits.TextEdit(e.get("offset").getAsInt(), e.get("length").getAsInt(), e.get("text").getAsString());
        if (!want.equals(r.edit())) {
          failures.add(where + ": edit " + r.edit() + ", expected " + want);
          continue;
        }
        // Round trip: apply, rebuild, read the value back.
        XmlDocumentModel after = model(r.edit().applyTo(text));
        if (after.hasErrors()) failures.add(where + ": result is not well-formed: " + after.errors().get(0));
        XNode el = after.byPath(path.toString());
        String readBack = el == null ? null : target.isAttr() ? valueOf(el.attr(target.attrName())) : el.text();
        if (!value.strip().equals(readBack)) failures.add(where + ": read back " + readBack);
      }
    }
    assertTrue("no edit cases checked", checks > 0);
    if (!failures.isEmpty()) fail(failures.size() + " edit fixture mismatch(es):\n" + String.join("\n", failures));
  }

  private static String valueOf(XAttr a) {
    return a == null ? null : a.value();
  }
}
