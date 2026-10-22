import { execFile } from "node:child_process";
import * as path from "node:path";
import * as vscode from "vscode";

export type BaselineListener = (text: string | null, label: string) => void;

const LAST_COMMIT = "the last commit";
const OPENED = "when the file was opened";

/** Runs git in `cwd`; rejects when git is missing or the command fails (e.g. not a repository). */
function git(cwd: string, args: string[]): Promise<string> {
  return new Promise((resolve, reject) =>
    execFile(
      "git",
      // No fsmonitor hook: only plain reads of the object database run here.
      ["-c", "core.fsmonitor=false", ...args],
      { cwd, encoding: "utf8", maxBuffer: 1024 * 1024 * 1024, windowsHide: true },
      (err, stdout) => (err ? reject(err) : resolve(stdout)),
    ),
  );
}

/**
 * Keeps the view's baseline for change highlighting up to date: the file as of the last commit when
 * it is tracked in a git repository (refreshed when HEAD moves), otherwise the text it had when it
 * was opened. Git runs directly (not through the Git extension), so files in repositories above
 * the workspace folder or outside it work too; only in trusted workspaces. Off when the
 * `xmlGridView.showChanges` setting is false.
 */
export function watchBaseline(document: vscode.TextDocument, listener: BaselineListener): vscode.Disposable {
  const openedText = document.getText();
  const disposables: vscode.Disposable[] = [];
  const file = document.uri.fsPath;
  const dir = path.dirname(file);
  let disposed = false;
  let watched = false;
  let head: string | undefined;
  let last: string | null | undefined;
  let lastLabel = "";
  const send = (text: string | null, label: string) => {
    if (disposed || (text === last && label === lastLabel)) return;
    last = text;
    lastLabel = label;
    listener(text, label);
  };
  const enabled = () => vscode.workspace.getConfiguration("xmlGridView").get<boolean>("showChanges", true);
  const useGit = () => document.uri.scheme === "file" && vscode.workspace.isTrusted;

  /** Watches the repository's HEAD (commits, checkouts, resets) once the repository is known. */
  const watch = (gitDir: string) => {
    if (watched) return;
    watched = true;
    const w = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(vscode.Uri.file(gitDir), "{HEAD,logs/HEAD}"));
    w.onDidChange(() => schedule());
    w.onDidCreate(() => schedule());
    disposables.push(w);
  };

  const refresh = async () => {
    if (!enabled()) return send(null, "");
    if (useGit()) {
      try {
        const [gitDir, commit] = (await git(dir, ["rev-parse", "--absolute-git-dir", "HEAD"])).trim().split(/\r?\n/);
        watch(gitDir);
        if (commit === head && last !== undefined) return;
        head = commit;
        let text = await git(dir, ["show", `HEAD:./${path.basename(file)}`]);
        // Checked-out files may use CRLF while the repository stores LF.
        if (document.eol === vscode.EndOfLine.CRLF && !text.includes("\r\n")) text = text.replace(/\n/g, "\r\n");
        return send(text, LAST_COMMIT);
      } catch {
        // Not in a repository, not committed yet, or no git: fall back below.
        head = undefined;
      }
    }
    send(openedText, OPENED);
  };

  let timer: ReturnType<typeof setTimeout> | undefined;
  const schedule = () => {
    clearTimeout(timer);
    timer = setTimeout(() => void refresh(), 300);
  };

  void refresh();
  disposables.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration("xmlGridView.showChanges")) {
        last = undefined;
        void refresh();
      }
    }),
    vscode.workspace.onDidGrantWorkspaceTrust(() => void refresh()),
    // The file may get committed (or the repository created) outside VS Code.
    vscode.window.onDidChangeWindowState((s) => s.focused && schedule()),
    vscode.workspace.onDidSaveTextDocument((d) => d === document && schedule()),
  );
  return {
    dispose() {
      disposed = true;
      clearTimeout(timer);
      for (const d of disposables) d.dispose();
    },
  };
}
