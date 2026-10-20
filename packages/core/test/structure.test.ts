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
