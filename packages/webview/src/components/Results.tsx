import type { Matcher } from "@xmlgridview/core";
import { useEffect } from "preact/hooks";
import { Highlight } from "./Highlight";
import { scrollRowIntoView, useViewport } from "./useViewport";

export interface ResultItem {
  path: string;
  kind: string;
  snippet: string;
}

const H = 22;

export function Results(p: { items: ResultItem[]; index: number; matcher: Matcher | null; onPick(i: number): void }) {
  const [ref, vp] = useViewport<HTMLDivElement>();
  useEffect(() => {
    if (p.index >= 0) scrollRowIntoView(ref.current, p.index, H);
  }, [p.index, ref]);
  const first = Math.max(0, Math.floor(vp.top / H) - 5);
  const last = Math.min(p.items.length, Math.ceil((vp.top + vp.height) / H) + 5);
  const rows = [];
  for (let i = first; i < last; i++) {
    const it = p.items[i];
    rows.push(
      <div key={i} class={"result" + (i === p.index ? " selected" : "")} style={{ top: i * H }} onClick={() => p.onPick(i)} role="option" aria-selected={i === p.index}>
        <span class="result-path">{it.path}</span>
        <span class="result-kind">{it.kind}</span>
        <span class="result-snippet">
          <Highlight text={it.snippet} matcher={p.matcher} />
        </span>
      </div>,
    );
  }
  return (
    <div class="results" ref={ref} role="listbox" aria-label="Search results">
      <div style={{ height: p.items.length * H, position: "relative" }}>{rows}</div>
    </div>
  );
}
