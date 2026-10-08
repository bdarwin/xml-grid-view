import { describe, expect, it } from "vitest";
import { JsonNumber, cellText, detectJson, jsonAt, jsonPointer, jsonTable, parseJson, prettyJson } from "../src/index.js";

describe("json", () => {
  it("keeps number literals exactly", () => {
    const d = detectJson('{"id": 12345678901234567890, "p": 1.50, "e": 1e3}');
    expect(d.kind).toBe("json");
    if (d.kind !== "json") return;
    expect(d.pretty).toBe('{\n  "id": 12345678901234567890,\n  "p": 1.50,\n  "e": 1e3\n}');
    expect(jsonAt(d.value, ["id"])).toEqual(new JsonNumber("12345678901234567890"));
  });

  it("only treats text starting with { or [ as JSON", () => {
    expect(detectJson("hello {x}").kind).toBe("text");
    expect(detectJson('"just a string"').kind).toBe("text");
    expect(detectJson("  [1]  ").kind).toBe("json");
  });

  it("reports syntax errors with location", () => {
    const d = detectJson('{\n  "a": 1,\n}');
    expect(d).toMatchObject({ kind: "text", jsonError: { line: 3, column: 1 } });
    expect(parseJson("[1, 2")).toMatchObject({ error: { message: expect.stringMatching(/end/i) } });
    expect(parseJson("{'a': 1}")).toHaveProperty("error");
  });

  it("treats __proto__ as a plain key", () => {
    const r = parseJson('{"__proto__": {"x": 1}}');
    expect("value" in r && r.value instanceof Map && r.value.has("__proto__")).toBe(true);
    expect(({} as Record<string, unknown>).x).toBeUndefined();
  });

  it("builds grids for arrays of objects with drill cells", () => {
    const d = detectJson('[{"a": 1, "b": {"c": 2}}, {"a": 3, "d": [1, 2]}]');
    if (d.kind !== "json") throw new Error();
    const t = jsonTable(d.value);
    expect(t.columns.map((c) => [c.key, c.kind])).toEqual([
      ["a", "leaf"],
      ["b", "complex"],
      ["d", "complex"],
    ]);
    expect(t.cells).toEqual(["1", 1, null, "3", null, 2]);
    expect(t.labels[1]).toBe("{ 1 key }");
    expect(cellText(t.columns[2], t.cells[5])).toBe("{d ×2}");
    expect(t.rowSegments).toEqual([0, 1]);
  });

  it("uses a map table for objects of objects, key/value otherwise", () => {
    const m = jsonTable((parseJson('{"x": {"v": 1}, "y": {"w": 2}}') as { value: never }).value);
    expect(m.columns.map((c) => c.key)).toEqual(["(key)", "v", "w"]);
    const kv = jsonTable((parseJson('{"x": 1, "y": {"w": 2}}') as { value: never }).value);
    expect(kv.columns.map((c) => c.key)).toEqual(["(key)", "(value)"]);
    expect(kv.cells).toEqual(["x", "1", "y", 1]);
  });

  it("formats pointers and pretty prints", () => {
    expect(jsonPointer(["a/b", 0, "c~d"])).toBe("/a~1b/0/c~0d");
    expect(prettyJson([])).toBe("[]");
  });
});
