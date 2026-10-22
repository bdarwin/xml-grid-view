import * as vscode from "vscode";

/** Completion kinds that are values (a schema's enumeration, JSON Schema enums), not snippets, tags or properties. */
const VALUE_KINDS = new Set([vscode.CompletionItemKind.Value, vscode.CompletionItemKind.EnumMember, vscode.CompletionItemKind.Enum, vscode.CompletionItemKind.Constant]);
/** JSON skeletons the JSON language service offers for any value. */
const SKELETONS = new Set(["{}", "[]", '""']);

/**
 * The values the editor's language support offers at `offset` (code completion at the value): for
 * XML with an XSD or DTD this needs an XML extension (e.g. Red Hat's), for JSON the built-in JSON
 * Schema support. Empty when there are none, or when the document changed since `docVersion`.
 */
export async function suggestionsAt(document: vscode.TextDocument, offset: number, docVersion?: number): Promise<string[]> {
  if (docVersion !== undefined && docVersion !== document.version) return [];
  let list: vscode.CompletionList | undefined;
  try {
    list = await vscode.commands.executeCommand<vscode.CompletionList>("vscode.executeCompletionItemProvider", document.uri, document.positionAt(offset));
  } catch {
    return [];
  }
  const json = document.languageId === "json" || document.languageId === "jsonc";
  const out: string[] = [];
  const seen = new Set<string>();
  // In the order the suggest widget shows them.
  const key = (i: vscode.CompletionItem) => i.sortText ?? (typeof i.label === "string" ? i.label : i.label.label);
  const items = [...(list?.items ?? [])].sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0));
  for (const item of items) {
    if (item.kind === undefined || !VALUE_KINDS.has(item.kind)) continue;
    let label = typeof item.label === "string" ? item.label : item.label.label;
    if (SKELETONS.has(label)) continue;
    // JSON string values come quoted.
    if (json && label.length >= 2 && label.startsWith('"') && label.endsWith('"')) {
      try {
        label = JSON.parse(label) as string;
      } catch {
        continue;
      }
    }
    if (!seen.has(label)) {
      seen.add(label);
      out.push(label);
    }
    if (out.length >= 500) break;
  }
  return out;
}
