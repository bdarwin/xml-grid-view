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
  text: string;
  /** Host document version of `text`; edits must name the version they were computed against. */
  docVersion?: number;
  theme: ThemeVars;
  settings: Partial<ViewSettings>;
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
export type HostToView = InitMessage | UpdateMessage | ThemeMessage | FocusFindMessage | EditResultMessage;

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
export type ViewToHost = ReadyMessage | NavigateMessage | CopyMessage | ErrorMessage | EditMessage;

const HOST_TYPES = new Set(["init", "update", "theme", "focusFind", "editResult"]);
const VIEW_TYPES = new Set(["ready", "navigate", "copy", "error", "edit"]);

export function isHostToView(m: unknown): m is HostToView {
  return typeof m === "object" && m !== null && HOST_TYPES.has((m as { type?: string }).type ?? "");
}

export function isViewToHost(m: unknown): m is ViewToHost {
  return typeof m === "object" && m !== null && VIEW_TYPES.has((m as { type?: string }).type ?? "");
}
