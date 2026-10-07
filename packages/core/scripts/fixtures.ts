/**
 * Computes the expected golden-fixture outputs for every case in fixtures/cases
 * using the TypeScript core (the reference implementation).
 */
import { readdirSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  XmlModel,
  canonicalErrors,
  canonicalGrids,
  canonicalTree,
  jsBackend,
  parseXml,
  runSearchCase,
  runXPathCase,
  stableJson,
  type SearchFile,
} from "../src/index.js";

export const CASES_DIR = resolve(fileURLToPath(new URL(".", import.meta.url)), "../../../fixtures/cases");

export interface FixtureCase {
  name: string;
  xmlPath: string;
  malformed: boolean;
}

export function listCases(): FixtureCase[] {
  return readdirSync(CASES_DIR)
    .filter((f) => f.endsWith(".xml"))
    .sort()
    .map((f) => {
      const name = f.slice(0, -4);
      return { name, xmlPath: join(CASES_DIR, f), malformed: name.startsWith("malformed-") };
    });
}

function readJson<T>(path: string): T | null {
  try {
    return JSON.parse(readFileSync(path, "utf8")) as T;
  } catch {
    return null;
  }
}

/** Expected file contents (path → text) for one case. */
export function expectedOutputs(c: FixtureCase): Map<string, string> {
  const text = readFileSync(c.xmlPath, "utf8");
  if (text.includes("\r")) throw new Error(`${c.name}: fixtures must use LF line endings`);
  const model = new XmlModel(parseXml(text));
  const out = new Map<string, string>();
  const base = join(CASES_DIR, c.name);
  if (c.malformed) {
    const errors = canonicalErrors(model);
    if (!errors) throw new Error(`${c.name}: expected parse errors but there were none`);
    out.set(`${base}.errors.json`, stableJson(errors));
    return out;
  }
  if (model.errors.length) throw new Error(`${c.name}: unexpected parse error ${JSON.stringify(model.errors[0])}`);
  out.set(`${base}.tree.json`, stableJson(canonicalTree(model)));
  out.set(`${base}.grids.json`, stableJson(canonicalGrids(model)));
  const search = readJson<SearchFile>(`${base}.search.json`);
  if (search) {
    const filled: SearchFile = {
      cases: search.cases.map((sc) => ({ ...sc, expected: runSearchCase(model, sc) })),
      xpath: search.xpath.map((xc) => ({ expr: xc.expr, expected: runXPathCase(model, text, xc.expr, jsBackend) })),
    };
    out.set(`${base}.search.json`, stableJson(filled));
  }
  return out;
}
