/**
 * Canonical JSON forms of the model, used by the shared golden fixtures in
 * `fixtures/`. See fixtures/SCHEMA.md for the exact format; the Java
 * implementation produces the same JSON.
 */
import { createMatcher, searchDocument, searchGrid, ALL_TARGETS, type SearchOptions, type SearchTargets } from "./search.js";
import { elementText, type XmlModel } from "./model.js";
import { pathKey, pathOfElement } from "./paths.js";
import { evaluateXPath, type XPathBackend } from "./xpath.js";
import type { XElement } from "./types.js";

export interface CanonicalAttr {
  name: string;
  value: string;
  start: number;
  end: number;
}

export interface CanonicalNode {
  tag: string;
  ns: string;
  path: string;
  start: number;
  end: number;
  attrs: CanonicalAttr[];
  text: string;
  children: CanonicalNode[];
}

export type CanonicalCell = string | null | { drill: string; count: number };

export interface CanonicalGrid {
  columns: { key: string; kind: string }[];
  rows: CanonicalCell[][];
}

export interface CanonicalGridSet {
  groups: { tag: string; count: number }[];
  grids: Record<string, CanonicalGrid>;
}

export type SearchTargetName = "all" | "names" | "attrNames" | "attrValues" | "text";

export interface SearchCase {
  query: string;
  options: SearchOptions;
  scope: "document" | "grid";
  target: SearchTargetName;
  /** Required for scope "grid". */
  grid?: { path: string; group: string };
  expected?: unknown;
}

export interface DocHit {
  path: string;
  target: "name" | "attrName" | "attrValue" | "text";
  attr: string | null;
  ranges: [number, number][];
}

export interface GridHit {
  row: number;
  column: string;
  ranges: [number, number][];
}

export interface XPathCase {
  expr: string;
  expected?: XPathExpected;
}

export type XPathExpected =
  | { nodes: { path: string; kind: string; attr: string | null }[] }
  | { scalar: string | number | boolean }
  | { error: true };

export interface SearchFile {
  cases: SearchCase[];
  xpath: XPathCase[];
}

export interface ErrorsFile {
  hasErrors: true;
  firstError: { line: number; column: number };
}

function nodeOf(el: XElement): CanonicalNode {
  return {
    tag: el.name,
    ns: el.uri,
    path: pathKey(pathOfElement(el)),
    start: el.start,
    end: el.end,
    attrs: el.attrs.map((a) => ({ name: a.name, value: a.value, start: a.start, end: a.end })),
    text: elementText(el),
    children: el.elements.map(nodeOf),
  };
}

/** Canonical tree of the (single) root element. */
export function canonicalTree(model: XmlModel): CanonicalNode | null {
  const root = model.doc.roots[0];
  return root ? nodeOf(root) : null;
}

/** Grids for every element that has element children, keyed by path. */
export function canonicalGrids(model: XmlModel): Record<string, CanonicalGridSet> {
  const out: Record<string, CanonicalGridSet> = {};
  for (const el of model.elements) {
    if (!el.elements.length) continue;
    const groups = model.groups(el);
    const grids: Record<string, CanonicalGrid> = {};
    for (const g of groups) {
      const t = model.table(el.id, g.name);
      const ncols = t.columns.length;
      const rows: CanonicalCell[][] = [];
      for (let r = 0; r < t.rowIds.length; r++) {
        const row: CanonicalCell[] = [];
        for (let c = 0; c < ncols; c++) {
          const v = t.cells[r * ncols + c];
          row.push(typeof v === "number" ? { drill: t.columns[c].label, count: v } : v);
        }
        rows.push(row);
      }
      grids[g.name] = { columns: t.columns.map((c) => ({ key: c.key, kind: c.kind })), rows };
    }
    out[pathKey(pathOfElement(el))] = { groups: groups.map((g) => ({ tag: g.name, count: g.count })), grids };
  }
  return out;
}

export function targetsOf(name: SearchTargetName): SearchTargets {
  if (name === "all") return { ...ALL_TARGETS };
  return { names: name === "names", attrNames: name === "attrNames", attrValues: name === "attrValues", text: name === "text" };
}

/** Runs one search case and returns its canonical hits (or `{ error: true }`). */
export function runSearchCase(model: XmlModel, c: SearchCase): DocHit[] | GridHit[] | { error: true } {
  const query = { text: c.query, options: c.options, targets: targetsOf(c.target) };
  let matcher;
  try {
    matcher = createMatcher(c.query, c.options);
  } catch {
    return { error: true };
  }
  if (!matcher) return [];
  if (c.scope === "document") {
    const r = searchDocument(model, query);
    return r.matches.map((m) => {
      const el = model.elements[m.elementId];
      const attr = m.attrIndex >= 0 ? el.attrs[m.attrIndex] : null;
      const field =
        m.target === "name" ? el.name : m.target === "attrName" ? attr!.name : m.target === "attrValue" ? attr!.value : model.index.texts[el.id];
      return { path: pathKey(pathOfElement(el)), target: m.target, attr: attr ? attr.name : null, ranges: matcher.ranges(field) };
    });
  }
  if (!c.grid) throw new Error("grid scope requires `grid`");
  const owner = elementByPath(model, c.grid.path);
  if (!owner) throw new Error(`no element at ${c.grid.path}`);
  const t = model.table(owner.id, c.grid.group);
  const r = searchGrid(t, query);
  const hits: GridHit[] = [];
  const ncols = t.columns.length;
  for (let i = 0; i < r.cells.length; i += 2) {
    const row = r.cells[i];
    const col = r.cells[i + 1];
    const v = t.cells[row * ncols + col];
    const s = typeof v === "number" ? t.columns[col].label : (v ?? "");
    hits.push({ row, column: t.columns[col].key, ranges: matcher.ranges(s) });
  }
  return hits;
}

export function runXPathCase(model: XmlModel, text: string, expr: string, backend?: XPathBackend): XPathExpected {
  const r = evaluateXPath(model, text, expr, backend);
  if (r.error) return { error: true };
  if (r.scalar !== undefined) return { scalar: r.scalar };
  return {
    nodes: r.items.map((i) => ({
      path: pathKey(pathOfElement(model.elements[i.elementId])),
      kind: i.kind,
      attr: i.attrName ?? null,
    })),
  };
}

function elementByPath(model: XmlModel, key: string): XElement | null {
  const parts = key.split("/").map(Number);
  let el: XElement | undefined = model.doc.roots[parts[0]];
  for (let i = 1; el && i < parts.length; i++) el = el.elements[parts[i]];
  return el ?? null;
}

export function canonicalErrors(model: XmlModel): ErrorsFile | null {
  const e = model.errors[0];
  return e ? { hasErrors: true, firstError: { line: e.line, column: e.column } } : null;
}

/**
 * Stable JSON: 2-space indent; arrays holding only primitives, small objects
 * without nesting, or such arrays (rows, ranges) are kept on one line for readable diffs.
 */
export function stableJson(value: unknown): string {
  return write(value, "") + "\n";
}

function isFlat(v: unknown): boolean {
  if (v === null || typeof v !== "object") return true;
  if (Array.isArray(v)) return v.every((x) => x === null || typeof x !== "object");
  return Object.values(v).every((x) => x === null || typeof x !== "object");
}

function write(v: unknown, indent: string): string {
  if (v === null || typeof v !== "object") return JSON.stringify(v);
  const inner = indent + "  ";
  if (Array.isArray(v)) {
    if (v.length === 0) return "[]";
    if (v.every(isFlat)) return "[" + v.map((x) => write(x, inner)).join(", ") + "]";
    return "[\n" + v.map((x) => inner + write(x, inner)).join(",\n") + "\n" + indent + "]";
  }
  const entries = Object.entries(v).filter(([, x]) => x !== undefined);
  if (entries.length === 0) return "{}";
  if (isFlat(v) && entries.length <= 4) return "{ " + entries.map(([k, x]) => `${JSON.stringify(k)}: ${JSON.stringify(x)}`).join(", ") + " }";
  return "{\n" + entries.map(([k, x]) => `${inner}${JSON.stringify(k)}: ${write(x, inner)}`).join(",\n") + "\n" + indent + "}";
}
