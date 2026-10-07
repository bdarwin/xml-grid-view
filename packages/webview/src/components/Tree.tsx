import type { Matcher, TreeSkeleton } from "@xmlgridview/core";
import { useEffect, useMemo } from "preact/hooks";
import { Highlight } from "./Highlight";
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
}

/** Visible rows in display order. */
function flatten(sk: TreeSkeleton, expanded: Set<number>, filter: Set<number> | null): Int32Array {
  const out: number[] = [];
  const stack: number[] = [];
  const pushSiblings = (first: number) => {
    const sibs: number[] = [];
    for (let c = first; c >= 0; c = sk.nextSibling[c]) if (!filter || filter.has(c)) sibs.push(c);
    for (let i = sibs.length - 1; i >= 0; i--) stack.push(sibs[i]);
  };
  pushSiblings(sk.firstRoot);
  while (stack.length) {
    const id = stack.pop()!;
    out.push(id);
    if (sk.firstChild[id] >= 0 && (filter ? true : expanded.has(id))) pushSiblings(sk.firstChild[id]);
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
    const move = (to: number) => {
      const t = Math.max(0, Math.min(rows.length - 1, to));
      if (rows.length) p.onSelect(rows[t]);
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
        if (id >= 0) p.onActivate(id);
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
    const hasKids = sk.firstChild[id] >= 0;
    const open = hasKids && (p.filter ? true : p.expanded.has(id));
    const cls = "tree-row" + (id === p.selected ? " selected" : "") + (p.hits?.has(id) ? " hit" : "");
    items.push(
      <div
        key={id}
        class={cls}
        role="treeitem"
        aria-level={sk.depth[id] + 1}
        aria-expanded={hasKids ? open : undefined}
        aria-selected={id === p.selected}
        style={{ top: i * TREE_ROW_HEIGHT, paddingLeft: `calc(${sk.depth[id]} * var(--xgv-tree-indent) + 4px)` }}
        onMouseDown={(e) => {
          if ((e.target as HTMLElement).classList.contains("twisty")) return;
          p.onSelect(id);
        }}
        onDblClick={() => p.onActivate(id)}
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
            <Highlight text={sk.preview[id]} matcher={p.highlightPreview ? p.matcher : null} />
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
