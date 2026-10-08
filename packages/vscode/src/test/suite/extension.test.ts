import * as assert from "node:assert";
import * as path from "node:path";
import * as vscode from "vscode";

const VIEW_TYPE = "xmlGridView.editor";

interface Api {
  viewCount(uri: vscode.Uri): number;
  readyCount(uri: vscode.Uri): number;
  simulateViewMessage(uri: vscode.Uri, msg: { type: string; [k: string]: unknown }): Promise<void>;
}

function fixture(): vscode.Uri {
  const root = vscode.workspace.workspaceFolders![0].uri.fsPath;
  return vscode.Uri.file(path.join(root, "books.xml"));
}

async function waitFor<T>(fn: () => T | undefined | false, what: string, ms = 20_000): Promise<T> {
  const end = Date.now() + ms;
  for (;;) {
    const v = fn();
    if (v) return v;
    if (Date.now() > end) throw new Error(`Timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 100));
  }
}

function customTab(uri: vscode.Uri) {
  return vscode.window.tabGroups.all
    .flatMap((g) => g.tabs)
    .find((t) => t.input instanceof vscode.TabInputCustom && t.input.viewType === VIEW_TYPE && t.input.uri.toString() === uri.toString());
}

suite("XML Grid View", () => {
  let api: Api;

  suiteSetup(async () => {
    const ext = vscode.extensions.all.find((e) => e.packageJSON.name === "xml-grid-view");
    assert.ok(ext, "extension is installed");
    api = (await ext.activate()) as Api;
  });

  teardown(async () => {
    await vscode.commands.executeCommand("workbench.action.closeAllEditors");
  });

  test("registers its commands", async () => {
    const cmds = await vscode.commands.getCommands(true);
    for (const c of ["xmlGridView.openWith", "xmlGridView.showSource", "xmlGridView.find"]) assert.ok(cmds.includes(c), c);
  });

  test("text editor stays the default for .xml", async () => {
    const doc = await vscode.workspace.openTextDocument(fixture());
    const editor = await vscode.window.showTextDocument(doc);
    assert.strictEqual(editor.document.uri.toString(), fixture().toString());
    assert.strictEqual(customTab(fixture()), undefined);
  });

  test("Open With XML Grid View opens the custom editor", async () => {
    await vscode.commands.executeCommand("xmlGridView.openWith", fixture());
    await waitFor(() => customTab(fixture()), "custom editor tab");
    await waitFor(() => api.viewCount(fixture()) > 0, "view to resolve");
    // The web view script loaded under the CSP and posted `ready`.
    await waitFor(() => api.readyCount(fixture()) > 0, "web view ready message");
  });

  test("navigate reveals and selects the range in a text editor", async () => {
    await vscode.commands.executeCommand("xmlGridView.openWith", fixture());
    await waitFor(() => api.viewCount(fixture()) > 0, "view to resolve");
    const doc = await vscode.workspace.openTextDocument(fixture());
    const offset = doc.getText().indexOf('<book id="b2"');
    await api.simulateViewMessage(fixture(), { type: "navigate", offset, length: 5 });
    const editor = await waitFor(
      () => vscode.window.visibleTextEditors.find((e) => e.document.uri.toString() === fixture().toString()),
      "text editor",
    );
    assert.strictEqual(doc.offsetAt(editor.selection.start), offset);
    assert.strictEqual(doc.offsetAt(editor.selection.end), offset + 5);
    assert.ok(customTab(fixture()), "grid view stays open");
    assert.strictEqual(doc.isDirty, false, "document is not modified");
  });

  test("edit applies to the document; a stale edit is rejected", async () => {
    await vscode.commands.executeCommand("xmlGridView.openWith", fixture());
    await waitFor(() => api.viewCount(fixture()) > 0, "view to resolve");
    const doc = await vscode.workspace.openTextDocument(fixture());
    const original = doc.getText();
    const offset = original.indexOf('"b1"') + 1;
    try {
      // Stale version: rejected, nothing changes.
      await api.simulateViewMessage(fixture(), { type: "edit", docVersion: doc.version - 1, edits: [{ offset, length: 2, text: "zz" }], label: "Edit @id" });
      assert.strictEqual(doc.getText(), original);
      // Current version: applied as a normal (undoable) document edit.
      await api.simulateViewMessage(fixture(), { type: "edit", docVersion: doc.version, edits: [{ offset, length: 2, text: "B-1" }], label: "Edit @id" });
      assert.ok(doc.getText().includes('id="B-1"'), "edit applied");
      assert.strictEqual(doc.isDirty, true, "document is modified, not saved behind the user's back");
    } finally {
      const editor = await vscode.window.showTextDocument(doc);
      await editor.edit((b) => b.replace(new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length)), original));
      await doc.save();
    }
  });

  test("copy writes to the clipboard", async () => {
    await vscode.commands.executeCommand("xmlGridView.openWith", fixture());
    await waitFor(() => api.viewCount(fixture()) > 0, "view to resolve");
    await api.simulateViewMessage(fixture(), { type: "copy", text: "a\tb" });
    assert.strictEqual(await vscode.env.clipboard.readText(), "a\tb");
  });
});
