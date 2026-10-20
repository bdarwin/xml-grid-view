import * as vscode from "vscode";
import { PROTOCOL_VERSION, isViewToHost, type HostToView } from "@xmlgridview/core/protocol";
import { readSettings, viewHtml, type ViewEntry } from "./provider";
import { themeVars } from "./theme";

export const LARGE_VIEW_TYPE = "xmlGridView.largeEditor";
/** VS Code doesn't hand files over this size to extensions as text documents. */
export const TEXT_DOCUMENT_LIMIT = 50 * 1024 * 1024;
const RELOAD_DEBOUNCE_MS = 500;

/**
 * Read-only view for files too large for a VS Code text document: reads the file from
 * disk and reloads it when it changes. Files over `xmlGridView.maxFileSizeMB` (more than
 * the view's memory can hold) get a notice instead of the view.
 */
export class LargeFileEditorProvider implements vscode.CustomReadonlyEditorProvider {
  private readonly views = new Set<ViewEntry>();

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly log: vscode.OutputChannel,
  ) {}

  get active(): ViewEntry | undefined {
    return [...this.views].find((v) => v.panel.active);
  }

  viewsFor(uri: vscode.Uri): ViewEntry[] {
    return [...this.views].filter((v) => v.uri.toString() === uri.toString());
  }

  openCustomDocument(uri: vscode.Uri): vscode.CustomDocument {
    return { uri, dispose: () => {} };
  }

  async resolveCustomEditor(document: vscode.CustomDocument, panel: vscode.WebviewPanel): Promise<void> {
    const uri = document.uri;
    const size = (await vscode.workspace.fs.stat(uri)).size;
    const maxBytes = maxFileSizeMB() * 1024 * 1024;
    if (size > maxBytes) {
      panel.webview.html = tooLargeHtml(vscode.workspace.asRelativePath(uri, false), size, maxBytes);
      return;
    }

    const webviewRoot = vscode.Uri.joinPath(this.context.extensionUri, "dist", "webview");
    panel.webview.options = { enableScripts: true, localResourceRoots: [webviewRoot] };
    panel.webview.html = viewHtml(panel.webview, webviewRoot);

    const disposables: vscode.Disposable[] = [];
    let ready = false;
    let text = "";
    let version = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const post = (msg: HostToView) => {
      void panel.webview.postMessage(msg);
    };
    const load = async () => {
      text = new TextDecoder("utf-8").decode(await vscode.workspace.fs.readFile(uri));
      return ++version;
    };

    const entry: ViewEntry = {
      uri,
      panel,
      get ready() {
        return ready;
      },
      post,
      showSource: async () => {
        await vscode.commands.executeCommand("vscode.open", uri, { viewColumn: vscode.ViewColumn.Beside, preview: false });
      },
      handle: async (msg) => {
        switch (msg.type) {
          case "ready": {
            ready = true;
            const docVersion = await load();
            post({
              type: "init",
              version: PROTOCOL_VERSION,
              fileName: vscode.workspace.asRelativePath(uri, false),
              text,
              docVersion,
              theme: themeVars(),
              settings: { ...readSettings(), readOnly: true },
            });
            break;
          }
          case "navigate": {
            const selection = new vscode.Range(positionAt(text, msg.offset), positionAt(text, msg.offset + msg.length));
            await vscode.commands.executeCommand("vscode.open", uri, { viewColumn: vscode.ViewColumn.Beside, preview: false, selection });
            break;
          }
          case "copy":
            await vscode.env.clipboard.writeText(msg.text);
            break;
          case "error":
            this.log.appendLine(`[${uri.fsPath}] ${msg.message}`);
            break;
          case "edit":
            post({ type: "editResult", ok: false, message: "Files over 50 MB are read-only in VS Code." });
            break;
        }
      },
    };
    this.views.add(entry);

    const dir = vscode.Uri.joinPath(uri, "..");
    const watcher = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(dir, uri.path.split("/").pop()!));
    const reload = () => {
      if (!ready) return;
      clearTimeout(timer);
      timer = setTimeout(async () => {
        try {
          const docVersion = await load();
          post({ type: "update", text, docVersion });
        } catch (e) {
          this.log.appendLine(`[${uri.fsPath}] reload failed: ${String(e)}`);
        }
      }, RELOAD_DEBOUNCE_MS);
    };
    disposables.push(
      watcher,
      watcher.onDidChange(reload),
      watcher.onDidCreate(reload),
      panel.webview.onDidReceiveMessage((m: unknown) => {
        if (isViewToHost(m)) void entry.handle(m);
      }),
    );
    panel.onDidDispose(() => {
      clearTimeout(timer);
      text = "";
      this.views.delete(entry);
      for (const d of disposables) d.dispose();
    });
  }
}

/** Like TextDocument.positionAt: CR, LF and CRLF end lines; columns in UTF-16 units. */
function positionAt(text: string, offset: number): vscode.Position {
  const end = Math.max(0, Math.min(offset, text.length));
  let line = 0;
  let lineStart = 0;
  for (let i = text.indexOf("\n"); i >= 0 && i < end; i = text.indexOf("\n", i + 1)) {
    line++;
    lineStart = i + 1;
  }
  if (text.lastIndexOf("\r", end) >= 0) {
    // Rare: lone CRs also end lines. Recount the slow way.
    line = 0;
    lineStart = 0;
    for (let i = 0; i < end; i++) {
      const c = text.charCodeAt(i);
      if (c === 10 || (c === 13 && text.charCodeAt(i + 1) !== 10)) {
        line++;
        lineStart = i + 1;
      }
    }
  }
  return new vscode.Position(line, end - lineStart);
}

function maxFileSizeMB(): number {
  return vscode.workspace.getConfiguration("xmlGridView").get<number>("maxFileSizeMB", 150);
}

function formatMB(bytes: number): string {
  return `${Math.round(bytes / (1024 * 1024))} MB`;
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);
}

function tooLargeHtml(name: string, size: number, maxBytes: number): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<style>
body { font-family: var(--vscode-font-family); color: var(--vscode-foreground); padding: 24px; line-height: 1.5; }
code { font-family: var(--vscode-editor-font-family); }
</style>
</head>
<body>
<h3>${escapeHtml(name)} is too large for XML Grid View in VS Code</h3>
<p>The file is ${formatMB(size)}. VS Code's view holds the whole document in memory and supports files up to ${formatMB(maxBytes)}
(the <code>xmlGridView.maxFileSizeMB</code> setting); larger files would crash it.</p>
<p>The IntelliJ plugin handles files of several hundred MB.</p>
</body>
</html>`;
}
