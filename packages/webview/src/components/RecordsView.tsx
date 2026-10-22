import { XmlModel, parseXml, type GridColumn, type GridTable, type HostToView, type RecordsInfo } from "@xmlgridview/core";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { autoFitWidth, selectionStats, statsText, toTsv } from "../gridView";
import type { HostBridge } from "../host";
import { Grid, selectionRange, type GridSelection } from "./Grid";

/** Records read per request. */
export const RECORD_PAGE = 200;
/** Searches report at most this many matches (the total is still counted). */
const SEARCH_LIMIT = 100_000;

interface Page {
  /** Column key → index into a row's values. */
  keys: Map<string, number>;
  rows: (string | number | null)[][];
}

interface SearchState {
  query: string;
  matches: number[];
  total: number;
  done: boolean;
  index: number;
}

/**
 * Records mode, for files too large to load: a read-only grid of the root's children (one group,
 * i.e. tag, at a time) whose pages are read from disk by the host as they scroll into view and
 * parsed here. Columns are the union of those seen so far. Find searches the whole file on the host.
 */
export function RecordsView(p: { host: HostBridge; info: RecordsInfo; fileName: string; copyWithHeader: boolean }) {
  const { host, info } = p;
  const [group, setGroup] = useState(info.groups[0]?.name ?? "");
  const total = info.groups.find((g) => g.name === group)?.count ?? 0;
  const pages = useRef(new Map<number, Page>());
  const pending = useRef(new Set<number>());
  const columnsRef = useRef<GridColumn[]>([]);
  const [rev, setRev] = useState(0);
  const [widths, setWidths] = useState<number[]>([]);
  const [sel, setSel] = useState<GridSelection | null>(null);
  const [search, setSearch] = useState<SearchState | null>(null);
  const [query, setQuery] = useState("");
  const nextId = useRef(1);
  const waiters = useRef(new Map<number, (msg: HostToView) => void>());
  const wrapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    host.onMessage((msg) => {
      if (msg.type === "recordPage" || msg.type === "recordSearch") waiters.current.get(msg.id)?.(msg);
    });
  }, []);

  // A new group starts over.
  useEffect(() => {
    pages.current = new Map();
    pending.current = new Set();
    columnsRef.current = [];
    setWidths([]);
    setSel(null);
    setSearch(null);
    setRev((r) => r + 1);
  }, [group]);

  const loadPage = (n: number) => {
    if (pages.current.has(n) || pending.current.has(n) || n * RECORD_PAGE >= total) return;
    pending.current.add(n);
    const id = nextId.current++;
    const g = group;
    waiters.current.set(id, (msg) => {
      waiters.current.delete(id);
      if (msg.type !== "recordPage" || g !== groupRef.current) return;
      pending.current.delete(n);
      pages.current.set(n, parsePage(info, g, msg.texts, columnsRef.current));
      setRev((r) => r + 1);
    });
    host.post({ type: "requestRecordPage", id, group: g, start: n * RECORD_PAGE, count: RECORD_PAGE });
  };
  const groupRef = useRef(group);
  groupRef.current = group;

  // Only the pages around the visible rows stay loaded.
  const onVisibleRange = (first: number, last: number) => {
    const p0 = Math.floor(first / RECORD_PAGE);
    const p1 = Math.floor(Math.max(first, last - 1) / RECORD_PAGE);
    for (let n = p0; n <= p1; n++) loadPage(n);
    for (const n of pages.current.keys()) if (n < p0 - 4 || n > p1 + 4) pages.current.delete(n);
  };

  // A grid table whose cells come from the loaded pages (null while a page loads).
  const table = useMemo((): GridTable => {
    // A copy: the shared list grows as pages bring new columns, and must match this table's ncols.
    const columns = [...columnsRef.current];
    const ncols = columns.length;
    const cellAt = (i: number): string | number | null => {
      const r = Math.floor(i / ncols);
      const page = pages.current.get(Math.floor(r / RECORD_PAGE));
      if (!page) return null;
      const k = page.keys.get(columns[i % ncols].key);
      return k === undefined ? null : (page.rows[r % RECORD_PAGE]?.[k] ?? null);
    };
    const cells = new Proxy([] as (string | number | null)[], {
      get: (target, prop) =>
        typeof prop === "string" && /^\d+$/.test(prop) ? cellAt(Number(prop)) : prop === "length" ? total * ncols : Reflect.get(target, prop),
    });
    const spans = new Proxy(new Int32Array(0), {
      get: (target, prop) => (typeof prop === "string" && /^\d+$/.test(prop) ? -1 : Reflect.get(target, prop)),
    });
    return { elementId: 0, group, groups: info.groups, columns, rowIds: identity(total), cells, spans };
  }, [rev, group, total]);
  const view = table.rowIds;

  // Fit the columns once the first page is in (and when new columns appear).
  useEffect(() => {
    if (!table.columns.length || widths.length === table.columns.length) return;
    const cs = getComputedStyle(wrapRef.current ?? document.body);
    const font = `${cs.fontSize} ${cs.fontFamily}`;
    const mono = `${cs.getPropertyValue("--xgv-mono-font-size").trim() || cs.fontSize} ${cs.getPropertyValue("--xgv-mono-font-family").trim() || "monospace"}`;
    const sample = view.subarray(0, Math.min(view.length, RECORD_PAGE));
    setWidths(table.columns.map((_, c) => widths[c] ?? autoFitWidth(table, sample, c, font, `600 ${font}`, mono)));
  }, [table]);

  const runSearch = (q: string) => {
    if (!q) {
      setSearch(null);
      return;
    }
    const id = nextId.current++;
    setSearch({ query: q, matches: [], total: 0, done: false, index: -1 });
    waiters.current.set(id, (msg) => {
      if (msg.type !== "recordSearch") return;
      if (msg.done) waiters.current.delete(id);
      setSearch((s) =>
        s && s.query === q
          ? { ...s, matches: msg.matches, total: msg.total, done: msg.done, index: s.index < 0 && msg.matches.length ? 0 : s.index }
          : s,
      );
      if (msg.matches.length) setSel((cur) => cur ?? { anchor: { v: msg.matches[0], c: 0 }, focus: { v: msg.matches[0], c: 0 } });
    });
    host.post({ type: "requestRecordSearch", id, group, query: q, caseSensitive: false });
  };
  const step = (d: number) => {
    if (!search || !search.matches.length) return;
    const n = search.matches.length;
    const index = ((((search.index < 0 ? (d > 0 ? -1 : 0) : search.index) + d) % n) + n) % n;
    setSearch({ ...search, index });
    const v = search.matches[index];
    setSel({ anchor: { v, c: 0 }, focus: { v, c: sel?.focus.c ?? 0 } });
  };
  const matchSet = useMemo(() => (search ? new Set(search.matches) : null), [search?.matches]);

  const range = sel ? selectionRange(sel) : null;
  const statsLine =
    range && (range.r1 - range.r0 + 1) * (range.c1 - range.c0 + 1) > 1 && range.r1 - range.r0 < 100_000
      ? statsText(selectionStats(table, view, range))
      : "";
  const openRecord = (v: number) => host.post({ type: "openRecord", group, index: v });

  return (
    <div class="records">
      <div class="records-bar">
        <span class="records-title">
          <b>{info.rootName}</b> · {total.toLocaleString()} {group} record{total === 1 ? "" : "s"} · {formatSize(info.fileSize)} · read-only
        </span>
        {info.groups.length > 1 && (
          <span class="chips">
            {info.groups.slice(0, 20).map((g) => (
              <button key={g.name} class={"chip" + (g.name === group ? " active" : "")} onClick={() => setGroup(g.name)}>
                {g.name} <span class="chip-count">{g.count.toLocaleString()}</span>
              </button>
            ))}
          </span>
        )}
        <input
          class="records-find"
          type="search"
          placeholder="Find in all records"
          aria-label="Find in all records"
          value={query}
          onInput={(e) => setQuery((e.target as HTMLInputElement).value)}
          onKeyDown={(e) => {
            if (e.key !== "Enter") return;
            e.preventDefault();
            if (search && search.query === query) step(e.shiftKey ? -1 : 1);
            else runSearch(query);
          }}
        />
        {search && (
          <span class="records-hits" role="status">
            {search.total === 0
              ? search.done
                ? "No matches"
                : "Searching…"
              : `${search.index + 1} of ${search.total.toLocaleString()}${search.total > search.matches.length ? ` (first ${SEARCH_LIMIT.toLocaleString()} listed)` : ""}${search.done ? "" : "…"}`}
          </span>
        )}
      </div>
      {info.error && <div class="banner warning">This file is not well-formed ({info.error}); the records before that point are shown.</div>}
      <div class="grid-wrap records-grid" ref={wrapRef}>
        <Grid
          table={table}
          view={view}
          widths={widths}
          sort={null}
          filters={{}}
          sel={sel}
          matcher={null}
          hitCells={null}
          currentHit={-1}
          noFilters
          onSort={() => {}}
          onOpenFilter={() => {}}
          onResize={(col, w) => setWidths((ws) => ws.map((x, i) => (i === col ? w : x)))}
          onAutoFit={() => {}}
          onSelect={setSel}
          onActivate={(pos) => openRecord(pos.v)}
          onDrill={(pos) => openRecord(pos.v)}
          onCopy={(r, invert) => host.copy(toTsv(table, view, r, p.copyWithHeader !== invert))}
          onVisibleRange={onVisibleRange}
          rowClass={
            matchSet ? (v) => (matchSet.has(v) ? (search?.matches[search.index] === v ? " record-hit current" : " record-hit") : "") : undefined
          }
        />
        <div class="statusbar">
          <span>{total.toLocaleString()} records</span>
          <span class="selection-stats">{statsLine}</span>
          <span class="muted">Double-click a row to open the record · {p.fileName}</span>
        </div>
      </div>
    </div>
  );
}

const identities = new Map<number, Int32Array>();
function identity(n: number): Int32Array {
  let a = identities.get(n);
  if (!a) {
    a = new Int32Array(n);
    for (let i = 0; i < n; i++) a[i] = i;
    identities.clear();
    identities.set(n, a);
  }
  return a;
}

/** Parses a page of records inside the root's start tag (for its namespaces) and adds new columns to `columns`. */
export function parsePage(info: RecordsInfo, group: string, texts: string[], columns: GridColumn[]): Page {
  const doc = parseXml(`${selfOpen(info.rootStartTag)}\n${texts.join("\n")}\n</${info.rootName}>`);
  const model = new XmlModel(doc);
  const keys = new Map<string, number>();
  const rows: (string | number | null)[][] = [];
  if (!doc.roots.length) return { keys, rows };
  const t = model.table(0, group);
  t.columns.forEach((c, i) => {
    keys.set(c.key, i);
    if (!columns.some((x) => x.key === c.key)) columns.push(c);
  });
  const ncols = t.columns.length;
  for (let r = 0; r < t.rowIds.length; r++) rows.push(t.cells.slice(r * ncols, (r + 1) * ncols));
  return { keys, rows };
}

/** `<root .../>` written as `<root ...>` (a root without records). */
function selfOpen(tag: string): string {
  return tag.endsWith("/>") ? tag.slice(0, -2).trimEnd() + ">" : tag;
}

function formatSize(bytes: number): string {
  return bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(1)} GB` : `${Math.round(bytes / 1024 ** 2)} MB`;
}
