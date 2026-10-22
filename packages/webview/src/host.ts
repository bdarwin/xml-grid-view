import { isHostToView, type HostToView, type ViewToHost } from "@xmlgridview/core";

/** Persisted per-view UI state (VS Code keeps it while the tab is hidden). */
export type PersistedState = Record<string, unknown>;

export interface HostBridge {
  post(msg: ViewToHost): void;
  onMessage(cb: (msg: HostToView) => void): void;
  getState(): PersistedState | undefined;
  setState(s: PersistedState): void;
  /** Asks the host to write text to the clipboard. */
  copy(text: string): void;
  /** Reads the clipboard through the host. */
  readClipboard(): Promise<string>;
  /** Values the editor suggests at a document offset (empty when none, or when the host doesn't answer). */
  suggest(offset: number, docVersion?: number): Promise<string[]>;
}

interface VsCodeApi {
  postMessage(msg: unknown): void;
  getState(): unknown;
  setState(s: unknown): void;
}

declare global {
  interface Window {
    acquireVsCodeApi?: () => VsCodeApi;
    /** Generic embedding hook: hosts without postMessage may define this to receive messages. */
    __xgvHostPost?: (json: string) => void;
    /** Generic embedding hook: hosts call this to deliver a message. */
    __xgvReceive?: (msg: HostToView | string) => void;
  }
}

export function createHostBridge(): HostBridge {
  const listeners: ((m: HostToView) => void)[] = [];
  const clipboardWaiters: ((text: string) => void)[] = [];
  const suggestWaiters = new Map<number, (items: string[]) => void>();
  let nextSuggest = 1;
  const deliver = (raw: unknown) => {
    let m = raw;
    if (typeof m === "string") {
      try {
        m = JSON.parse(m);
      } catch {
        return;
      }
    }
    if (!isHostToView(m)) return;
    if (m.type === "clipboard") {
      for (const w of clipboardWaiters.splice(0)) w(m.text);
      return;
    }
    if (m.type === "suggestions") {
      suggestWaiters.get(m.id)?.(m.items);
      suggestWaiters.delete(m.id);
      return;
    }
    for (const l of listeners) l(m);
  };
  window.addEventListener("message", (e) => deliver(e.data));
  window.__xgvReceive = deliver;

  const vscode = typeof window.acquireVsCodeApi === "function" ? window.acquireVsCodeApi() : null;
  let memoryState: PersistedState | undefined;

  const post = (msg: ViewToHost) => {
    if (vscode) vscode.postMessage(msg);
    else if (window.__xgvHostPost) window.__xgvHostPost(JSON.stringify(msg));
    else if (window.parent !== window) window.parent.postMessage(msg, "*");
    else window.dispatchEvent(new CustomEvent("xgv-host", { detail: msg }));
  };

  return {
    post,
    onMessage: (cb) => listeners.push(cb),
    getState: () => (vscode ? ((vscode.getState() as PersistedState | undefined) ?? undefined) : memoryState),
    setState: (s) => {
      if (vscode) vscode.setState(s);
      else memoryState = s;
    },
    copy: (text) => post({ type: "copy", text }),
    readClipboard: () =>
      new Promise<string>((resolve) => {
        const timer = setTimeout(() => resolve(""), 5000);
        clipboardWaiters.push((t) => {
          clearTimeout(timer);
          resolve(t);
        });
        post({ type: "readClipboard" });
      }),
    suggest: (offset, docVersion) =>
      new Promise<string[]>((resolve) => {
        const id = nextSuggest++;
        // Hosts without suggestion support never answer.
        const timer = setTimeout(() => {
          suggestWaiters.delete(id);
          resolve([]);
        }, 3000);
        suggestWaiters.set(id, (items) => {
          clearTimeout(timer);
          resolve(items);
        });
        post({ type: "suggest", id, offset, docVersion });
      }),
  };
}
