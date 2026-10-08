import * as vscode from "vscode";
import {
  PROTOCOL_VERSION,
  isViewToHost,
  type HostToView,
  type ViewSettings,
  type ViewToHost,
} from "@xmlgridview/core/protocol";
import { themeVars } from "./theme";

export const VIEW_TYPE = "xmlGridView.editor";
const UPDATE_DEBOUNCE_MS = 300;

interface ViewEntry {
  document: vscode.TextDocument;
  panel: vscode.WebviewPanel;
  /** True once the view's script has started and sent `ready`. */
  readonly ready: boolean;
  post(msg: HostToView): void;
  handle(msg: ViewToHost): Promise<void>;
}

/**
 * Read-only custom editor for XML. The text document is never modified; the
 * view receives the text on open and after (debounced) changes.
 */
export class XmlGridEditorProvider implements vscode.CustomTextEditorProvider {
  private readonly views = new Set<ViewEntry>();
  private activeView: ViewEntry | undefined;

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly log: vscode.OutputChannel,
  ) {}

  /** The view whose panel is active (for commands). */
  get active(): ViewEntry | undefined {
    return this.activeView?.panel.active ? this.activeView : [...this.views].find((v) => v.panel.active);
  }

  viewsFor(uri: vscode.Uri): ViewEntry[] {
    return [...this.views].filter((v) => v.document.uri.toString() === uri.toString());
  }

  async resolveCustomTextEditor(document: vscode.TextDocument, panel: vscode.WebviewPanel): Promise<void> {
    const webviewRoot = vscode.Uri.joinPath(this.context.extensionUri, "dist", "webview");
    panel.webview.options = { enableScripts: true, localResourceRoots: [webviewRoot] };
    panel.webview.html = this.html(panel.webview, webviewRoot);

    const disposables: vscode.Disposable[] = [];
    let ready = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const post = (msg: HostToView) => {
      void panel.webview.postMessage(msg);
    };

    const sendInit = () =>
      post({
        type: "init",
        version: PROTOCOL_VERSION,
        fileName: vscode.workspace.asRelativePath(document.uri, false),
        text: document.getText(),
        docVersion: document.version,
        theme: themeVars(),
        settings: { ...readSettings(), readOnly: readSettings().readOnly || isReadOnly(document) },
      });

    const entry: ViewEntry = {
      document,
      panel,
      get ready() {
        return ready;
      },
      post,
      handle: async (msg) => {
        switch (msg.type) {
          case "ready":
            ready = true;
            sendInit();
            break;
          case "navigate":
            await revealInTextEditor(document, msg.offset, msg.length);
            break;
          case "copy":
            await vscode.env.clipboard.writeText(msg.text);
            break;
          case "error":
            this.log.appendLine(`[${document.uri.fsPath}] ${msg.message}`);
            break;
          case "edit": {
            // Applied through the text document, so undo/redo, dirty state and save work as usual.
            const reject = (message: string) => post({ type: "editResult", ok: false, message });
            if (readSettings().readOnly || isReadOnly(document)) return reject("This document is read-only.");
            if (msg.docVersion !== undefined && msg.docVersion !== document.version) {
              return reject("The document changed while editing; please try again.");
            }
            const edit = new vscode.WorkspaceEdit();
            for (const e of msg.edits) {
              edit.replace(document.uri, new vscode.Range(document.positionAt(e.offset), document.positionAt(e.offset + e.length)), e.text, {
                label: msg.label,
                needsConfirmation: false,
              });
            }
            const ok = await vscode.workspace.applyEdit(edit);
            if (!ok) return reject("VS Code could not apply the edit.");
            clearTimeout(timer);
            post({ type: "update", text: document.getText(), docVersion: document.version, fromEdit: true });
            post({ type: "editResult", ok: true });
            break;
          }
        }
      },
    };
    this.views.add(entry);
    this.activeView = entry;

    disposables.push(
      panel.webview.onDidReceiveMessage((m: unknown) => {
        if (isViewToHost(m)) void entry.handle(m);
      }),
      vscode.workspace.onDidChangeTextDocument((e) => {
        if (e.document.uri.toString() !== document.uri.toString() || !ready || e.contentChanges.length === 0) return;
        clearTimeout(timer);
        timer = setTimeout(() => post({ type: "update", text: document.getText(), docVersion: document.version }), UPDATE_DEBOUNCE_MS);
      }),
      panel.onDidChangeViewState((e) => {
        if (e.webviewPanel.active) this.activeView = entry;
      }),
    );

    panel.onDidDispose(() => {
      clearTimeout(timer);
      this.views.delete(entry);
      if (this.activeView === entry) this.activeView = undefined;
      for (const d of disposables) d.dispose();
    });
  }

  private html(webview: vscode.Webview, root: vscode.Uri): string {
    const nonce = makeNonce();
    const script = webview.asWebviewUri(vscode.Uri.joinPath(root, "webview.js"));
    const style = webview.asWebviewUri(vscode.Uri.joinPath(root, "webview.css"));
    const csp = [
      "default-src 'none'",
      `style-src ${webview.cspSource}`,
      `script-src 'nonce-${nonce}'`,
      "worker-src blob:",
      `img-src ${webview.cspSource} data:`,
      `font-src ${webview.cspSource}`,
    ].join("; ");
    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="${csp}">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<link rel="stylesheet" href="${style}">
<title>XML Grid View</title>
</head>
<body>
<div id="root"></div>
<script nonce="${nonce}" src="${script}"></script>
</body>
</html>`;
  }
}

function readSettings(): ViewSettings {
  const cfg = vscode.workspace.getConfiguration("xmlGridView");
  return {
    copyWithHeader: cfg.get<boolean>("copyWithHeader", false),
    largeFileThreshold: Math.round(cfg.get<number>("largeFileThresholdMB", 50) * 1024 * 1024),
    debounceMs: UPDATE_DEBOUNCE_MS,
    readOnly: cfg.get<boolean>("readOnly", false),
  };
}

/** Documents on read-only file systems (e.g. git: diffs, extension resources) can't be edited. */
function isReadOnly(document: vscode.TextDocument): boolean {
  return vscode.workspace.fs.isWritableFileSystem(document.uri.scheme) === false;
}

/** Opens (or focuses) the text editor beside the view, selects the range and centers it. */
export async function revealInTextEditor(document: vscode.TextDocument, offset: number, length: number): Promise<vscode.TextEditor> {
  const visible = vscode.window.visibleTextEditors.find((e) => e.document.uri.toString() === document.uri.toString());
  const editor = await vscode.window.showTextDocument(document, {
    viewColumn: visible?.viewColumn ?? vscode.ViewColumn.Beside,
    preserveFocus: false,
    preview: false,
  });
  const max = document.getText().length;
  const start = document.positionAt(Math.max(0, Math.min(offset, max)));
  const end = document.positionAt(Math.max(0, Math.min(offset + length, max)));
  editor.selection = new vscode.Selection(start, end);
  editor.revealRange(new vscode.Range(start, end), vscode.TextEditorRevealType.InCenter);
  return editor;
}

function makeNonce(): string {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  let s = "";
  for (let i = 0; i < 32; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}
