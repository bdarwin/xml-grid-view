import type { XDocument, XElement } from "./types.js";

/** Pseudo column key/label for an element's own direct text. */
export const TEXT_COLUMN = "#text";

/**
 * Flat, transferable view of the element tree for the UI thread.
 * Arrays are indexed by element id (document order). -1 means "none".
 */
export interface TreeSkeleton {
  count: number;
  /** First top-level element (normally the root), -1 for an empty document. */
  firstRoot: number;
  parent: Int32Array;
  firstChild: Int32Array;
  nextSibling: Int32Array;
  childCount: Int32Array;
  /** Index among element siblings. */
  index: Int32Array;
  depth: Int32Array;
  nameIdx: Int32Array;
  names: string[];
  start: Int32Array;
  openEnd: Int32Array;
  end: Int32Array;
  /** Short preview: a summary of the first attributes followed by the element text. */
  preview: string[];
}

export type ColumnKind = "attr" | "leaf" | "complex" | "text";

export interface GridColumn {
  /** Stable key: `@name`, `name` for child elements, or `#text`. */
  key: string;
  label: string;
  kind: ColumnKind;
}

export interface GroupInfo {
  name: string;
  count: number;
}

/** A grid of the child elements of `elementId` that share the tag `group`. */
export interface GridTable {
  elementId: number;
  /** Tag of the rows, or null for the "self" view of an element without element children. */
  group: string | null;
  groups: GroupInfo[];
  columns: GridColumn[];
  rowIds: Int32Array;
  /** Row-major cells. Strings for values, numbers for complex-cell counts, null for empty. */
  cells: (string | number | null)[];
  /** Row-major [offset, length] source span per cell, -1 when there is none. */
  spans: Int32Array;
}

const PREVIEW_MAX = 80;

export function isLeaf(el: XElement): boolean {
  return el.elements.length === 0 && el.attrs.length === 0;
}

/** Concatenated direct text/CDATA children, untrimmed. */
export function directText(el: XElement): string {
  let s = "";
  for (const c of el.children) if (c.kind !== "element") s += c.value;
  return s;
}

const XML_WS = new Set([0x20, 0x09, 0x0a, 0x0d]);

/** Trims XML whitespace (space, tab, CR, LF) only — not other Unicode spaces. */
export function xmlTrim(s: string): string {
  let a = 0;
  let b = s.length;
  while (a < b && XML_WS.has(s.charCodeAt(a))) a++;
  while (b > a && XML_WS.has(s.charCodeAt(b - 1))) b--;
  return a === 0 && b === s.length ? s : s.slice(a, b);
}

/** An element's text value: its direct text with XML whitespace trimmed. */
export function elementText(el: XElement): string {
  return xmlTrim(directText(el));
}

function collapse(s: string, max = PREVIEW_MAX): string {
  const t = s.replace(/\s+/g, " ").trim();
  return t.length > max ? t.slice(0, max - 1) + "…" : t;
}

/** Lowercased per-element strings used by the search engine. Built once per model. */
export interface SearchIndex {
  names: string[];
  attrNames: string[][];
  attrValues: string[][];
  texts: string[];
  lower: {
    names: string[];
    attrNames: string[][];
    attrValues: string[][];
    texts: string[];
  };
}

export class XmlModel {
  readonly index: SearchIndex;
  private readonly tableCache = new Map<string, GridTable>();

  constructor(readonly doc: XDocument) {
    this.index = buildIndex(doc);
  }

  get elements(): XElement[] {
    return this.doc.elements;
  }

  get errors() {
    return this.doc.errors;
  }

  skeleton(): TreeSkeleton {
    return buildSkeleton(this.doc, this.index.texts);
  }

  /** Child tag groups of an element in first-seen order. */
  groups(el: XElement): GroupInfo[] {
    const map = new Map<string, GroupInfo>();
    for (const c of el.elements) {
      const g = map.get(c.name);
      if (g) g.count++;
      else map.set(c.name, { name: c.name, count: 1 });
    }
    return [...map.values()];
  }

  /**
   * Builds (and caches) the grid for an element. `group` selects the tag group;
   * when omitted or not present the first group is used, and elements without
   * element children produce a one-row "self" table.
   */
  table(elementId: number, group?: string | null): GridTable {
    const el = this.doc.elements[elementId];
    if (!el) throw new Error(`No element ${elementId}`);
    const groups = this.groups(el);
    const g = groups.find((x) => x.name === group)?.name ?? groups[0]?.name ?? null;
    const key = `${elementId}\u0000${g ?? ""}`;
    let t = this.tableCache.get(key);
    if (!t) {
      const rows = g === null ? [el] : el.elements.filter((c) => c.name === g);
      t = buildTable(elementId, g, groups, rows, this.index.texts);
      this.tableCache.set(key, t);
    }
    return t;
  }
}

function buildIndex(doc: XDocument): SearchIndex {
  const n = doc.elements.length;
  const names = new Array<string>(n);
  const attrNames = new Array<string[]>(n);
  const attrValues = new Array<string[]>(n);
  const texts = new Array<string>(n);
  const lower = {
    names: new Array<string>(n),
    attrNames: new Array<string[]>(n),
    attrValues: new Array<string[]>(n),
    texts: new Array<string>(n),
  };
  for (let i = 0; i < n; i++) {
    const el = doc.elements[i];
    names[i] = el.name;
    attrNames[i] = el.attrs.map((a) => a.name);
    attrValues[i] = el.attrs.map((a) => a.value);
    texts[i] = elementText(el);
    lower.names[i] = el.name.toLowerCase();
    lower.attrNames[i] = attrNames[i].map((s) => s.toLowerCase());
    lower.attrValues[i] = attrValues[i].map((s) => s.toLowerCase());
    lower.texts[i] = texts[i].toLowerCase();
  }
  return { names, attrNames, attrValues, texts, lower };
}

function buildSkeleton(doc: XDocument, texts: string[]): TreeSkeleton {
  const n = doc.elements.length;
  const parent = new Int32Array(n).fill(-1);
  const firstChild = new Int32Array(n).fill(-1);
  const nextSibling = new Int32Array(n).fill(-1);
  const childCount = new Int32Array(n);
  const index = new Int32Array(n);
  const depth = new Int32Array(n);
  const nameIdx = new Int32Array(n);
  const start = new Int32Array(n);
  const openEnd = new Int32Array(n);
  const end = new Int32Array(n);
  const preview = new Array<string>(n);
  const names: string[] = [];
  const nameMap = new Map<string, number>();

  for (let i = 0; i < n; i++) {
    const el = doc.elements[i];
    const p = el.parent;
    parent[i] = p ? p.id : -1;
    depth[i] = p ? depth[p.id] + 1 : 0;
    index[i] = el.index;
    childCount[i] = el.elements.length;
    if (el.elements.length) firstChild[i] = el.elements[0].id;
    const sibs = p ? p.elements : doc.roots;
    const next = sibs[el.index + 1];
    if (next) nextSibling[i] = next.id;
    let ni = nameMap.get(el.name);
    if (ni === undefined) {
      ni = names.length;
      names.push(el.name);
      nameMap.set(el.name, ni);
    }
    nameIdx[i] = ni;
    start[i] = el.start;
    openEnd[i] = el.openEnd;
    end[i] = el.end;
    preview[i] = elementPreview(el, texts[i]);
  }
  return {
    count: n,
    firstRoot: doc.roots.length ? doc.roots[0].id : -1,
    parent,
    firstChild,
    nextSibling,
    childCount,
    index,
    depth,
    nameIdx,
    names,
    start,
    openEnd,
    end,
    preview,
  };
}

function elementPreview(el: XElement, text: string): string {
  const parts: string[] = [];
  let len = 0;
  for (const a of el.attrs) {
    if (a.prefix === "xmlns" || a.name === "xmlns") continue;
    const p = `${a.name}="${collapse(a.value, 30)}"`;
    parts.push(p);
    len += p.length;
    if (len > PREVIEW_MAX) break;
  }
  const t = collapse(text);
  if (t) parts.push(t);
  return collapse(parts.join(" "));
}

interface ColumnBuild {
  col: GridColumn;
  /** For child-element columns: whether every occurrence is a leaf appearing at most once per row. */
  simple: boolean;
}

function buildTable(
  elementId: number,
  group: string | null,
  groups: GroupInfo[],
  rows: XElement[],
  texts: string[],
): GridTable {
  const attrCols = new Map<string, ColumnBuild>();
  const childCols = new Map<string, ColumnBuild>();
  let hasText = false;

  // Pass 1: discover columns.
  for (const row of rows) {
    for (const a of row.attrs) {
      if (!attrCols.has(a.name)) {
        attrCols.set(a.name, { col: { key: "@" + a.name, label: "@" + a.name, kind: "attr" }, simple: true });
      }
    }
    let seen: Set<string> | null = null;
    for (const c of row.elements) {
      let cb = childCols.get(c.name);
      if (!cb) {
        cb = { col: { key: c.name, label: c.name, kind: "leaf" }, simple: true };
        childCols.set(c.name, cb);
      }
      if (cb.simple) {
        if (!isLeaf(c)) cb.simple = false;
        else {
          seen ??= new Set();
          if (seen.has(c.name)) cb.simple = false;
          else seen.add(c.name);
        }
      }
    }
    if (!hasText && texts[row.id] !== "") hasText = true;
  }

  const columns: GridColumn[] = [];
  for (const cb of attrCols.values()) columns.push(cb.col);
  for (const cb of childCols.values()) {
    cb.col.kind = cb.simple ? "leaf" : "complex";
    columns.push(cb.col);
  }
  if (hasText) columns.push({ key: TEXT_COLUMN, label: TEXT_COLUMN, kind: "text" });

  const ncols = columns.length;
  const colIndex = new Map<string, number>();
  columns.forEach((c, i) => colIndex.set(c.key, i));

  const nrows = rows.length;
  const cells = new Array<string | number | null>(nrows * ncols).fill(null);
  const spans = new Int32Array(nrows * ncols * 2).fill(-1);
  const rowIds = new Int32Array(nrows);

  // Pass 2: fill cells.
  for (let r = 0; r < nrows; r++) {
    const row = rows[r];
    rowIds[r] = row.id;
    const base = r * ncols;
    for (const a of row.attrs) {
      const ci = colIndex.get("@" + a.name)!;
      cells[base + ci] = a.value;
      spans[(base + ci) * 2] = a.start;
      spans[(base + ci) * 2 + 1] = a.end - a.start;
    }
    for (const c of row.elements) {
      const ci = colIndex.get(c.name)!;
      const i = base + ci;
      if (columns[ci].kind === "leaf") {
        cells[i] = texts[c.id];
      } else {
        cells[i] = ((cells[i] as number | null) ?? 0) + 1;
      }
      if (spans[i * 2] < 0) {
        spans[i * 2] = c.start;
        spans[i * 2 + 1] = c.end - c.start;
      }
    }
    if (hasText) {
      const t = texts[row.id];
      const i = base + ncols - 1;
      if (t) {
        cells[i] = t;
        const first = row.children.find((ch) => ch.kind !== "element" && xmlTrim(ch.value) !== "");
        if (first) {
          spans[i * 2] = first.start;
          spans[i * 2 + 1] = first.end - first.start;
        }
      }
    }
  }

  return { elementId, group, groups, columns, rowIds, cells, spans };
}

/** Formats a complex cell, e.g. `{item ×3}`. */
export function complexLabel(tag: string, count: number): string {
  return `{${tag} ×${count}}`;
}

/** Display text of a cell, as shown in the grid and copied as TSV. */
export function cellText(col: GridColumn, value: string | number | null): string {
  if (value === null) return "";
  if (typeof value === "number") return complexLabel(col.label, value);
  return value;
}
