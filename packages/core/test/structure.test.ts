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
