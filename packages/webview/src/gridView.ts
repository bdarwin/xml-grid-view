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
  return cellText(t.columns[col], t.cells[row * ncols + col]);
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
export function autoFitWidth(t: GridTable, view: Int32Array, col: number, font: string, headerFont: string): number {
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
