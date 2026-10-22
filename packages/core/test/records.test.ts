import { describe, expect, it } from "vitest";
import { RecordScanner, scanRecords, type RecordScan } from "../src/index.js";

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
