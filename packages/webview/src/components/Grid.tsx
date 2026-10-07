import type { GridTable, Matcher } from "@xmlgridview/core";
import { useEffect, useRef } from "preact/hooks";
import { cellString, isFilterActive, type CellRange, type Filters, type SortState } from "../gridView";
import { Highlight } from "./Highlight";
import { useViewport } from "./useViewport";

export const ROW_HEIGHT = 22;

export interface CellPos {
  /** Row index in the view (after sort/filter). */
  v: number;
  c: number;
}

export interface GridSelection {
  anchor: CellPos;
  focus: CellPos;
}

export function selectionRange(sel: GridSelection): CellRange {
  return {
    r0: Math.min(sel.anchor.v, sel.focus.v),
    r1: Math.max(sel.anchor.v, sel.focus.v),
    c0: Math.min(sel.anchor.c, sel.focus.c),
    c1: Math.max(sel.anchor.c, sel.focus.c),
  };
}

export interface GridProps {
  table: GridTable;
  view: Int32Array;
  widths: number[];
  sort: SortState | null;
  filters: Filters;
  sel: GridSelection | null;
  matcher: Matcher | null;
  /** Table-coordinate cells (row * ncols + col) that are search hits. */
  hitCells: Set<number> | null;
  /** Current hit, table coordinates, or -1. */
  currentHit: number;
  onSort(key: string): void;
  onOpenFilter(col: number, anchor: DOMRect): void;
  onResize(col: number, width: number): void;
  onAutoFit(col: number): void;
  onSelect(sel: GridSelection): void;
  onActivate(pos: CellPos): void;
  onDrill(pos: CellPos): void;
  onCopy(range: CellRange, invertHeader: boolean): void;
}

export function Grid(p: GridProps) {
  const { table: t, view, widths } = p;
  const ncols = t.columns.length;
  const [ref, vp] = useViewport<HTMLDivElement>();
  const dragging = useRef(false);
  const digits = String(t.rowIds.length).length;
  const rowNumWidth = `calc(${Math.max(2, digits)}ch + 16px)`;

  const range = p.sel ? selectionRange(p.sel) : null;

  // Keep the focused cell visible.
  useEffect(() => {
    const el = ref.current;
    if (!el || !p.sel) return;
    const { v, c } = p.sel.focus;
    const header = (el.querySelector(".grid-header") as HTMLElement | null)?.offsetHeight ?? ROW_HEIGHT;
    const rowNum = (el.querySelector(".grid-header .rownum") as HTMLElement | null)?.offsetWidth ?? 0;
    const top = v * ROW_HEIGHT;
    const viewH = el.clientHeight - header;
    if (top < el.scrollTop) el.scrollTop = top;
    else if (top + ROW_HEIGHT > el.scrollTop + viewH) el.scrollTop = top + ROW_HEIGHT - viewH;
    let left = 0;
    for (let i = 0; i < c; i++) left += widths[i] ?? 100;
    const w = widths[c] ?? 100;
    const viewW = el.clientWidth - rowNum;
    if (left < el.scrollLeft) el.scrollLeft = left;
    else if (left + w > el.scrollLeft + viewW) el.scrollLeft = left + w - viewW;
  }, [p.sel?.focus.v, p.sel?.focus.c, ref]);

  useEffect(() => {
    const up = () => (dragging.current = false);
    window.addEventListener("mouseup", up);
    return () => window.removeEventListener("mouseup", up);
  }, []);

  const page = Math.max(1, Math.floor(vp.height / ROW_HEIGHT) - 2);
  const first = Math.max(0, Math.floor(vp.top / ROW_HEIGHT) - 10);
  const last = Math.min(view.length, Math.ceil((vp.top + vp.height) / ROW_HEIGHT) + 10);

  const select = (focus: CellPos, extend: boolean) => {
    const f = { v: clamp(focus.v, 0, view.length - 1), c: clamp(focus.c, 0, ncols - 1) };
    if (view.length === 0 || ncols === 0) return;
    p.onSelect({ anchor: extend && p.sel ? p.sel.anchor : f, focus: f });
  };

  const onKeyDown = (e: KeyboardEvent) => {
    const mod = e.ctrlKey || e.metaKey;
    const cur = p.sel?.focus ?? { v: 0, c: 0 };
    const ext = e.shiftKey;
    switch (e.key) {
      case "ArrowDown":
        select({ v: mod ? view.length - 1 : cur.v + 1, c: cur.c }, ext);
        break;
      case "ArrowUp":
        select({ v: mod ? 0 : cur.v - 1, c: cur.c }, ext);
        break;
      case "ArrowRight":
        select({ v: cur.v, c: mod ? ncols - 1 : cur.c + 1 }, ext);
        break;
      case "ArrowLeft":
        select({ v: cur.v, c: mod ? 0 : cur.c - 1 }, ext);
        break;
      case "Home":
        select({ v: mod ? 0 : cur.v, c: 0 }, ext);
        break;
      case "End":
        select({ v: mod ? view.length - 1 : cur.v, c: ncols - 1 }, ext);
        break;
      case "PageDown":
        select({ v: cur.v + page, c: cur.c }, ext);
        break;
      case "PageUp":
        select({ v: cur.v - page, c: cur.c }, ext);
        break;
      case "Enter":
      case "F4":
        if (!p.sel) break;
        if (mod && e.key === "Enter") p.onDrill(p.sel.focus);
        else p.onActivate(p.sel.focus);
        break;
      case " ":
        if (p.sel) p.onDrill(p.sel.focus);
        break;
      case "a":
      case "A":
        if (!mod) return;
        if (view.length && ncols) p.onSelect({ anchor: { v: 0, c: 0 }, focus: { v: view.length - 1, c: ncols - 1 } });
        break;
      case "c":
      case "C":
        if (!mod || !range) return;
        p.onCopy(range, e.shiftKey);
        break;
      default:
        return;
    }
    e.preventDefault();
    e.stopPropagation();
  };

  const cellDown = (e: MouseEvent, pos: CellPos) => {
    if (e.button !== 0) return;
    (ref.current as HTMLElement | null)?.focus({ preventScroll: true });
    dragging.current = true;
    select(pos, e.shiftKey);
    e.preventDefault();
  };
  const cellEnter = (pos: CellPos) => {
    if (dragging.current && p.sel) p.onSelect({ anchor: p.sel.anchor, focus: pos });
  };
  const rowHeaderDown = (e: MouseEvent, v: number) => {
    if (e.button !== 0) return;
    ref.current?.focus({ preventScroll: true });
    const anchor = e.shiftKey && p.sel ? { v: p.sel.anchor.v, c: 0 } : { v, c: 0 };
    p.onSelect({ anchor, focus: { v, c: ncols - 1 } });
    e.preventDefault();
  };

  const startResize = (e: MouseEvent, col: number) => {
    e.preventDefault();
    e.stopPropagation();
    const startX = e.clientX;
    const startW = widths[col] ?? 100;
    const move = (ev: MouseEvent) => p.onResize(col, Math.max(32, startW + ev.clientX - startX));
    const up = () => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
    };
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
  };

  const header = (
    <div class="grid-header" role="row">
      <div class="rownum corner" style={{ width: rowNumWidth }} role="columnheader">
        #
      </div>
      {t.columns.map((col, c) => {
        const sorted = p.sort?.key === col.key ? p.sort.dir : 0;
        const filtered = isFilterActive(p.filters[col.key]);
        return (
          <div
            key={col.key}
            class={`hcell kind-${col.kind}`}
            style={{ width: widths[c] }}
            role="columnheader"
            aria-sort={sorted === 1 ? "ascending" : sorted === -1 ? "descending" : "none"}
            title={`${col.label} (${col.kind})`}
            onClick={() => p.onSort(col.key)}
          >
            <span class="hlabel">{col.label}</span>
            {sorted !== 0 && <span class={"sort " + (sorted === 1 ? "asc" : "desc")} />}
            <button
              class={"filter-btn" + (filtered ? " active" : "")}
              title={filtered ? "Filter (active)" : "Filter"}
              aria-label={`Filter ${col.label}`}
              onClick={(e) => {
                e.stopPropagation();
                p.onOpenFilter(c, (e.currentTarget as HTMLElement).getBoundingClientRect());
              }}
            />
            <span class="resize" onMouseDown={(e) => startResize(e, c)} onClick={(e) => e.stopPropagation()} onDblClick={(e) => {
              e.stopPropagation();
              p.onAutoFit(c);
            }} />
          </div>
        );
      })}
    </div>
  );

  const rows = [];
  for (let v = first; v < last; v++) {
    const r = view[v];
    const rowSelected = range && v >= range.r0 && v <= range.r1;
    const cells = [];
    for (let c = 0; c < ncols; c++) {
      const col = t.columns[c];
      const raw = t.cells[r * ncols + c];
      const key = r * ncols + c;
      const inSel = rowSelected && c >= range!.c0 && c <= range!.c1;
      const isFocus = p.sel && p.sel.focus.v === v && p.sel.focus.c === c;
      const hit = p.hitCells?.has(key);
      const cls =
        "cell" +
        (inSel ? " sel" : "") +
        (isFocus ? " focus" : "") +
        (hit ? " hit" : "") +
        (key === p.currentHit ? " current-hit" : "") +
        (raw === null ? " empty" : "");
      const pos = { v, c };
      let content;
      if (typeof raw === "number") {
        content = (
          <span
            class="drill"
            title="Open nested table"
            onMouseDown={(e) => e.stopPropagation()}
            onClick={(e) => {
              e.stopPropagation();
              p.onDrill(pos);
            }}
          >
            <Highlight text={cellString(t, r, c)} matcher={p.matcher} current={key === p.currentHit} />
          </span>
        );
      } else if (raw !== null) {
        content = <Highlight text={raw} matcher={p.matcher} current={key === p.currentHit} />;
      }
      cells.push(
        <div
          key={col.key}
          class={cls}
          style={{ width: widths[c] }}
          role="gridcell"
          aria-selected={!!inSel}
          title={typeof raw === "string" && raw.length > 40 ? raw.slice(0, 1000) : undefined}
          onMouseDown={(e) => cellDown(e, pos)}
          onMouseEnter={() => cellEnter(pos)}
          onDblClick={() => (typeof raw === "number" ? p.onDrill(pos) : p.onActivate(pos))}
        >
          {content}
        </div>,
      );
    }
    rows.push(
      <div key={r} class={"grid-row" + (rowSelected ? " row-sel" : "")} role="row" style={{ top: v * ROW_HEIGHT }}>
        <div class="rownum" style={{ width: rowNumWidth }} onMouseDown={(e) => rowHeaderDown(e, v)} onDblClick={() => p.onActivate({ v, c: -1 })}>
          {r + 1}
        </div>
        {cells}
      </div>,
    );
  }

  return (
    <div class="grid" ref={ref} tabIndex={0} role="grid" aria-rowcount={view.length} aria-colcount={ncols} onKeyDown={onKeyDown}>
      {header}
      <div class="grid-body" style={{ height: view.length * ROW_HEIGHT }}>
        {rows}
      </div>
      {view.length === 0 && <div class="grid-empty">{t.rowIds.length ? "No rows match the filters." : "No rows."}</div>}
    </div>
  );
}

function clamp(n: number, lo: number, hi: number) {
  return Math.max(lo, Math.min(hi, n));
}
