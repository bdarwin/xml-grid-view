import {
  ALL_TARGETS,
  DEFAULT_OPTIONS,
  DEFAULT_SETTINGS,
  createMatcher,
  idAtPath,
  pathKey,
  pathOfId,
  parsePathKey,
  type DocSearchResult,
  type FlatValues,
  type GridTable,
  type HostToView,
  type Matcher,
  type ParseError,
  type SearchQuery,
  type ThemeVars,
  type TreeSkeleton,
  type ViewSettings,
  type XPathEvalResult,
} from "@xmlgridview/core";
import { useCallback, useEffect, useMemo, useRef, useState } from "preact/hooks";
import { FilterPopup } from "./components/FilterPopup";
import { FindBar, type FindState } from "./components/FindBar";
import { FlatView, flatRows, rowIndexOf, type FlatPos, type FlatSelection } from "./components/FlatView";
import { Grid, selectionRange, type CellPos, type GridSelection } from "./components/Grid";
import { Results, type ResultItem } from "./components/Results";
import { Split } from "./components/Split";
import { Tree } from "./components/Tree";
import { ValueInspector, type InspectorTarget } from "./components/ValueInspector";
import { autoFitWidth, computeView, isFilterActive, toTsv, type CellRange, type ColumnFilter, type Filters, type SortState } from "./gridView";
import type { HostBridge } from "./host";
import { StaleError, type WorkerClient } from "./workerClient";

type Phase = "waiting" | "large" | "loading" | "ready";
type ViewMode = "grid" | "flat";

/** Path plus tag name, so a restored path only applies when the element is still the same kind. */
type NamedPath = [path: string, name: string];

/** Everything restored across updates and across view reloads. */
interface Snapshot {
  expanded: NamedPath[];
  selected: NamedPath | null;
  groups: [string, string][];
  sort: SortState | null;
  filters: Filters;
  quick: string;
  gridSel: GridSelection | null;
  split: number;
  find?: FindState;
  mode?: ViewMode;
  flatCollapsed?: NamedPath[];
  flatFocus?: [NamedPath, number] | null;
}

type FindResults =
  | { kind: "doc"; result: DocSearchResult }
  | { kind: "grid"; hits: { row: number; col: number }[]; truncated: false }
  | { kind: "xpath"; result: XPathEvalResult };

const INITIAL_FIND: FindState = {
  open: false,
  mode: "text",
  query: "",
  xpath: "",
  options: { ...DEFAULT_OPTIONS },
  scope: "grid",
  targets: { ...ALL_TARGETS },
  showOnlyMatches: false,
  resultsOpen: true,
};

function namedPath(sk: TreeSkeleton, id: number): NamedPath {
  return [pathKey(pathOfId(sk, id)), sk.names[sk.nameIdx[id]]];
}

function resolveNamed(sk: TreeSkeleton, np: NamedPath): number {
  const id = idAtPath(sk, parsePathKey(np[0]));
  return id >= 0 && sk.names[sk.nameIdx[id]] === np[1] ? id : -1;
}

function ancestors(sk: TreeSkeleton, id: number): number[] {
  const out: number[] = [];
  for (let p = sk.parent[id]; p >= 0; p = sk.parent[p]) out.push(p);
  return out.reverse();
}

function formatSize(chars: number) {
  return `${(chars / (1024 * 1024)).toFixed(1)} MB`;
}

export function App({ host, worker }: { host: HostBridge; worker: WorkerClient }) {
  const [phase, setPhase] = useState<Phase>("waiting");
  const [fileName, setFileName] = useState("");
  const [settings, setSettings] = useState<ViewSettings>(DEFAULT_SETTINGS);
  const [skeleton, setSkeleton] = useState<TreeSkeleton | null>(null);
  const [gen, setGen] = useState(0);
  const [errors, setErrors] = useState<ParseError[]>([]);
  const [keptOld, setKeptOld] = useState(false);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [selected, setSelected] = useState(-1);
  const [group, setGroup] = useState<string | null>(null);
  const [table, setTable] = useState<GridTable | null>(null);
  const [sort, setSort] = useState<SortState | null>(null);
  const [filters, setFilters] = useState<Filters>({});
  const [quick, setQuick] = useState("");
  const [widths, setWidths] = useState<number[]>([]);
  const [gridSel, setGridSel] = useState<GridSelection | null>(null);
  const [split, setSplit] = useState(0.3);
  const [filterPopup, setFilterPopup] = useState<{ col: number; anchor: DOMRect } | null>(null);
  const [find, setFind] = useState<FindState>(INITIAL_FIND);
  const [findResults, setFindResults] = useState<FindResults | null>(null);
  const [findIndex, setFindIndex] = useState(-1);
  const [findError, setFindError] = useState<string | undefined>();
  const [findBusy, setFindBusy] = useState(false);
  const [fatal, setFatal] = useState<string | null>(null);
  const [mode, setMode] = useState<ViewMode>("grid");
  const [flatCollapsed, setFlatCollapsed] = useState<Set<number>>(new Set());
  const [flatSel, setFlatSel] = useState<FlatSelection | null>(null);
  const [flatValues, setFlatValues] = useState<Map<number, FlatValues>>(new Map());
  const flatPending = useRef(new Set<number>());
  const [inspector, setInspector] = useState<InspectorTarget | null>(null);

  const textRef = useRef("");
  const settingsRef = useRef(settings);
  settingsRef.current = settings;
  const groupMemory = useRef(new Map<string, string>());
  const pendingRestore = useRef<Snapshot | null>(null);
  const loadTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const findInput = useRef<HTMLInputElement>(null);
  const gridWrap = useRef<HTMLDivElement>(null);
  const treeWrap = useRef<HTMLDivElement>(null);
  const tableKey = useRef("");

  // ----- snapshot / restore ------------------------------------------------

  const stateRef = useRef({ skeleton, expanded, selected, group, sort, filters, quick, gridSel, split, find, mode, flatCollapsed, flatSel });
  stateRef.current = { skeleton, expanded, selected, group, sort, filters, quick, gridSel, split, find, mode, flatCollapsed, flatSel };

  const takeSnapshot = useCallback((): Snapshot | null => {
    const s = stateRef.current;
    if (!s.skeleton) return pendingRestore.current;
    const sk = s.skeleton;
    return {
      expanded: [...s.expanded].filter((id) => id < sk.count).map((id) => namedPath(sk, id)),
      selected: s.selected >= 0 && s.selected < sk.count ? namedPath(sk, s.selected) : null,
      groups: [...groupMemory.current.entries()],
      sort: s.sort,
      filters: s.filters,
      quick: s.quick,
      gridSel: s.gridSel,
      split: s.split,
      find: s.find,
      mode: s.mode,
      flatCollapsed: [...s.flatCollapsed].filter((id) => id < sk.count).map((id) => namedPath(sk, id)),
      flatFocus: s.flatSel && s.flatSel.focus.id < sk.count ? [namedPath(sk, s.flatSel.focus.id), s.flatSel.focus.attr] : null,
    };
  }, []);

  const applySnapshot = (sk: TreeSkeleton, snap: Snapshot | null) => {
    const exp = new Set<number>();
    let sel = -1;
    if (snap) {
      for (const np of snap.expanded) {
        const id = resolveNamed(sk, np);
        if (id >= 0) exp.add(id);
      }
      if (snap.selected) sel = resolveNamed(sk, snap.selected);
      groupMemory.current = new Map(snap.groups);
      setSplit(snap.split);
      if (snap.mode) setMode(snap.mode);
      const fc = new Set<number>();
      for (const np of snap.flatCollapsed ?? []) {
        const id = resolveNamed(sk, np);
        if (id >= 0) fc.add(id);
      }
      setFlatCollapsed(fc);
      const ff = snap.flatFocus ? resolveNamed(sk, snap.flatFocus[0]) : -1;
      if (ff >= 0) {
        const attr = snap.flatFocus![1] < sk.attrCount[ff] ? snap.flatFocus![1] : -1;
        setFlatSel({ anchor: { id: ff, attr, col: 0 }, focus: { id: ff, attr, col: 1 } });
      } else setFlatSel(null);
    }
    if (sel < 0) {
      // Default: root selected and expanded; restored filters belonged to another element.
      sel = sk.firstRoot;
      if (sel >= 0) exp.add(sel);
      setSort(null);
      setFilters({});
      setQuick("");
      setGridSel(null);
    } else if (snap) {
      setSort(snap.sort);
      setFilters(snap.filters);
      setQuick(snap.quick);
      setGridSel(snap.gridSel);
    }
    setExpanded(exp);
    setSelected(sel);
    setGroup(sel >= 0 ? (groupMemory.current.get(pathKey(pathOfId(sk, sel))) ?? null) : null);
  };

  // Persist UI state so a reloaded view (e.g. a hidden VS Code tab) comes back as it was.
  useEffect(() => {
    if (!skeleton) return;
    const t = setTimeout(() => {
      const snap = takeSnapshot();
      if (snap) host.setState({ snapshot: snap });
    }, 500);
    return () => clearTimeout(t);
  }, [skeleton, expanded, selected, group, sort, filters, quick, gridSel, split, find, mode, flatCollapsed, flatSel]);

  // ----- loading -----------------------------------------------------------

  const load = useCallback(async () => {
    const text = textRef.current;
    const snap = takeSnapshot();
    setPhase((p) => (p === "ready" ? p : "loading"));
    try {
      const r = await worker.request<"load">({ type: "load", text });
      setErrors(r.errors);
      if (r.skeleton) {
        setKeptOld(false);
        applySnapshot(r.skeleton, snap);
        pendingRestore.current = null;
        setSkeleton(r.skeleton);
        setGen(r.gen);
      } else {
        setKeptOld(true);
      }
      setPhase("ready");
    } catch (e) {
      if (e instanceof StaleError) return;
      setFatal(String((e as Error).message ?? e));
      host.post({ type: "error", message: `Failed to parse: ${String((e as Error).message ?? e)}` });
    }
  }, []);

  const scheduleLoad = (immediate: boolean) => {
    clearTimeout(loadTimer.current);
    if (immediate) void load();
    else loadTimer.current = setTimeout(() => void load(), settingsRef.current.debounceMs);
  };

  const applyTheme = (vars: ThemeVars) => {
    const st = document.documentElement.style;
    for (const [k, v] of Object.entries(vars)) if (v) st.setProperty(k, v);
  };

  const largeConfirmed = useRef(false);

  useEffect(() => {
    host.onMessage((msg: HostToView) => {
      switch (msg.type) {
        case "init": {
          const s = { ...DEFAULT_SETTINGS, ...msg.settings };
          setSettings(s);
          settingsRef.current = s;
          setFileName(msg.fileName);
          applyTheme(msg.theme);
          textRef.current = msg.text;
          const saved = host.getState()?.snapshot as Snapshot | undefined;
          if (saved && !stateRef.current.skeleton) {
            pendingRestore.current = saved;
            if (saved.find) setFind({ ...saved.find, open: saved.find.open });
          }
          if (msg.text.length > s.largeFileThreshold && !largeConfirmed.current) setPhase("large");
          else scheduleLoad(true);
          break;
        }
        case "update":
          textRef.current = msg.text;
          if (msg.text.length > settingsRef.current.largeFileThreshold && !largeConfirmed.current) setPhase("large");
          else scheduleLoad(false);
          break;
        case "theme":
          applyTheme(msg.vars);
          break;
        case "focusFind":
          openFind();
          break;
      }
    });
    const onError = (e: ErrorEvent) => host.post({ type: "error", message: String(e.message) });
    window.addEventListener("error", onError);
    host.post({ type: "ready" });
    return () => window.removeEventListener("error", onError);
  }, []);

  // ----- grid table --------------------------------------------------------

  useEffect(() => {
    if (!skeleton || selected < 0 || selected >= skeleton.count) {
      setTable(null);
      return;
    }
    let cancelled = false;
    worker
      .request<"table">({ type: "table", gen, elementId: selected, group })
      .then((t) => {
        if (cancelled) return;
        const key = `${pathKey(pathOfId(skeleton, selected))}#${t.group ?? ""}#${t.columns.map((c) => c.key).join("|")}`;
        const sameGrid = key === tableKey.current;
        tableKey.current = key;
        setTable(t);
        if (t.group !== group) setGroup(t.group);
        if (!sameGrid) setWidths([]); // recomputed by auto-fit below
      })
      .catch((e) => {
        if (!(e instanceof StaleError) && !cancelled) setTable(null);
      });
    return () => {
      cancelled = true;
    };
  }, [skeleton, gen, selected, group]);

  const view = useMemo(() => (table ? computeView(table, quick, filters, sort) : new Int32Array(0)), [table, quick, filters, sort]);

  const fonts = () => {
    const cs = getComputedStyle(gridWrap.current ?? document.body);
    const font = `${cs.fontSize} ${cs.fontFamily}`;
    const mono = `${cs.getPropertyValue("--xgv-mono-font-size").trim() || cs.fontSize} ${cs.getPropertyValue("--xgv-mono-font-family").trim() || "monospace"}`;
    return { font, header: `600 ${font}`, mono };
  };

  // Auto-fit columns whenever a different grid is shown.
  useEffect(() => {
    if (!table || widths.length === table.columns.length) return;
    const f = fonts();
    setWidths(table.columns.map((_, c) => autoFitWidth(table, view, c, f.font, f.header, f.mono)));
  }, [table, widths]);

  // Clamp the grid selection when the view shrinks.
  useEffect(() => {
    if (!gridSel || !table) return;
    const maxV = view.length - 1;
    const maxC = table.columns.length - 1;
    if (maxV < 0 || maxC < 0) {
      setGridSel(null);
      return;
    }
    const clampPos = (p: CellPos) => ({ v: Math.min(p.v, maxV), c: Math.min(p.c, maxC) });
    if (gridSel.focus.v > maxV || gridSel.anchor.v > maxV || gridSel.focus.c > maxC || gridSel.anchor.c > maxC) {
      setGridSel({ anchor: clampPos(gridSel.anchor), focus: clampPos(gridSel.focus) });
    }
  }, [view, table]);

  // ----- selection & navigation -------------------------------------------

  const expandTo = (sk: TreeSkeleton, id: number) => {
    setExpanded((prev) => {
      const anc = ancestors(sk, id);
      if (anc.every((a) => prev.has(a))) return prev;
      const next = new Set(prev);
      for (const a of anc) next.add(a);
      return next;
    });
  };

  /** User-initiated selection: resets grid sort/filters, remembers the group per element. */
  const selectElement = (id: number, opts: { group?: string | null; reveal?: boolean } = {}) => {
    if (!skeleton || id < 0) return;
    if (opts.reveal) expandTo(skeleton, id);
    const path = pathKey(pathOfId(skeleton, id));
    let g = opts.group ?? groupMemory.current.get(path) ?? null;
    if (opts.group) groupMemory.current.set(path, opts.group);
    if (id === selected && g === group) return;
    if (id !== selected) {
      setSort(null);
      setFilters({});
      setQuick("");
      setGridSel(null);
    }
    if (g === undefined) g = null;
    setSelected(id);
    setGroup(g);
  };

  const chooseGroup = (g: string) => {
    if (!skeleton || selected < 0) return;
    groupMemory.current.set(pathKey(pathOfId(skeleton, selected)), g);
    setSort(null);
    setFilters({});
    setGridSel(null);
    setGroup(g);
  };

  const navigateTo = (offset: number, length: number) => {
    if (offset >= 0) host.post({ type: "navigate", offset, length: Math.max(0, length) });
  };

  const activateElement = (id: number) => {
    if (!skeleton) return;
    navigateTo(skeleton.start[id], skeleton.openEnd[id] - skeleton.start[id]);
  };

  const activateCell = (pos: CellPos) => {
    if (!table || !skeleton) return;
    const r = view[pos.v];
    if (r === undefined) return;
    const ncols = table.columns.length;
    const i = r * ncols + pos.c;
    if (pos.c < 0 || table.spans[i * 2] < 0) activateElement(table.rowIds[r]);
    else navigateTo(table.spans[i * 2], table.spans[i * 2 + 1]);
  };

  const drill = (pos: CellPos) => {
    if (!table || !skeleton) return;
    const r = view[pos.v];
    if (r === undefined) return;
    const rowId = table.rowIds[r];
    const col = table.columns[pos.c];
    if (rowId === table.elementId) return; // self view: nothing below
    if (col && col.kind === "complex") selectElement(rowId, { group: col.label, reveal: true });
    else if (skeleton.childCount[rowId] > 0) selectElement(rowId, { reveal: true });
  };

  const copy = (range: CellRange, invert: boolean) => {
    if (!table) return;
    host.copy(toTsv(table, view, range, settings.copyWithHeader !== invert));
  };

  // ----- flat view ---------------------------------------------------------

  // Values belong to a model generation.
  useEffect(() => {
    flatPending.current = new Set();
    setFlatValues(new Map());
  }, [gen]);

  const fetchFlatValues = useCallback(
    async (ids: number[]): Promise<Map<number, FlatValues>> => {
      const r = await worker.request<"flatValues">({ type: "flatValues", gen, ids });
      let merged!: Map<number, FlatValues>;
      setFlatValues((prev) => {
        merged = new Map(prev);
        for (const v of r) merged.set(v.id, v);
        return merged;
      });
      return new Map(r.map((v) => [v.id, v]));
    },
    [gen],
  );

  const needFlatValues = (ids: number[]) => {
    const todo = ids.filter((id) => !flatPending.current.has(id));
    if (!todo.length) return;
    for (const id of todo) flatPending.current.add(id);
    fetchFlatValues(todo)
      .catch(() => undefined)
      .finally(() => {
        for (const id of todo) flatPending.current.delete(id);
      });
  };

  const revealFlat = (sk: TreeSkeleton, id: number) => {
    setFlatCollapsed((prev) => {
      const anc = ancestors(sk, id);
      if (!anc.some((a) => prev.has(a))) return prev;
      const next = new Set(prev);
      for (const a of anc) next.delete(a);
      return next;
    });
  };

  const activateFlat = async (pos: FlatPos) => {
    if (!skeleton) return;
    if (pos.attr < 0) return activateElement(pos.id);
    const v = flatValues.get(pos.id) ?? (await fetchFlatValues([pos.id]).catch(() => null))?.get(pos.id);
    const a = v?.attrs[pos.attr];
    if (a) navigateTo(a.start, a.end - a.start);
    else activateElement(pos.id);
  };

  const copyFlat = async (sel: FlatSelection, rows: ReturnType<typeof flatRows>) => {
    if (!skeleton) return;
    const i0 = rowIndexOf(rows, sel.anchor);
    const i1 = rowIndexOf(rows, sel.focus);
    if (i0 < 0 || i1 < 0) return;
    const [a, b] = i0 <= i1 ? [i0, i1] : [i1, i0];
    const ids = [...new Set(Array.from(rows.ids.subarray(a, b + 1)))];
    const missing = ids.filter((id) => !flatValues.has(id));
    const extra = missing.length ? await fetchFlatValues(missing).catch(() => new Map<number, FlatValues>()) : new Map<number, FlatValues>();
    const get = (id: number) => flatValues.get(id) ?? extra.get(id);
    // Copy exactly the selected columns, like a spreadsheet range. Names keep their indentation.
    const withName = Math.min(sel.anchor.col, sel.focus.col) === 0;
    const withValue = Math.max(sel.anchor.col, sel.focus.col) === 1;
    const lines: string[] = [];
    for (let i = a; i <= b; i++) {
      const id = rows.ids[i];
      const attr = rows.attrs[i];
      const v = get(id);
      const depth = skeleton.depth[id] + (attr >= 0 ? 1 : 0);
      const cells: string[] = [];
      if (withName) cells.push("  ".repeat(depth) + tsvCell(attr >= 0 ? "@" + (v?.attrs[attr]?.name ?? "") : skeleton.names[skeleton.nameIdx[id]]));
      // Values are copied on one line, as displayed.
      if (withValue) cells.push(tsvCell(((attr >= 0 ? v?.attrs[attr]?.value : v?.text) ?? "").replace(/\s+/g, " ")));
      lines.push(cells.join("\t"));
    }
    host.copy(lines.join("\n"));
  };

  const switchMode = (m: ViewMode) => {
    if (m === mode || !skeleton) return;
    if (m === "flat" && selected >= 0) {
      revealFlat(skeleton, selected);
      setFlatSel({ anchor: { id: selected, attr: -1, col: 0 }, focus: { id: selected, attr: -1, col: 1 } });
    } else if (m === "grid" && flatSel) {
      selectElement(flatSel.focus.id, { reveal: true });
    }
    setMode(m);
    requestAnimationFrame(() => (document.querySelector(m === "flat" ? ".flat" : ".grid, .tree") as HTMLElement | null)?.focus());
  };

  // ----- value inspector -----------------------------------------------------

  /** e.g. `catalog › book[2] › description` */
  const elementTitle = (id: number): string => {
    if (!skeleton) return "";
    return [...ancestors(skeleton, id), id]
      .map((a) => {
        const name = skeleton.names[skeleton.nameIdx[a]];
        return skeleton.parent[a] >= 0 && sameNameSiblings(skeleton, a) > 1 ? `${name}[${indexAmongSameName(skeleton, a) + 1}]` : name;
      })
      .join(" › ");
  };

  const elementValues = async (id: number) => flatValues.get(id) ?? (await fetchFlatValues([id]).catch(() => null))?.get(id);

  const inspectCell = (pos: CellPos) => {
    if (!table) return;
    const r = view[pos.v];
    if (r === undefined || pos.c < 0) return;
    const col = table.columns[pos.c];
    const raw = table.cells[r * table.columns.length + pos.c];
    if (typeof raw !== "string") return;
    const rowId = table.rowIds[r];
    setInspector({ title: col.kind === "text" ? elementTitle(rowId) : `${elementTitle(rowId)} › ${col.label}`, text: raw });
  };

  const inspectFlat = async (pos: FlatPos) => {
    const v = await elementValues(pos.id);
    if (!v) return;
    const a = pos.attr >= 0 ? v.attrs[pos.attr] : null;
    setInspector({ title: a ? `${elementTitle(pos.id)} › @${a.name}` : elementTitle(pos.id), text: a ? a.value : v.text });
  };

  const inspectElement = async (id: number) => {
    const v = await elementValues(id);
    if (v) setInspector({ title: elementTitle(id), text: v.text });
  };

  // ----- find --------------------------------------------------------------

  const openFind = () => {
    setFind((f) => ({ ...f, open: true }));
    requestAnimationFrame(() => {
      findInput.current?.focus();
      findInput.current?.select();
    });
  };

  const closeFind = () => {
    setFind((f) => ({ ...f, open: false, showOnlyMatches: false }));
    setFindResults(null);
    setFindIndex(-1);
    setFindError(undefined);
    (gridWrap.current?.querySelector(".grid") as HTMLElement | null)?.focus();
  };

  const searchText = find.mode === "xpath" ? find.xpath : find.query;
  /** The Flat view has no "current grid", so it always searches the whole document. */
  const scope = mode === "flat" ? "document" : find.scope;

  const matcher: Matcher | null = useMemo(() => {
    if (!find.open || find.mode !== "text") return null;
    try {
      return createMatcher(find.query, find.options);
    } catch {
      return null;
    }
  }, [find.open, find.mode, find.query, find.options]);

  // Run searches in the worker, debounced; stale responses are dropped.
  const searchSeq = useRef(0);
  useEffect(() => {
    if (!find.open || !skeleton || searchText.trim() === "") {
      setFindResults(null);
      setFindIndex(-1);
      setFindError(undefined);
      setFindBusy(false);
      return;
    }
    const seq = ++searchSeq.current;
    setFindBusy(true);
    const t = setTimeout(async () => {
      try {
        let res: FindResults;
        let error: string | undefined;
        if (find.mode === "xpath") {
          const r = await worker.request<"xpath">({ type: "xpath", gen, expr: find.xpath });
          res = { kind: "xpath", result: r };
          error = r.error;
        } else {
          const query: SearchQuery = { text: find.query, options: find.options, targets: find.targets };
          if (scope === "document") {
            const r = await worker.request<"searchDoc">({ type: "searchDoc", gen, query });
            res = { kind: "doc", result: r };
            error = r.error;
          } else {
            if (selected < 0) return;
            const r = await worker.request<"searchGrid">({ type: "searchGrid", gen, elementId: selected, group, query });
            const hits: { row: number; col: number }[] = [];
            for (let i = 0; i < r.cells.length; i += 2) hits.push({ row: r.cells[i], col: r.cells[i + 1] });
            res = { kind: "grid", hits, truncated: false };
            error = r.error;
          }
        }
        if (seq !== searchSeq.current) return;
        setFindResults(res);
        setFindError(error);
        setFindIndex(-1);
        setFindBusy(false);
      } catch (e) {
        if (seq !== searchSeq.current || e instanceof StaleError) return;
        setFindError(String((e as Error).message ?? e));
        setFindBusy(false);
      }
    }, settings.debounceMs);
    return () => clearTimeout(t);
  }, [find.open, find.mode, find.query, find.xpath, find.options, find.targets, scope, gen, scope === "grid" ? selected : -1, scope === "grid" ? group : null]);

  /** Grid hits in view order (rows hidden by filters are skipped). */
  const gridHits = useMemo(() => {
    if (findResults?.kind !== "grid" || !table) return null;
    const pos = new Int32Array(table.rowIds.length).fill(-1);
    view.forEach((r, v) => (pos[r] = v));
    return findResults.hits
      .filter((h) => pos[h.row] >= 0)
      .map((h) => ({ v: pos[h.row], c: h.col, key: h.row * table.columns.length + h.col }))
      .sort((a, b) => a.v - b.v || a.c - b.c);
  }, [findResults, view, table]);

  /** Element-level hits for doc and XPath searches. */
  const elementHits = useMemo(() => {
    if (findResults?.kind === "doc") return findResults.result.matches.map((m) => m.elementId);
    if (findResults?.kind === "xpath") return findResults.result.items.map((m) => m.elementId);
    return null;
  }, [findResults]);

  const hitSet = useMemo(() => (elementHits ? new Set(elementHits) : null), [elementHits]);

  const treeFilter = useMemo(() => {
    if (!find.open || !find.showOnlyMatches || !hitSet || !skeleton) return null;
    const keep = new Set<number>();
    for (const id of hitSet) {
      for (let a = id; a >= 0 && !keep.has(a); a = skeleton.parent[a]) keep.add(a);
    }
    return keep;
  }, [find.open, find.showOnlyMatches, hitSet, skeleton]);

  const rowsForFlat = useMemo(
    () => (mode === "flat" && skeleton ? flatRows(skeleton, flatCollapsed, treeFilter) : null),
    [mode, skeleton, flatCollapsed, treeFilter],
  );

  const findCount = gridHits ? gridHits.length : (elementHits?.length ?? 0);

  const goToHit = (i: number) => {
    if (!findCount) return;
    const idx = ((i % findCount) + findCount) % findCount;
    setFindIndex(idx);
    if (gridHits) {
      const h = gridHits[idx];
      setGridSel({ anchor: { v: h.v, c: h.c }, focus: { v: h.v, c: h.c } });
    } else if (elementHits) {
      const id = elementHits[idx];
      if (mode === "flat" && skeleton) {
        revealFlat(skeleton, id);
        setFlatSel({ anchor: { id, attr: -1, col: 0 }, focus: { id, attr: -1, col: 1 } });
      }
      selectElement(id, { reveal: true });
    }
  };
  const nextHit = () => goToHit(findIndex + 1);
  const prevHit = () => goToHit(findIndex < 0 ? findCount - 1 : findIndex - 1);

  const resultItems: ResultItem[] = useMemo(() => {
    if (!skeleton || !findResults || findResults.kind === "grid") return [];
    const label = (id: number) => {
      const parts = [...ancestors(skeleton, id), id].map((a) => skeleton.names[skeleton.nameIdx[a]]);
      return (parts.length > 4 ? "… › " + parts.slice(-4).join(" › ") : parts.join(" › ")) || "?";
    };
    if (findResults.kind === "doc") {
      return findResults.result.matches.map((m) => ({
        path: label(m.elementId),
        kind: m.target === "name" ? "name" : m.target === "attrName" ? "attr name" : m.target === "attrValue" ? "@value" : "text",
        snippet: m.snippet,
      }));
    }
    return findResults.result.items.map((it) => ({
      path: label(it.elementId),
      kind: it.kind === "attribute" ? `@${it.attrName}` : it.kind,
      snippet: it.kind === "element" ? skeleton.preview[it.elementId] : (it.value ?? ""),
    }));
  }, [findResults, skeleton]);

  // Global keys: find, F3, Escape, Backspace (up one level).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (document.querySelector(".inspector")) return;
      const mod = e.ctrlKey || e.metaKey;
      if (mod && !e.altKey && e.key.toLowerCase() === "f") {
        e.preventDefault();
        openFind();
      } else if (e.key === "F3") {
        e.preventDefault();
        if (!stateRef.current.find.open) openFind();
        else if (e.shiftKey) prevHit();
        else nextHit();
      } else if (e.key === "Escape" && stateRef.current.find.open && !filterPopup) {
        e.preventDefault();
        closeFind();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  // ----- rendering ---------------------------------------------------------

  if (fatal) {
    return (
      <div class="notice">
        <p>XML Grid View failed: {fatal}</p>
      </div>
    );
  }

  if (phase === "large") {
    return (
      <div class="notice" role="alert">
        <h2>{fileName || "This file"} is large ({formatSize(textRef.current.length)})</h2>
        <p>Files over {formatSize(settings.largeFileThreshold)} can take a while to parse and use a lot of memory.</p>
        <button
          onClick={() => {
            largeConfirmed.current = true;
            scheduleLoad(true);
          }}
        >
          Load anyway
        </button>
      </div>
    );
  }

  if (!skeleton) {
    const err = errors[0];
    return (
      <div class="notice">
        {phase === "ready" && err ? (
          <p class="error-text">
            Cannot display this file: {err.message} (line {err.line}, column {err.column}).{" "}
            <button class="link" onClick={() => navigateTo(err.offset, 0)}>
              Go to error
            </button>
          </p>
        ) : (
          <p class="muted">{phase === "waiting" ? "Waiting for document…" : "Parsing…"}</p>
        )}
      </div>
    );
  }

  const crumbs = selected >= 0 ? [...ancestors(skeleton, selected), selected] : [];
  const activeFilterCount = Object.values(filters).filter((f: ColumnFilter) => isFilterActive(f)).length;
  const currentGridHit = gridHits && findIndex >= 0 ? (gridHits[findIndex]?.key ?? -1) : -1;
  const hitCells = gridHits ? new Set(gridHits.map((h) => h.key)) : null;
  const err = errors[0];

  return (
    <div class="app">
      {err && (
        <button class="banner" onClick={() => navigateTo(err.offset, 0)} title="Go to the error in the text editor">
          <span class="banner-icon" aria-hidden="true">
            ⚠
          </span>
          <span>
            XML is not well-formed: {err.message} (line {err.line}, column {err.column}).{" "}
            {keptOld ? "Showing the last valid version." : "Showing a partial tree."}
            {errors.length > 1 ? ` ${errors.length - 1} more error${errors.length > 2 ? "s" : ""}.` : ""}
          </span>
        </button>
      )}
      {find.open && (
        <FindBar
          state={find}
          onChange={(patch) => setFind((f) => ({ ...f, ...patch }))}
          count={findCount}
          index={findIndex}
          truncated={findResults?.kind === "doc" ? findResults.result.truncated : false}
          busy={findBusy}
          error={findError}
          scalar={findResults?.kind === "xpath" ? findResults.result.scalar : undefined}
          namespaces={findResults?.kind === "xpath" ? findResults.result.namespaces : undefined}
          inputRef={findInput}
          documentOnly={mode === "flat"}
          onNext={nextHit}
          onPrev={prevHit}
          onClose={closeFind}
        />
      )}
      {find.open && find.resultsOpen && resultItems.length > 0 && (
        <Results items={resultItems} index={findIndex} matcher={find.mode === "text" ? matcher : null} onPick={goToHit} />
      )}
      <div class="modebar" role="tablist" aria-label="View">
        {(["grid", "flat"] as const).map((m) => (
          <button
            key={m}
            role="tab"
            aria-selected={mode === m}
            class={"modetab" + (mode === m ? " active" : "")}
            title={m === "grid" ? "Tree and grid of repeating elements" : "Whole document as an outline sheet (name / value)"}
            onClick={() => switchMode(m)}
          >
            {m === "grid" ? "Grid" : "Flat"}
          </button>
        ))}
        {mode === "flat" && (
          <span class="modebar-actions">
            <button class="secondary small" onClick={() => setFlatCollapsed(new Set())} title="Expand all">
              Expand all
            </button>
            <button
              class="secondary small"
              title="Collapse all below the root"
              onClick={() => {
                const c = new Set<number>();
                for (let r = skeleton.firstRoot; r >= 0; r = skeleton.nextSibling[r])
                  for (let ch = skeleton.firstChild[r]; ch >= 0; ch = skeleton.nextSibling[ch]) c.add(ch);
                setFlatCollapsed(c);
              }}
            >
              Collapse all
            </button>
          </span>
        )}
      </div>
      {mode === "flat" && rowsForFlat && (
        <FlatView
          skeleton={skeleton}
          rows={rowsForFlat}
          values={flatValues}
          sel={flatSel}
          hits={hitSet}
          matcher={matcher}
          highlightNames={find.targets.names || find.targets.attrNames}
          highlightValues={find.targets.text || find.targets.attrValues}
          isCollapsed={(id) => flatCollapsed.has(id)}
          onNeedValues={needFlatValues}
          onToggle={(id, expand) =>
            setFlatCollapsed((prev) => {
              const next = new Set(prev);
              if (expand ?? prev.has(id)) next.delete(id);
              else next.add(id);
              return next;
            })
          }
          onSelect={setFlatSel}
          onActivate={(pos) => void activateFlat(pos)}
          onCopy={(sel) => void copyFlat(sel, rowsForFlat)}
          onInspect={(pos) => void inspectFlat(pos)}
        />
      )}
      {mode === "grid" && <Split
        ratio={split}
        onRatio={setSplit}
        left={
          <div class="tree-wrap" ref={treeWrap}>
            <Tree
              skeleton={skeleton}
              expanded={expanded}
              selected={selected}
              filter={treeFilter}
              hits={hitSet}
              matcher={matcher}
              highlightNames={find.targets.names}
              highlightPreview={find.targets.attrNames || find.targets.attrValues || find.targets.text}
              onToggle={(id, expand) =>
                setExpanded((prev) => {
                  const next = new Set(prev);
                  if (expand ?? !prev.has(id)) next.add(id);
                  else next.delete(id);
                  return next;
                })
              }
              onSelect={(id) => selectElement(id)}
              onActivate={activateElement}
              onInspect={(id) => void inspectElement(id)}
            />
          </div>
        }
        right={
          <div
            class="grid-wrap"
            ref={gridWrap}
            onKeyDown={(e) => {
              if ((e.key === "Backspace" || (e.altKey && e.key === "ArrowUp")) && (e.target as HTMLElement).tagName !== "INPUT") {
                const p = selected >= 0 ? skeleton.parent[selected] : -1;
                if (p >= 0) {
                  e.preventDefault();
                  selectElement(p, { group: skeleton.names[skeleton.nameIdx[selected]] });
                }
              }
            }}
          >
            <div class="toolbar">
              <nav class="breadcrumb" aria-label="Breadcrumb">
                {crumbs.map((id, i) => (
                  <span key={id} class="crumb-wrap">
                    {i > 0 && <span class="crumb-sep">›</span>}
                    <button
                      class={"crumb" + (id === selected ? " current" : "")}
                      onClick={() => selectElement(id, i < crumbs.length - 1 ? { group: skeleton.names[skeleton.nameIdx[crumbs[i + 1]]] } : {})}
                      title={pathKey(pathOfId(skeleton, id))}
                    >
                      {skeleton.names[skeleton.nameIdx[id]]}
                      {skeleton.parent[id] >= 0 && sameNameSiblings(skeleton, id) > 1 ? `[${indexAmongSameName(skeleton, id) + 1}]` : ""}
                    </button>
                  </span>
                ))}
                {table?.group && (
                  <span class="crumb-wrap">
                    <span class="crumb-sep">›</span>
                    {table.groups.length > 1 ? (
                      <select
                        class="group-select"
                        value={table.group}
                        aria-label="Tag group"
                        title="Child tag group"
                        onChange={(e) => chooseGroup((e.target as HTMLSelectElement).value)}
                      >
                        {table.groups.map((g) => (
                          <option key={g.name} value={g.name}>
                            {g.name} ({g.count})
                          </option>
                        ))}
                      </select>
                    ) : (
                      <span class="crumb group">
                        {table.group} ({table.groups[0]?.count ?? 0})
                      </span>
                    )}
                  </span>
                )}
              </nav>
              <div class="toolbar-right">
                {activeFilterCount > 0 && (
                  <button class="secondary small" onClick={() => setFilters({})} title="Clear all column filters">
                    Clear filters ({activeFilterCount})
                  </button>
                )}
                <input
                  class="quick-filter"
                  type="search"
                  placeholder="Filter rows"
                  aria-label="Quick filter"
                  value={quick}
                  onInput={(e) => setQuick((e.target as HTMLInputElement).value)}
                />
              </div>
            </div>
            {table ? (
              <Grid
                table={table}
                view={view}
                widths={widths.length === table.columns.length ? widths : table.columns.map(() => 120)}
                sort={sort}
                filters={filters}
                sel={gridSel}
                matcher={matcher}
                hitCells={hitCells}
                currentHit={currentGridHit}
                onSort={(key) =>
                  setSort((s) => (s?.key !== key ? { key, dir: 1 } : s.dir === 1 ? { key, dir: -1 } : null))
                }
                onOpenFilter={(col, anchor) => setFilterPopup({ col, anchor })}
                onResize={(col, w) => setWidths((ws) => ws.map((x, i) => (i === col ? w : x)))}
                onAutoFit={(col) => {
                  const f = fonts();
                  const w = autoFitWidth(table, view, col, f.font, f.header, f.mono);
                  setWidths((ws) => ws.map((x, i) => (i === col ? w : x)));
                }}
                onSelect={setGridSel}
                onActivate={activateCell}
                onDrill={drill}
                onCopy={copy}
                onInspect={inspectCell}
              />
            ) : (
              <div class="grid-empty">Select an element.</div>
            )}
            {table && (
              <div class="statusbar">
                <span>
                  {view.length === table.rowIds.length
                    ? `${table.rowIds.length.toLocaleString()} row${table.rowIds.length === 1 ? "" : "s"}`
                    : `${view.length.toLocaleString()} of ${table.rowIds.length.toLocaleString()} rows`}
                </span>
                {gridSel && (
                  <span>
                    {(() => {
                      const r = selectionRange(gridSel);
                      const n = (r.r1 - r.r0 + 1) * (r.c1 - r.c0 + 1);
                      return n > 1 ? `${n.toLocaleString()} cells selected` : "";
                    })()}
                  </span>
                )}
                <span class="muted">{fileName}</span>
              </div>
            )}
          </div>
        }
      />}
      {inspector && (
        <ValueInspector
          target={inspector}
          onClose={() => {
            setInspector(null);
            requestAnimationFrame(() => (document.querySelector(mode === "flat" ? ".flat" : ".grid") as HTMLElement | null)?.focus());
          }}
          onCopy={(text) => host.copy(text)}
        />
      )}
      {filterPopup && mode === "grid" && table && filterPopup.col < table.columns.length && (
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
            setGridSel(null);
            setFilterPopup(null);
          }}
          onClose={() => setFilterPopup(null)}
        />
      )}
    </div>
  );
}

function tsvCell(s: string): string {
  return /[\t\n\r"]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function sameNameSiblings(sk: TreeSkeleton, id: number): number {
  const p = sk.parent[id];
  let n = 0;
  for (let c = p >= 0 ? sk.firstChild[p] : sk.firstRoot; c >= 0; c = sk.nextSibling[c]) if (sk.nameIdx[c] === sk.nameIdx[id]) n++;
  return n;
}

function indexAmongSameName(sk: TreeSkeleton, id: number): number {
  const p = sk.parent[id];
  let n = 0;
  for (let c = p >= 0 ? sk.firstChild[p] : sk.firstRoot; c >= 0 && c !== id; c = sk.nextSibling[c]) if (sk.nameIdx[c] === sk.nameIdx[id]) n++;
  return n;
}
