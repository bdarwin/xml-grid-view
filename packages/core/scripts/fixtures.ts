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
  canonicalFlat,
  canonicalJsonValue,
  applyEdit,
  computeValueEdit,
  elementAtPath,
  elementText,
  type EditTarget,
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
export const JSON_DIR = resolve(CASES_DIR, "../json");
export const EDITS_DIR = resolve(CASES_DIR, "../edits");

/** Value-edit cases: fixtures/edits/<name>.xml with <name>.edits.json (inputs + expected). */
export function listEditCases(): { name: string; xml: string; edits: string }[] {
  return readdirSync(EDITS_DIR)
    .filter((f) => f.endsWith(".xml"))
    .sort()
    .map((f) => ({ name: f.slice(0, -4), xml: join(EDITS_DIR, f), edits: join(EDITS_DIR, f.slice(0, -4) + ".edits.json") }));
}

export function expectedEditsOutput(c: { xml: string; edits: string }): string {
  const text = readFileSync(c.xml, "utf8");
  const model = new XmlModel(parseXml(text));
  const input = JSON.parse(readFileSync(c.edits, "utf8")) as { cases: { target: EditTarget; value: string }[] };
  const cases = input.cases.map(({ target, value }) => {
    const r = computeValueEdit(text, model, target, value);
    if ("error" in r) return { target, value, expected: { error: true } };
    // Round trip: apply, re-parse, and read the value back.
    const after = new XmlModel(parseXml(applyEdit(text, r.edit)));
    const el = elementAtPath(after.doc, target.path);
    const readBack = !el ? null : target.kind === "attr" ? (el.attrs.find((a) => a.name === target.name)?.value ?? null) : elementText(el);
    return { target, value, expected: { edit: r.edit, readBack, wellFormed: after.errors.length === 0 } };
  });
  return stableJson({ cases });
}

/** JSON value-inspector cases: fixtures/json/<name>.txt -> <name>.json. */
export function listJsonCases(): { name: string; input: string; expected: string }[] {
  return readdirSync(JSON_DIR)
    .filter((f) => f.endsWith(".txt"))
    .sort()
    .map((f) => ({ name: f.slice(0, -4), input: join(JSON_DIR, f), expected: join(JSON_DIR, f.slice(0, -4) + ".json") }));
}

export function expectedJsonOutput(c: { input: string }): string {
  return stableJson(canonicalJsonValue(readFileSync(c.input, "utf8")));
}

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
  out.set(`${base}.flat.json`, stableJson(canonicalFlat(model)));
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
