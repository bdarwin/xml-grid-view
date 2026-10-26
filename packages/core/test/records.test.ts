import { describe, expect, it } from "vitest";
import { RecordScanner, parseXml, scanRecords, xmlEncoding, type RecordScan } from "../src/index.js";

const enc = new TextEncoder();
const dec = new TextDecoder();

function records(text: string, scan: RecordScan): string[] {
  const bytes = enc.encode(text);
  return Array.from({ length: scan.count }, (_, i) => dec.decode(bytes.subarray(scan.starts[i], scan.ends[i])));
}

/** Feeds `text` in chunks of `size` bytes. */
function scanChunked(text: string, size: number): RecordScan {
  const bytes = enc.encode(text);
  const s = new RecordScanner();
  for (let i = 0; i < bytes.length; i += size) s.feed(bytes.subarray(i, i + size));
  return s.finish();
}

const TRICKY = `<?xml version="1.0"?>
<!DOCTYPE catalog [
  <!ENTITY note "a > b">
  <!ELEMENT catalog (book*)>
]>
<!-- <book>not a record</book> -->
<catalog xmlns:x="urn:x" title='a > "b"'>
  <book id="1" note="x>y"><title>Ünïcödé — 日本</title><x:extra/></book>
  <?pi <book>?>
  <book id="2"><![CDATA[</book><book>]]><book>nested same name</book></book>
  <magazine id="m1"/>
  <book id="3" >
    <!-- </book> -->
  </book >
</catalog>
`;

describe("record scanner", () => {
  it("finds the root's children as byte ranges, skipping comments, CDATA, PIs and the DOCTYPE", () => {
    const s = scanRecords(enc.encode(TRICKY));
    expect(s.error).toBeUndefined();
    expect(s.rootName).toBe("catalog");
    expect(s.rootStartTag).toBe(`<catalog xmlns:x="urn:x" title='a > "b"'>`);
    expect(records(TRICKY, s)).toEqual([
      `<book id="1" note="x>y"><title>Ünïcödé — 日本</title><x:extra/></book>`,
      `<book id="2"><![CDATA[</book><book>]]><book>nested same name</book></book>`,
      `<magazine id="m1"/>`,
      `<book id="3" >\n    <!-- </book> -->\n  </book >`,
    ]);
    expect(Array.from(s.nameIdx, (i) => s.names[i])).toEqual(["book", "book", "magazine", "book"]);
  });

  it("gives the same result however the bytes are chunked", () => {
    const whole = scanRecords(enc.encode(TRICKY));
    for (const size of [1, 2, 3, 7, 64]) {
      const s = scanChunked(TRICKY, size);
      expect(Array.from(s.starts), `chunks of ${size}`).toEqual(Array.from(whole.starts));
      expect(Array.from(s.ends), `chunks of ${size}`).toEqual(Array.from(whole.ends));
      expect(s.rootStartTag).toBe(whole.rootStartTag);
    }
  });

  it("grows past its initial capacity", () => {
    const text = `<log>${"<e>x</e>".repeat(5000)}</log>`;
    const s = scanRecords(enc.encode(text));
    expect(s.count).toBe(5000);
    expect(records(text, s)[4999]).toBe("<e>x</e>");
  });

  it("reports malformed input and keeps the complete records before it", () => {
    const cut = scanRecords(enc.encode(`<r><a>1</a><b>2`));
    expect(cut.error?.message).toBe("The file ends inside an element");
    expect(cut.count).toBe(1);
    expect(scanRecords(enc.encode(`<r><a></r>`)).error?.message).toBe("End tag </r> does not match <a>");
    expect(scanRecords(enc.encode(`<r/><r/>`)).error?.message).toBe("Content after the root element");
    expect(scanRecords(enc.encode(`<r><a <b></a></r>`)).error?.message).toBe("'<' inside a start tag");
    expect(scanRecords(enc.encode(`hello`)).error?.message).toBe("No root element");
  });

  it("handles a root without records", () => {
    const s = scanRecords(enc.encode(`<root a="1"/>`));
    expect(s.error).toBeUndefined();
    expect(s.count).toBe(0);
    expect(s.rootName).toBe("root");
  });
});

describe("DOCTYPE and encoding", () => {
  it("keeps the DOCTYPE, so pages decode the entities it declares", () => {
    const scan = scanChunked(TRICKY, 7);
    expect(scan.doctype).toMatch(/^<!DOCTYPE catalog \[[\s\S]*<!ENTITY note "a > b">[\s\S]*\]>$/);
    expect(scanRecords(enc.encode("<r><a/></r>")).doctype).toBe("");
  });

  it("decodes records of an ISO-8859-1 file with an external DTD, as the view parses a page", () => {
    // DBLP-style: Latin-1 bytes, HTML entities declared in a DTD that isn't read.
    const head = '<?xml version="1.0" encoding="ISO-8859-1"?>\n<!DOCTYPE dblp SYSTEM "dblp.dtd">\n<dblp>\n';
    const body = '<article key="a"><author>J&uuml;rgen</author><title>Café</title></article>\n</dblp>\n';
    const bytes = Uint8Array.from([...head, ...body].map((c) => c.charCodeAt(0)));
    const encoding = xmlEncoding(bytes);
    expect(encoding).toBe("windows-1252");
    const s = new RecordScanner(encoding);
    s.feed(bytes);
    const scan = s.finish();
    expect(scan.error).toBeUndefined();
    const latin1 = new TextDecoder(encoding);
    const record = latin1.decode(bytes.subarray(scan.starts[0], scan.ends[0]));
    const doc = parseXml(`${scan.doctype}${scan.rootStartTag}${record}</dblp>`);
    expect(doc.errors).toEqual([]);
    const article = doc.roots[0].elements[0];
    expect(article.elements.map((e) => e.children.map((c) => (c.kind === "element" ? "" : c.value)).join(""))).toEqual(["Jürgen", "Café"]);
  });

  it("finds the encoding from a byte order mark or the declaration", () => {
    const b = (s: string) => Uint8Array.from([...s].map((c) => c.charCodeAt(0)));
    expect(xmlEncoding(b("<root/>"))).toBe("utf-8");
    expect(xmlEncoding(b('<?xml version="1.0" encoding="UTF-8"?><r/>'))).toBe("utf-8");
    expect(xmlEncoding(b("<?xml version='1.0' encoding='windows-1252'?><r/>"))).toBe("windows-1252");
    expect(xmlEncoding(b('<?xml version="1.0" encoding="no-such-charset"?><r/>'))).toBe("utf-8");
    expect(xmlEncoding(Uint8Array.from([0xef, 0xbb, 0xbf, 0x3c]))).toBe("utf-8");
    expect(xmlEncoding(Uint8Array.from([0xff, 0xfe, 0x3c, 0x00]))).toBe("utf-16le");
  });
});
