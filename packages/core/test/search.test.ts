import { describe, expect, it } from "vitest";
import {
  ALL_TARGETS,
  DEFAULT_OPTIONS,
  XmlModel,
  createMatcher,
  evaluateXPath,
  jsBackend,
  parseXml,
  searchDocument,
  searchGrid,
  type SearchQuery,
} from "../src/index.js";

const XML = `<catalog>
  <book id="b1" lang="en"><title>The Cat</title><author>Smith</author></book>
  <book id="b2" lang="fr"><title>Concatenate</title><author>CAT Jones</author></book>
  <magazine id="m1"><title>Cats weekly</title></magazine>
</catalog>`;

const m = new XmlModel(parseXml(XML));

function q(text: string, opts: Partial<SearchQuery["options"]> = {}, targets: Partial<SearchQuery["targets"]> = {}): SearchQuery {
  return { text, options: { ...DEFAULT_OPTIONS, ...opts }, targets: { ...ALL_TARGETS, ...targets } };
}

function hits(query: SearchQuery) {
  return searchDocument(m, query).matches.map((x) => `${m.elements[x.elementId].name}:${x.target}`);
}

describe("search", () => {
  it("is case-insensitive by default", () => {
    expect(hits(q("cat", {}, { names: false }))).toEqual(["title:text", "title:text", "author:text", "title:text"]);
    expect(hits(q("CAT", {}, { attrNames: false, attrValues: false, text: false }))).toEqual(["catalog:name"]);
  });

  it("respects case sensitivity", () => {
    expect(hits(q("Cat", { caseSensitive: true }))).toEqual(["title:text", "title:text"]);
  });

  it("supports whole word", () => {
    expect(hits(q("cat", { wholeWord: true }))).toEqual(["title:text", "author:text"]);
  });

  it("supports regular expressions", () => {
    expect(hits(q("^b\\d$", { regex: true }))).toEqual(["book:attrValue", "book:attrValue"]);
    expect(searchDocument(m, q("(", { regex: true })).error).toBeTruthy();
  });

  it("filters by target", () => {
    expect(hits(q("lang", {}, { attrValues: false, text: false }))).toEqual(["book:attrName", "book:attrName"]);
    expect(hits(q("book", {}, { attrNames: false, attrValues: false, text: false }))).toEqual(["book:name", "book:name"]);
    expect(hits(q("en", {}, { names: false, attrNames: false, text: false }))).toEqual(["book:attrValue"]);
  });

  it("returns highlight ranges", () => {
    const matcher = createMatcher("at", DEFAULT_OPTIONS)!;
    expect(matcher.ranges("Cat at bat")).toEqual([
      [1, 3],
      [4, 6],
      [8, 10],
    ]);
    const re = createMatcher("a*", { ...DEFAULT_OPTIONS, regex: true })!;
    expect(re.ranges("baab")).toEqual([[1, 3]]);
    expect(createMatcher("", DEFAULT_OPTIONS)).toBeNull();
  });

  it("searches the current grid only", () => {
    const t = m.table(0, "book");
    const r = searchGrid(t, q("cat"));
    const cells = Array.from(r.cells);
    const titleCol = t.columns.findIndex((c) => c.key === "title");
    const authorCol = t.columns.findIndex((c) => c.key === "author");
    expect(cells).toEqual([0, titleCol, 1, titleCol, 1, authorCol]);
  });

  it("truncates huge result sets", () => {
    const r = searchDocument(m, q("t"), 2);
    expect(r.matches).toHaveLength(2);
    expect(r.truncated).toBe(true);
  });
});

describe("xpath", () => {
  it("maps element results back to the model", () => {
    const r = evaluateXPath(m, XML, "//book[@lang='fr']/title", jsBackend);
    expect(r.error).toBeUndefined();
    expect(r.items.map((i) => m.elements[i.elementId].name)).toEqual(["title"]);
    expect(m.elements[r.items[0].elementId].parent?.attrs[0].value).toBe("b2");
  });

  it("maps attribute and text results to their owner elements", () => {
    const attrs = evaluateXPath(m, XML, "//@id", jsBackend);
    expect(attrs.items.map((i) => i.value)).toEqual(["b1", "b2", "m1"]);
    expect(attrs.items[2]).toMatchObject({ kind: "attribute", attrName: "id" });
    const texts = evaluateXPath(m, XML, "//author/text()", jsBackend);
    expect(texts.items.map((i) => m.elements[i.elementId].name)).toEqual(["author", "author"]);
  });

  it("returns scalars", () => {
    expect(evaluateXPath(m, XML, "count(//book)", jsBackend).scalar).toBe(2);
  });

  it("reports syntax errors", () => {
    expect(evaluateXPath(m, XML, "//book[", jsBackend).error).toBeTruthy();
  });

  it("resolves document prefixes and aliases the default namespace", () => {
    const xml = `<r xmlns="urn:d" xmlns:p="urn:p"><p:a><b>1</b></p:a><p:a><b>2</b></p:a></r>`;
    const nm = new XmlModel(parseXml(xml));
    const r = evaluateXPath(nm, xml, "/d:r/p:a/d:b", jsBackend);
    expect(r.error).toBeUndefined();
    expect(r.items.map((i) => nm.elements[i.elementId].start)).toEqual([xml.indexOf("<b>1"), xml.indexOf("<b>2")]);
    expect(r.namespaces).toEqual({ p: "urn:p", d: "urn:d" });
    // Without a prefix, nothing in the default namespace matches (XPath 1.0 semantics).
    expect(evaluateXPath(nm, xml, "/r", jsBackend).items).toEqual([]);
  });

  it("ignores comments when mapping paths", () => {
    const xml = "<r><!-- c --><a/><?pi x?><a id='2'/></r>";
    const nm = new XmlModel(parseXml(xml));
    const r = evaluateXPath(nm, xml, "/r/a[2]", jsBackend);
    expect(nm.elements[r.items[0].elementId].attrs[0].value).toBe("2");
  });

  it("reports malformed documents", () => {
    const xml = "<r><a></r>";
    const nm = new XmlModel(parseXml(xml));
    expect(evaluateXPath(nm, xml, "//a", jsBackend).error).toMatch(/not well-formed/);
  });
});
