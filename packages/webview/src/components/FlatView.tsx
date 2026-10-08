import type { FlatValues, Matcher, TreeSkeleton } from "@xmlgridview/core";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { Highlight } from "./Highlight";
import { isInspectable } from "../inspect";
import { useViewport } from "./useViewport";

export const FLAT_ROW_HEIGHT = 22;

/** A Flat row: an element (attr = -1) or the attr-th attribute of an element. */
export interface FlatPos {
  id: number;
  attr: number;
}

/** Column of the Flat sheet: 0 = Name, 1 = Value. */
export type FlatCol = 0 | 1;

/** A cell of the Flat sheet. */
export interface FlatCell extends FlatPos {
  col: FlatCol;
}

/** A rectangular cell selection, like a spreadsheet range. */
export interface FlatSelection {
  anchor: FlatCell;
  focus: FlatCell;
}

export interface FlatRows {
  ids: Int32Array;
  attrs: Int32Array;
}

/** Visible Flat rows: each element, then its attributes, then its children; collapsed elements hide both. */
export function flatRows(sk: TreeSkeleton, collapsed: Set<number>, filter: Set<number> | null): FlatRows {
  let total = 0;
  for (let i = 0; i < sk.count; i++) total += 1 + sk.attrCount[i];
  const ids = new Int32Array(total);
  const attrs = new Int32Array(total);
  let n = 0;
  const stack: number[] = [];
  const pushSiblings = (first: number) => {
    const sibs: number[] = [];
    for (let c = first; c >= 0; c = sk.nextSibling[c]) if (!filter || filter.has(c)) sibs.push(c);
    for (let i = sibs.length - 1; i >= 0; i--) stack.push(sibs[i]);
  };
  pushSiblings(sk.firstRoot);
  while (stack.length) {
    const id = stack.pop()!;
    ids[n] = id;
    attrs[n++] = -1;
    if (collapsed.has(id)) continue;
    for (let a = 0; a < sk.attrCount[id]; a++) {
      ids[n] = id;
      attrs[n++] = a;
    }
    if (sk.firstChild[id] >= 0) pushSiblings(sk.firstChild[id]);
  }
  return { ids: ids.subarray(0, n), attrs: attrs.subarray(0, n) };
}

export function rowIndexOf(rows: FlatRows, pos: FlatPos): number {
  for (let i = 0; i < rows.ids.length; i++) if (rows.ids[i] === pos.id && rows.attrs[i] === pos.attr) return i;
  return -1;
}

function oneLine(s: string): string {
  return s.replace(/\s+/g, " ");
}

export interface FlatViewProps {
  skeleton: TreeSkeleton;
  rows: FlatRows;
  /** Values for visible rows; missing entries are requested through `onNeedValues`. */
  values: Map<number, FlatValues>;
  sel: FlatSelection | null;
  hits: Set<number> | null;
  matcher: Matcher | null;
  highlightNames: boolean;
  highlightValues: boolean;
  isCollapsed(id: number): boolean;
  onNeedValues(ids: number[]): void;
  onToggle(id: number, expand?: boolean): void;
  onSelect(sel: FlatSelection): void;
  onActivate(pos: FlatPos): void;
  onCopy(sel: FlatSelection): void;
  /** Opens the value inspector for a row (Shift+Enter or the inline button). */
  onInspect(pos: FlatPos): void;
}

export function FlatView(p: FlatViewProps) {
  const { skeleton: sk, rows } = p;
  const [ref, vp] = useViewport<HTMLDivElement>();
  const [nameWidth, setNameWidth] = useState(0);
  const dragging = useRef(false);

  const focusIndex = useMemo(() => (p.sel ? rowIndexOf(rows, p.sel.focus) : -1), [rows, p.sel?.focus.id, p.sel?.focus.attr]);
  const anchorIndex = useMemo(() => (p.sel ? rowIndexOf(rows, p.sel.anchor) : -1), [rows, p.sel?.anchor.id, p.sel?.anchor.attr]);
  const r0 = Math.min(focusIndex, anchorIndex < 0 ? focusIndex : anchorIndex);
  const r1 = Math.max(focusIndex, anchorIndex);

  // Default name column width: deepest indentation plus the longest tag name (sampled).
  const autoName = useMemo(() => {
    let maxDepth = 0;
    let maxLen = 4;
    const n = Math.min(sk.count, 50_000);
    for (let i = 0; i < n; i++) {
      if (sk.depth[i] > maxDepth) maxDepth = sk.depth[i];
      const len = sk.names[sk.nameIdx[i]].length;
      if (len > maxLen) maxLen = len;
    }
    return Math.min(520, Math.max(160, (Math.min(maxDepth, 12) + 1) * 14 + 28 + Math.min(maxLen + 1, 40) * 7.5));
  }, [sk]);
  const nameW = nameWidth || autoName;

  const first = Math.max(0, Math.floor(vp.top / FLAT_ROW_HEIGHT) - 10);
  const last = Math.min(rows.ids.length, Math.ceil((vp.top + vp.height) / FLAT_ROW_HEIGHT) + 10);
  const page = Math.max(1, Math.floor(vp.height / FLAT_ROW_HEIGHT) - 2);

  // Ask for values of visible elements that are not loaded yet.
  useEffect(() => {
    const need: number[] = [];
    const seen = new Set<number>();
    for (let i = first; i < last; i++) {
      const id = rows.ids[i];
      if (!seen.has(id) && !p.values.has(id)) {
        seen.add(id);
        need.push(id);
      }
    }
    if (need.length) p.onNeedValues(need);
  }, [first, last, rows, p.values]);

  // Keep the focused row visible (the sticky header covers one row).
  useEffect(() => {
    const el = ref.current;
    if (!el || focusIndex < 0) return;
    const header = (el.querySelector(".flat-header") as HTMLElement | null)?.offsetHeight ?? FLAT_ROW_HEIGHT;
    const top = focusIndex * FLAT_ROW_HEIGHT;
    const viewH = el.clientHeight - header;
    if (top < el.scrollTop) el.scrollTop = top;
    else if (top + FLAT_ROW_HEIGHT > el.scrollTop + viewH) el.scrollTop = top + FLAT_ROW_HEIGHT - viewH;
  }, [focusIndex, ref]);

  useEffect(() => {
    const up = () => (dragging.current = false);
    window.addEventListener("mouseup", up);
    return () => window.removeEventListener("mouseup", up);
  }, []);

  const posAt = (i: number): FlatPos => ({ id: rows.ids[i], attr: rows.attrs[i] });
  const cellAt = (i: number, col: FlatCol): FlatCell => ({ id: rows.ids[i], attr: rows.attrs[i], col });
  const curCol: FlatCol = p.sel?.focus.col ?? 0;
  const c0 = p.sel ? Math.min(p.sel.anchor.col, p.sel.focus.col) : 0;
  const c1 = p.sel ? Math.max(p.sel.anchor.col, p.sel.focus.col) : 1;

  const select = (i: number, extend: boolean, col: FlatCol = curCol) => {
    if (!rows.ids.length) return;
    const t = Math.max(0, Math.min(rows.ids.length - 1, i));
    const focus = cellAt(t, col);
    p.onSelect({ anchor: extend && p.sel ? p.sel.anchor : focus, focus });
  };
  const selectRows = (from: number, to: number) => {
    if (!rows.ids.length) return;
    p.onSelect({ anchor: cellAt(from, 0), focus: cellAt(to, 1) });
  };

  const collapsible = (id: number) => sk.firstChild[id] >= 0 || sk.attrCount[id] > 0;

  const onKeyDown = (e: KeyboardEvent) => {
    const mod = e.ctrlKey || e.metaKey;
    const i = focusIndex;
    const pos = i >= 0 ? posAt(i) : null;
    switch (e.key) {
      case "ArrowDown":
        select(mod ? rows.ids.length - 1 : i + 1, e.shiftKey);
        break;
      case "ArrowUp":
        select(mod ? 0 : i < 0 ? 0 : i - 1, e.shiftKey);
        break;
      case "Home":
        select(0, e.shiftKey);
        break;
      case "End":
        select(rows.ids.length - 1, e.shiftKey);
        break;
      case "PageDown":
        select(i + page, e.shiftKey);
        break;
      case "PageUp":
        select(i - page, e.shiftKey);
        break;
      case "ArrowRight":
        if (!pos) break;
        if (e.shiftKey) select(i, true, 1);
        // In the Name column, Right expands a collapsed row first (like a tree), then moves to Value.
        else if (curCol === 0 && pos.attr < 0 && collapsible(pos.id) && p.isCollapsed(pos.id)) p.onToggle(pos.id, true);
        else select(i, false, 1);
        break;
      case "ArrowLeft":
        if (!pos) break;
        if (e.shiftKey) select(i, true, 0);
        else if (curCol === 1) select(i, false, 0);
        else if (pos.attr < 0 && collapsible(pos.id) && !p.isCollapsed(pos.id)) p.onToggle(pos.id, false);
        else {
          const owner = pos.attr >= 0 ? pos.id : sk.parent[pos.id];
          if (owner >= 0) p.onSelect({ anchor: { id: owner, attr: -1, col: 0 }, focus: { id: owner, attr: -1, col: 0 } });
        }
        break;
      case "Enter":
      case "F4":
        if (pos && e.shiftKey && e.key === "Enter") p.onInspect(pos);
        else if (pos) p.onActivate(pos);
        break;
      case "a":
      case "A":
        if (!mod) return;
        selectRows(0, rows.ids.length - 1);
        break;
      case "c":
      case "C":
        if (!mod || !p.sel) return;
        p.onCopy(p.sel);
        break;
      default:
        return;
    }
    e.preventDefault();
    e.stopPropagation();
  };

  const startResize = (e: MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startW = nameW;
    const move = (ev: MouseEvent) => setNameWidth(Math.max(80, startW + ev.clientX - startX));
    const up = () => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
    };
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
  };

  const digits = String(rows.ids.length).length;
  const rowNumWidth = `calc(${Math.max(2, digits)}ch + 16px)`;

  const items = [];
  for (let i = first; i < last; i++) {
    const id = rows.ids[i];
    const attr = rows.attrs[i];
    const isAttr = attr >= 0;
    const v = p.values.get(id);
    const depth = sk.depth[id] + (isAttr ? 1 : 0);
    const name = isAttr ? "@" + (v?.attrs[attr]?.name ?? "…") : sk.names[sk.nameIdx[id]];
    const raw = isAttr ? v?.attrs[attr]?.value : v?.text;
    const canToggle = !isAttr && collapsible(id);
    const open = canToggle && !p.isCollapsed(id);
    const inRows = i >= r0 && i <= r1 && r0 >= 0;
    const nameSel = inRows && c0 === 0;
    const valueSel = inRows && c1 === 1;
    const isFocus = i === focusIndex;
    const cls = "flat-row" + (isAttr ? " attr" : "") + (inRows ? " row-sel" : "") + (!isAttr && p.hits?.has(id) ? " hit" : "");
    const cellDown = (col: FlatCol) => (e: MouseEvent) => {
      if (e.button !== 0 || (e.target as HTMLElement).classList.contains("twisty")) return;
      ref.current?.focus({ preventScroll: true });
      dragging.current = true;
      select(i, e.shiftKey, col);
      e.preventDefault();
    };
    const cellEnter = (col: FlatCol) => () => {
      if (dragging.current && p.sel) p.onSelect({ anchor: p.sel.anchor, focus: cellAt(i, col) });
    };
    items.push(
      <div
        key={`${id}:${attr}`}
        class={cls}
        role="row"
        aria-selected={inRows}
        style={{ top: i * FLAT_ROW_HEIGHT }}
        onDblClick={() => p.onActivate(posAt(i))}
      >
        <div
          class="rownum"
          style={{ width: rowNumWidth }}
          onMouseDown={(e) => {
            if (e.button !== 0) return;
            ref.current?.focus({ preventScroll: true });
            const from = e.shiftKey && anchorIndex >= 0 ? anchorIndex : i;
            selectRows(from, i);
            e.preventDefault();
          }}
        >
          {i + 1}
        </div>
        <div
          class={"flat-name" + (nameSel ? " sel" : "") + (isFocus && curCol === 0 ? " focus" : "")}
          role="gridcell"
          aria-selected={nameSel}
          style={{ width: nameW, paddingLeft: `calc(${depth} * var(--xgv-tree-indent) + 4px)` }}
          onMouseDown={cellDown(0)}
          onMouseEnter={cellEnter(0)}
        >
          <span
            class={"twisty" + (canToggle ? (open ? " open" : " closed") : "")}
            onMouseDown={(e) => {
              e.preventDefault();
              e.stopPropagation();
              if (canToggle) p.onToggle(id, !open);
            }}
          />
          <span class={isAttr ? "attr-name" : "tag"}>
            <Highlight text={name} matcher={p.highlightNames ? p.matcher : null} />
          </span>
        </div>
        <div
          class={"flat-value" + (valueSel ? " sel" : "") + (isFocus && curCol === 1 ? " focus" : "")}
          role="gridcell"
          aria-selected={valueSel}
          title={raw && raw.length > 60 ? raw.slice(0, 2000) : undefined}
          onMouseDown={cellDown(1)}
          onMouseEnter={cellEnter(1)}
        >
          {raw === undefined ? <span class="muted">…</span> : <Highlight text={oneLine(raw.length > 1000 ? raw.slice(0, 1000) : raw)} matcher={p.highlightValues ? p.matcher : null} />}
          {isInspectable(raw) && (
            <button
              class="inspect-btn"
              title="Open value (Shift+Enter)"
              aria-label="Open value"
              onMouseDown={(e) => e.stopPropagation()}
              onClick={(e) => {
                e.stopPropagation();
                p.onInspect(posAt(i));
              }}
            >
              ⤢
            </button>
          )}
        </div>
      </div>,
    );
  }

  return (
    <div class="flat" ref={ref} tabIndex={0} role="treegrid" aria-label="Flat view" aria-rowcount={rows.ids.length} onKeyDown={onKeyDown}>
      <div class="flat-header" role="row">
        <div class="rownum corner" style={{ width: rowNumWidth }}>
          #
        </div>
        <div class="flat-name hcell-flat" style={{ width: nameW }} role="columnheader">
          Name
          <span class="resize" onMouseDown={startResize} onDblClick={() => setNameWidth(0)} />
        </div>
        <div class="flat-value hcell-flat" role="columnheader">
          Value
        </div>
      </div>
      <div class="flat-body" style={{ height: rows.ids.length * FLAT_ROW_HEIGHT }}>
        {items}
      </div>
    </div>
  );
}
