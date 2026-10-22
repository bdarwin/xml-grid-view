import {
  ALL_TARGETS,
  SELF_GROUP,
  DEFAULT_OPTIONS,
  DEFAULT_SETTINGS,
  createMatcher,
  idAtPath,
  elementAtOffset,
  displayName,
  isJsonFileName,
  jsonTypeOf,
  isQName,
  miscDisplay,
  pathKey,
  pathOfId,
  parsePathKey,
  type DocSearchResult,
  type DocumentChanges,
  type Diagnostic,
  type EditTarget,
  type ElementPath,
  type TextEdit,
  type FlatValues,
  type GridTable,
  type HostToView,
  type RecordsInfo,
  type Matcher,
  type ParseError,
  type SearchQuery,
  type StructureOp,
  type ThemeVars,
  type TreeSkeleton,
  type ViewSettings,
  type XPathEvalResult,
} from "@xmlgridview/core";
import { useCallback, useEffect, useMemo, useRef, useState } from "preact/hooks";
import { FilterPopup } from "./components/FilterPopup";
import { FindBar, type FindState } from "./components/FindBar";
import { FlatView, flatMisc, flatRows, miscLabel, rowIndexOf, type FlatPos, type FlatSelection } from "./components/FlatView";
import { ContextMenu, type MenuItem } from "./components/ContextMenu";
import { Grid, selectionRange, type CellPos, type GridSelection } from "./components/Grid";
import { PromptDialog, type PromptDialogProps, type PromptField } from "./components/PromptDialog";
import { Results, type ResultItem } from "./components/Results";
import { Split } from "./components/Split";
import { RecordsView } from "./components/RecordsView";
import { Summary } from "./components/Summary";
import { Tree } from "./components/Tree";
import { ValueInspector, type InspectorTarget } from "./components/ValueInspector";
import { changeMarks, gridChanges } from "./changes";
import { autoFitWidth, computeView, selectionStats, statsText, isFilterActive, parseTsv, toCsv, toJsonRows, toTsv, type CellRange, type ColumnFilter, type Filters, type SortState } from "./gridView";
import type { HostBridge } from "./host";
import { StaleError, type WorkerClient } from "./workerClient";

type Phase = "waiting" | "large" | "loading" | "ready";
type ViewMode = "grid" | "flat";

/** Path plus tag name, so a restored path only applies when the element is still the same kind. */
const MOD = typeof navigator !== "undefined" && /Mac/.test(navigator.platform) ? { alt: "⌥", shift: "⇧", ctrl: "⌘" } : { alt: "Alt+", shift: "Shift+", ctrl: "Ctrl+" };

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
  if (chars < 1024 * 1024) return `${Math.max(1, Math.round(chars / 1024))} KB`;
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
  /** The selected element's one-row fields table, for the summary above a grid of its children. */
  const [fields, setFields] = useState<GridTable | null>(null);
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
  const [toast, setToast] = useState<string | null>(null);
  /** Problems the editor reports for the document (schema validation etc.). */
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  const [problemIndex, setProblemIndex] = useState(-1);
  /** Changes against the host's baseline (last commit / when opened); null when there is none. */
  const [changes, setChanges] = useState<DocumentChanges | null>(null);
  const [baselineLabel, setBaselineLabel] = useState<string | null>(null);
  /** The user picked the baseline (Compare with…): the counter shows even without differences. */
  const [baselineExplicit, setBaselineExplicit] = useState(false);
  /** The host supports comparing (it has sent a baseline and change highlighting is on). */
  const [canCompare, setCanCompare] = useState(false);
  const [baselineRev, setBaselineRev] = useState(0);
  const [changeIndex, setChangeIndex] = useState(-1);
  /** Records mode (files too large to load): the records' index from the host, and its progress before that. */
  const [records, setRecords] = useState<RecordsInfo | null>(null);
  const [recordsProgress, setRecordsProgress] = useState<{ bytes: number; total: number } | null>(null);
  const docVersionRef = useRef<number | undefined>(undefined);
  /** Host document version of the text the current model was parsed from. */
  const modelDocVersion = useRef<number | undefined>(undefined);
  const pendingEdit = useRef<((r: { ok: boolean; message?: string }) => void) | null>(null);
  const [nodeFilter, setNodeFilter] = useState("");
  const [nodeFilterHits, setNodeFilterHits] = useState<Set<number> | null>(null);

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
      flatFocus: s.flatSel && s.flatSel.focus.id >= 0 && s.flatSel.focus.id < sk.count ? [namedPath(sk, s.flatSel.focus.id), s.flatSel.focus.attr] : null,
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
    const version = docVersionRef.current;
    const snap = takeSnapshot();
    setPhase((p) => (p === "ready" ? p : "loading"));
    try {
      const r = await worker.request<"load">({ type: "load", text, format: formatRef.current });
      setErrors(r.errors);
      if (r.skeleton) {
        setKeptOld(false);
        applySnapshot(r.skeleton, snap);
        const want = selectAfterLoad.current;
        selectAfterLoad.current = null;
        const newId = want ? resolveNamed(r.skeleton, want) : -1;
        if (newId >= 0) {
          const sk = r.skeleton;
          setSelected(newId);
          setGroup(null);
          setExpanded((prev) => new Set([...prev, ...ancestors(sk, newId)]));
          setFlatCollapsed((prev) => new Set([...prev].filter((c) => !ancestors(sk, newId).includes(c))));
          setFlatSel({ anchor: { id: newId, attr: -1, col: 0 }, focus: { id: newId, attr: -1, col: 1 } });
        }
        const wantRow = gridSelectAfterLoad.current;
        gridSelectAfterLoad.current = null;
        pendingGridRow.current = wantRow ? { id: resolveNamed(r.skeleton, wantRow.row), c: wantRow.c } : null;
        pendingRestore.current = null;
        modelDocVersion.current = version;
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
  /** "json" when the document is JSON (members and items as elements); "xml" otherwise. */
  const formatRef = useRef<"xml" | "json">("xml");
  const [format, setFormat] = useState<"xml" | "json">("xml");

  useEffect(() => {
    host.onMessage((msg: HostToView) => {
      switch (msg.type) {
        case "init": {
          const s = { ...DEFAULT_SETTINGS, ...msg.settings };
          formatRef.current = msg.format ?? (isJsonFileName(msg.fileName) ? "json" : "xml");
          setFormat(formatRef.current);
          if (formatRef.current === "json") setFind((f) => (f.mode === "xpath" ? { ...f, mode: "text" } : f));
          setSettings(s);
          settingsRef.current = s;
          setFileName(msg.fileName);
          applyTheme(msg.theme);
          if (msg.records) {
            setRecords(msg.records);
            break;
          }
          textRef.current = msg.text;
          docVersionRef.current = msg.docVersion;
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
          docVersionRef.current = msg.docVersion;
          if (msg.text.length > settingsRef.current.largeFileThreshold && !largeConfirmed.current) setPhase("large");
          else scheduleLoad(!!msg.fromEdit); // our own edits re-parse immediately
          break;
        case "diagnostics":
          setDiagnostics(msg.items.filter((d) => d.severity !== "info"));
          break;
        case "recordsProgress":
          setRecordsProgress({ bytes: msg.bytes, total: msg.total });
          break;
        case "baseline": {
          const label = msg.label;
          const on = msg.text !== null;
          const explicit = !!msg.explicit;
          void worker.request<"baseline">({ type: "baseline", text: msg.text, format: formatRef.current }).then((parsed) => {
            if (!parsed) setToast(`Can't compare with ${label}: it is not well-formed.`);
            setBaselineLabel(on && parsed ? label : null);
            setBaselineExplicit(explicit);
            setCanCompare(on);
            setBaselineRev((r) => r + 1);
          });
          break;
        }
        case "editResult":
          pendingEdit.current?.(msg);
          pendingEdit.current = null;
          if (!msg.ok && msg.message) setToast(msg.message);
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

  // ----- problems (diagnostics) -------------------------------------------

  /** Problems mapped to elements, in document order; plus every ancestor of an element with a problem. */
  const problems = useMemo(() => {
    if (!skeleton || !diagnostics.length) return null;
    const list = diagnostics
      .map((d) => ({ d, id: elementAtOffset(skeleton, d.offset) }))
      .filter((x) => x.id >= 0)
      .sort((a, b) => a.d.offset - b.d.offset);
    const byElement = new Map<number, Diagnostic[]>();
    const within = new Set<number>();
    for (const x of list) {
      const l = byElement.get(x.id);
      if (l) l.push(x.d);
      else byElement.set(x.id, [x.d]);
      for (let a = skeleton.parent[x.id]; a >= 0 && !within.has(a); a = skeleton.parent[a]) within.add(a);
    }
    return { list, byElement, within, errors: list.filter((x) => x.d.severity === "error").length };
  }, [skeleton, diagnostics]);

  useEffect(() => setProblemIndex(-1), [problems]);

  // ----- changes against the baseline ---------------------------------------

  useEffect(() => {
    if (!baselineLabel || !skeleton) {
      setChanges(null);
      return;
    }
    let cancelled = false;
    worker
      .request<"changes">({ type: "changes", gen })
      .then((c) => !cancelled && setChanges(c))
      .catch((e) => {
        if (!(e instanceof StaleError) && !cancelled) setChanges(null);
      });
    return () => {
      cancelled = true;
    };
  }, [gen, skeleton, baselineLabel, baselineRev]);

  const marks = useMemo(() => (skeleton && changes && baselineLabel ? changeMarks(skeleton, changes, baselineLabel) : null), [skeleton, changes, baselineLabel]);
  const changeDetails = useMemo(() => new Map((changes?.modified ?? []).map((c) => [c.id, c])), [changes]);
  useEffect(() => setChangeIndex(-1), [marks]);

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

  useEffect(() => {
    if (!table || table.group === null || table.group === SELF_GROUP) {
      setFields(table?.group === SELF_GROUP ? table : null);
      return;
    }
    let cancelled = false;
    worker
      .request<"table">({ type: "table", gen, elementId: table.elementId, group: SELF_GROUP })
      .then((t) => !cancelled && setFields(t))
      .catch(() => !cancelled && setFields(null));
    return () => {
      cancelled = true;
    };
  }, [table]);

  const view = useMemo(() => (table ? computeView(table, quick, filters, sort) : new Int32Array(0)), [table, quick, filters, sort]);

  /**
   * Problems on grid cells (table coordinates r * ncols + c) and rows: a problem inside a row's
   * child goes to that child's column, one in an attribute to its column, the rest to the row.
   */
  const gridProblems = useMemo(() => {
    if (!table || !problems || !skeleton) return null;
    const ncols = table.columns.length;
    const rowOf = new Map<number, number>();
    table.rowIds.forEach((id, r) => rowOf.set(id, r));
    const cells = new Map<number, Diagnostic[]>();
    const rows = new Map<number, Diagnostic[]>();
    const add = (m: Map<number, Diagnostic[]>, k: number, d: Diagnostic) => {
      const l = m.get(k);
      if (l) l.push(d);
      else m.set(k, [d]);
    };
    for (const { d, id } of problems.list) {
      let child = -1;
      let e = id;
      while (e >= 0 && !rowOf.has(e)) {
        child = e;
        e = skeleton.parent[e];
      }
      if (e < 0) continue;
      const r = rowOf.get(e)!;
      let c = -1;
      if (child >= 0) {
        const name = skeleton.names[skeleton.nameIdx[child]];
        c = table.columns.findIndex((col) => col.key === name);
      } else {
        for (let k = 0; k < ncols && c < 0; k++) {
          if (table.columns[k].kind !== "attr") continue;
          const at = table.spans[(r * ncols + k) * 2];
          if (at >= 0 && d.offset >= at && d.offset < at + table.spans[(r * ncols + k) * 2 + 1]) c = k;
        }
      }
      if (c >= 0) add(cells, r * ncols + c, d);
      else add(rows, r, d);
    }
    return { cells, rows };
  }, [table, problems, skeleton]);

  const gridChangeMarks = useMemo(() => (table && marks && skeleton ? gridChanges(table, skeleton, marks, changeDetails) : null), [table, marks, skeleton, changeDetails]);

  // After a grid edit (new row, move, paste), select the row it was about once the grid is rebuilt.
  useEffect(() => {
    const want = pendingGridRow.current;
    if (!want || !table) return;
    const r = table.rowIds.indexOf(want.id);
    if (r < 0) return;
    pendingGridRow.current = null;
    const v = view.indexOf(r);
    if (v >= 0) {
      const c = Math.min(Math.max(0, want.c), table.columns.length - 1);
      setGridSel({ anchor: { v, c }, focus: { v, c } });
      // Keep typing in the grid (a menu or dialog may have taken focus).
      gridWrap.current?.querySelector<HTMLElement>(".grid")?.focus({ preventScroll: true });
    }
  }, [table, view]);

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

  /** Tree selection while in Flat: select the element there too and scroll it into view. */
  const showInFlat = (id: number) => {
    if (!skeleton) return;
    selectElement(id);
    revealFlat(skeleton, id);
    setFlatSel({ anchor: { id, attr: -1, col: 0 }, focus: { id, attr: -1, col: 1 } });
  };

  /** Flat selection: keeps the tree selection on the focused row's element. */
  const selectFlat = (sel: FlatSelection | null) => {
    setFlatSel(sel);
    if (sel && skeleton && sel.focus.id >= 0 && sel.focus.id !== selected) selectElement(sel.focus.id, { reveal: true });
  };

  const activateFlat = async (pos: FlatPos) => {
    if (!skeleton) return;
    const m = flatMisc(skeleton, pos.id);
    if (m) return navigateTo(m.start, m.end - m.start);
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
    const ids = [...new Set(Array.from(rows.ids.subarray(a, b + 1)))].filter((id) => id >= 0);
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
      const m = flatMisc(skeleton, id);
      const v = get(id);
      const cells: string[] = [];
      if (withName) cells.push(tsvCell(m ? miscLabel(m) : attr >= 0 ? "@" + (v?.attrs[attr]?.name ?? "") : skeleton.names[skeleton.nameIdx[id]]));
      // Values are copied on one line, as displayed.
      if (withValue) cells.push(tsvCell(((m ? m.value : attr >= 0 ? v?.attrs[attr]?.value : v?.text) ?? "").replace(/\s+/g, " ")));
      lines.push(cells.join("\t"));
    }
    host.copy(lines.join("\n"));
  };

  const switchMode = (m: ViewMode) => {
    if (m === mode || !skeleton) return;
    if (m === "flat" && selected >= 0) {
      revealFlat(skeleton, selected);
      setFlatSel({ anchor: { id: selected, attr: -1, col: 0 }, focus: { id: selected, attr: -1, col: 1 } });
    } else if (m === "grid" && flatSel && flatSel.focus.id >= 0) {
      selectElement(flatSel.focus.id, { reveal: true });
    }
    setMode(m);
    requestAnimationFrame(() => (document.querySelector(m === "flat" ? ".flat" : ".grid, .tree") as HTMLElement | null)?.focus());
  };

  // ----- value editing --------------------------------------------------------

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(t);
  }, [toast]);

  const canEditDoc = !settings.readOnly && errors.length === 0;

  /** Asks the host to apply text edits as one undoable step; resolves with the host's answer. */
  const postEdits = (edits: { offset: number; length: number; text: string }[], label: string) =>
    new Promise<{ ok: boolean; message?: string }>((resolve) => {
      pendingEdit.current = resolve;
      host.post({ type: "edit", docVersion: modelDocVersion.current, edits, label });
      setTimeout(() => resolve({ ok: false, message: "The editor did not respond to the edit." }), 10_000);
    });

  // ----- structural edits (context menu) ---------------------------------------

  const [menu, setMenu] = useState<{ x: number; y: number; items: MenuItem[]; note?: string } | null>(null);
  const [prompt, setPrompt] = useState<PromptDialogProps | null>(null);
  /** Element to select once the edited document has been re-parsed (e.g. the one just inserted). */
  const selectAfterLoad = useRef<NamedPath | null>(null);
  /** Grid row (and column) to select after a grid edit, staying on the same grid. */
  const gridSelectAfterLoad = useRef<{ row: NamedPath; c: number } | null>(null);
  const pendingGridRow = useRef<{ id: number; c: number } | null>(null);

  /** Computes several structural edits on the same model and applies them as one undoable step. */
  const requestOps = async (ops: StructureOp[], label: string, gridRow?: { row: NamedPath; c: number }) => {
    try {
      const edits: TextEdit[] = [];
      for (const op of ops) {
        const r = await worker.request<"structure">({ type: "structure", gen, op });
        if ("error" in r) return setToast(r.error);
        edits.push(...r.edits);
      }
      if (!edits.length) return;
      edits.sort((a, b) => b.offset - a.offset);
      gridSelectAfterLoad.current = gridRow ?? null;
      const result = await postEdits(edits, label);
      if (!result.ok) {
        gridSelectAfterLoad.current = null;
        setToast(result.message ?? "The edit was not applied.");
      }
    } catch (e) {
      setToast(e instanceof StaleError ? "The document changed while editing; please try again." : String((e as Error).message ?? e));
    }
  };

  /** The grid's rows are children of the shown element sharing one tag (not the fields or leaf view). */
  const isRowGrid = (t: GridTable | null): t is GridTable => !!t && t === table && t.group !== null && t.group !== SELF_GROUP;

  /** View rows covered by the grid selection, or just `v` when it lies outside the selection. */
  const selectedViewRows = (v: number): number[] => {
    if (gridSel) {
      const r = selectionRange(gridSel);
      if (v >= r.r0 && v <= r.r1) return Array.from({ length: r.r1 - r.r0 + 1 }, (_, i) => r.r0 + i);
    }
    return [v];
  };

  const rowNamedPath = (id: number): NamedPath => [pathKey(pathOfId(skeleton!, id)), skeleton!.names[skeleton!.nameIdx[id]]];

  /** The nearest sibling of `id` with the same name before (up) or after it, or -1. */
  const sameNameSibling = (id: number, up: boolean): number => {
    const sk = skeleton!;
    const name = sk.nameIdx[id];
    let prev = -1;
    for (let c = sk.firstChild[sk.parent[id]]; c >= 0; c = sk.nextSibling[c]) {
      if (sk.nameIdx[c] !== name) continue;
      if (c === id) {
        if (up) return prev;
      } else if (!up && prev === id) return c;
      prev = c;
    }
    return -1;
  };

  const moveRow = (v: number, up: boolean, c = 0) => {
    if (!isRowGrid(table) || !skeleton || !canEditDoc) return;
    const r = view[v];
    if (r === undefined) return;
    const id = table.rowIds[r];
    const other = sameNameSibling(id, up);
    if (other < 0) return;
    // After the swap, the moved row sits where its sibling was.
    void requestOps([{ kind: "moveElement", path: pathOfId(skeleton, id), direction: up ? "up" : "down", sameName: true }], `Move <${table.group}> ${up ? "Up" : "Down"}`, {
      row: rowNamedPath(other),
      c,
    });
  };

  /** Empties the selected cells that have a value (complex cells and absent values are left alone). */
  const clearCells = (range: CellRange) => {
    if (!table || !skeleton || !canEditDoc) return;
    const cells: { path: ElementPath; key: string; value: string }[] = [];
    for (let v = range.r0; v <= range.r1; v++) {
      const r = view[v];
      if (r === undefined) continue;
      for (let c = range.c0; c <= range.c1; c++) {
        const col = table.columns[c];
        const raw = table.cells[r * table.columns.length + c];
        if (typeof raw !== "string" || raw === "" || col.kind === "complex") continue;
        if (!cellEditTarget(table, r, c)) continue;
        cells.push({ path: pathOfId(skeleton, table.rowIds[r]), key: col.key, value: "" });
      }
    }
    if (cells.length) void requestOps([{ kind: "setCells", cells }], cells.length === 1 ? "Clear Cell" : `Clear ${cells.length} Cells`);
  };

  /**
   * Pastes tab-separated text at `focus`: values go into the cells right and down from it (missing
   * attributes and children are added), rows beyond the last become new rows. A header row that
   * matches the column labels is skipped; nested-table columns are left alone.
   */
  const pasteGrid = async (focus: CellPos) => {
    if (!table || !skeleton) return;
    if (!canEditDoc) return setToast(settings.readOnly ? "This document is read-only." : "Fix the XML errors to edit.");
    let rows = parseTsv(await host.readClipboard());
    if (!rows.length) return setToast("The clipboard is empty.");
    const labels = table.columns.slice(focus.c).map((c) => c.label);
    if (rows.length > 1 && rows[0].every((h, j) => h === labels[j])) rows = rows.slice(1);
    const rowGrid = isRowGrid(table);
    const cells: { path: ElementPath; key: string; value: string }[] = [];
    const added: Record<string, string>[] = [];
    rows.forEach((values, i) => {
      const v = focus.v + i;
      const r = view[v];
      if (r === undefined && (!rowGrid || v < view.length)) return;
      const rec: Record<string, string> = {};
      values.forEach((value, j) => {
        const col = table.columns[focus.c + j];
        if (!col || col.kind === "complex") return;
        if (r !== undefined) cells.push({ path: pathOfId(skeleton, table.rowIds[r]), key: col.key, value });
        else rec[col.key] = value;
      });
      if (r === undefined && Object.keys(rec).length) added.push(rec);
    });
    const ops: StructureOp[] = [];
    if (cells.length) ops.push({ kind: "setCells", cells });
    if (added.length && rowGrid) ops.push({ kind: "insertRows", path: pathOfId(skeleton, table.elementId), group: table.group!, rows: added });
    if (!ops.length) return setToast("Nothing to paste here.");
    const n = rows.length;
    void requestOps(ops, n === 1 ? "Paste" : `Paste ${n} Rows`);
  };

  const exportGrid = (kind: "csv" | "json") => {
    if (!table || !skeleton) return;
    const base = (fileName.replace(/^.*[\\/]/, "").replace(/\.[^.]*$/, "") || "export") + "-" + (table.group && table.group !== SELF_GROUP ? table.group : skeleton.names[skeleton.nameIdx[table.elementId]]);
    host.post({ type: "save", fileName: `${base.replace(/[^\w.-]+/g, "_")}.${kind}`, text: kind === "csv" ? toCsv(table, view) : toJsonRows(table, view) });
  };

  /** Row and column actions for a cell of a row grid, shown above the element actions. */
  const gridRowItems = (t: GridTable, r: number, c: number): MenuItem[] => {
    if (!isRowGrid(t) || !skeleton) return [];
    const ro = !canEditDoc;
    const v = view.indexOf(r);
    const id = t.rowIds[r];
    const rowPath = pathOfId(skeleton, id);
    const parentPath = pathOfId(skeleton, t.elementId);
    const group = t.group!;
    const col = c >= 0 ? t.columns[c] : null;
    const rows = selectedViewRows(v).map((x) => t.rowIds[view[x]]);
    const items: MenuItem[] = [
      {
        label: "Insert Row Below",
        disabled: ro,
        onClick: () =>
          void requestOps([{ kind: "newRow", path: rowPath }], `New <${group}>`, {
            row: [pathKey([...rowPath.slice(0, -1), rowPath[rowPath.length - 1] + 1]), group],
            c: Math.max(0, c),
          }),
      },
      { label: "Move Row Up", hint: `${MOD.alt}${MOD.shift}↑`, disabled: ro || sameNameSibling(id, true) < 0, onClick: () => moveRow(v, true, Math.max(0, c)) },
      { label: "Move Row Down", hint: `${MOD.alt}${MOD.shift}↓`, disabled: ro || sameNameSibling(id, false) < 0, onClick: () => moveRow(v, false, Math.max(0, c)) },
      {
        label: rows.length === 1 ? "Delete Row" : `Delete ${rows.length} Rows`,
        danger: true,
        disabled: ro,
        onClick: () =>
          void requestOps([{ kind: "deleteElements", paths: rows.map((x) => pathOfId(skeleton, x)) }], rows.length === 1 ? "Delete Row" : `Delete ${rows.length} Rows`),
      },
      "separator",
      {
        label: "Paste",
        hint: `${MOD.ctrl}V`,
        disabled: ro,
        onClick: () => void pasteGrid({ v, c: Math.max(0, c) }),
      },
      {
        label: "Clear Cells",
        hint: "Del",
        disabled: ro,
        onClick: () => {
          const s = gridSel ? selectionRange(gridSel) : null;
          const inSel = s && v >= s.r0 && v <= s.r1 && c >= s.c0 && c <= s.c1;
          clearCells(inSel ? s : { r0: v, r1: v, c0: Math.max(0, c), c1: Math.max(0, c) });
        },
      },
      {
        label: "Add Column…",
        disabled: ro,
        onClick: () =>
          setPrompt({
            title: skeleton.json ? "New column" : `New column for <${group}>`,
            fields: [
              skeleton.json
                ? { label: "Key", initial: "", validate: (x) => (x ? null : "Enter a key.") }
                : {
                    label: "Name (@name for an attribute)",
                    initial: "",
                    validate: (x) => (isQName(x.trim().replace(/^@/, "")) ? null : "Not a valid XML name."),
                  },
            ],
            okLabel: "Add",
            onCancel: () => setPrompt(null),
            onSubmit: ([n]) => {
              setPrompt(null);
              const col = skeleton.json ? n : n.trim();
              void requestOps([{ kind: "addColumn", path: parentPath, group, name: col }], `Add Column ${col}`);
            },
          }),
      },
    ];
    if (col && col.kind !== "text") {
      items.push({
        label: `Delete Column ${col.label}`,
        danger: true,
        disabled: ro,
        onClick: () => void requestOps([{ kind: "deleteColumn", path: parentPath, group, key: col.key }], `Delete Column ${col.label}`),
      });
    }
    items.push("separator");
    return items;
  };

  /** Computes a structural edit in the worker and asks the host to apply it. */
  const requestStructure = async (op: StructureOp, label: string, select?: NamedPath) => {
    try {
      const r = await worker.request<"structure">({ type: "structure", gen, op });
      if ("error" in r) return setToast(r.error);
      if (!r.edits.length) return;
      selectAfterLoad.current = select ?? null;
      const result = await postEdits(r.edits, label);
      if (!result.ok) {
        selectAfterLoad.current = null;
        setToast(result.message ?? "The edit was not applied.");
      }
    } catch (e) {
      setToast(e instanceof StaleError ? "The document changed while editing; please try again." : String((e as Error).message ?? e));
    }
  };

  /** Opens the element (and attribute) actions menu at (x, y). */
  const openStructureMenu = (id: number, attr: string | null, x: number, y: number, extra: MenuItem[] = []) => {
    if (!skeleton || id < 0) return;
    const sk = skeleton;
    const path = pathOfId(sk, id);
    const name = sk.names[sk.nameIdx[id]];
    const tag = `<${name}>`;
    const isRoot = sk.parent[id] < 0;
    const ro = !canEditDoc;
    const parentPath = path.slice(0, -1);
    const next = [...parentPath, path[path.length - 1] + 1];
    const nameField = (label: string, initial = ""): PromptField => ({
      label,
      initial,
      validate: (v) => (isQName(v.trim()) ? null : "Not a valid XML name."),
    });
    const ask = (title: string, fields: PromptField[], okLabel: string, run: (values: string[]) => void) =>
      setPrompt({
        title,
        fields,
        okLabel,
        onCancel: () => setPrompt(null),
        onSubmit: (values) => {
          setPrompt(null);
          run(values);
        },
      });
    const insert = (where: "before" | "after" | "child", title: string, selectPath: number[]) => () =>
      ask(title, [nameField("Element name")], "Insert", ([n]) =>
        void requestStructure({ kind: "insertElement", path, where, name: n.trim() }, `Insert <${n.trim()}>`, [pathKey(selectPath), n.trim()]),
      );
    const note = ro ? (settings.readOnly ? "This document is read-only." : `Fix the ${sk.json ? "JSON" : "XML"} errors to edit.`) : undefined;
    if (sk.json) {
      // JSON: members have keys, array items don't; no attributes.
      const type = jsonTypeOf(sk, id);
      const parentType = isRoot ? null : jsonTypeOf(sk, sk.parent[id]);
      const label = `"${displayName(sk, id)}"`;
      const keyField = (initial = ""): PromptField => ({ label: "Key", initial, validate: (v) => (v ? null : "Enter a key.") });
      const add = (where: "before" | "after" | "child", container: string | null, selectPath: number[], title: string) => () => {
        if (container === "array") return void requestStructure({ kind: "insertElement", path, where, name: "item" }, "Insert Item", [pathKey(selectPath), "item"]);
        ask(title, [keyField()], "Insert", ([k]) => void requestStructure({ kind: "insertElement", path, where, name: k }, `Add "${k}"`, [pathKey(selectPath), k]));
      };
      const jsonItems: MenuItem[] = [
        ...extra,
        {
          label: type === "array" ? "Add Item" : "Add Member…",
          disabled: ro || (type !== "array" && type !== "object"),
          onClick: add("child", type, [...path, sk.childCount[id]], `New member of ${label}`),
        },
        {
          label: parentType === "array" ? "Insert Item Before" : "Insert Member Before…",
          disabled: ro || isRoot,
          onClick: add("before", parentType, path, `New member before ${label}`),
        },
        {
          label: parentType === "array" ? "Insert Item After" : "Insert Member After…",
          disabled: ro || isRoot,
          onClick: add("after", parentType, next, `New member after ${label}`),
        },
        {
          label: "Duplicate",
          disabled: ro || parentType !== "array",
          onClick: () => void requestStructure({ kind: "duplicateElement", path }, "Duplicate Item", [pathKey(next), name]),
        },
        "separator",
        {
          label: "Rename Key…",
          disabled: ro || parentType !== "object",
          onClick: () =>
            ask(`Rename ${label}`, [keyField(name)], "Rename", ([k]) =>
              void requestStructure({ kind: "renameElement", path, name: k }, `Rename "${name}" to "${k}"`, [pathKey(path), k]),
            ),
        },
        "separator",
        {
          label: `Delete ${label}`,
          danger: true,
          disabled: ro || isRoot,
          onClick: () =>
            void requestStructure({ kind: "deleteElement", path }, `Delete ${label}`, isRoot ? undefined : [pathKey(parentPath), sk.names[sk.nameIdx[sk.parent[id]]]]),
        },
      ];
      return setMenu({ x, y, items: jsonItems, note });
    }
    const items: MenuItem[] = [
      ...extra,
      { label: "Insert Child Element…", disabled: ro, onClick: insert("child", `New child of ${tag}`, [...path, sk.childCount[id]]) },
      { label: "Insert Element Before…", disabled: ro || isRoot, onClick: insert("before", `New element before ${tag}`, path) },
      { label: "Insert Element After…", disabled: ro || isRoot, onClick: insert("after", `New element after ${tag}`, next) },
      {
        label: "Duplicate",
        disabled: ro || isRoot,
        onClick: () => void requestStructure({ kind: "duplicateElement", path }, `Duplicate ${tag}`, [pathKey(next), name]),
      },
      "separator",
      {
        label: "Rename…",
        disabled: ro,
        onClick: () =>
          ask(`Rename ${tag}`, [nameField("New name", name)], "Rename", ([n]) =>
            void requestStructure({ kind: "renameElement", path, name: n.trim() }, `Rename ${tag} to <${n.trim()}>`, [pathKey(path), n.trim()]),
          ),
      },
      {
        label: "Add Attribute…",
        disabled: ro,
        onClick: () =>
          ask(`New attribute on ${tag}`, [nameField("Attribute name"), { label: "Value" }], "Add", ([n, v]) =>
            void requestStructure({ kind: "addAttr", path, name: n.trim(), value: v }, `Add @${n.trim()}`),
          ),
      },
    ];
    if (attr) {
      items.push({
        label: `Delete Attribute @${attr}`,
        danger: true,
        disabled: ro,
        onClick: () => void requestStructure({ kind: "deleteAttr", path, name: attr }, `Delete @${attr}`),
      });
    }
    items.push("separator", {
      label: `Delete ${tag}`,
      danger: true,
      disabled: ro || isRoot,
      onClick: () =>
        void requestStructure({ kind: "deleteElement", path }, `Delete ${tag}`, isRoot ? undefined : [pathKey(parentPath), sk.names[sk.nameIdx[sk.parent[id]]]]),
    });
    setMenu({ x, y, items, note });
  };

  /** Grid cell → the element it shows: a leaf cell's child element, else the row element (with its attribute for @ cells). */
  const openGridMenu = (pos: CellPos, x: number, y: number) => {
    if (table) openCellMenu(table, view[pos.v], pos.c, x, y);
  };

  const openCellMenu = (t: GridTable, r: number | undefined, c: number, x: number, y: number) => {
    if (!skeleton || r === undefined) return;
    const rowId = t.rowIds[r];
    const col = c >= 0 ? t.columns[c] : null;
    const raw = col ? t.cells[r * t.columns.length + c] : null;
    const extra = gridRowItems(t, r, c);
    if (col?.kind === "attr" && raw !== null) return openStructureMenu(rowId, col.label.slice(1), x, y, extra);
    if (col?.kind === "leaf" && raw !== null) {
      const child = childNamed(rowId, col.label);
      if (child >= 0) return openStructureMenu(child, null, x, y, extra);
    }
    openStructureMenu(rowId, null, x, y, extra);
  };

  /** Computes the text edit in the worker and asks the host to apply it (one undoable step). */
  const requestEdit = async (target: EditTarget, value: string, label: string): Promise<string | null> => {
    try {
      const r = await worker.request<"edit">({ type: "edit", gen, target, value });
      if ("error" in r) {
        setToast(r.error);
        return r.error;
      }
      const result = await postEdits([r.edit], label);
      return result.ok ? null : (result.message ?? "The edit was not applied.");
    } catch (e) {
      const message = e instanceof StaleError ? "The document changed while editing; please try again." : String((e as Error).message ?? e);
      setToast(message);
      return message;
    }
  };

  /** The child element of `parent` named `name` (leaf columns have at most one). */
  const childNamed = (parent: number, name: string): number => {
    if (!skeleton) return -1;
    for (let c = skeleton.firstChild[parent]; c >= 0; c = skeleton.nextSibling[c]) if (skeleton.names[skeleton.nameIdx[c]] === name) return c;
    return -1;
  };

  /** Edit target for a grid cell, or null when it is not editable (absent, complex, has child elements). */
  const gridEditTarget = (pos: CellPos) => (table ? cellEditTarget(table, view[pos.v], pos.c) : null);

  /** Values the host's editor suggests for an edit target (completion at the value: a schema's enumeration, …). */
  const suggestFor = async (target: EditTarget | undefined): Promise<string[]> => {
    if (!target) return [];
    try {
      const offset = await worker.request<"valueOffset">({ type: "valueOffset", gen, target });
      return offset === null ? [] : await host.suggest(offset, modelDocVersion.current);
    } catch {
      return [];
    }
  };

  const cellEditTarget = (t: GridTable, r: number | undefined, c: number): { target: EditTarget; label: string } | null => {
    if (!skeleton || !canEditDoc || c < 0 || r === undefined) return null;
    const col = t.columns[c];
    const raw = t.cells[r * t.columns.length + c];
    const rowId = t.rowIds[r];
    if (col.kind === "attr" && typeof raw === "string") {
      return { target: { kind: "attr", path: pathOfId(skeleton, rowId), name: col.label.slice(1) }, label: `Edit ${col.label}` };
    }
    if (col.kind === "leaf" && typeof raw === "string") {
      const child = childNamed(rowId, col.label);
      if (child >= 0 && skeleton.childCount[child] === 0) return { target: { kind: "text", path: pathOfId(skeleton, child) }, label: `Edit ${col.label}` };
    }
    if (col.kind === "text" && skeleton.childCount[rowId] === 0) {
      return { target: { kind: "text", path: pathOfId(skeleton, rowId) }, label: `Edit ${skeleton.names[skeleton.nameIdx[rowId]]}` };
    }
    return null;
  };

  const flatEditTarget = (pos: FlatPos): { target: EditTarget; label: string } | null => {
    if (!skeleton || !canEditDoc || pos.id < 0) return null;
    if (pos.attr >= 0) {
      const name = flatValues.get(pos.id)?.attrs[pos.attr]?.name;
      return name ? { target: { kind: "attr", path: pathOfId(skeleton, pos.id), name }, label: `Edit @${name}` } : null;
    }
    if (skeleton.childCount[pos.id] > 0) return null;
    return { target: { kind: "text", path: pathOfId(skeleton, pos.id) }, label: `Edit ${skeleton.names[skeleton.nameIdx[pos.id]]}` };
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
    if (table) inspectTableCell(table, view[pos.v], pos.c);
  };

  const inspectTableCell = (t: GridTable, r: number | undefined, c: number) => {
    if (r === undefined || c < 0) return;
    const col = t.columns[c];
    const raw = t.cells[r * t.columns.length + c];
    if (typeof raw !== "string") return;
    const rowId = t.rowIds[r];
    const editable = cellEditTarget(t, r, c);
    setInspector({
      title: col.kind === "text" ? elementTitle(rowId) : `${elementTitle(rowId)} › ${col.label}`,
      text: raw,
      onSave: editable ? (value) => requestEdit(editable.target, value, editable.label) : undefined,
    });
  };

  const inspectFlat = async (pos: FlatPos) => {
    const m = skeleton ? flatMisc(skeleton, pos.id) : null;
    if (m) return setInspector({ title: miscDisplay(m).slice(0, 60), text: m.value });
    const v = await elementValues(pos.id);
    if (!v) return;
    const a = pos.attr >= 0 ? v.attrs[pos.attr] : null;
    const editable = flatEditTarget(pos);
    setInspector({
      title: a ? `${elementTitle(pos.id)} › @${a.name}` : elementTitle(pos.id),
      text: a ? a.value : v.text,
      onSave: editable ? (value) => requestEdit(editable.target, value, editable.label) : undefined,
    });
  };

  const inspectElement = async (id: number) => {
    const v = await elementValues(id);
    const editable = flatEditTarget({ id, attr: -1 });
    if (v) setInspector({ title: elementTitle(id), text: v.text, onSave: editable ? (value) => requestEdit(editable.target, value, editable.label) : undefined });
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

  // "Filter nodes" box above the tree: tag names, attribute names/values and text, case-insensitive.
  useEffect(() => {
    const q = nodeFilter.trim();
    if (!q || !skeleton) {
      setNodeFilterHits(null);
      return;
    }
    let cancelled = false;
    const t = setTimeout(() => {
      worker
        .request<"searchDoc">({ type: "searchDoc", gen, query: { text: q, options: { ...DEFAULT_OPTIONS }, targets: { ...ALL_TARGETS } } })
        .then((r) => {
          if (cancelled) return;
          const keep = new Set<number>();
          for (const m of r.matches) for (let a = m.elementId; a >= 0 && !keep.has(a); a = skeleton.parent[a]) keep.add(a);
          setNodeFilterHits(keep);
        })
        .catch(() => undefined);
    }, settings.debounceMs);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [nodeFilter, gen, skeleton]);

  /** Tree filter: the filter box and Find's "show only matches" combined (both must match). */
  const treeViewFilter = useMemo(() => {
    if (!nodeFilterHits) return treeFilter;
    if (!treeFilter) return nodeFilterHits;
    const both = new Set<number>();
    for (const id of nodeFilterHits) if (treeFilter.has(id)) both.add(id);
    return both;
  }, [nodeFilterHits, treeFilter]);

  const rowsForFlat = useMemo(
    () => (mode === "flat" && skeleton ? flatRows(skeleton, flatCollapsed, treeViewFilter) : null),
    [mode, skeleton, flatCollapsed, treeViewFilter],
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

  /** Shows problem `i` (wrapping): its element in the current view, with the message. */
  const goToProblem = (i: number) => {
    if (!problems || !skeleton || !problems.list.length) return;
    const n = problems.list.length;
    const idx = ((i % n) + n) % n;
    setProblemIndex(idx);
    const { d, id } = problems.list[idx];
    setToast(`${d.severity === "error" ? "Error" : "Warning"} ${idx + 1}/${n}: ${d.message}`);
    revealInView(id);
  };
  /** Shows element `id`: in Flat, its row; otherwise its row in the grid of its siblings. */
  const revealInView = (id: number) => {
    if (!skeleton) return;
    if (mode === "flat") {
      revealFlat(skeleton, id);
      setFlatSel({ anchor: { id, attr: -1, col: 0 }, focus: { id, attr: -1, col: 1 } });
      selectElement(id, { reveal: true });
      return;
    }
    const p = skeleton.parent[id];
    if (p < 0) return selectElement(id, { reveal: true });
    const name = skeleton.names[skeleton.nameIdx[id]];
    if (table && table.elementId === p && table.group === name) {
      const r = table.rowIds.indexOf(id);
      const v = view.indexOf(r);
      if (v >= 0) setGridSel({ anchor: { v, c: 0 }, focus: { v, c: 0 } });
      return;
    }
    pendingGridRow.current = { id, c: 0 };
    selectElement(p, { group: name, reveal: true });
  };
  /** Shows change `i` (wrapping), with what changed. */
  const goToChange = (i: number) => {
    if (!marks || !skeleton || !marks.list.length) return;
    const n = marks.list.length;
    const idx = ((i % n) + n) % n;
    setChangeIndex(idx);
    const stop = marks.list[idx];
    const own = marks.byElement.get(stop.id);
    if (stop.removed) {
      const { parent, items } = stop.removed;
      setToast(`Change ${idx + 1}/${n}: removed ${items.map((r) => (r.preview ? `${r.name} (${r.preview})` : r.name)).join(", ")}`);
      // The parent's grid of the removed rows' tag, with the row after the gap selected.
      if (mode !== "flat" && parent >= 0) {
        if (stop.id !== parent) pendingGridRow.current = { id: stop.id, c: 0 };
        return selectElement(parent, { group: items[0].name, reveal: true });
      }
    } else setToast(`Change ${idx + 1}/${n}: ${displayName(skeleton, stop.id)} — ${own ? own.title.split("\n").slice(0, 3).join(" · ") : ""}`);
    revealInView(stop.id);
  };
  const changeNav = useRef(goToChange);
  changeNav.current = (step: number) => goToChange(changeIndex < 0 ? (step > 0 ? 0 : -1) : changeIndex + step);
  const problemNav = useRef(goToProblem);
  problemNav.current = (step: number) => goToProblem(problemIndex < 0 ? (step > 0 ? 0 : -1) : problemIndex + step);
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
      } else if (e.key === "F5" && e.altKey) {
        e.preventDefault();
        changeNav.current(e.shiftKey ? -1 : 1);
      } else if (e.key === "F8") {
        e.preventDefault();
        problemNav.current(e.shiftKey ? -1 : 1);
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

  if (records) return <RecordsView host={host} info={records} fileName={fileName} copyWithHeader={!!settings.copyWithHeader} />;

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
          <p class="muted">
            {recordsProgress
              ? `Indexing records… ${Math.floor((100 * recordsProgress.bytes) / Math.max(1, recordsProgress.total))}% of ${formatSize(recordsProgress.total)}`
              : phase === "waiting"
                ? "Waiting for document…"
                : "Parsing…"}
          </p>
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
            {format === "json" ? "JSON is not valid" : "XML is not well-formed"}: {err.message} (line {err.line}, column {err.column}).{" "}
            {keptOld ? "Showing the last valid version." : "Showing a partial tree."}
            {errors.length > 1 ? ` ${errors.length - 1} more error${errors.length > 2 ? "s" : ""}.` : ""}
          </span>
        </button>
      )}
      {find.open && (
        <FindBar
          xpath={format !== "json"}
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
        {canCompare && (
          <button
            class={"compare-btn" + (marks && (marks.list.length > 0 || baselineExplicit) ? " with-changes" : "")}
            title="Show changes compared with the last commit, a branch, tag or commit, or another file"
            onClick={() => host.post({ type: "compareWith" })}
          >
            Compare…
          </button>
        )}
        {marks && (marks.list.length > 0 || baselineExplicit) && (
          <button
            class={"changes-btn" + (problems ? " with-problems" : "")}
            title={`Changes compared with ${marks.label}. Click (or Alt+F5 / Shift+Alt+F5) to go to the next / previous.`}
            onClick={(e) => changeNav.current(e.shiftKey ? -1 : 1)}
          >
            {!marks.list.length && <span class="changes-none">No differences from {marks.label}</span>}
            {marks.added > 0 && <span class="changes-added">+{marks.added}</span>}
            {marks.modified > 0 && <span class="changes-modified">~{marks.modified}</span>}
            {marks.removedCount > 0 && <span class="changes-removed">−{marks.removedCount}</span>}
          </button>
        )}
        {problems && (
          <button
            class={"problems-btn " + (problems.errors ? "has-errors" : "has-warnings")}
            title="Problems the editor reports for this file. Click (or F8 / Shift+F8) to go to the next / previous."
            onClick={(e) => problemNav.current(e.shiftKey ? -1 : 1)}
          >
            {problems.errors > 0 && <span class="problems-errors">✖ {problems.errors}</span>}
            {problems.list.length - problems.errors > 0 && <span class="problems-warnings">⚠ {problems.list.length - problems.errors}</span>}
          </button>
        )}
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
      <Split
        ratio={split}
        onRatio={setSplit}
        left={
          <div class="tree-wrap" ref={treeWrap}>
            <div class="tree-filter">
              <input
                type="search"
                placeholder="Filter nodes (tag, attribute, text)"
                aria-label="Filter nodes"
                value={nodeFilter}
                onInput={(e) => setNodeFilter((e.target as HTMLInputElement).value)}
                onKeyDown={(e) => {
                  if (e.key === "Escape" && nodeFilter) {
                    e.stopPropagation();
                    setNodeFilter("");
                  }
                }}
              />
              {nodeFilterHits && <span class="muted small">{nodeFilterHits.size ? "" : "No matches"}</span>}
            </div>
            <Tree
              skeleton={skeleton}
              expanded={expanded}
              selected={selected}
              filter={treeViewFilter}
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
              onSelect={(id) => (mode === "flat" ? showInFlat(id) : selectElement(id))}
              onActivate={activateElement}
              onActivateOffset={navigateTo}
              problems={problems?.byElement}
              problemWithin={problems?.within}
              changes={marks}
              onInspect={(id) => void inspectElement(id)}
              onContextMenu={(id, x, y) => openStructureMenu(id, null, x, y)}
            />
          </div>
        }
        right={
          mode === "flat" ? (
            <div class="grid-wrap flat-wrap">
              {rowsForFlat && (
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
              onSelect={selectFlat}
              problems={problems?.byElement}
              changes={marks}
              changeDetails={changeDetails}
              onActivate={(pos) => void activateFlat(pos)}
              onCopy={(sel) => void copyFlat(sel, rowsForFlat)}
              onInspect={(pos) => void inspectFlat(pos)}
              canEdit={(pos) => flatEditTarget(pos) !== null}
              suggest={(pos) => suggestFor(flatEditTarget(pos)?.target)}
              onEdit={(pos, value) => {
                const e = flatEditTarget(pos);
                if (e) void requestEdit(e.target, value, e.label);
              }}
              onContextMenu={(pos, x, y) =>
                pos.id >= 0 && openStructureMenu(pos.id, pos.attr >= 0 ? (flatValues.get(pos.id)?.attrs[pos.attr]?.name ?? null) : null, x, y)
              }
            />
              )}
            </div>
          ) : (
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
                      {skeleton.json ? displayName(skeleton, id) : skeleton.names[skeleton.nameIdx[id]]}
                      {!skeleton.json && skeleton.parent[id] >= 0 && sameNameSiblings(skeleton, id) > 1 ? `[${indexAmongSameName(skeleton, id) + 1}]` : ""}
                    </button>
                  </span>
                ))}
                {table?.group && (
                  <span class="crumb-wrap">
                    <span class="crumb-sep">›</span>
                    <span class="crumb group">{table.group === SELF_GROUP ? "(fields)" : table.group}</span>
                  </span>
                )}
              </nav>
              <div class="toolbar-right">
                {activeFilterCount > 0 && (
                  <button class="secondary small" onClick={() => setFilters({})} title="Clear all column filters">
                    Clear filters ({activeFilterCount})
                  </button>
                )}
                {table && (
                  <button
                    class="secondary small"
                    title="Export the visible rows"
                    onClick={(e) => {
                      const b = (e.currentTarget as HTMLElement).getBoundingClientRect();
                      setMenu({
                        x: b.left,
                        y: b.bottom + 2,
                        items: [
                          { label: "Export as CSV…", onClick: () => exportGrid("csv") },
                          { label: "Export as JSON…", onClick: () => exportGrid("json") },
                        ],
                      });
                    }}
                  >
                    Export
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
            {table && (
              <Summary
                key={table.elementId}
                table={table}
                fields={fields}
                onGroup={chooseGroup}
                canEdit={(c) => !!fields && cellEditTarget(fields, 0, c) !== null}
                suggest={(c) => suggestFor((fields && cellEditTarget(fields, 0, c))?.target)}
                onEdit={(c, value) => {
                  const e = fields && cellEditTarget(fields, 0, c);
                  if (e) void requestEdit(e.target, value, e.label);
                }}
                onInspect={(c) => fields && inspectTableCell(fields, 0, c)}
                onContextMenu={(c, x, y) => fields && openCellMenu(fields, 0, c, x, y)}
              />
            )}
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
                canEdit={(pos) => gridEditTarget(pos) !== null}
                suggest={(pos) => suggestFor(gridEditTarget(pos)?.target)}
                onEdit={(pos, value) => {
                  const e = gridEditTarget(pos);
                  if (e) void requestEdit(e.target, value, e.label);
                }}
                onContextMenu={openGridMenu}
                problemCells={gridProblems?.cells}
                problemRows={gridProblems?.rows}
                changes={gridChangeMarks}
                onClear={clearCells}
                onMoveRow={(v, up, c) => moveRow(v, up, c)}
                onPaste={(focus) => void pasteGrid(focus)}
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
                  <span class="selection-stats" title="Count of non-empty cells; sum, average, min and max of the numbers among them">
                    {(() => {
                      const r = selectionRange(gridSel);
                      const n = (r.r1 - r.r0 + 1) * (r.c1 - r.c0 + 1);
                      return n > 1 ? statsText(selectionStats(table, view, r)) : "";
                    })()}
                  </span>
                )}
                <span class="muted">{fileName}</span>
              </div>
            )}
          </div>
          )
        }
      />
      {menu && <ContextMenu {...menu} onClose={() => setMenu(null)} />}
      {prompt && <PromptDialog {...prompt} />}
      {toast && (
        <div class="toast" role="status" onClick={() => setToast(null)}>
          {toast}
        </div>
      )}
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
