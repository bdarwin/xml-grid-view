import * as vscode from "vscode";
import type { ViewToHost } from "@xmlgridview/core/protocol";
import { VIEW_TYPE, XmlGridEditorProvider } from "./provider";
import { LARGE_VIEW_TYPE, LargeFileEditorProvider, TEXT_DOCUMENT_LIMIT } from "./large";

/** Test hooks returned from activate(); not a public API. */
export interface XmlGridViewApi {
  viewCount(uri: vscode.Uri): number;
  readyCount(uri: vscode.Uri): number;
  simulateViewMessage(uri: vscode.Uri, msg: ViewToHost): Promise<void>;
  baseline(uri: vscode.Uri): { text: string | null; label: string } | undefined;
}

export function activate(context: vscode.ExtensionContext): XmlGridViewApi {
  const log = vscode.window.createOutputChannel("XML Grid View");
  const provider = new XmlGridEditorProvider(context, log);
  const large = new LargeFileEditorProvider(context, log);
  const active = () => provider.active ?? large.active;
  const viewsFor = (uri: vscode.Uri) => [...provider.viewsFor(uri), ...large.viewsFor(uri)];

  context.subscriptions.push(
    log,
    vscode.window.registerCustomEditorProvider(VIEW_TYPE, provider, {
      webviewOptions: { retainContextWhenHidden: false },
      supportsMultipleEditorsPerDocument: true,
    }),
    vscode.window.registerCustomEditorProvider(LARGE_VIEW_TYPE, large, {
      webviewOptions: { retainContextWhenHidden: false },
      supportsMultipleEditorsPerDocument: true,
    }),
    vscode.commands.registerCommand("xmlGridView.openWith", async (uri?: vscode.Uri) => {
      const target = uri ?? vscode.window.activeTextEditor?.document.uri;
      if (!target) {
        void vscode.window.showInformationMessage("Open an XML file first.");
        return;
      }
      // VS Code can't give us files over 50 MB as text documents; those open in the read-only view.
      const size = await vscode.workspace.fs.stat(target).then((s) => s.size, () => 0);
      await vscode.commands.executeCommand("vscode.openWith", target, size > TEXT_DOCUMENT_LIMIT ? LARGE_VIEW_TYPE : VIEW_TYPE);
    }),
    vscode.commands.registerCommand("xmlGridView.showSource", async () => {
      await active()?.showSource();
    }),
    vscode.commands.registerCommand("xmlGridView.find", () => {
      active()?.post({ type: "focusFind" });
    }),
  );

  return {
    viewCount: (uri) => viewsFor(uri).length,
    readyCount: (uri) => viewsFor(uri).filter((v) => v.ready).length,
    simulateViewMessage: async (uri, msg) => {
      for (const v of viewsFor(uri)) await v.handle(msg);
    },
    baseline: (uri) => viewsFor(uri)[0]?.baseline,
  };
}

export function deactivate(): void {}
