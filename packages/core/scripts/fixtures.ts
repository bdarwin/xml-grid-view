/**
 * Computes the expected golden-fixture outputs for every case in fixtures/cases
 * using the TypeScript core (the reference implementation).
 */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  XmlModel,
  canonicalErrors,
  canonicalFlat,
  canonicalJsonValue,
  applyEdit,
  applyEdits,
  computeStructureEdit,
  diffDocuments,
  pathKey,
  pathOfElement,
  type StructureOp,
  computeValueEdit,
  elementAtPath,
  elementText,
  type EditTarget,
  canonicalGrids,
  canonicalTree,
  jsBackend,
  parseJsonDocument,
  parseXml,
  runSearchCase,
  runXPathCase,
  stableJson,
  type SearchFile,
} from "../src/index.js";

export const CASES_DIR = resolve(fileURLToPath(new URL(".", import.meta.url)), "../../../fixtures/cases");
export const JSON_DIR = resolve(CASES_DIR, "../json");
export const EDITS_DIR = resolve(CASES_DIR, "../edits");
export const STRUCTURE_DIR = resolve(CASES_DIR, "../structure");

export const JSONEDIT_DIR = resolve(CASES_DIR, "../jsonedit");
export const CHANGES_DIR = resolve(CASES_DIR, "../changes");

/** Parses a fixture input by its extension (.json → JSON document, else XML). */
function parseByName(path: string, text: string) {
  return path.endsWith(".json") ? parseJsonDocument(text) : parseXml(text);
}

/** JSON edit inputs: fixtures/jsonedit/<name>.json (not the generated .ops.json / .edits.json). */
function jsonEditInputs(): string[] {
  return readdirSync(JSONEDIT_DIR)
    .filter((f) => f.endsWith(".json") && !/\.(ops|edits)\.json$/.test(f))
    .sort();
}

/** Value-edit cases: fixtures/edits/<name>.xml and fixtures/jsonedit/<name>.json, with <name>.edits.json (inputs + expected). */
export function listEditCases(): { name: string; xml: string; edits: string }[] {
  const xml = readdirSync(EDITS_DIR)
    .filter((f) => f.endsWith(".xml"))
    .sort()
    .map((f) => ({ name: f.slice(0, -4), xml: join(EDITS_DIR, f), edits: join(EDITS_DIR, f.slice(0, -4) + ".edits.json") }));
  const json = jsonEditInputs()
    .filter((f) => existsSync(join(JSONEDIT_DIR, f.slice(0, -5) + ".edits.json")))
    .map((f) => ({ name: "json/" + f.slice(0, -5), xml: join(JSONEDIT_DIR, f), edits: join(JSONEDIT_DIR, f.slice(0, -5) + ".edits.json") }));
  return [...xml, ...json];
}

export function expectedEditsOutput(c: { xml: string; edits: string }): string {
  const text = readFileSync(c.xml, "utf8");
  const model = new XmlModel(parseByName(c.xml, text));
  const input = JSON.parse(readFileSync(c.edits, "utf8")) as { cases: { target: EditTarget; value: string }[] };
  const cases = input.cases.map(({ target, value }) => {
    const r = computeValueEdit(text, model, target, value);
    if ("error" in r) return { target, value, expected: { error: true } };
    // Round trip: apply, re-parse, and read the value back.
    const after = new XmlModel(parseByName(c.xml, applyEdit(text, r.edit)));
    const el = elementAtPath(after.doc, target.path);
    const readBack = !el ? null : target.kind === "attr" ? (el.attrs.find((a) => a.name === target.name)?.value ?? null) : elementText(el);
    return { target, value, expected: { edit: r.edit, readBack, wellFormed: after.errors.length === 0 } };
  });
  return stableJson({ cases });
}

/** Structural-edit cases: fixtures/structure/<name>.xml and fixtures/jsonedit/<name>.json, with <name>.ops.json (inputs + expected). */
export function listStructureCases(): { name: string; xml: string; ops: string }[] {
  const xml = readdirSync(STRUCTURE_DIR)
    .filter((f) => f.endsWith(".xml"))
    .sort()
    .map((f) => ({ name: f.slice(0, -4), xml: join(STRUCTURE_DIR, f), ops: join(STRUCTURE_DIR, f.slice(0, -4) + ".ops.json") }));
  const json = jsonEditInputs()
    .filter((f) => existsSync(join(JSONEDIT_DIR, f.slice(0, -5) + ".ops.json")))
    .map((f) => ({ name: "json/" + f.slice(0, -5), xml: join(JSONEDIT_DIR, f), ops: join(JSONEDIT_DIR, f.slice(0, -5) + ".ops.json") }));
  return [...xml, ...json];
}

export function expectedStructureOutput(c: { xml: string; ops: string }): string {
  const text = readFileSync(c.xml, "utf8");
  const model = new XmlModel(parseByName(c.xml, text));
  const input = JSON.parse(readFileSync(c.ops, "utf8")) as { cases: { op: StructureOp }[] };
  const cases = input.cases.map(({ op }) => {
    const r = computeStructureEdit(text, model, op);
    if ("error" in r) return { op, expected: { error: true } };
    // The whole document after the edit, for review, and a well-formedness self-check.
    const result = applyEdits(text, r.edits);
    return { op, expected: { edits: r.edits, result, wellFormed: parseByName(c.xml, result).errors.length === 0 } };
  });
  return stableJson({ cases });
}

/** Change cases: fixtures/changes/<name>.base.{xml,json} (the baseline) and <name>.{xml,json} -> <name>.changes.json. */
export function listChangeCases(): { name: string; base: string; cur: string; expected: string }[] {
  return readdirSync(CHANGES_DIR)
    .filter((f) => /\.base\.(xml|json)$/.test(f))
    .sort()
    .map((f) => {
      const name = f.replace(/\.base\.(xml|json)$/, "");
      const ext = f.slice(f.lastIndexOf("."));
      return { name, base: join(CHANGES_DIR, f), cur: join(CHANGES_DIR, name + ext), expected: join(CHANGES_DIR, name + ".changes.json") };
    });
}

/** The changes from the baseline to the current document, with elements by path ("/"-joined indexes). */
export function expectedChangesOutput(c: { base: string; cur: string }): string {
  const base = parseByName(c.base, readFileSync(c.base, "utf8"));
  const cur = parseByName(c.cur, readFileSync(c.cur, "utf8"));
  if (base.errors.length || cur.errors.length) throw new Error(`${c.cur}: change fixtures must be well-formed`);
  const at = (id: number) => pathKey(pathOfElement(cur.elements[id]));
  const d = diffDocuments(base, cur);
  return stableJson({
    added: d.added.map(at),
    modified: d.modified.map(({ id, ...rest }) => ({ path: at(id), ...rest })),
    removed: d.removed.map(({ parent, ...rest }) => ({ parent: parent < 0 ? null : at(parent), ...rest })),
  });
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
  /** The input document (XML in fixtures/cases, JSON in fixtures/jsondoc). */
  xmlPath: string;
  malformed: boolean;
  format: "xml" | "json";
}

export const JSONDOC_DIR = resolve(CASES_DIR, "../jsondoc");

/** Document cases: fixtures/cases/<name>.xml and fixtures/jsondoc/<name>.json (outputs next to them). */
export function listCases(): FixtureCase[] {
  const xml = readdirSync(CASES_DIR)
    .filter((f) => f.endsWith(".xml"))
    .sort()
    .map((f): FixtureCase => {
      const name = f.slice(0, -4);
      return { name, xmlPath: join(CASES_DIR, f), malformed: name.startsWith("malformed-"), format: "xml" };
    });
  const json = readdirSync(JSONDOC_DIR)
    .filter((f) => f.endsWith(".json") && !/\.(tree|grids|flat|errors|search)\.json$/.test(f))
    .sort()
    .map((f): FixtureCase => {
      const name = f.slice(0, -5);
      return { name, xmlPath: join(JSONDOC_DIR, f), malformed: name.startsWith("malformed-"), format: "json" };
    });
  return [...xml, ...json];
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
  const model = new XmlModel(c.format === "json" ? parseJsonDocument(text) : parseXml(text));
  const out = new Map<string, string>();
  const base = join(c.format === "json" ? JSONDOC_DIR : CASES_DIR, c.name);
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
