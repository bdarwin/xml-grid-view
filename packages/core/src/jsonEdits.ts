/**
 * Edits for JSON documents (see parseJsonDocument): value edits and the structural and grid
 * operations of structure.ts, as text replacements that keep the file's formatting (indentation,
 * spacing after `:` and commas, CRLF). Members are named by their key, array items have no name.
 * See fixtures/SCHEMA.md, "JSON edits".
 */
import type { XmlModel } from "./model.js";
import type { XElement } from "./types.js";
import { elementAtPath, type ElementPath } from "./paths.js";
import type { EditResult, EditTarget, TextEdit } from "./edits.js";
import type { StructureOp, StructureResult } from "./structure.js";

const NUMBER = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/;

const isContainer = (el: XElement) => el.json === "object" || el.json === "array";
const isScalar = (el: XElement) => !!el.json && !isContainer(el);
const inArray = (el: XElement) => el.parent?.json === "array";

/**
 * The JSON token for a new value: strings stay strings; otherwise a value that reads as a
 * number, true, false or null is written as such when `typed` (the old value wasn't a string).
 */
export function jsonToken(value: string, typed: boolean): string {
  if (typed && (NUMBER.test(value) || value === "true" || value === "false" || value === "null")) return value;
  return JSON.stringify(value);
}

function newline(source: string): string {
  return source.includes("\r\n") ? "\r\n" : "\n";
}

function lineStart(source: string, offset: number): number {
  return Math.max(source.lastIndexOf("\n", offset - 1), source.lastIndexOf("\r", offset - 1)) + 1;
}

/** The indentation before `offset` when only spaces/tabs precede it on its line, else null. */
function ownLineIndent(source: string, offset: number): string | null {
  const ls = lineStart(source, offset);
  for (let i = ls; i < offset; i++) if (source[i] !== " " && source[i] !== "\t") return null;
  return source.slice(ls, offset);
}

/** Indentation of the line `offset` is on (its leading spaces/tabs). */
function lineIndent(source: string, offset: number): string {
  const ls = lineStart(source, offset);
  let i = ls;
  while (source[i] === " " || source[i] === "\t") i++;
  return source.slice(ls, i);
}

/** One level of indentation, from the document: the first child on its own line deeper than its parent's line; else two spaces. */
function indentUnit(source: string, model: XmlModel): string {
  for (const el of model.elements) {
    if (!el.parent) continue;
    const b = ownLineIndent(source, el.start);
    const a = lineIndent(source, el.parent.start);
    if (b !== null && b.length > a.length && b.startsWith(a)) return b.slice(a.length);
  }
  return "  ";
}

/** End of the key token of a member (just after its closing quote). */
function keyEnd(source: string, el: XElement): number {
  let i = el.start + 1;
  while (i < source.length && source[i] !== '"') i += source[i] === "\\" ? 2 : 1;
  return i + 1;
}

/** The separator between a member's key and value as written (e.g. `": "`), from the first member found; default `": "`. */
function colon(source: string, model: XmlModel): string {
  for (const el of model.elements) {
    if (el.parent?.json !== "object") continue;
    const k = keyEnd(source, el);
    const s = source.slice(k, el.openEnd - (isContainer(el) ? 1 : 0));
    return /^[ \t]*:[ \t]*$/.test(s) ? s : ": ";
  }
  return ": ";
}

/** Offset of the container's closing bracket. */
const closeOf = (el: XElement) => el.end - 1;

/** How `child` would be written as a member or item of `parent`. */
function entry(parent: XElement, key: string, value: string, sep: string): string {
  return parent.json === "array" ? value : JSON.stringify(key) + sep + value;
}

/**
 * Inserts entries (already written) as the last children of a container: after the last child
 * on the same layout, or into an empty container (multi-line when the container's line is
 * indented and it isn't written on one line).
 */
function appendEntries(source: string, model: XmlModel, el: XElement, entries: string[]): TextEdit {
  const nl = newline(source);
  const last = el.elements[el.elements.length - 1];
  if (last) {
    const ind = ownLineIndent(source, last.start);
    return { offset: last.end, length: 0, text: entries.map((e) => "," + (ind === null ? " " : nl + ind) + e).join("") };
  }
  const inner = source.slice(el.openEnd, closeOf(el));
  if (inner.includes("\n") || inner.includes("\r")) {
    const ind = lineIndent(source, el.start) + indentUnit(source, model);
    return { offset: el.openEnd, length: closeOf(el) - el.openEnd, text: entries.map((e) => nl + ind + e).join(",") + nl + lineIndent(source, el.start) };
  }
  // `{}` or `[]` on one line: keep it on one line.
  const pad = el.json === "object" ? " " : "";
  return { offset: el.openEnd, length: closeOf(el) - el.openEnd, text: pad + entries.join(", ") + pad };
}

/** Deletes runs of consecutive siblings so that commas and lines stay right. */
function deleteRuns(source: string, parent: XElement, del: Set<XElement>): TextEdit[] {
  const kids = parent.elements;
  const edits: TextEdit[] = [];
  if (kids.every((k) => del.has(k))) return [{ offset: parent.openEnd, length: closeOf(parent) - parent.openEnd, text: "" }];
  let i = 0;
  while (i < kids.length) {
    if (!del.has(kids[i])) {
      i++;
      continue;
    }
    let j = i;
    while (j + 1 < kids.length && del.has(kids[j + 1])) j++;
    const next = kids[j + 1];
    const prev = kids[i - 1];
    // Up to the next kept sibling (its indentation stays), or back to the previous one's end.
    if (next) edits.push({ offset: kids[i].start, length: next.start - kids[i].start, text: "" });
    else edits.push({ offset: prev.end, length: kids[j].end - prev.end, text: "" });
    i = j + 1;
  }
  return edits;
}

function sortEdits(edits: TextEdit[]): TextEdit[] {
  return [...edits].sort((a, b) => b.offset - a.offset);
}

/** A copy of a value with every scalar blanked ("" , 0, false, null kept), objects and arrays recursively (arrays emptied). */
function blankValue(source: string, el: XElement, sep: string): string {
  switch (el.json) {
    case "string":
      return '""';
    case "number":
      return "0";
    case "boolean":
      return "false";
    case "null":
      return "null";
    case "array":
      return "[]";
    default: {
      if (!el.elements.length) return source.slice(el.openEnd - 1, el.end);
      const nl = newline(source);
      const first = el.elements[0];
      const ind = ownLineIndent(source, first.start);
      const parts = el.elements.map((c) => JSON.stringify(c.name) + sep + blankValue(source, c, sep));
      if (ind === null) {
        const pad = source[el.openEnd] === " " ? " " : "";
        return "{" + pad + parts.join(", ") + pad + "}";
      }
      return "{" + parts.map((p) => nl + ind + p).join(",") + nl + lineIndent(source, closeOf(el)) + "}";
    }
  }
}

/** Keys that hold a number, boolean or null in some row of `array`: new values for them are written typed. */
function typedKeys(array: XElement | null): Set<string> {
  const out = new Set<string>();
  for (const row of array?.elements ?? []) for (const c of row.elements) if (isScalar(c) && c.json !== "string") out.add(c.name);
  return out;
}

/** A new object for `values` (key → value), laid out like `template` (an existing row) when given. */
function buildObject(source: string, template: XElement | null, values: Record<string, string>, sep: string, nl: string, rowIndent: string, unit: string): string {
  const typed = typedKeys(template?.parent ?? null);
  const keys = Object.keys(values);
  if (keys.length === 1 && keys[0] === "#text") return jsonToken(values["#text"], template ? template.json !== "string" : false);
  const parts = keys.filter((k) => k !== "#text").map((k) => JSON.stringify(k) + sep + jsonToken(values[k], typed.has(k)));
  const first = template?.elements[0];
  const ind = template ? (first ? ownLineIndent(source, first.start) : null) : unit === "" ? null : rowIndent + unit;
  if (ind === null) {
    const pad = template && source[template.openEnd] !== " " ? "" : " ";
    return "{" + pad + parts.join(", ") + pad + "}";
  }
  return "{" + parts.map((p) => nl + ind + p).join(",") + nl + rowIndent + "}";
}

export function computeJsonValueEdit(source: string, model: XmlModel, target: EditTarget, value: string): EditResult {
  const el = elementAtPath(model.doc, target.path);
  if (!el) return { error: "The element no longer exists." };
  if (model.errors.length) return { error: "The document is not valid JSON; fix it before editing values." };
  if (target.kind === "attr") return { error: "JSON has no attributes." };
  if (!isScalar(el)) return { error: `"${el.name}" is an ${el.json}; edit its ${el.json === "array" ? "items" : "members"} instead.` };
  return { edit: { offset: el.openEnd, length: el.end - el.openEnd, text: jsonToken(value, el.json !== "string") } };
}

export function computeJsonStructureEdit(source: string, model: XmlModel, op: StructureOp): StructureResult {
  if (model.errors.length) return { error: "The document is not valid JSON; fix it before editing." };
  const sep = colon(source, model);
  const nl = newline(source);
  const at = (p: ElementPath) => elementAtPath(model.doc, p);

  switch (op.kind) {
    case "deleteElements": {
      const byParent = new Map<XElement, Set<XElement>>();
      for (const p of op.paths) {
        const el = at(p);
        if (!el) return { error: "The element no longer exists." };
        if (!el.parent) return { error: "The root value can't be deleted." };
        const set = byParent.get(el.parent) ?? new Set();
        set.add(el);
        byParent.set(el.parent, set);
      }
      // Drop elements inside another deleted one.
      const all = new Set(op.paths.map(at) as XElement[]);
      const edits: TextEdit[] = [];
      for (const [parent, set] of byParent) {
        let covered = false;
        for (let a: XElement | null = parent; a; a = a.parent) if (all.has(a)) covered = true;
        if (!covered) edits.push(...deleteRuns(source, parent, set));
      }
      return { edits: sortEdits(edits) };
    }
    case "setCells":
      return setCells(source, model, op.cells, sep);
    default:
  }

  const el = at(op.path);
  if (!el) return { error: "The element no longer exists." };
  const isRoot = el.parent === null;

  switch (op.kind) {
    case "deleteElement":
      if (isRoot) return { error: "The root value can't be deleted." };
      return { edits: deleteRuns(source, el.parent!, new Set([el])) };

    case "deleteAttr":
    case "addAttr":
      return { error: "JSON has no attributes." };

    case "renameElement": {
      if (isRoot || inArray(el)) return { error: "Only object members have a name." };
      if (op.name === el.name) return { edits: [] };
      if (el.parent!.elements.some((s) => s !== el && s.name === op.name)) return { error: `The object already has a member "${op.name}".` };
      return { edits: [{ offset: el.start, length: keyEnd(source, el) - el.start, text: JSON.stringify(op.name) }] };
    }

    case "insertElement": {
      const container = op.where === "child" ? el : el.parent;
      if (!container) return { error: "Values can't be added next to the root (a document has one root)." };
      if (!isContainer(container)) return { error: `"${container.name}" is a ${container.json}; only objects and arrays have members or items.` };
      if (container.json === "object" && container.elements.some((s) => s.name === op.name)) return { error: `The object already has a member "${op.name}".` };
      const text = entry(container, op.name, '""', sep);
      if (op.where === "child") return { edits: [appendEntries(source, model, el, [text])] };
      const ind = ownLineIndent(source, el.start);
      const gap = ind === null ? " " : nl + ind;
      if (op.where === "after") return { edits: [{ offset: el.end, length: 0, text: "," + gap + text }] };
      return { edits: [{ offset: el.start, length: 0, text: text + "," + gap }] };
    }

    case "duplicateElement": {
      if (isRoot) return { error: "The root value can't be duplicated (a document has one root)." };
      if (!inArray(el)) return { error: "Object keys must be unique; duplicate array items instead." };
      const ind = ownLineIndent(source, el.start);
      return { edits: [{ offset: el.end, length: 0, text: "," + (ind === null ? " " : nl + ind) + source.slice(el.start, el.end) }] };
    }

    case "newRow": {
      if (isRoot) return { error: "The root value can't be repeated (a document has one root)." };
      if (!inArray(el)) return { error: "New rows are added to arrays." };
      const ind = ownLineIndent(source, el.start);
      return { edits: [{ offset: el.end, length: 0, text: "," + (ind === null ? " " : nl + ind) + blankValue(source, el, sep) }] };
    }

    case "moveElement": {
      if (isRoot) return { error: "The root value can't be moved." };
      const sibs = el.parent!.elements;
      const i = sibs.indexOf(el);
      const other = sibs[op.direction === "up" ? i - 1 : i + 1];
      if (!other) return { error: `It is already the ${op.direction === "up" ? "first" : "last"}.` };
      const [a, b] = other.start < el.start ? [other, el] : [el, other];
      // Members move with their keys; items are values.
      return {
        edits: [
          { offset: b.start, length: b.end - b.start, text: source.slice(a.start, a.end) },
          { offset: a.start, length: a.end - a.start, text: source.slice(b.start, b.end) },
        ],
      };
    }

    case "addColumn": {
      const rows = el.elements.filter((c) => c.name === op.group && c.json === "object");
      if (!rows.length) return { error: `No objects to add "${op.name}" to.` };
      const name = op.name;
      const edits: TextEdit[] = [];
      for (const row of rows) {
        if (row.elements.some((c) => c.name === name)) continue;
        edits.push(appendEntries(source, model, row, [JSON.stringify(name) + sep + '""']));
      }
      if (!edits.length) return { error: `Every row already has "${name}".` };
      return { edits: sortEdits(edits) };
    }

    case "deleteColumn": {
      if (op.key === "#text") return { error: "The value column can't be deleted; clear its cells instead." };
      const edits: TextEdit[] = [];
      for (const row of el.elements.filter((c) => c.name === op.group && c.json === "object")) {
        const del = new Set(row.elements.filter((c) => c.name === op.key));
        if (del.size) edits.push(...deleteRuns(source, row, del));
      }
      if (!edits.length) return { error: `No row has "${op.key}".` };
      return { edits: sortEdits(edits) };
    }

    case "insertRows": {
      if (!op.rows.length) return { edits: [] };
      if (el.json !== "array") return { error: "Rows are added to arrays." };
      const template = el.elements[el.elements.length - 1] ?? null;
      const rowIndent = template ? (ownLineIndent(source, template.start) ?? lineIndent(source, template.start)) : lineIndent(source, el.start) + indentUnit(source, model);
      // An empty array written on one line gets one-line rows (unit ""), like appendEntries keeps it on one line.
      const inner = source.slice(el.openEnd, closeOf(el));
      const unit = !template && !/[\r\n]/.test(inner) ? "" : indentUnit(source, model);
      const texts = op.rows.map((r) => buildObject(source, template, r, sep, nl, rowIndent, unit));
      return { edits: [appendEntries(source, model, el, texts)] };
    }

    default:
      return { error: "This operation isn't available for JSON." };
  }
}

/**
 * Sets cells of rows: an existing scalar member gets a new value (strings stay strings), a
 * missing member is added at the end of the object (one insertion per row), `#text` sets a
 * scalar row itself.
 */
function setCells(source: string, model: XmlModel, cells: { path: ElementPath; key: string; value: string }[], sep: string): StructureResult {
  const byRow = new Map<XElement, { key: string; value: string }[]>();
  for (const c of cells) {
    const el = elementAtPath(model.doc, c.path);
    if (!el) return { error: "The element no longer exists." };
    const l = byRow.get(el) ?? [];
    l.push(c);
    byRow.set(el, l);
  }
  const edits: TextEdit[] = [];
  for (const [row, list] of byRow) {
    const added: string[] = [];
    const typed = row.parent?.json === "array" ? typedKeys(row.parent) : new Set<string>();
    for (const { key, value } of list) {
      if (key === "#text") {
        if (!isScalar(row)) return { error: "Only a single value can be set here." };
        edits.push({ offset: row.openEnd, length: row.end - row.openEnd, text: jsonToken(value, row.json !== "string") });
        continue;
      }
      if (row.json !== "object") return { error: "Only objects have named values." };
      const name = key;
      const m = row.elements.find((c) => c.name === name);
      if (m) {
        if (!isScalar(m)) return { error: `"${name}" is an ${m.json}; edit its ${m.json === "array" ? "items" : "members"} instead.` };
        edits.push({ offset: m.openEnd, length: m.end - m.openEnd, text: jsonToken(value, m.json !== "string") });
      } else added.push(JSON.stringify(name) + sep + jsonToken(value, typed.has(name)));
    }
    if (added.length) edits.push(appendEntries(source, model, row, added));
  }
  return { edits: sortEdits(edits) };
}

