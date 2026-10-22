import { useLayoutEffect, useRef, useState } from "preact/hooks";
import { SELF_GROUP, type GridTable } from "@xmlgridview/core";
import { isInspectable } from "../inspect";
import { CellEditor } from "./CellEditor";

/**
 * The selected element's own data above its grid: attributes, single leaf children and
 * text (from its one-row fields table), and chips for its child tag groups. Each value is
 * column `c` of row 0 of `fields`; the grid's current group is left out of the values.
 */
export function Summary(p: {
  table: GridTable;
  fields: GridTable | null;
  onGroup(group: string): void;
  canEdit(c: number): boolean;
  onEdit(c: number, value: string): void;
  /** Values the editor suggests for field c while it is edited. */
  suggest?(c: number): Promise<string[]>;
  onInspect(c: number): void;
  onContextMenu(c: number, x: number, y: number): void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);
  /** Values that fit on the collapsed line; the rest are behind "+N more". */
  const [fit, setFit] = useState(Infinity);
  const line = useRef<HTMLDivElement>(null);
  const { table, fields } = p;
  const items: { c: number; label: string; kind: string; value: string }[] = [];
  const complex = new Set<string>();
  if (fields && fields.elementId === table.elementId) {
    fields.columns.forEach((col, c) => {
      const raw = fields.cells[c];
      if (col.kind === "complex") complex.add(col.label);
      else if (typeof raw === "string" && col.label !== table.group) items.push({ c, label: col.label, kind: col.kind, value: raw });
    });
  }
  const showValues = table.group !== SELF_GROUP && items.length > 0;
  const chips = table.groups.filter((g) => complex.has(g.name) || g.name === table.group);

  // Collapsed: measure which values fit whole on one line (values are never squeezed).
  useLayoutEffect(() => {
    const el = line.current;
    if (!el || expanded) return;
    const measure = () => {
      const max = el.getBoundingClientRect().left + el.clientWidth;
      let n = 0;
      for (const child of Array.from(el.children)) {
        if (child.getBoundingClientRect().right > max + 1) break;
        n++;
      }
      setFit(n);
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [expanded, items.length, fields, editing]);

  const hidden = expanded ? 0 : Math.max(0, items.length - fit);
  // Leaf elements (the grid already is all there is), or only "(fields)" while showing it.
  if (table.group === null || (!showValues && chips.length === 0)) return null;

  return (
    <div class="summary" aria-label="Element summary">
      {showValues && (
        <div class={"summary-fields" + (expanded ? " expanded" : "")}>
          <div class="summary-items" ref={line}>
            {items.map((it, i) => (
              <span
                key={it.label}
                class={`summary-item kind-${it.kind}`}
                style={!expanded && i >= fit ? { visibility: "hidden" } : undefined}
                title={`${it.label}: ${it.value.length > 1000 ? it.value.slice(0, 1000) + "…" : it.value}`}
                onDblClick={() => (p.canEdit(it.c) ? setEditing(it.c) : p.onInspect(it.c))}
                onContextMenu={(e) => {
                  e.preventDefault();
                  p.onContextMenu(it.c, e.clientX, e.clientY);
                }}
              >
                <span class="summary-name">{it.label === "#text" ? "text" : it.label}</span>
                {editing === it.c ? (
                  <span class="summary-edit">
                    <CellEditor
                      initial={it.value}
                      selectAll
                      suggest={p.suggest ? () => p.suggest!(it.c) : undefined}
                      onCommit={(v) => {
                        setEditing(null);
                        if (v !== it.value) p.onEdit(it.c, v);
                      }}
                      onCancel={() => setEditing(null)}
                    />
                  </span>
                ) : (
                  <span class="summary-value">{it.value.length > 300 ? it.value.slice(0, 300) : it.value || " "}</span>
                )}
                {editing !== it.c && isInspectable(it.value) && (
                  <button
                    class="inspect-btn"
                    title="Open value"
                    aria-label={`Open ${it.label}`}
                    onClick={(e) => {
                      e.stopPropagation();
                      p.onInspect(it.c);
                    }}
                  >
                    ⤢
                  </button>
                )}
              </span>
            ))}
          </div>
          {(hidden > 0 || expanded) && (
            <button class="link summary-more" onClick={() => setExpanded(!expanded)}>
              {expanded ? "less" : `+${hidden} more`}
            </button>
          )}
        </div>
      )}
      <div class="summary-groups" role="tablist" aria-label="Rows">
        <span class="summary-label">Rows</span>
        {chips.map((g) => (
          <button
            key={g.name}
            role="tab"
            aria-selected={g.name === table.group}
            class={"chip" + (g.name === table.group ? " active" : "")}
            title={`One row per <${g.name}> child`}
            onClick={() => p.onGroup(g.name)}
          >
            {g.name}
            {g.count > 1 && <span class="chip-count">{g.count}</span>}
          </button>
        ))}
        <button
          role="tab"
          aria-selected={table.group === SELF_GROUP}
          class={"chip" + (table.group === SELF_GROUP ? " active" : "")}
          title="This element as a single row, every attribute and child as a column"
          onClick={() => p.onGroup(SELF_GROUP)}
        >
          (fields)
        </button>
      </div>
    </div>
  );
}
