import * as vscode from "vscode";
import type { ViewToHost } from "@xmlgridview/core/protocol";
import { VIEW_TYPE, XmlGridEditorProvider } from "./provider";

/** Test hooks returned from activate(); not a public API. */
export interface XmlGridViewApi {
  viewCount(uri: vscode.Uri): number;
  readyCount(uri: vscode.Uri): number;
  simulateViewMessage(uri: vscode.Uri, msg: ViewToHost): Promise<void>;
}

export function activate(context: vscode.ExtensionContext): XmlGridViewApi {
  const log = vscode.window.createOutputChannel("XML Grid View");
  const provider = new XmlGridEditorProvider(context, log);

  context.subscriptions.push(
    log,
    vscode.window.registerCustomEditorProvider(VIEW_TYPE, provider, {
      webviewOptions: { retainContextWhenHidden: false },
      supportsMultipleEditorsPerDocument: true,
    }),
    vscode.commands.registerCommand("xmlGridView.openWith", async (uri?: vscode.Uri) => {
      const target = uri ?? vscode.window.activeTextEditor?.document.uri;
      if (!target) {
        void vscode.window.showInformationMessage("Open an XML file first.");
        return;
      }
      await vscode.commands.executeCommand("vscode.openWith", target, VIEW_TYPE);
    }),
    vscode.commands.registerCommand("xmlGridView.showSource", async () => {
      const view = provider.active;
      if (view) await vscode.window.showTextDocument(view.document, { viewColumn: vscode.ViewColumn.Beside });
    }),
    vscode.commands.registerCommand("xmlGridView.find", () => {
      provider.active?.post({ type: "focusFind" });
    }),
  );

  return {
    viewCount: (uri) => provider.viewsFor(uri).length,
    readyCount: (uri) => provider.viewsFor(uri).filter((v) => v.ready).length,
    simulateViewMessage: async (uri, msg) => {
      for (const v of provider.viewsFor(uri)) await v.handle(msg);
    },
  };
}

export function deactivate(): void {}
