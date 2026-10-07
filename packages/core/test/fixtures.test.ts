import { readFileSync, existsSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { expectedOutputs, listCases } from "../scripts/fixtures.js";

describe("golden fixtures", () => {
  const cases = listCases();
  it("has cases", () => expect(cases.length).toBeGreaterThan(0));
  for (const c of cases) {
    it(c.name, () => {
      for (const [path, content] of expectedOutputs(c)) {
        expect(existsSync(path), `${path} is missing; run pnpm fixtures:generate`).toBe(true);
        const actual = JSON.parse(content);
        const expected = JSON.parse(readFileSync(path, "utf8"));
        if (c.malformed) {
          // Parsers recover differently; only error presence and location are compared.
          expect(actual.hasErrors).toBe(true);
          expect(actual.firstError.line).toBe(expected.firstError.line);
          expect(actual.firstError.column).toBe(expected.firstError.column);
        } else {
          expect(actual).toEqual(expected);
        }
      }
    });
  }
});
