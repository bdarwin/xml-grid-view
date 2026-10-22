/**
 * Message protocol between a host (VS Code extension, IntelliJ plugin) and the
 * web view. Messages are plain JSON objects discriminated by `type`.
 */
export const PROTOCOL_VERSION = 1;

/** CSS custom properties the view reads. Hosts supply values for these names. */
export const THEME_VARS = [
  "--xgv-bg",
  "--xgv-fg",
  "--xgv-muted-fg",
  "--xgv-border",
  "--xgv-header-bg",
  "--xgv-header-fg",
  "--xgv-hover-bg",
  "--xgv-selection-bg",
  "--xgv-selection-fg",
  "--xgv-selection-inactive-bg",
  "--xgv-focus-border",
  "--xgv-input-bg",
  "--xgv-input-fg",
  "--xgv-input-border",
  "--xgv-button-bg",
  "--xgv-button-fg",
  "--xgv-button-hover-bg",
  "--xgv-link-fg",
  "--xgv-match-bg",
  "--xgv-match-current-bg",
  "--xgv-warning-bg",
  "--xgv-warning-fg",
  "--xgv-error-fg",
  "--xgv-tag-fg",
  "--xgv-attr-fg",
  "--xgv-attr-name-fg",
  "--xgv-attr-value-fg",
  "--xgv-stripe-bg",
  "--xgv-json-key-fg",
  "--xgv-json-string-fg",
  "--xgv-json-number-fg",
  "--xgv-json-keyword-fg",
  "--xgv-font-family",
  "--xgv-font-size",
  "--xgv-mono-font-family",
  "--xgv-mono-font-size",
] as const;

export type ThemeVar = (typeof THEME_VARS)[number];
export type ThemeVars = Partial<Record<ThemeVar, string>>;

export interface ViewSettings {
  /** Include the header row when copying cells (Ctrl/Cmd+C); Shift inverts it. */
  copyWithHeader: boolean;
  /** Files larger than this many characters require "Load anyway". */
  largeFileThreshold: number;
  /** Debounce for re-parsing after updates, in ms. */
  debounceMs: number;
  /** When true, value editing is disabled (read-only document or host setting). */
  readOnly: boolean;
}

export const DEFAULT_SETTINGS: ViewSettings = {
  copyWithHeader: false,
  largeFileThreshold: 50 * 1024 * 1024,
  debounceMs: 300,
  readOnly: false,
};

export interface InitMessage {
  type: "init";
  version: number;
  fileName: string;
  /** How to parse the text; when absent, decided by the file name's extension. */
  format?: "xml" | "json";
  text: string;
  /** Host document version of `text`; edits must name the version they were computed against. */
  docVersion?: number;
  theme: ThemeVars;
  settings: Partial<ViewSettings>;
  /**
   * Records mode, for files too large to load: `text` is empty and the view shows the root's
   * children (the records) a page at a time, read from disk by the host.
   */
  records?: RecordsInfo;
}
/** A large file's records: the root's children, found by streaming through the file. */
export interface RecordsInfo {
  rootName: string;
  /** The root's start tag as written (for namespaces and the root's attributes). */
  rootStartTag: string;
  /** Records by tag, most first. */
  groups: { name: string; count: number }[];
  fileSize: number;
  /** Set when the file is not well-formed: the records before the problem are shown. */
  error?: string;
}
/** Progress of indexing a large file before `init`. */
export interface RecordsProgressMessage {
  type: "recordsProgress";
  bytes: number;
  total: number;
}
/** Reply to `recordPage`: the records' XML, in order. */
export interface RecordPageMessage {
  type: "recordPage";
  id: number;
  texts: string[];
}
/** Reply to `recordSearch`: matching record indices (within the group), the first `limit` of them, and how many matched in all. */
export interface RecordSearchMessage {
  type: "recordSearch";
  id: number;
  matches: number[];
  total: number;
  done: boolean;
}
export interface UpdateMessage {
  type: "update";
  text: string;
  docVersion?: number;
  /** True when this update is the result of an edit the view requested (parse without debounce). */
  fromEdit?: boolean;
}
export interface EditResultMessage {
  type: "editResult";
  ok: boolean;
  /** Why the edit was not applied (stale document, read-only, …). */
  message?: string;
}
export interface ThemeMessage {
  type: "theme";
  vars: ThemeVars;
}
export interface FocusFindMessage {
  type: "focusFind";
}
/** A problem reported for the document by the editor (schema validation, linters, …). */
export interface Diagnostic {
  /** UTF-16 offset and length in the current document text. */
  offset: number;
  length: number;
  severity: "error" | "warning" | "info";
  message: string;
  /** Who reported it, e.g. "xml". */
  source?: string;
}
/** The document's current problems; sent whenever they change. */
export interface DiagnosticsMessage {
  type: "diagnostics";
  items: Diagnostic[];
}
/** Reply to `suggest`: the values the editor offers there, in its order (empty when none). */
export interface SuggestionsMessage {
  type: "suggestions";
  id: number;
  items: string[];
}
/** Reply to `readClipboard`. */
export interface ClipboardMessage {
  type: "clipboard";
  text: string;
}
/**
 * The earlier version of the document that changes are shown against: the last commit when the
 * file is in a git repository, otherwise the text when it was opened. `text: null` turns change
 * highlighting off.
 */
export interface BaselineMessage {
  type: "baseline";
  text: string | null;
  /** What the baseline is, for tooltips, e.g. "the last commit" or "when the file was opened". */
  label: string;
  /** True when the user chose it (Compare with…), so "no differences" is worth saying. */
  explicit?: boolean;
}
export type HostToView = InitMessage | UpdateMessage | ThemeMessage | FocusFindMessage | EditResultMessage | ClipboardMessage | DiagnosticsMessage | BaselineMessage | SuggestionsMessage | RecordsProgressMessage | RecordPageMessage | RecordSearchMessage;

export interface ReadyMessage {
  type: "ready";
}
export interface NavigateMessage {
  type: "navigate";
  offset: number;
  length: number;
}
export interface CopyMessage {
  type: "copy";
  text: string;
}
export interface ErrorMessage {
  type: "error";
  message: string;
}
/** Replace text in the document as one undoable step (offsets in UTF-16 units of `docVersion`'s text). */
export interface EditMessage {
  type: "edit";
  docVersion?: number;
  edits: { offset: number; length: number; text: string }[];
  /** Short description for the undo history, e.g. "Edit @id". */
  label: string;
}
/** Ask for the clipboard text; the host answers with a `clipboard` message. */
export interface ReadClipboardMessage {
  type: "readClipboard";
}
/** Ask what to compare the document with (a branch, another file, …); the host answers with `baseline`. */
export interface CompareWithMessage {
  type: "compareWith";
}
/** Ask which values the editor suggests at a document offset (e.g. a schema's enumeration); answered by `suggestions`. */
export interface SuggestMessage {
  type: "suggest";
  id: number;
  offset: number;
  docVersion?: number;
}
/** Records mode: ask for `count` records of a group (tag), starting at `start`; answered by `recordPage`. */
export interface RequestRecordPageMessage {
  type: "requestRecordPage";
  id: number;
  group: string;
  start: number;
  count: number;
}
/** Records mode: find records of a group containing `query`; answered by `recordSearch` (progressively). */
export interface RequestRecordSearchMessage {
  type: "requestRecordSearch";
  id: number;
  group: string;
  query: string;
  caseSensitive: boolean;
}
/** Records mode: open one record as text in an editor. */
export interface OpenRecordMessage {
  type: "openRecord";
  group: string;
  index: number;
}
/** Offer to save `text` as a new file (export); the host asks where. */
export interface SaveMessage {
  type: "save";
  fileName: string;
  text: string;
}
export type ViewToHost = ReadyMessage | NavigateMessage | CopyMessage | ErrorMessage | EditMessage | ReadClipboardMessage | SaveMessage | CompareWithMessage | SuggestMessage | RequestRecordPageMessage | RequestRecordSearchMessage | OpenRecordMessage;

const HOST_TYPES = new Set(["init", "update", "theme", "focusFind", "editResult", "clipboard", "diagnostics", "baseline", "suggestions", "recordsProgress", "recordPage", "recordSearch"]);
const VIEW_TYPES = new Set(["ready", "navigate", "copy", "error", "edit", "readClipboard", "save", "compareWith", "suggest", "requestRecordPage", "requestRecordSearch", "openRecord"]);

export function isHostToView(m: unknown): m is HostToView {
  return typeof m === "object" && m !== null && HOST_TYPES.has((m as { type?: string }).type ?? "");
}

export function isViewToHost(m: unknown): m is ViewToHost {
  return typeof m === "object" && m !== null && VIEW_TYPES.has((m as { type?: string }).type ?? "");
}
