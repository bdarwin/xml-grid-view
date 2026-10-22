import { execFile } from "node:child_process";
import * as path from "node:path";
import * as vscode from "vscode";

export type BaselineListener = (text: string | null, label: string, explicit: boolean) => void;

/**
 * What changes are shown against: the last commit (the default; the opened text outside git), a
 * branch, tag or commit, another file, or the text when the file was opened.
 */
export type BaselineSource = { kind: "head" } | { kind: "ref"; ref: string } | { kind: "file"; uri: vscode.Uri } | { kind: "opened" };

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

export interface BaselineWatch extends vscode.Disposable {
  readonly source: BaselineSource;
  /** Switches to another baseline; resolves once it is read (rejects with a message when it can't be). */
  compareWith(source: BaselineSource): Promise<void>;
  /** Branches, tags and remote branches of the file's repository (empty outside git). */
  refs(): Promise<string[]>;
}

/**
 * Keeps the view's baseline for change highlighting up to date. The default is the file as of the
 * last commit when it is tracked in a git repository (refreshed when HEAD moves), otherwise the text
 * it had when it was opened. Git runs directly (not through the Git extension), so files in
 * repositories above the workspace folder or outside it work too; only in trusted workspaces. Off
 * when the `xmlGridView.showChanges` setting is false.
 */
export function watchBaseline(document: vscode.TextDocument, listener: BaselineListener): BaselineWatch {
  const openedText = document.getText();
  const disposables: vscode.Disposable[] = [];
  const file = document.uri.fsPath;
  const dir = path.dirname(file);
  let source: BaselineSource = { kind: "head" };
  let disposed = false;
  let watchedGitDir: string | undefined;
  let fileWatcher: vscode.Disposable | undefined;
  let head: string | undefined;
  let last: string | null | undefined;
  let lastLabel = "";
  const send = (text: string | null, label: string) => {
    if (disposed || (text === last && label === lastLabel)) return;
    last = text;
    lastLabel = label;
    listener(text, label, source.kind !== "head");
  };
  const enabled = () => vscode.workspace.getConfiguration("xmlGridView").get<boolean>("showChanges", true);
  const useGit = () => document.uri.scheme === "file" && vscode.workspace.isTrusted;
  const crlf = (text: string) => (document.eol === vscode.EndOfLine.CRLF && !text.includes("\r\n") ? text.replace(/\n/g, "\r\n") : text);

  /** Watches the repository's HEAD and refs (commits, checkouts, fetches) once the repository is known. */
  const watchGit = (gitDir: string) => {
    if (watchedGitDir) return;
    watchedGitDir = gitDir;
    const w = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(vscode.Uri.file(gitDir), "{HEAD,logs/HEAD,refs/**,packed-refs}"));
    w.onDidChange(() => schedule());
    w.onDidCreate(() => schedule());
    disposables.push(w);
  };

  /** Reads the current source's text; throws an Error with a message for the user when it can't. */
  const read = async (): Promise<{ text: string; label: string }> => {
    switch (source.kind) {
      case "opened":
        return { text: openedText, label: OPENED };
      case "file": {
        const bytes = await vscode.workspace.fs.readFile(source.uri);
        return { text: new TextDecoder("utf-8").decode(bytes), label: vscode.workspace.asRelativePath(source.uri, false) };
      }
      case "ref": {
        if (!useGit()) throw new Error("Comparing with a branch needs git and a trusted workspace.");
        const [gitDir] = (await git(dir, ["rev-parse", "--absolute-git-dir"])).trim().split(/\r?\n/);
        watchGit(gitDir);
        try {
          return { text: crlf(await git(dir, ["show", `${source.ref}:./${path.basename(file)}`])), label: source.ref };
        } catch {
          throw new Error(`The file is not in ${source.ref}.`);
        }
      }
      case "head": {
        if (useGit()) {
          try {
            const [gitDir, commit] = (await git(dir, ["rev-parse", "--absolute-git-dir", "HEAD"])).trim().split(/\r?\n/);
            watchGit(gitDir);
            if (commit === head && last !== undefined && lastLabel === LAST_COMMIT) return { text: last!, label: LAST_COMMIT };
            head = commit;
            return { text: crlf(await git(dir, ["show", `HEAD:./${path.basename(file)}`])), label: LAST_COMMIT };
          } catch {
            // Not in a repository, not committed yet, or no git: fall back below.
            head = undefined;
          }
        }
        return { text: openedText, label: OPENED };
      }
    }
  };

  const refresh = async () => {
    if (!enabled()) return send(null, "");
    try {
      const { text, label } = await read();
      send(text, label);
    } catch {
      // The branch or file went away: keep showing the last baseline.
    }
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
    get source() {
      return source;
    },
    async compareWith(next: BaselineSource) {
      const previous = source;
      source = next;
      try {
        const { text, label } = await read();
        fileWatcher?.dispose();
        fileWatcher = undefined;
        if (next.kind === "file") {
          const w = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(vscode.Uri.joinPath(next.uri, ".."), path.basename(next.uri.fsPath)));
          w.onDidChange(() => schedule());
          fileWatcher = w;
        }
        last = undefined;
        send(enabled() ? text : null, label);
      } catch (e) {
        source = previous;
        throw e;
      }
    },
    async refs() {
      if (!useGit()) return [];
      try {
        const out = await git(dir, ["for-each-ref", "--sort=-committerdate", "--format=%(refname:short)", "refs/heads", "refs/remotes", "refs/tags"]);
        return out
          .split(/\r?\n/)
          .filter((r) => r && !r.endsWith("/HEAD"))
          .slice(0, 200);
      } catch {
        return [];
      }
    },
    dispose() {
      disposed = true;
      clearTimeout(timer);
      fileWatcher?.dispose();
      for (const d of disposables) d.dispose();
    },
  };
}

/** Asks what to compare with (quick picks); undefined when cancelled. */
export async function pickBaselineSource(watch: BaselineWatch): Promise<BaselineSource | undefined> {
  type Item = vscode.QuickPickItem & { source?: BaselineSource; action?: "ref" | "file" };
  const current = watch.source;
  const items: Item[] = [
    { label: "$(git-commit) Last commit", description: "the default", source: { kind: "head" } },
    { label: "$(git-branch) Branch, tag or commit…", action: "ref" },
    { label: "$(file) Another file…", action: "file" },
    { label: "$(history) When the file was opened", source: { kind: "opened" } },
  ];
  for (const it of items) if (it.source?.kind === current.kind) it.detail = "Current";
  const choice = await vscode.window.showQuickPick(items, { title: "Show changes compared with", placeHolder: "What to compare this file with" });
  if (!choice) return undefined;
  if (choice.source) return choice.source;
  if (choice.action === "file") {
    const picked = await vscode.window.showOpenDialog({ canSelectMany: false, openLabel: "Compare", title: "Compare with file" });
    return picked?.[0] ? { kind: "file", uri: picked[0] } : undefined;
  }
  // Branches, tags and remotes, most recent first; anything else (a commit hash, HEAD~3) can be typed.
  const refs = await watch.refs();
  const qp = vscode.window.createQuickPick<vscode.QuickPickItem>();
  qp.title = "Compare with branch, tag or commit";
  qp.placeholder = "Type a branch, tag or commit (e.g. main, v1.2, HEAD~1)";
  const base = refs.map((r) => ({ label: r }));
  qp.items = base;
  qp.onDidChangeValue((v) => {
    qp.items = v && !refs.includes(v) ? [{ label: v, description: "use this" }, ...base] : base;
  });
  const ref = await new Promise<string | undefined>((resolve) => {
    qp.onDidAccept(() => {
      resolve(qp.selectedItems[0]?.label ?? (qp.value || undefined));
      qp.hide();
    });
    qp.onDidHide(() => resolve(undefined));
    qp.show();
  });
  qp.dispose();
  return ref ? { kind: "ref", ref } : undefined;
}
