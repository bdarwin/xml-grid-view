import { describe, expect, it } from "vitest";
import { XmlModel, cellText, parseXml, pathOfElement, elementAtPath, idAtPath, pathOfId, XmlParser } from "../src/index.js";

function model(xml: string) {
  return new XmlModel(parseXml(xml));
}

function grid(m: XmlModel, id = 0, group?: string) {
  const t = m.table(id, group);
  const ncols = t.columns.length;
  const rows: string[][] = [];
  for (let r = 0; r < t.rowIds.length; r++) {
    rows.push(t.columns.map((c, i) => cellText(c, t.cells[r * ncols + i])));
  }
  return { t, keys: t.columns.map((c) => c.key), kinds: t.columns.map((c) => c.kind), rows };
}

describe("parser", () => {
  it("records offsets for elements, attributes and text", () => {
    const xml = '<?xml version="1.0"?>\r\n<a x="1">\r\n  <b>😀é</b><c/><!--c--><![CDATA[z]]></a>';
    const doc = parseXml(xml);
    expect(doc.errors).toEqual([]);
    const [a, b, c] = doc.elements;
    expect(xml.slice(a.start, a.openEnd)).toBe('<a x="1">');
    expect(xml.slice(a.start, a.end)).toBe(xml.slice(xml.indexOf("<a")));
    expect(xml.slice(a.attrs[0].start, a.attrs[0].end)).toBe('x="1"');
    expect(xml.slice(b.start, b.end)).toBe("<b>😀é</b>");
    expect(xml.slice(c.start, c.end)).toBe("<c/>");
    const text = b.children[0];
    expect(text.kind).toBe("text");
    expect(xml.slice(text.start, text.end)).toBe("😀é");
    const cdata = a.children.find((ch) => ch.kind === "cdata")!;
    expect(cdata.kind === "cdata" && cdata.value).toBe("z");
    expect(xml.slice(cdata.start, cdata.end)).toBe("<![CDATA[z]]>");
  });

  it("skips comments and processing instructions", () => {
    const doc = parseXml("<a><!-- x --><?pi data?><b/></a>");
    expect(doc.elements.map((e) => e.name)).toEqual(["a", "b"]);
    expect(doc.elements[0].children).toHaveLength(1);
  });

  it("gives the same result when parsed in small chunks", () => {
    const xml = '<r>\r\n<i a="1">😀😀😀</i>\r\n<i a="2"><![CDATA[x]]></i></r>';
    const whole = parseXml(xml);
    const chunked = new XmlParser(xml, 3).run();
    const strip = (d: typeof whole) => d.elements.map((e) => [e.name, e.start, e.openEnd, e.end, e.attrs.map((a) => [a.start, a.end])]);
    expect(strip(chunked)).toEqual(strip(whole));
  });

  it("tolerates malformed input, returning a partial tree and errors", () => {
    const xml = "<root>\n  <a>1</a>\n  <b x='1>2</b>\n  <c>";
    const doc = parseXml(xml);
    expect(doc.errors.length).toBeGreaterThan(0);
    expect(doc.errors[0].line).toBeGreaterThanOrEqual(1);
    expect(doc.errors[0].offset).toBeGreaterThan(0);
    expect(doc.elements[0].name).toBe("root");
    expect(doc.elements.some((e) => e.name === "a")).toBe(true);
    // Unclosed elements end at the end of the text.
    expect(doc.elements[0].end).toBe(xml.length);
  });

  it("keeps parsing after mismatched close tags", () => {
    const doc = parseXml("<a><b></c></b><d/></a>");
    expect(doc.errors[0]).toMatchObject({ line: 1 });
    // saxes recovers by closing all open elements; later elements are still collected.
    expect(doc.elements.map((e) => e.name)).toEqual(["a", "b", "d"]);
    for (const e of doc.elements) expect(e.end).toBeGreaterThanOrEqual(e.openEnd);
  });

  it("returns errors with location for garbage", () => {
    const doc = parseXml("not xml at all");
    expect(doc.roots).toHaveLength(0);
    expect(doc.errors[0]).toMatchObject({ line: 1 });
  });
});

describe("grid model", () => {
  it("builds uniform records", () => {
    const m = model(`<books>
      <book id="1"><title>A</title><price>10</price></book>
      <book id="2"><title>B</title><price>20</price></book>
    </books>`);
    const g = grid(m);
    expect(g.t.group).toBe("book");
    expect(g.keys).toEqual(["@id", "title", "price"]);
    expect(g.kinds).toEqual(["attr", "leaf", "leaf"]);
    expect(g.rows).toEqual([
      ["1", "A", "10"],
      ["2", "B", "20"],
    ]);
  });

  it("unions columns of heterogeneous siblings and exposes tag groups", () => {
    const m = model(`<r>
      <item a="1"><x>1</x></item>
      <item b="2"><y>2</y></item>
      <other/>
      <item a="3"/>
    </r>`);
    const g = grid(m);
    expect(g.t.groups).toEqual([
      { name: "item", count: 3 },
      { name: "other", count: 1 },
    ]);
    expect(g.keys).toEqual(["@a", "@b", "x", "y"]);
    expect(g.rows).toEqual([
      ["1", "", "1", ""],
      ["", "2", "", "2"],
      ["3", "", "", ""],
    ]);
    const other = grid(m, 0, "other");
    expect(other.t.group).toBe("other");
    expect(other.rows).toEqual([[]]);
  });

  it("shows nested repeats as drill-down cells", () => {
    const m = model(`<orders>
      <order id="1"><line sku="a"/><line sku="b"/><note>n</note><note>m</note></order>
      <order id="2"><line sku="c"/></order>
    </orders>`);
    const g = grid(m);
    expect(g.keys).toEqual(["@id", "line", "note"]);
    expect(g.kinds).toEqual(["attr", "complex", "complex"]);
    expect(g.rows).toEqual([
      ["1", "{line ×2}", "{note ×2}"],
      ["2", "{line ×1}", ""],
    ]);
    // Drill down into the first order's lines.
    const order1 = g.t.rowIds[0];
    const lines = grid(m, order1, "line");
    expect(lines.rows).toEqual([["a"], ["b"]]);
  });

  it("handles attribute-only rows", () => {
    const m = model('<r><p x="1" y="2"/><p x="3" z="4"/></r>');
    const g = grid(m);
    expect(g.keys).toEqual(["@x", "@y", "@z"]);
    expect(g.rows).toEqual([
      ["1", "2", ""],
      ["3", "", "4"],
    ]);
  });

  it("keeps namespace-qualified names", () => {
    const m = model(`<r xmlns="urn:d" xmlns:p="urn:p">
      <p:item p:id="1"><p:name>a</p:name></p:item>
    </r>`);
    const g = grid(m);
    expect(g.t.group).toBe("p:item");
    expect(g.keys).toEqual(["@p:id", "p:name"]);
    const item = m.elements[1];
    expect(item).toMatchObject({ local: "item", prefix: "p", uri: "urn:p" });
    expect(m.elements[0]).toMatchObject({ uri: "urn:d" });
    expect(item.attrs[0]).toMatchObject({ local: "id", uri: "urn:p" });
  });

  it("adds a #text column for mixed content", () => {
    const m = model("<r><p>Hello <b>world</b> again</p><p>plain</p></r>");
    const g = grid(m);
    expect(g.keys).toEqual(["b", "#text"]);
    expect(g.rows).toEqual([
      ["world", "Hello  again"],
      ["", "plain"],
    ]);
  });

  it("includes CDATA in text values", () => {
    const m = model("<r><s><![CDATA[a < b]]></s><s>c</s></r>");
    expect(grid(m).rows).toEqual([["a < b"], ["c"]]);
  });

  it("shows a self row for elements without element children", () => {
    const m = model('<r><e/><e a="1">t</e></r>');
    const empty = grid(m, 1);
    expect(empty.t.group).toBeNull();
    expect(empty.rows).toEqual([[]]);
    const withAttr = grid(m, 2);
    expect(withAttr.keys).toEqual(["@a", "#text"]);
    expect(withAttr.rows).toEqual([["1", "t"]]);
  });

  it("treats empty elements as empty leaf values", () => {
    const m = model("<r><row><a/><b></b><c>x</c></row></r>");
    const g = grid(m);
    expect(g.kinds).toEqual(["leaf", "leaf", "leaf"]);
    expect(g.rows).toEqual([["", "", "x"]]);
  });

  it("provides source spans for cells", () => {
    const xml = '<r><i k="v"><n>t</n></i></r>';
    const m = model(xml);
    const t = m.table(0);
    expect(xml.substr(t.spans[0], t.spans[1])).toBe('k="v"');
    expect(xml.substr(t.spans[2], t.spans[3])).toBe("<n>t</n>");
  });

  it("builds a skeleton and round-trips paths", () => {
    const m = model("<r><a/><b><c/><c/></b></r>");
    const sk = m.skeleton();
    expect(sk.count).toBe(5);
    expect(sk.names[sk.nameIdx[4]]).toBe("c");
    expect(pathOfId(sk, 4)).toEqual([0, 1, 1]);
    expect(idAtPath(sk, [0, 1, 1])).toBe(4);
    expect(idAtPath(sk, [0, 5])).toBe(-1);
    expect(pathOfElement(m.elements[4])).toEqual([0, 1, 1]);
    expect(elementAtPath(m.doc, [0, 1, 1])).toBe(m.elements[4]);
  });

  it("handles 100k rows quickly", () => {
    const rows = Array.from({ length: 100_000 }, (_, i) => `<r id="${i}"><v>${i}</v></r>`).join("");
    const t0 = performance.now();
    const m = model(`<root>${rows}</root>`);
    const t = m.table(0);
    expect(t.rowIds.length).toBe(100_000);
    expect(performance.now() - t0).toBeLessThan(10_000);
  });
});
