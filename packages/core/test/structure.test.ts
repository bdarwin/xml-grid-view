import { describe, expect, it } from "vitest";
import { isQName } from "../src/index.js";

describe("isQName", () => {
  it("accepts XML names with an optional prefix", () => {
    for (const n of ["a", "_x", "book-2", "a.b", "m:thumb", "é", "名前", "x·y"]) expect(isQName(n), n).toBe(true);
  });
  it("rejects everything else", () => {
    for (const n of ["", "1a", "-a", ".a", "a b", "a:b:c", ":a", "a:", "a/b", "a&b", "<a>"]) expect(isQName(n), n).toBe(false);
  });
});

describe("structure fixtures", () => {
  it("never produce overlapping edits or two edits at one offset", async () => {
    const { readdirSync, readFileSync } = await import("node:fs");
    const { join } = await import("node:path");
    const dir = join(__dirname, "..", "..", "..", "fixtures", "structure");
    for (const f of readdirSync(dir).filter((x) => x.endsWith(".ops.json"))) {
      const data = JSON.parse(readFileSync(join(dir, f), "utf8")) as { cases: { op: unknown; expected: { edits?: { offset: number; length: number }[] } }[] };
      for (const c of data.cases) {
        const edits = [...(c.expected.edits ?? [])].sort((a, b) => a.offset - b.offset);
        for (let i = 1; i < edits.length; i++) {
          expect(edits[i - 1].offset + edits[i - 1].length, `${f} ${JSON.stringify(c.op)}`).toBeLessThanOrEqual(edits[i].offset);
          expect(edits[i - 1].offset, `${f} ${JSON.stringify(c.op)}`).not.toBe(edits[i].offset);
        }
      }
    }
  });
});

describe("elementAtOffset", () => {
  it("finds the innermost element containing an offset", async () => {
    const { parseXml, XmlModel, elementAtOffset } = await import("../src/index.js");
    const text = '<a x="1">\n  <b>hi</b>\n  <c/>\n</a> ';
    const sk = new XmlModel(parseXml(text)).skeleton();
    const at = (s: string, d = 0) => elementAtOffset(sk, text.indexOf(s) + d);
    expect(at("x=")).toBe(0);
    expect(at("hi")).toBe(1);
    expect(at("</b>", 3)).toBe(1);
    expect(at("\n  <c")).toBe(0);
    expect(at("<c/>", 3)).toBe(2);
    expect(at("</a>")).toBe(0);
    expect(elementAtOffset(sk, text.length - 1)).toBe(-1);
  });
});
