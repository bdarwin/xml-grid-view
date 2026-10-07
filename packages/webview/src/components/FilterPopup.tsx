import type { GridTable } from "@xmlgridview/core";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { DISTINCT_CAP, distinctValues, type ColumnFilter } from "../gridView";

export interface FilterPopupProps {
  table: GridTable;
  col: number;
  anchor: DOMRect;
  filter: ColumnFilter | undefined;
  onApply(f: ColumnFilter | null): void;
  onClose(): void;
}

/** Per-column filter: "contains" text plus a distinct-values checklist (capped). */
export function FilterPopup(p: FilterPopupProps) {
  const { values, capped } = useMemo(() => distinctValues(p.table, p.col), [p.table, p.col]);
  const [text, setText] = useState(p.filter?.text ?? "");
  const [checked, setChecked] = useState<Set<string>>(() => {
    const ex = new Set(p.filter?.excluded ?? []);
    return new Set(values.filter((v) => !ex.has(v)));
  });
  const [listQuery, setListQuery] = useState("");
  const ref = useRef<HTMLDivElement>(null);
  const textRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    textRef.current?.focus();
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) p.onClose();
    };
    window.addEventListener("mousedown", onDown, true);
    return () => window.removeEventListener("mousedown", onDown, true);
  }, []);

  const shown = listQuery ? values.filter((v) => v.toLowerCase().includes(listQuery.toLowerCase())) : values;
  const allChecked = values.every((v) => checked.has(v));

  const apply = () => {
    const excluded = allChecked ? null : values.filter((v) => !checked.has(v));
    p.onApply(text === "" && excluded === null ? null : { text, excluded });
  };

  const toggle = (v: string) => {
    const next = new Set(checked);
    if (next.has(v)) next.delete(v);
    else next.add(v);
    setChecked(next);
  };

  const left = Math.min(p.anchor.left, window.innerWidth - 280);
  const top = Math.min(p.anchor.bottom + 2, window.innerHeight - 200);

  return (
    <div
      class="popup filter-popup"
      ref={ref}
      style={{ left: Math.max(4, left), top: Math.max(4, top) }}
      role="dialog"
      aria-label={`Filter ${p.table.columns[p.col].label}`}
      onKeyDown={(e) => {
        if (e.key === "Escape") {
          e.stopPropagation();
          p.onClose();
        } else if (e.key === "Enter" && (e.target as HTMLElement).tagName === "INPUT" && (e.target as HTMLInputElement).type === "text") {
          e.preventDefault();
          apply();
        }
      }}
    >
      <div class="popup-title">{p.table.columns[p.col].label}</div>
      <label class="field">
        <span>Contains</span>
        <input ref={textRef} type="text" value={text} placeholder="Text filter" onInput={(e) => setText((e.target as HTMLInputElement).value)} />
      </label>
      <div class="values-head">
        <label>
          <input
            type="checkbox"
            checked={allChecked}
            onChange={() => setChecked(allChecked ? new Set() : new Set(values))}
          />
          Select all
        </label>
        <input
          type="text"
          class="values-search"
          placeholder="Search values"
          value={listQuery}
          onInput={(e) => setListQuery((e.target as HTMLInputElement).value)}
        />
      </div>
      <div class="values-list">
        {shown.map((v) => (
          <label key={v} class="value-item">
            <input type="checkbox" checked={checked.has(v)} onChange={() => toggle(v)} />
            <span class={v === "" ? "muted" : ""}>{v === "" ? "(empty)" : v}</span>
          </label>
        ))}
      </div>
      {capped && <div class="note">Showing the first {DISTINCT_CAP.toLocaleString()} distinct values.</div>}
      <div class="popup-actions">
        <button class="secondary" onClick={() => p.onApply(null)}>
          Clear
        </button>
        <button onClick={apply}>Apply</button>
      </div>
    </div>
  );
}
