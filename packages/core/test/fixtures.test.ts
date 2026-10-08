import { readFileSync, existsSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { expectedEditsOutput, expectedJsonOutput, expectedOutputs, listCases, listEditCases, listJsonCases } from "../scripts/fixtures.js";

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
  for (const c of listJsonCases()) {
    it(`json/${c.name}`, () => {
      expect(existsSync(c.expected), `${c.expected} is missing; run pnpm fixtures:generate`).toBe(true);
      expect(JSON.parse(expectedJsonOutput(c))).toEqual(JSON.parse(readFileSync(c.expected, "utf8")));
    });
  }
  for (const c of listEditCases()) {
    it(`edits/${c.name}`, () => {
      const expected = JSON.parse(readFileSync(c.edits, "utf8"));
      expect(JSON.parse(expectedEditsOutput(c))).toEqual(expected);
      // Every successful edit must round-trip to the requested value and keep the document well-formed.
      for (const k of expected.cases) {
        if (k.expected.error) continue;
        expect(k.expected.wellFormed, JSON.stringify(k.target)).toBe(true);
        expect(k.expected.readBack, JSON.stringify(k.target)).toBe(k.value.trim());
      }
    });
  }
});
