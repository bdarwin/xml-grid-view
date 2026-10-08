import {
  createMatcher,
  detectJson,
  isJsonContainer,
  jsonAt,
  jsonPointer,
  jsonPrimitiveText,
  jsonTable,
  JSON_KEY_COLUMN,
  JSON_VALUE_COLUMN,
  prettyJson,
  type JsonPath,
  type JsonValue,
  type Matcher,
  type SearchOptions,
} from "@xmlgridview/core";
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "preact/hooks";
import { autoFitWidth, cellString, computeView, toTsv, type Filters, type SortState } from "../gridView";
import { FilterPopup } from "./FilterPopup";
import { Grid, type GridSelection } from "./Grid";
import { allJsonNodes, JsonTree, type JsonNode } from "./JsonTree";

type Tab = "tree" | "grid" | "text";

const MAX_MARKS = 5000;

export interface InspectorTarget {
  /** Where the value comes from, e.g. `catalog › book[2] › description`. */
  title: string;
  text: string;
}

/** Full-text view with search highlighting; the current match scrolls into view. */
function TextView({ text, matcher, current, wrap }: { text: string; matcher: Matcher | null; current: number; wrap: boolean }) {
  const ref = useRef<HTMLPreElement>(null);
  const parts = useMemo(() => {
    if (!matcher) return [text];
    const ranges = matcher.ranges(text).slice(0, MAX_MARKS);
    const out: (string | preact.JSX.Element)[] = [];
    let last = 0;
    ranges.forEach(([s, e], k) => {
      if (s > last) out.push(text.slice(last, s));
      out.push(
        <mark key={k} data-k={k} class={"match" + (k === current ? " current" : "")}>
          {text.slice(s, e)}
        </mark>,
      );
      last = e;
    });
    out.push(text.slice(last));
    return out;
  }, [text, matcher, current]);
  useEffect(() => {
    ref.current?.querySelector(`mark[data-k="${current}"]`)?.scrollIntoView({ block: "center" });
  }, [current, matcher]);
  return (
    <pre class={"inspector-text" + (wrap ? " wrap" : "")} ref={ref} tabIndex={0}>
      {parts}
    </pre>
  );
}

export function ValueInspector(p: { target: InspectorTarget; onClose(): void; onCopy(text: string): void }) {
  const detection = useMemo(() => detectJson(p.target.text), [p.target.text]);
  const json = detection.kind === "json" ? detection : null;
  const [tab, setTab] = useState<Tab>(json ? "tree" : "text");
  const [query, setQuery] = useState("");
  const [options, setOptions] = useState<SearchOptions>({ caseSensitive: false, wholeWord: false, regex: false });
  const [current, setCurrent] = useState(0);
  const [wrap, setWrap] = useState(true);
  const searchRef = useRef<HTMLInputElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  // JSON tree state
  const [expanded, setExpanded] = useState<Set<string>>(() => {
    const s = new Set<string>([""]);
    if (json && isJsonContainer(json.value)) {
      const kids = Array.isArray(json.value) ? json.value.map((_, i) => i) : [...json.value.keys()];
      if (kids.length <= 50) for (const k of kids) s.add(jsonPointer([k]));
    }
    return s;
  });
  const [selected, setSelected] = useState("");

  // JSON grid state
  const [path, setPath] = useState<JsonPath>([]);
  const [sort, setSort] = useState<SortState | null>(null);
  const [filters, setFilters] = useState<Filters>({});
  const [quick, setQuick] = useState("");
  const [gridSel, setGridSel] = useState<GridSelection | null>(null);
  const [widths, setWidths] = useState<number[]>([]);
  const [filterPopup, setFilterPopup] = useState<{ col: number; anchor: DOMRect } | null>(null);

  let matcher: Matcher | null = null;
  let searchError: string | undefined;
  try {
    matcher = createMatcher(query, options);
  } catch (e) {
    searchError = (e as Error).message;
  }

  const textForTab = tab === "text" && json ? json.pretty : p.target.text;
  const nodes = useMemo(() => (json ? allJsonNodes(json.value) : []), [json]);
  const node = json ? jsonAt(json.value, path) : undefined;
  const table = useMemo(() => (json && node !== undefined ? jsonTable(node) : null), [json, node]);
  const view = useMemo(() => (table ? computeView(table, quick, filters, sort) : new Int32Array(0)), [table, quick, filters, sort]);

  useEffect(() => {
    if (!table || widths.length === table.columns.length) return;
    const cs = getComputedStyle(rootRef.current ?? document.body);
    const font = `${cs.fontSize} ${cs.fontFamily}`;
    const mono = `${cs.getPropertyValue("--xgv-mono-font-size").trim() || cs.fontSize} ${cs.getPropertyValue("--xgv-mono-font-family").trim() || "monospace"}`;
    setWidths(table.columns.map((_, c) => autoFitWidth(table, view, c, font, `600 ${font}`, mono)));
  }, [table, widths]);

  // Matches for the active tab.
  const treeHits = useMemo(() => {
    if (!matcher || !json) return [] as JsonNode[];
    return nodes.filter((n) => (n.key !== null && matcher!.test(n.key)) || (!isJsonContainer(n.value) && matcher!.test(jsonPrimitiveText(n.value))));
  }, [nodes, matcher, json]);
  const textHitCount = useMemo(() => (matcher ? Math.min(MAX_MARKS, matcher.ranges(textForTab).length) : 0), [matcher, textForTab]);
  const gridHits = useMemo(() => {
    if (!matcher || !table) return [] as { v: number; c: number; key: number }[];
    const out: { v: number; c: number; key: number }[] = [];
    const n = table.columns.length;
    view.forEach((r, v) => {
      for (let c = 0; c < n; c++) if (table.cells[r * n + c] !== null && matcher!.test(cellString(table, r, c))) out.push({ v, c, key: r * n + c });
    });
    return out;
  }, [matcher, table, view]);
  const count = tab === "tree" ? treeHits.length : tab === "grid" ? gridHits.length : textHitCount;

  useEffect(() => setCurrent(0), [query, options, tab, path]);

  const goTo = (i: number) => {
    if (!count) return;
    const k = ((i % count) + count) % count;
    setCurrent(k);
    if (tab === "tree") {
      const hit = treeHits[k];
      setExpanded((prev) => {
        const next = new Set(prev);
        for (let d = 0; d < hit.path.length; d++) next.add(jsonPointer(hit.path.slice(0, d)));
        return next;
      });
      setSelected(hit.pointer);
    } else if (tab === "grid") {
      const h = gridHits[k];
      setGridSel({ anchor: { v: h.v, c: h.c }, focus: { v: h.v, c: h.c } });
    }
  };

  const drillTo = (r: number, c: number) => {
    if (!table) return;
    const i = r * table.columns.length + c;
    if (!table.drillKinds[i]) return;
    const col = table.columns[c].key;
    const seg = table.rowSegments[r];
    const spread = col !== JSON_VALUE_COLUMN && col !== JSON_KEY_COLUMN;
    setPath([...path, seg, ...(spread ? [col] : [])]);
    setSort(null);
    setFilters({});
    setQuick("");
    setGridSel(null);
    setWidths([]);
  };

  // Focus search as soon as the dialog is in the DOM, so typing right away goes there.
  useLayoutEffect(() => {
    searchRef.current?.focus();
  }, []);

  const onKeyDown = (e: KeyboardEvent) => {
    const mod = e.ctrlKey || e.metaKey;
    if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      if (filterPopup) setFilterPopup(null);
      else p.onClose();
    } else if (mod && e.key.toLowerCase() === "f") {
      e.preventDefault();
      e.stopPropagation();
      searchRef.current?.focus();
      searchRef.current?.select();
    } else if (e.key === "F3") {
      e.preventDefault();
      e.stopPropagation();
      goTo(e.shiftKey ? current - 1 : current + 1);
    } else {
      // Keep the main view's shortcuts out of the inspector.
      e.stopPropagation();
    }
  };

  const copyCurrent = () => {
    if (tab === "grid" && table && gridSel) {
      const r = { r0: Math.min(gridSel.anchor.v, gridSel.focus.v), r1: Math.max(gridSel.anchor.v, gridSel.focus.v), c0: Math.min(gridSel.anchor.c, gridSel.focus.c), c1: Math.max(gridSel.anchor.c, gridSel.focus.c) };
      p.onCopy(toTsv(table, view, r, false));
    } else p.onCopy(tab === "text" && json ? json.pretty : p.target.text);
  };

  // Full value of the selected tree node or grid cell, shown wrapped below the view.
  let detailText: string | null = null;
  if (json && tab === "tree" && selected !== null) {
    const node = nodes.find((n) => n.pointer === selected);
    if (node) detailText = isJsonContainer(node.value) ? prettyJson(node.value) : jsonPrimitiveText(node.value);
  } else if (json && tab === "grid" && table && gridSel) {
    const r = view[gridSel.focus.v];
    if (r !== undefined) {
      const i = r * table.columns.length + gridSel.focus.c;
      const v = table.cells[i];
      detailText = typeof v === "number" ? `${table.labels[i]} (double-click or Enter to open)` : (v ?? "");
    }
  }

  const crumbs = ["$", ...path.map(String)];
  const lines = p.target.text.split("\n").length;

  return (
    <div class="overlay" onMouseDown={(e) => e.target === e.currentTarget && p.onClose()}>
      <div class="inspector" ref={rootRef} role="dialog" aria-modal="true" aria-label={`Value of ${p.target.title}`} onKeyDown={onKeyDown}>
        <div class="inspector-head">
          <span class="inspector-title" title={p.target.title}>
            {p.target.title}
          </span>
          <span class="badge">{json ? "JSON" : "Text"}</span>
          <span class="muted small">
            {p.target.text.length.toLocaleString()} chars · {lines.toLocaleString()} line{lines === 1 ? "" : "s"}
          </span>
          <span class="spacer" />
          <button class="secondary small" onClick={copyCurrent} title="Copy (selection in Grid, otherwise the whole value)">
            Copy
          </button>
          <button class="icon" aria-label="Close" title="Close (Escape)" onClick={p.onClose}>
            ×
          </button>
        </div>
        {detection.kind === "text" && detection.jsonError && (
          <div class="banner static">
            Looks like JSON but could not be parsed: {detection.jsonError.message} (line {detection.jsonError.line}, column {detection.jsonError.column}).
          </div>
        )}
        <div class="inspector-bar">
          {json && (
            <div class="segmented" role="tablist">
              {(["tree", "grid", "text"] as const).map((t) => (
                <button key={t} role="tab" aria-selected={tab === t} class={"modetab" + (tab === t ? " active" : "")} onClick={() => setTab(t)}>
                  {t === "tree" ? "Tree" : t === "grid" ? "Grid" : "Text"}
                </button>
              ))}
            </div>
          )}
          <div class={"find-input" + (searchError ? " invalid" : "")}>
            <input
              ref={searchRef}
              type="text"
              value={query}
              placeholder={json && tab !== "text" ? "Search keys and values" : "Search text"}
              aria-label="Search value"
              onInput={(e) => setQuery((e.target as HTMLInputElement).value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  goTo(e.shiftKey ? current - 1 : current + 1);
                }
              }}
            />
            <span class="input-toggles">
              <button class={"toggle" + (options.caseSensitive ? " on" : "")} title="Match case" onClick={() => setOptions({ ...options, caseSensitive: !options.caseSensitive })}>
                Aa
              </button>
              <button class={"toggle" + (options.wholeWord ? " on" : "")} title="Whole word" onClick={() => setOptions({ ...options, wholeWord: !options.wholeWord })}>
                ab
              </button>
              <button class={"toggle" + (options.regex ? " on" : "")} title="Regular expression" onClick={() => setOptions({ ...options, regex: !options.regex })}>
                .*
              </button>
            </span>
          </div>
          <span class="counter">{searchError ? searchError : query ? (count ? `${Math.min(current + 1, count)} of ${count}` : "No results") : ""}</span>
          <button class="icon" disabled={!count} title="Previous (Shift+Enter)" onClick={() => goTo(current - 1)}>
            ↑
          </button>
          <button class="icon" disabled={!count} title="Next (Enter)" onClick={() => goTo(current + 1)}>
            ↓
          </button>
          <span class="spacer" />
          {json && tab === "tree" && (
            <>
              <button
                class="secondary small"
                onClick={() => setExpanded(new Set(nodes.filter((n) => isJsonContainer(n.value)).map((n) => n.pointer)))}
              >
                Expand all
              </button>
              <button class="secondary small" onClick={() => setExpanded(new Set([""]))}>
                Collapse all
              </button>
            </>
          )}
          {(tab === "text" || !json) && (
            <label class="check">
              <input type="checkbox" checked={wrap} onChange={() => setWrap(!wrap)} />
              Wrap
            </label>
          )}
          {json && tab === "grid" && (
            <input class="quick-filter" type="search" placeholder="Filter rows" value={quick} onInput={(e) => setQuick((e.target as HTMLInputElement).value)} />
          )}
        </div>
        {json && tab === "grid" && (
          <nav class="breadcrumb inspector-crumbs" aria-label="JSON path">
            {crumbs.map((c, i) => (
              <span key={i} class="crumb-wrap">
                {i > 0 && <span class="crumb-sep">›</span>}
                <button
                  class={"crumb" + (i === crumbs.length - 1 ? " current" : "")}
                  onClick={() => {
                    setPath(path.slice(0, i));
                    setSort(null);
                    setFilters({});
                    setGridSel(null);
                    setWidths([]);
                  }}
                >
                  {c}
                </button>
              </span>
            ))}
          </nav>
        )}
        <div class={"inspector-body" + (json && tab !== "text" ? " with-detail" : "")}>
          {json && tab === "tree" && (
            <JsonTree
              root={json.value}
              expanded={expanded}
              selected={selected}
              matcher={matcher}
              hits={new Set(treeHits.map((h) => h.pointer))}
              onToggle={(ptr, expand) =>
                setExpanded((prev) => {
                  const next = new Set(prev);
                  if (expand ?? !prev.has(ptr)) next.add(ptr);
                  else next.delete(ptr);
                  return next;
                })
              }
              onSelect={setSelected}
              onCopy={(n) => p.onCopy(isJsonContainer(n.value) ? prettyJson(n.value as JsonValue) : jsonPrimitiveText(n.value))}
            />
          )}
          {json && tab === "grid" && table && (
            <Grid
              table={table}
              view={view}
              widths={widths.length === table.columns.length ? widths : table.columns.map(() => 120)}
              sort={sort}
              filters={filters}
              sel={gridSel}
              matcher={matcher}
              hitCells={new Set(gridHits.map((h) => h.key))}
              currentHit={gridHits[current]?.key ?? -1}
              onSort={(key) => setSort((s) => (s?.key !== key ? { key, dir: 1 } : s.dir === 1 ? { key, dir: -1 } : null))}
              onOpenFilter={(col, anchor) => setFilterPopup({ col, anchor })}
              onResize={(col, w) => setWidths((ws) => ws.map((x, i) => (i === col ? w : x)))}
              onAutoFit={() => setWidths([])}
              onSelect={setGridSel}
              onActivate={(pos) => pos.c >= 0 && drillTo(view[pos.v], pos.c)}
              onDrill={(pos) => drillTo(view[pos.v], pos.c)}
              onCopy={(range, invert) => p.onCopy(toTsv(table, view, range, invert))}
            />
          )}
          {(tab === "text" || !json) && <TextView text={textForTab} matcher={matcher} current={current} wrap={wrap} />}
        </div>
        {json && tab !== "text" && (
          <pre class="inspector-detail" aria-label="Selected value" tabIndex={0}>
            {detailText ?? <span class="muted">Select an item to see its full value.</span>}
          </pre>
        )}
        {filterPopup && table && filterPopup.col < table.columns.length && (
          <FilterPopup
            table={table}
            col={filterPopup.col}
            anchor={filterPopup.anchor}
            filter={filters[table.columns[filterPopup.col].key]}
            onApply={(f) => {
              const key = table.columns[filterPopup.col].key;
              setFilters((prev) => {
                const next = { ...prev };
                if (f) next[key] = f;
                else delete next[key];
                return next;
              });
              setFilterPopup(null);
            }}
            onClose={() => setFilterPopup(null)}
          />
        )}
      </div>
    </div>
  );
}
