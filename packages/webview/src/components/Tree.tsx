import { miscByParent, miscDisplay, type Diagnostic, type Matcher, type TreeSkeleton } from "@xmlgridview/core";
import { problemClass, problemTitle } from "../problems";
import { useEffect, useMemo } from "preact/hooks";
import { Highlight, PreviewText } from "./Highlight";
import { scrollRowIntoView, useViewport } from "./useViewport";

export const TREE_ROW_HEIGHT = 22;

export interface TreeProps {
  skeleton: TreeSkeleton;
  expanded: Set<number>;
  selected: number;
  /** When set, only these elements are shown (all of them expanded). */
  filter: Set<number> | null;
  /** Elements that are search hits (for row marking). */
  hits: Set<number> | null;
  matcher: Matcher | null;
  highlightNames: boolean;
  highlightPreview: boolean;
  onToggle(id: number, expand?: boolean): void;
  onSelect(id: number): void;
  onActivate(id: number): void;
  /** Problems by element, and the ancestors of elements with problems (marked while collapsed). */
  problems?: Map<number, Diagnostic[]>;
  problemWithin?: Set<number>;
  /** Double-click on a comment, PI or DOCTYPE: go to its source. */
  onActivateOffset?(offset: number, length: number): void;
  onInspect?(id: number): void;
  /** Right-click (or the context-menu key) on a row; the row is selected first. */
  onContextMenu?(id: number, x: number, y: number): void;
}

/** Row id of comment/PI/DOCTYPE `k`: -k-2 (-1 means "no element"). */
export const miscRow = (k: number) => -k - 2;
export const miscIndex = (row: number) => -row - 2;

/**
 * Visible rows in display order: element ids, and comments, PIs and the DOCTYPE (miscRow) among
 * the children of elements that have element children (not when filtering).
 */
function flatten(sk: TreeSkeleton, expanded: Set<number>, filter: Set<number> | null): Int32Array {
  const out: number[] = [];
  const stack: number[] = [];
  const misc = filter || !sk.misc.length ? null : miscByParent(sk);
  const pushSiblings = (parent: number, first: number) => {
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
  pushSiblings(-1, sk.firstRoot);
  while (stack.length) {
    const id = stack.pop()!;
    out.push(id);
    if (id >= 0 && sk.firstChild[id] >= 0 && (filter ? true : expanded.has(id))) pushSiblings(id, sk.firstChild[id]);
  }
  return Int32Array.from(out);
}

export function Tree(p: TreeProps) {
  const { skeleton: sk } = p;
  const rows = useMemo(() => flatten(sk, p.expanded, p.filter), [sk, p.expanded, p.filter]);
  const [ref, vp] = useViewport<HTMLDivElement>();
  const selIndex = useMemo(() => rows.indexOf(p.selected), [rows, p.selected]);

  useEffect(() => {
    if (selIndex >= 0) scrollRowIntoView(ref.current, selIndex, TREE_ROW_HEIGHT);
  }, [selIndex, ref]);

  const first = Math.max(0, Math.floor(vp.top / TREE_ROW_HEIGHT) - 10);
  const last = Math.min(rows.length, Math.ceil((vp.top + vp.height) / TREE_ROW_HEIGHT) + 10);
  const page = Math.max(1, Math.floor(vp.height / TREE_ROW_HEIGHT) - 1);

  const onKeyDown = (e: KeyboardEvent) => {
    const i = selIndex;
    const id = p.selected;
    // Moves to the element row nearest `to` in the direction of travel (comment rows are skipped).
    const move = (to: number) => {
      let t = Math.max(0, Math.min(rows.length - 1, to));
      const step = to >= i ? 1 : -1;
      while (t >= 0 && t < rows.length && rows[t] < 0) t += step;
      if (t < 0 || t >= rows.length) {
        t = Math.max(0, Math.min(rows.length - 1, to));
        while (t >= 0 && t < rows.length && rows[t] < 0) t -= step;
      }
      if (t >= 0 && t < rows.length) p.onSelect(rows[t]);
    };
    switch (e.key) {
      case "ArrowDown":
        move(i + 1);
        break;
      case "ArrowUp":
        move(i < 0 ? 0 : i - 1);
        break;
      case "Home":
        move(0);
        break;
      case "End":
        move(rows.length - 1);
        break;
      case "PageDown":
        move(i + page);
        break;
      case "PageUp":
        move(i - page);
        break;
      case "ArrowRight":
        if (id < 0) break;
        if (sk.firstChild[id] >= 0 && !p.filter && !p.expanded.has(id)) p.onToggle(id, true);
        else if (sk.firstChild[id] >= 0) move(i + 1);
        break;
      case "ArrowLeft":
        if (id < 0) break;
        if (!p.filter && p.expanded.has(id)) p.onToggle(id, false);
        else if (sk.parent[id] >= 0) p.onSelect(sk.parent[id]);
        break;
      case "Enter":
      case "F4":
        if (id >= 0 && e.shiftKey && e.key === "Enter" && p.onInspect) p.onInspect(id);
        else if (id >= 0) p.onActivate(id);
        break;
      case "*":
        if (id >= 0) p.onToggle(id, true);
        break;
      default:
        return;
    }
    e.preventDefault();
    e.stopPropagation();
  };

  const items = [];
  for (let i = first; i < last; i++) {
    const id = rows[i];
    if (id < 0) {
      const m = sk.misc[miscIndex(id)];
      const depth = m.parent < 0 ? 0 : sk.depth[m.parent] + 1;
      items.push(
        <div
          key={id}
          class={`tree-row misc misc-${m.kind}`}
          role="treeitem"
          aria-level={depth + 1}
          title={m.value.length > 1000 ? m.value.slice(0, 1000) + "…" : m.value}
          style={{ top: i * TREE_ROW_HEIGHT, paddingLeft: `calc(${depth} * var(--xgv-tree-indent) + 4px)` }}
          onDblClick={() => p.onActivateOffset?.(m.start, m.end - m.start)}
        >
          <span class="twisty" />
          <span class="misc-text">{miscDisplay(m)}</span>
        </div>,
      );
      continue;
    }
    const hasKids = sk.firstChild[id] >= 0;
    const open = hasKids && (p.filter ? true : p.expanded.has(id));
    const own = p.problems?.get(id);
    const cls =
      "tree-row" +
      (id === p.selected ? " selected" : "") +
      (p.hits?.has(id) ? " hit" : "") +
      problemClass(own) +
      (!own && hasKids && !open && p.problemWithin?.has(id) ? " problem-within" : "");
    items.push(
      <div
        key={id}
        class={cls}
        role="treeitem"
        aria-level={sk.depth[id] + 1}
        aria-expanded={hasKids ? open : undefined}
        aria-selected={id === p.selected}
        title={problemTitle(own)}
        style={{ top: i * TREE_ROW_HEIGHT, paddingLeft: `calc(${sk.depth[id]} * var(--xgv-tree-indent) + 4px)` }}
        onMouseDown={(e) => {
          if ((e.target as HTMLElement).classList.contains("twisty")) return;
          p.onSelect(id);
        }}
        onDblClick={() => p.onActivate(id)}
        onContextMenu={(e) => {
          if (!p.onContextMenu) return;
          e.preventDefault();
          e.stopPropagation();
          p.onSelect(id);
          p.onContextMenu(id, e.clientX, e.clientY);
        }}
      >
        <span
          class={"twisty" + (hasKids ? (open ? " open" : " closed") : "")}
          onMouseDown={(e) => {
            e.preventDefault();
            if (hasKids && !p.filter) p.onToggle(id, !open);
          }}
        />
        <span class="tag">
          <Highlight text={sk.names[sk.nameIdx[id]]} matcher={p.highlightNames ? p.matcher : null} />
        </span>
        {sk.preview[id] && (
          <span class="preview">
            <PreviewText text={sk.preview[id]} matcher={p.highlightPreview ? p.matcher : null} />
          </span>
        )}
        {sk.childCount[id] > 0 && <span class="count">{sk.childCount[id]}</span>}
      </div>,
    );
  }

  return (
    <div class="tree" ref={ref} tabIndex={0} role="tree" aria-label="XML tree" onKeyDown={onKeyDown}>
      <div class="tree-spacer" style={{ height: rows.length * TREE_ROW_HEIGHT }}>
        {items}
      </div>
    </div>
  );
}
