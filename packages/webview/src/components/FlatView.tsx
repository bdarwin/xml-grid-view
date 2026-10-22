import { displayName, miscByParent, type Diagnostic, type ElementChange, type FlatValues, type Matcher, type SkeletonMisc, type TreeSkeleton } from "@xmlgridview/core";
import { changeClass, modifiedTitle, removedTitle, type ChangeMark, type ChangeMarks } from "../changes";
import { problemClass, problemTitle } from "../problems";
import { miscIndex, miscRow } from "./Tree";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { Highlight } from "./Highlight";
import { isInspectable } from "../inspect";
import { CellEditor } from "./CellEditor";
import { useViewport } from "./useViewport";

export const FLAT_ROW_HEIGHT = 22;

/** A Flat row: an element (attr = -1), the attr-th attribute of an element, or a comment, PI or DOCTYPE (id = miscRow(k)). */
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
  let total = sk.misc.length;
  for (let i = 0; i < sk.count; i++) total += 1 + sk.attrCount[i];
  const ids = new Int32Array(total);
  const attrs = new Int32Array(total);
  let n = 0;
  const stack: number[] = [];
  // Comments, PIs and DOCTYPE come among the children in document order (not when filtering).
  const misc = filter || !sk.misc.length ? null : miscByParent(sk);
  const pushChildren = (parent: number, first: number) => {
    const sibs: number[] = [];
    const ms = misc?.get(parent) ?? [];
    let j = 0;
    let i = 0;
    for (let c = first; c >= 0; c = sk.nextSibling[c], i++) {
      while (j < ms.length && sk.misc[ms[j]].before <= i) sibs.push(miscRow(ms[j++]));
      if (!filter || filter.has(c)) sibs.push(c);
    }
    while (j < ms.length) sibs.push(miscRow(ms[j++]));
    for (let k = sibs.length - 1; k >= 0; k--) stack.push(sibs[k]);
  };
  pushChildren(-1, sk.firstRoot);
  while (stack.length) {
    const id = stack.pop()!;
    ids[n] = id;
    attrs[n++] = -1;
    if (id < 0 || collapsed.has(id)) continue;
    for (let a = 0; a < sk.attrCount[id]; a++) {
      ids[n] = id;
      attrs[n++] = a;
    }
    pushChildren(id, sk.firstChild[id]);
  }
  return { ids: ids.subarray(0, n), attrs: attrs.subarray(0, n) };
}

/** The comment, PI or DOCTYPE of a Flat row id, or null for elements. */
export function flatMisc(sk: TreeSkeleton, id: number): SkeletonMisc | null {
  return id < 0 ? (sk.misc[miscIndex(id)] ?? null) : null;
}

/** Name-column label of a comment, PI or DOCTYPE row. */
export function miscLabel(m: SkeletonMisc): string {
  return m.kind === "comment" ? "<!-- -->" : m.kind === "pi" ? `<?${m.name}?>` : "<!DOCTYPE>";
}

/** Depth of a Flat row's element (or a misc row's position). */
function rowDepth(sk: TreeSkeleton, id: number): number {
  const m = flatMisc(sk, id);
  return m ? (m.parent < 0 ? 0 : sk.depth[m.parent] + 1) : sk.depth[id];
}

/**
 * Colour band per element for the Flat view: 0 = above the section level (untinted),
 * 1 or 2 = alternating bands. The section level is the first level with more than one
 * element under a chain of single children (e.g. each <book> under <catalog>, or under
 * <catalog><books>); every row inside a section takes its band.
 */
export function flatBands(sk: TreeSkeleton): Uint8Array {
  const band = new Uint8Array(sk.count);
  let level = 1;
  for (let r = sk.firstRoot; r >= 0 && sk.childCount[r] === 1; r = sk.firstChild[r]) level++;
  for (let id = 0; id < sk.count; id++) {
    const d = sk.depth[id];
    if (d === level) band[id] = (sk.index[id] % 2) + 1;
    else if (d > level) band[id] = band[sk.parent[id]];
  }
  return band;
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
  /** Whether a row's value can be edited in place. */
  canEdit?(pos: FlatPos): boolean;
  /** Commits an in-place edit (only called when the value changed). */
  onEdit?(pos: FlatPos, value: string): void;
  /** Right-click on a row. */
  onContextMenu?(pos: FlatPos, x: number, y: number): void;
  /** Problems by element; ones inside an attribute's span mark that attribute's row. */
  problems?: Map<number, Diagnostic[]>;
  /** Changes against the baseline, and the details of changed elements by id. */
  changes?: ChangeMarks | null;
  changeDetails?: Map<number, ElementChange>;
}

let measureCtx: CanvasRenderingContext2D | null = null;

export function FlatView(p: FlatViewProps) {
  const { skeleton: sk, rows } = p;
  const bands = useMemo(() => flatBands(sk), [sk]);
  const [ref, vp] = useViewport<HTMLDivElement>();
  const [nameWidth, setNameWidth] = useState(0);
  const [editing, setEditing] = useState<{ id: number; attr: number; initial: string; selectAll: boolean } | null>(null);
  const currentValue = (pos: FlatPos) => {
    const m = flatMisc(sk, pos.id);
    if (m) return m.value;
    const v = p.values.get(pos.id);
    return (pos.attr >= 0 ? v?.attrs[pos.attr]?.value : v?.text) ?? "";
  };
  const dragging = useRef(false);

  const focusIndex = useMemo(() => (p.sel ? rowIndexOf(rows, p.sel.focus) : -1), [rows, p.sel?.focus.id, p.sel?.focus.attr]);
  const anchorIndex = useMemo(() => (p.sel ? rowIndexOf(rows, p.sel.anchor) : -1), [rows, p.sel?.anchor.id, p.sel?.anchor.attr]);
  const r0 = Math.min(focusIndex, anchorIndex < 0 ? focusIndex : anchorIndex);
  const r1 = Math.max(focusIndex, anchorIndex);

  // Name column width fitted to the visible rows: indentation + twisty + measured name (sampled).
  // Recomputed when rows expand/collapse or attribute names arrive; a manual resize overrides it.
  const autoName = useMemo(() => {
    const el = ref.current;
    const cs = el ? getComputedStyle(el) : null;
    const mono = cs
      ? `${cs.getPropertyValue("--xgv-mono-font-size").trim() || "12px"} ${cs.getPropertyValue("--xgv-mono-font-family").trim() || "monospace"}`
      : "12px monospace";
    const indent = cs ? parseFloat(cs.getPropertyValue("--xgv-tree-indent")) || 14 : 14;
    measureCtx ??= document.createElement("canvas").getContext("2d");
    if (measureCtx) measureCtx.font = mono;
    const width = (s: string) => (measureCtx ? measureCtx.measureText(s).width : s.length * 7.5);
    const cache = new Map<string, number>();
    let w = width("Name") + 16;
    const n = Math.min(rows.ids.length, 20_000);
    for (let i = 0; i < n; i++) {
      const id = rows.ids[i];
      const attr = rows.attrs[i];
      const m = flatMisc(sk, id);
      const name = m ? miscLabel(m) : attr >= 0 ? "@" + (p.values.get(id)?.attrs[attr]?.name ?? "") : displayName(sk, id);
      let tw = cache.get(name);
      if (tw === undefined) cache.set(name, (tw = width(name)));
      // padding-left 4 + twisty 14 + gap 4 + right padding 8 + border/slack 6
      const depth = rowDepth(sk, id) + (attr >= 0 ? 1 : 0);
      w = Math.max(w, depth * indent + 36 + tw);
    }
    return Math.round(Math.min(Math.max(w, 120), Math.max(240, vp.width * 0.6 || 600)));
  }, [rows, p.values, sk, vp.width > 0]);
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
      if (id >= 0 && !seen.has(id) && !p.values.has(id)) {
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

  const collapsible = (id: number) => id >= 0 && (sk.firstChild[id] >= 0 || sk.attrCount[id] > 0);

  const onKeyDown = (e: KeyboardEvent) => {
    const mod = e.ctrlKey || e.metaKey;
    const i = focusIndex;
    const pos = i >= 0 ? posAt(i) : null;
    // Excel-style editing of the Value column: F2 edits, typing replaces.
    if (pos && !editing && p.canEdit?.(pos) && p.onEdit && p.values.has(pos.id) &&
        (e.key === "F2" || (e.key.length === 1 && !mod && !e.altKey && e.key !== " " && curCol === 1))) {
      setEditing({ id: pos.id, attr: pos.attr, initial: e.key === "F2" ? currentValue(pos) : e.key, selectAll: e.key === "F2" });
      if (curCol !== 1) p.onSelect({ anchor: { ...pos, col: 1 }, focus: { ...pos, col: 1 } });
      e.preventDefault();
      e.stopPropagation();
      return;
    }
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
          const owner = pos.attr >= 0 ? pos.id : pos.id < 0 ? (flatMisc(sk, pos.id)?.parent ?? -1) : sk.parent[pos.id];
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
    const misc = flatMisc(sk, id);
    const v = p.values.get(id);
    const depth = rowDepth(sk, id) + (isAttr ? 1 : 0);
    const name = misc ? miscLabel(misc) : isAttr ? "@" + (v?.attrs[attr]?.name ?? "…") : displayName(sk, id);
    const raw = misc ? misc.value : isAttr ? v?.attrs[attr]?.value : v?.text;
    const canToggle = !isAttr && collapsible(id);
    const open = canToggle && !p.isCollapsed(id);
    const inRows = i >= r0 && i <= r1 && r0 >= 0;
    const nameSel = inRows && c0 === 0;
    const valueSel = inRows && c1 === 1;
    const isFocus = i === focusIndex;
    const b = misc ? (misc.parent < 0 ? 0 : bands[misc.parent]) : bands[id];
    // Problems of this row: an attribute row takes those inside its span, the element row the rest.
    const all = misc ? undefined : p.problems?.get(id);
    const attrSpans = all && v ? v.attrs : null;
    const inAttr = (d: Diagnostic, a: { start: number; end: number }) => d.offset >= a.start && d.offset < a.end;
    const rowProblems = !all
      ? undefined
      : isAttr
        ? attrSpans
          ? all.filter((d) => inAttr(d, attrSpans[attr]))
          : undefined
        : all.filter((d) => !attrSpans?.some((a) => inAttr(d, a)));
    // Changes: a new or changed attribute marks its row; an element row shows new elements, changed text
    // and removed attributes; removed children are listed on their parent's row.
    let change: ChangeMark | undefined;
    const el = misc ? undefined : p.changes?.byElement.get(id);
    if (el?.kind === "added") change = el;
    else if (el && p.changes) {
      const d = p.changeDetails?.get(id);
      if (isAttr) {
        const an = v?.attrs[attr]?.name;
        const was = an !== undefined ? d?.attrs?.[an] : undefined;
        if (was !== undefined) change = was === null ? { kind: "added", title: `New since ${p.changes.label}` } : { kind: "modified", title: `Was: ${was}` };
      } else if (d && (d.text !== undefined || d.removedAttrs)) change = { kind: "modified", title: modifiedTitle({ id, text: d.text, type: d.type, removedAttrs: d.removedAttrs }, p.changes.label) };
    }
    // Removed elements: a line above the element that now follows them, or below their parent's row when they were last.
    const goneBefore = !misc && !isAttr ? p.changes?.removedBefore.get(id) : undefined;
    const goneLast = !misc && !isAttr ? p.changes?.removed.get(id)?.filter((r) => r.before >= sk.childCount[id]) : undefined;
    const gone = goneBefore?.length ? goneBefore : goneLast?.length ? goneLast : undefined;
    const cls =
      "flat-row" +
      changeClass(change) +
      (goneBefore?.length ? " removed-above" : goneLast?.length ? " removed-child" : "") +
      (isAttr ? " attr" : "") +
      (misc ? " misc" : "") +
      problemClass(rowProblems) +
      (inRows ? " row-sel" : "") +
      (!isAttr && p.hits?.has(id) ? " hit" : "") +
      (b ? (b === 1 ? " band-a" : " band-b") + (misc || isAttr || sk.childCount[id] === 0 ? " val" : " node") : "");
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
        onContextMenu={(e) => {
          if (!p.onContextMenu) return;
          e.preventDefault();
          p.onContextMenu(posAt(i), e.clientX, e.clientY);
        }}
      >
        <div
          class="rownum"
          style={{ width: rowNumWidth }}
          onDblClick={() => p.onActivate(posAt(i))}
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
          title={[problemTitle(rowProblems), change?.title, gone && removedTitle(gone, p.changes!.label)].filter(Boolean).join("\n\n") || undefined}
          style={{ width: nameW, paddingLeft: `calc(${depth} * var(--xgv-tree-indent) + 4px)` }}
          onMouseDown={cellDown(0)}
          onMouseEnter={cellEnter(0)}
          onDblClick={(e) => !(e.target as HTMLElement).classList.contains("twisty") && p.onActivate(posAt(i))}
        >
          <span
            class={"twisty" + (canToggle ? (open ? " open" : " closed") : "")}
            onMouseDown={(e) => {
              e.preventDefault();
              e.stopPropagation();
              if (canToggle) p.onToggle(id, !open);
            }}
          />
          <span class={misc ? "misc-text" : isAttr ? "attr-name" : "tag"}>
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
          onDblClick={() => p.onInspect(posAt(i))}
        >
          {editing && editing.id === id && editing.attr === attr ? (
            <CellEditor
              initial={editing.initial}
              selectAll={editing.selectAll}
              onCommit={(value) => {
                const pos = { id, attr };
                setEditing(null);
                if (value !== currentValue(pos)) p.onEdit?.(pos, value);
                ref.current?.focus({ preventScroll: true });
              }}
              onCancel={() => {
                setEditing(null);
                ref.current?.focus({ preventScroll: true });
              }}
            />
          ) : raw === undefined ? (
            <span class="muted">…</span>
          ) : (
            <Highlight text={oneLine(raw.length > 1000 ? raw.slice(0, 1000) : raw)} matcher={p.highlightValues ? p.matcher : null} />
          )}
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
