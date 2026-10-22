import { describe, expect, it } from "vitest";
import { XmlModel, parseJsonDocument, parseXml, valueOffset } from "../src/index.js";

describe("valueOffset", () => {
  it("points inside an attribute's quotes and at an element's content", () => {
    const text = `<a><b format='ebook'>x</b><c/><d>\n  text</d></a>`;
    const m = new XmlModel(parseXml(text));
    const at = (o: number | null) => (o === null ? null : text.slice(o));
    expect(at(valueOffset(text, m, { path: [0, 0], kind: "attr", name: "format" }))).toMatch(/^ebook'/);
    expect(at(valueOffset(text, m, { path: [0, 0], kind: "text" }))).toMatch(/^x<\/b>/);
    expect(at(valueOffset(text, m, { path: [0, 2], kind: "text" }))).toMatch(/^\n {2}text/);
    // An empty-element tag has no place for a value.
    expect(valueOffset(text, m, { path: [0, 1], kind: "text" })).toBeNull();
    expect(valueOffset(text, m, { path: [0, 0], kind: "attr", name: "missing" })).toBeNull();
  });

  it("points inside JSON strings and at other JSON values", () => {
    const text = `{"color": "red", "n": 42}`;
    const m = new XmlModel(parseJsonDocument(text));
    expect(text.slice(valueOffset(text, m, { path: [0, 0], kind: "text" })!)).toMatch(/^red"/);
    expect(text.slice(valueOffset(text, m, { path: [0, 1], kind: "text" })!)).toMatch(/^42}/);
  });
});
