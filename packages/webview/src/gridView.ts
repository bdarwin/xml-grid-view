import { cellText, type GridTable } from "@xmlgridview/core";

export interface SortState {
  key: string;
  dir: 1 | -1;
}

export interface ColumnFilter {
  /** Case-insensitive "contains" filter; empty = off. */
  text: string;
  /**
   * Values unchecked in the checklist; null = none. A deny-list, so values that
   * appear after an edit (or beyond the checklist cap) stay visible.
   */
  excluded: string[] | null;
}

export type Filters = Record<string, ColumnFilter>;

export const DISTINCT_CAP = 1000;

export function isFilterActive(f: ColumnFilter | undefined): boolean {
  return !!f && (f.text !== "" || (f.excluded !== null && f.excluded.length > 0));
}

/** Display string of a cell. */
export function cellString(t: GridTable, row: number, col: number): string {
  const ncols = t.columns.length;
  // JSON tables carry their own labels for drill cells ("{ 3 keys }").
  const label = (t as GridTable & { labels?: (string | null)[] }).labels?.[row * ncols + col];
  return label ?? cellText(t.columns[col], t.cells[row * ncols + col]);
}

/**
 * Computes the visible row order (indices into the table) after the quick
 * filter, the per-column filters (ANDed) and the sort.
 */
export function computeView(t: GridTable, quick: string, filters: Filters, sort: SortState | null): Int32Array {
  const nrows = t.rowIds.length;
  const ncols = t.columns.length;
  const q = quick.trim().toLowerCase();
  const active = t.columns
    .map((c, i) => ({ i, f: filters[c.key] }))
    .filter((x) => isFilterActive(x.f))
    .map((x) => ({ i: x.i, text: x.f!.text.toLowerCase(), excluded: x.f!.excluded ? new Set(x.f!.excluded) : null }));

  let rows: number[];
  if (!q && !active.length) {
    rows = Array.from({ length: nrows }, (_, i) => i);
  } else {
    rows = [];
    for (let r = 0; r < nrows; r++) {
      let ok = true;
      for (const f of active) {
        const s = cellString(t, r, f.i);
        if ((f.text && !s.toLowerCase().includes(f.text)) || (f.excluded && f.excluded.has(s))) {
          ok = false;
          break;
        }
      }
      if (ok && q) {
        ok = false;
        for (let c = 0; c < ncols; c++) {
          if (cellString(t, r, c).toLowerCase().includes(q)) {
            ok = true;
            break;
          }
        }
      }
      if (ok) rows.push(r);
    }
  }

  if (sort) {
    const col = t.columns.findIndex((c) => c.key === sort.key);
    if (col >= 0) {
      const keys = new Array<string>(nrows);
      const nums = new Float64Array(nrows);
      let numeric = true;
      for (const r of rows) {
        const v = t.cells[r * ncols + col];
        if (typeof v === "number") {
          nums[r] = v;
          keys[r] = String(v);
        } else {
          const s = v ?? "";
          keys[r] = s.toLowerCase();
          if (s.trim() === "") nums[r] = NaN;
          else {
            const n = Number(s);
            if (Number.isNaN(n)) numeric = false;
            nums[r] = n;
          }
        }
      }
      const d = sort.dir;
      // Empty cells always sort last; ties keep document order.
      rows.sort((a, b) => {
        const ea = keys[a] === "";
        const eb = keys[b] === "";
        if (ea !== eb) return ea ? 1 : -1;
        if (ea) return a - b;
        let c: number;
        if (numeric) c = nums[a] - nums[b];
        else c = keys[a] < keys[b] ? -1 : keys[a] > keys[b] ? 1 : 0;
        return c !== 0 ? c * d : a - b;
      });
    }
  }
  return Int32Array.from(rows);
}

/** Distinct values of a column over all rows, sorted, capped. */
export function distinctValues(t: GridTable, col: number): { values: string[]; capped: boolean } {
  const set = new Set<string>();
  const nrows = t.rowIds.length;
  let capped = false;
  for (let r = 0; r < nrows; r++) {
    set.add(cellString(t, r, col));
    if (set.size >= DISTINCT_CAP) {
      capped = r < nrows - 1;
      break;
    }
  }
  const values = [...set].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
  return { values, capped };
}

function tsvField(s: string): string {
  return /[\t\n\r"]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export interface CellRange {
  r0: number;
  r1: number;
  c0: number;
  c1: number;
}

/** Formats a rectangular selection (view coordinates) as TSV. */
export function toTsv(t: GridTable, view: Int32Array, range: CellRange, withHeader: boolean): string {
  const lines: string[] = [];
  if (withHeader) {
    const h: string[] = [];
    for (let c = range.c0; c <= range.c1; c++) h.push(tsvField(t.columns[c].label));
    lines.push(h.join("\t"));
  }
  for (let v = range.r0; v <= range.r1; v++) {
    const r = view[v];
    const f: string[] = [];
    for (let c = range.c0; c <= range.c1; c++) f.push(tsvField(cellString(t, r, c)));
    lines.push(f.join("\t"));
  }
  return lines.join("\n");
}

let measureCtx: CanvasRenderingContext2D | null = null;

function textWidth(s: string, font: string): number {
  measureCtx ??= document.createElement("canvas").getContext("2d");
  if (!measureCtx) return s.length * 7;
  measureCtx.font = font;
  return measureCtx.measureText(s).width;
}

export const MIN_COL = 48;
export const MAX_COL = 480;

/** Width that fits the header and a sample of cells (first rows of the view). */
export function autoFitWidth(t: GridTable, view: Int32Array, col: number, font: string, headerFont: string, monoFont = font): number {
  // Complex `{tag ×n}` cells render in the monospace font.
  if (t.columns[col].kind === "complex") font = monoFont;
  // Header carries the sort indicator and filter button.
  let w = textWidth(t.columns[col].label, headerFont) + 44;
  const n = Math.min(view.length, 1000);
  for (let i = 0; i < n; i++) {
    const s = cellString(t, view[i], col);
    if (s.length * 12 < w) continue; // cannot be wider than the current max
    w = Math.max(w, textWidth(s.length > 200 ? s.slice(0, 200) : s, font) + 16);
    if (w >= MAX_COL) break;
  }
  return Math.round(Math.min(MAX_COL, Math.max(MIN_COL, w)));
}

/**
 * Parses tab-separated text as copied from a spreadsheet (or this grid): fields in double quotes
 * may hold tabs, line breaks and doubled quotes. A trailing line break doesn't add a row.
 */
export function parseTsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let i = 0;
  let quoted = false;
  let atFieldStart = true;
  while (i < text.length) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"' && text[i + 1] === '"') {
        field += '"';
        i += 2;
        continue;
      }
      if (ch === '"') quoted = false;
      else field += ch;
      i++;
      continue;
    }
    if (ch === '"' && atFieldStart) {
      quoted = true;
      atFieldStart = false;
      i++;
      continue;
    }
    if (ch === "\t") {
      row.push(field);
      field = "";
      atFieldStart = true;
      i++;
      continue;
    }
    if (ch === "\r" || ch === "\n") {
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
      atFieldStart = true;
      i += ch === "\r" && text[i + 1] === "\n" ? 2 : 1;
      continue;
    }
    field += ch;
    atFieldStart = false;
    i++;
  }
  if (field !== "" || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

/** CSV (RFC 4180) of the visible rows and every column, with a header row. */
export function toCsv(t: GridTable, view: Int32Array): string {
  const field = (s: string) => (/[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);
  const lines = [t.columns.map((c) => field(c.label)).join(",")];
  for (const r of view) lines.push(t.columns.map((_, c) => field(cellString(t, r, c))).join(","));
  return lines.join("\r\n") + "\r\n";
}

/** The visible rows as JSON objects keyed by column label; complex cells are left out, numbers stay strings. */
export function toJsonRows(t: GridTable, view: Int32Array): string {
  const out = [];
  for (const r of view) {
    const o: Record<string, string> = {};
    t.columns.forEach((col, c) => {
      const v = t.cells[r * t.columns.length + c];
      if (typeof v === "string") o[col.label] = v;
    });
    out.push(o);
  }
  return JSON.stringify(out, null, 2) + "\n";
}

/** Count, sum, average, min and max of the selected cells, like a spreadsheet's status bar. */
export interface SelectionStats {
  /** Cells in the selection. */
  cells: number;
  /** Non-empty cells. */
  count: number;
  /** Cells holding a plain number (e.g. `12`, `-3.5`, `1e3`; no thousands separators). */
  numbers: number;
  sum: number;
  min: number;
  max: number;
}

const NUMBER = /^\s*[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?\s*$/;

/** Stats of the cells in `range` (view rows and columns); drill-down cells count but are never numbers. */
export function selectionStats(t: GridTable, view: Int32Array, range: CellRange): SelectionStats {
  const ncols = t.columns.length;
  const s: SelectionStats = { cells: 0, count: 0, numbers: 0, sum: 0, min: Infinity, max: -Infinity };
  for (let v = range.r0; v <= range.r1 && v < view.length; v++) {
    const r = view[v];
    for (let c = range.c0; c <= range.c1 && c < ncols; c++) {
      s.cells++;
      const raw = t.cells[r * ncols + c];
      if (raw === null || raw === "") continue;
      s.count++;
      if (typeof raw !== "string" || !NUMBER.test(raw)) continue;
      const x = Number(raw);
      if (!Number.isFinite(x)) continue;
      s.numbers++;
      s.sum += x;
      if (x < s.min) s.min = x;
      if (x > s.max) s.max = x;
    }
  }
  return s;
}

/** A stat for display: grouped digits, at most 6 decimals (floating-point noise like 0.30000000000000004 rounded away). */
export function formatStat(x: number): string {
  return x.toLocaleString(undefined, { maximumFractionDigits: 6 });
}

/** The status bar text for a selection of more than one cell, e.g. "3 cells · Count 3 · Sum 6 · Avg 2 · Min 1 · Max 3". */
export function statsText(s: SelectionStats): string {
  const parts = [`${s.cells.toLocaleString()} cells`, `Count ${s.count.toLocaleString()}`];
  if (s.numbers > 0) {
    parts.push(`Sum ${formatStat(s.sum)}`, `Avg ${formatStat(s.sum / s.numbers)}`, `Min ${formatStat(s.min)}`, `Max ${formatStat(s.max)}`);
  }
  return parts.join(" · ");
}
