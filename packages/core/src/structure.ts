/**
 * Structural edits: delete, insert, duplicate and rename elements, add and delete
 * attributes. Each operation becomes a few text replacements on the source (in
 * descending offset order), applied by hosts as one undoable edit. Formatting is
 * kept: new lines copy the surrounding indentation. See fixtures/SCHEMA.md,
 * "Structural edits".
 */
import type { XmlModel } from "./model.js";
import type { XElement } from "./types.js";
import { elementAtPath, pathOfElement as pathOf, type ElementPath } from "./paths.js";
import { computeValueEdit, escapeAttr, escapeText, type TextEdit } from "./edits.js";

export type StructureOp =
  | { kind: "deleteElement"; path: ElementPath }
  | { kind: "deleteAttr"; path: ElementPath; name: string }
  | { kind: "addAttr"; path: ElementPath; name: string; value: string }
  | { kind: "renameElement"; path: ElementPath; name: string }
  | { kind: "insertElement"; path: ElementPath; where: "before" | "after" | "child"; name: string }
  | { kind: "duplicateElement"; path: ElementPath }
  // Grid operations: rows are the children of `path` named `group`; column keys are `@attr`, a child name or `#text`.
  | { kind: "newRow"; path: ElementPath }
  | { kind: "moveElement"; path: ElementPath; direction: "up" | "down"; sameName: boolean }
  | { kind: "deleteElements"; paths: ElementPath[] }
  | { kind: "addColumn"; path: ElementPath; group: string; name: string }
  | { kind: "deleteColumn"; path: ElementPath; group: string; key: string }
  | { kind: "setCells"; cells: { path: ElementPath; key: string; value: string }[] }
  | { kind: "insertRows"; path: ElementPath; group: string; rows: Record<string, string>[] };

export type StructureResult = { edits: TextEdit[] } | { error: string };

/** XML 1.0 NameStartChar ranges (without ':'), as code points. */
const NAME_START: [number, number][] = [
  [0x41, 0x5a], [0x5f, 0x5f], [0x61, 0x7a], [0xc0, 0xd6], [0xd8, 0xf6], [0xf8, 0x2ff], [0x370, 0x37d], [0x37f, 0x1fff],
  [0x200c, 0x200d], [0x2070, 0x218f], [0x2c00, 0x2fef], [0x3001, 0xd7ff], [0xf900, 0xfdcf], [0xfdf0, 0xfffd],
];
/** Further NameChar ranges. */
const NAME_MORE: [number, number][] = [[0x2d, 0x2e], [0x30, 0x39], [0xb7, 0xb7], [0x300, 0x36f], [0x203f, 0x2040]];

const inRanges = (cp: number, ranges: [number, number][]) => ranges.some(([a, b]) => cp >= a && cp <= b);

function isNcName(s: string): boolean {
  if (!s) return false;
  for (let i = 0; i < s.length; i++) {
    const cp = s.charCodeAt(i);
    if (!inRanges(cp, NAME_START) && (i === 0 || !inRanges(cp, NAME_MORE))) return false;
  }
  return true;
}

/** A qualified name: an XML NCName, optionally `prefix:` another NCName. */
export function isQName(name: string): boolean {
  const parts = name.split(":");
  return parts.length <= 2 && parts.every(isNcName);
}

function isWs(c: string | undefined): boolean {
  return c === " " || c === "\t";
}

/** XML whitespace: space, tab, CR, LF. */
function isXmlWs(c: string | undefined): boolean {
  return c === " " || c === "\t" || c === "\r" || c === "\n";
}

/** Start of the line containing `offset`. */
function lineStart(source: string, offset: number): number {
  const nl = Math.max(source.lastIndexOf("\n", offset - 1), source.lastIndexOf("\r", offset - 1));
  return nl + 1;
}

/** The indentation before `offset` when only spaces/tabs precede it on its line, else null. */
function ownLineIndent(source: string, offset: number): string | null {
  const ls = lineStart(source, offset);
  const before = source.slice(ls, offset);
  return [...before].every(isWs) ? before : null;
}

/** Offset just past the line break that follows `offset` when only spaces/tabs come before it, else -1. */
function restOfLineEnd(source: string, offset: number): number {
  let i = offset;
  while (isWs(source[i])) i++;
  if (source.startsWith("\r\n", i)) return i + 2;
  if (source[i] === "\n" || source[i] === "\r") return i + 1;
  if (i === source.length) return i;
  return -1;
}

function newline(source: string): string {
  return source.includes("\r\n") ? "\r\n" : "\n";
}

/** One level of indentation, from the document: the first child indented deeper than its parent; else two spaces. */
function indentUnit(source: string, model: XmlModel): string {
  for (const el of model.elements) {
    const parent = el.parent;
    if (!parent) continue;
    const a = ownLineIndent(source, parent.start);
    const b = ownLineIndent(source, el.start);
    if (a !== null && b !== null && b.length > a.length && b.startsWith(a)) return b.slice(a.length);
  }
  return "  ";
}

/** Prefixes in scope at `el` (including its own declarations). */
function prefixInScope(el: XElement, prefix: string): boolean {
  if (prefix === "xml") return true;
  for (let e: XElement | null = el; e; e = e.parent) if (prefix in e.nsDecls) return true;
  return false;
}

function checkName(name: string, el: XElement, what: string): string | null {
  if (!isQName(name)) return `"${name}" is not a valid XML ${what} name.`;
  const colon = name.indexOf(":");
  if (colon > 0 && !prefixInScope(el, name.slice(0, colon))) return `The prefix "${name.slice(0, colon)}" is not declared here.`;
  return null;
}

/** Offset of `</` of the end tag, or -1 for an empty-element tag. */
function closeStart(source: string, el: XElement): number {
  if (el.end === el.openEnd) return -1;
  return source.lastIndexOf("</", el.end - 1);
}

/** Inserts `text` on a new line after the element `after` ends, or right after it when it shares its line. */
function insertAfter(source: string, after: XElement, text: string): TextEdit {
  const indent = ownLineIndent(source, after.start);
  if (indent === null) return { offset: after.end, length: 0, text };
  return { offset: after.end, length: 0, text: newline(source) + indent + text };
}

export function computeStructureEdit(source: string, model: XmlModel, op: StructureOp): StructureResult {
  if (model.errors.length) return { error: "The document is not well-formed; fix it before editing." };
  switch (op.kind) {
    case "deleteElements":
      return deleteElements(source, model, op.paths);
    case "setCells":
      return setCells(source, model, op.cells);
    default:
  }
  const el = elementAtPath(model.doc, op.path);
  if (!el) return { error: "The element no longer exists." };
  if (model.errors.length) return { error: "The document is not well-formed; fix it before editing." };
  const isRoot = el.parent === null;

  switch (op.kind) {
    case "deleteElement": {
      if (isRoot) return { error: "The root element can't be deleted." };
      // On its own line(s): remove the whole lines, so no blank line is left behind.
      const indent = ownLineIndent(source, el.start);
      const end = restOfLineEnd(source, el.end);
      if (indent !== null && end >= 0) {
        const start = el.start - indent.length;
        return { edits: [{ offset: start, length: end - start, text: "" }] };
      }
      return { edits: [{ offset: el.start, length: el.end - el.start, text: "" }] };
    }

    case "deleteAttr": {
      const attr = el.attrs.find((a) => a.name === op.name);
      if (!attr) return { error: `Attribute ${op.name} does not exist on <${el.name}>.` };
      let start = attr.start;
      while (start > 0 && isXmlWs(source[start - 1])) start--;
      return { edits: [{ offset: start, length: attr.end - start, text: "" }] };
    }

    case "addAttr": {
      const bad = checkName(op.name, el, "attribute");
      if (bad) return { error: bad };
      if (op.name === "xmlns" || op.name.startsWith("xmlns:")) return { error: "Namespace declarations can't be added here." };
      if (el.attrs.some((a) => a.name === op.name)) return { error: `<${el.name}> already has an attribute ${op.name}.` };
      // Before the closing `>` or `/>` of the start tag, after any whitespace-free attribute list.
      let at = el.openEnd - 1;
      if (source[at - 1] === "/") at--;
      while (isXmlWs(source[at - 1])) at--;
      return { edits: [{ offset: at, length: 0, text: ` ${op.name}="${escapeAttr(op.value, '"')}"` }] };
    }

    case "renameElement": {
      const bad = checkName(op.name, el, "element");
      if (bad) return { error: bad };
      if (op.name === el.name) return { edits: [] };
      const edits: TextEdit[] = [];
      const close = closeStart(source, el);
      if (close >= 0) edits.push({ offset: close + 2, length: el.name.length, text: op.name });
      edits.push({ offset: el.start + 1, length: el.name.length, text: op.name });
      return { edits };
    }

    case "duplicateElement": {
      if (isRoot) return { error: "The root element can't be duplicated (a document has one root)." };
      return { edits: [insertAfter(source, el, source.slice(el.start, el.end))] };
    }

    case "insertElement": {
      const bad = checkName(op.name, op.where === "child" ? el : (el.parent ?? el), "element");
      if (bad) return { error: bad };
      const tag = `<${op.name}/>`;
      if (op.where !== "child") {
        if (isRoot) return { error: "Elements can't be added next to the root (a document has one root)." };
        if (op.where === "after") return { edits: [insertAfter(source, el, tag)] };
        const indent = ownLineIndent(source, el.start);
        return { edits: [{ offset: el.start, length: 0, text: indent === null ? tag : tag + newline(source) + indent }] };
      }
      const nl = newline(source);
      const indent = ownLineIndent(source, el.start);
      const childIndent = indent === null ? null : indent + indentUnit(source, model);
      if (el.elements.length) {
        // After the last element child, on its own line like it.
        return { edits: [insertAfter(source, el.elements[el.elements.length - 1], tag)] };
      }
      const close = closeStart(source, el);
      if (close < 0) {
        // <a .../>  ->  <a ...>NEW</a>; whitespace before the slash is kept.
        const slash = el.openEnd - 2;
        const inner = childIndent === null ? tag : nl + childIndent + tag + nl + indent;
        return { edits: [{ offset: slash, length: 2, text: `>${inner}</${el.name}>` }] };
      }
      const content = source.slice(el.openEnd, close);
      if ([...content].some((c) => !isXmlWs(c))) return { error: `<${el.name}> has text; adding an element would mix text and elements.` };
      const inner = childIndent === null ? tag : nl + childIndent + tag + nl + indent;
      return { edits: [{ offset: el.openEnd, length: close - el.openEnd, text: inner }] };
    }

    case "newRow": {
      if (isRoot) return { error: "The root element can't be repeated (a document has one root)." };
      return { edits: [insertAfter(source, el, blankCopy(source, el, ownLineIndent(source, el.start) ?? ""))] };
    }

    case "moveElement": {
      if (isRoot) return { error: "The root element can't be moved." };
      const sibs = el.parent!.elements.filter((s) => !op.sameName || s.name === el.name);
      const i = sibs.indexOf(el);
      const other = sibs[op.direction === "up" ? i - 1 : i + 1];
      if (!other) return { error: `<${el.name}> is already the ${op.direction === "up" ? "first" : "last"}.` };
      const [a, b] = other.start < el.start ? [other, el] : [el, other];
      return {
        edits: [
          { offset: b.start, length: b.end - b.start, text: source.slice(a.start, a.end) },
          { offset: a.start, length: a.end - a.start, text: source.slice(b.start, b.end) },
        ],
      };
    }

    case "addColumn": {
      const rows = el.elements.filter((c) => c.name === op.group);
      if (!rows.length) return { error: `<${el.name}> has no <${op.group}> children.` };
      const isAttr = op.name.startsWith("@");
      const name = isAttr ? op.name.slice(1) : op.name;
      const edits: TextEdit[] = [];
      for (const row of rows) {
        const has = isAttr ? row.attrs.some((a) => a.name === name) : row.elements.some((c) => c.name === name);
        if (has) continue;
        const r = computeStructureEdit(
          source,
          model,
          isAttr ? { kind: "addAttr", path: pathOf(row), name, value: "" } : { kind: "insertElement", path: pathOf(row), where: "child", name },
        );
        if ("error" in r) return r;
        edits.push(...r.edits);
      }
      if (!edits.length) return { error: `Every <${op.group}> already has ${isAttr ? "an attribute" : "a child"} ${name}.` };
      return { edits: sortEdits(edits) };
    }

    case "deleteColumn": {
      const rows = el.elements.filter((c) => c.name === op.group);
      if (op.key === "#text") return { error: "The text column can't be deleted; clear its cells instead." };
      const edits: TextEdit[] = [];
      for (const row of rows) {
        if (op.key.startsWith("@")) {
          if (!row.attrs.some((a) => a.name === op.key.slice(1))) continue;
          const r = computeStructureEdit(source, model, { kind: "deleteAttr", path: pathOf(row), name: op.key.slice(1) });
          if ("error" in r) return r;
          edits.push(...r.edits);
        } else {
          for (const c of row.elements) {
            if (c.name !== op.key) continue;
            const r = computeStructureEdit(source, model, { kind: "deleteElement", path: pathOf(c) });
            if ("error" in r) return r;
            edits.push(...r.edits);
          }
        }
      }
      if (!edits.length) return { error: `No <${op.group}> has ${op.key}.` };
      return { edits: sortEdits(edits) };
    }

    case "insertRows": {
      const rows = el.elements.filter((c) => c.name === op.group);
      if (!op.rows.length) return { edits: [] };
      if (!isQName(op.group)) return { error: `"${op.group}" is not a valid XML element name.` };
      for (const row of op.rows) {
        for (const key of Object.keys(row)) {
          const n = key.startsWith("@") ? key.slice(1) : key;
          if (key !== "#text") {
            const bad = checkName(n, el, key.startsWith("@") ? "attribute" : "element");
            if (bad) return { error: bad };
          }
        }
      }
      const template = rows[rows.length - 1] ?? null;
      const nl = newline(source);
      if (template) {
        const indent = ownLineIndent(source, template.start);
        const tmplChild = template.elements[0];
        const childIndent = tmplChild ? ownLineIndent(source, tmplChild.start) : indent === null ? null : indent + indentUnit(source, model);
        const text = op.rows.map((r) => buildRow(op.group, r, childIndent, indent, nl)).join(indent === null ? "" : nl + indent);
        return { edits: [insertAfter(source, template, text)] };
      }
      // No rows yet: append them as children of the element, laid out like its last child (or one level in).
      const indent = ownLineIndent(source, el.start);
      const last = el.elements[el.elements.length - 1];
      const rowIndent = last ? ownLineIndent(source, last.start) : indent === null ? null : indent + indentUnit(source, model);
      const childIndent = rowIndent === null ? null : rowIndent + indentUnit(source, model);
      const text = op.rows.map((r) => buildRow(op.group, r, childIndent, rowIndent, nl)).join(rowIndent === null ? "" : nl + rowIndent);
      return appendChildren(source, model, el, text);
    }

    default:
      return { error: "Unknown operation." };
  }
}

/** Descending offset order, as hosts apply them. */
function sortEdits(edits: TextEdit[]): TextEdit[] {
  return [...edits].sort((a, b) => b.offset - a.offset);
}

/** A row element with the given values: attributes in the start tag, child values as leaf children, `#text` as its text. */
function buildRow(name: string, values: Record<string, string>, childIndent: string | null, indent: string | null, nl: string): string {
  let attrs = "";
  const children: string[] = [];
  let text = "";
  for (const [key, value] of Object.entries(values)) {
    if (key.startsWith("@")) attrs += ` ${key.slice(1)}="${escapeAttr(value, '"')}"`;
    else if (key === "#text") text = escapeText(value);
    else children.push(value === "" ? `<${key}/>` : `<${key}>${escapeText(value)}</${key}>`);
  }
  if (children.length) {
    const inner = childIndent === null || indent === null ? children.join("") : children.map((c) => nl + childIndent + c).join("") + nl + indent;
    return `<${name}${attrs}>${inner}</${name}>`;
  }
  return text ? `<${name}${attrs}>${text}</${name}>` : `<${name}${attrs}/>`;
}

/**
 * A copy of `el` with every value emptied: attributes keep their names with "" values, text is
 * dropped, element children are kept (one of each name) and emptied the same way. Line layout
 * follows the original: children on their own lines stay on their own lines.
 */
function blankCopy(source: string, el: XElement, indent: string): string {
  let tag = `<${el.name}`;
  for (const [prefix, uri] of Object.entries(el.nsDecls)) tag += ` ${prefix ? "xmlns:" + prefix : "xmlns"}="${escapeAttr(uri, '"')}"`;
  for (const a of el.attrs) tag += ` ${a.name}=""`;
  const seen = new Set<string>();
  const kids = el.elements.filter((c) => !seen.has(c.name) && seen.add(c.name));
  if (!kids.length) return tag + "/>";
  const nl = newline(source);
  let inner = "";
  for (const c of kids) {
    const ci = ownLineIndent(source, c.start);
    inner += ci === null ? blankCopy(source, c, indent) : nl + ci + blankCopy(source, c, ci);
  }
  const close = closeStart(source, el);
  if (close >= 0 && ownLineIndent(source, close) !== null) inner += nl + indent;
  return `${tag}>${inner}</${el.name}>`;
}

/** Inserts `text` (one or more elements) as the last children of `el`. */
function appendChildren(source: string, model: XmlModel, el: XElement, text: string): StructureResult {
  const nl = newline(source);
  const indent = ownLineIndent(source, el.start);
  const childIndent = indent === null ? null : indent + indentUnit(source, model);
  if (el.elements.length) return { edits: [insertAfter(source, el.elements[el.elements.length - 1], text)] };
  const inner = childIndent === null ? text : nl + childIndent + text + nl + indent;
  const close = closeStart(source, el);
  if (close < 0) return { edits: [{ offset: el.openEnd - 2, length: 2, text: `>${inner}</${el.name}>` }] };
  const content = source.slice(el.openEnd, close);
  if ([...content].some((c) => !isXmlWs(c))) return { error: `<${el.name}> has text; adding an element would mix text and elements.` };
  return { edits: [{ offset: el.openEnd, length: close - el.openEnd, text: inner }] };
}

/** Deletes several elements (descendants of another listed element are covered by it). */
function deleteElements(source: string, model: XmlModel, paths: ElementPath[]): StructureResult {
  const els: XElement[] = [];
  for (const p of paths) {
    const el = elementAtPath(model.doc, p);
    if (!el) return { error: "The element no longer exists." };
    els.push(el);
  }
  const listed = new Set(els);
  const edits: TextEdit[] = [];
  for (const el of new Set(els)) {
    let covered = false;
    for (let a = el.parent; a; a = a.parent) if (listed.has(a)) covered = true;
    if (covered) continue;
    const r = computeStructureEdit(source, model, { kind: "deleteElement", path: pathOf(el) });
    if ("error" in r) return r;
    edits.push(...r.edits);
  }
  return { edits: sortEdits(edits) };
}

/**
 * Sets grid cells: existing attributes and leaf children get value edits; missing ones are
 * added (attributes at the end of the start tag, children as last children), one insertion per
 * element so edits never touch the same offset.
 */
function setCells(source: string, model: XmlModel, cells: { path: ElementPath; key: string; value: string }[]): StructureResult {
  const byRow = new Map<XElement, { key: string; value: string }[]>();
  for (const c of cells) {
    const el = elementAtPath(model.doc, c.path);
    if (!el) return { error: "The element no longer exists." };
    const list = byRow.get(el) ?? [];
    list.push(c);
    byRow.set(el, list);
  }
  const edits: TextEdit[] = [];
  for (const [el, list] of byRow) {
    const path = pathOf(el);
    let newAttrs = "";
    const newKids: string[] = [];
    for (const { key, value } of list) {
      if (key.startsWith("@")) {
        const name = key.slice(1);
        if (el.attrs.some((a) => a.name === name)) {
          const r = computeValueEdit(source, model, { kind: "attr", path, name }, value);
          if ("error" in r) return r;
          edits.push(r.edit);
        } else {
          const bad = checkName(name, el, "attribute");
          if (bad) return { error: bad };
          newAttrs += ` ${name}="${escapeAttr(value, '"')}"`;
        }
      } else if (key === "#text") {
        const r = computeValueEdit(source, model, { kind: "text", path }, value);
        if ("error" in r) return r;
        edits.push(r.edit);
      } else {
        const child = el.elements.find((c) => c.name === key);
        if (child) {
          const r = computeValueEdit(source, model, { kind: "text", path: pathOf(child) }, value);
          if ("error" in r) return r;
          edits.push(r.edit);
        } else {
          const bad = checkName(key, el, "element");
          if (bad) return { error: bad };
          newKids.push(value === "" ? `<${key}/>` : `<${key}>${escapeText(value)}</${key}>`);
        }
      }
    }
    // Attribute insertion point: after the last attribute, before any whitespace and `>` or `/>`.
    let at = el.openEnd - 1;
    if (source[at - 1] === "/") at--;
    while (isXmlWs(source[at - 1])) at--;
    if (newKids.length) {
      const nl = newline(source);
      const indent = ownLineIndent(source, el.start);
      const childIndent = indent === null ? null : indent + indentUnit(source, model);
      if (el.elements.length) {
        const last = el.elements[el.elements.length - 1];
        const ci = ownLineIndent(source, last.start);
        edits.push({ offset: last.end, length: 0, text: newKids.map((k) => (ci === null ? k : nl + ci + k)).join("") });
      } else {
        const inner = childIndent === null ? newKids.join("") : newKids.map((k) => nl + childIndent + k).join("") + nl + indent;
        const close = closeStart(source, el);
        if (close < 0) {
          // <a .../>: one edit for the new attributes, the opened tag and the children.
          const slash = el.openEnd - 2;
          edits.push({ offset: at, length: el.openEnd - at, text: newAttrs + source.slice(at, slash) + `>${inner}</${el.name}>` });
          newAttrs = "";
        } else {
          const content = source.slice(el.openEnd, close);
          if ([...content].some((c) => !isXmlWs(c))) return { error: `<${el.name}> has text; adding an element would mix text and elements.` };
          edits.push({ offset: el.openEnd, length: close - el.openEnd, text: inner });
        }
      }
    }
    if (newAttrs) edits.push({ offset: at, length: 0, text: newAttrs });
  }
  return { edits: sortEdits(edits) };
}

/** Applies edits given in any order (for tests and previews). */
export function applyEdits(source: string, edits: TextEdit[]): string {
  let out = source;
  for (const e of [...edits].sort((a, b) => b.offset - a.offset)) out = out.slice(0, e.offset) + e.text + out.slice(e.offset + e.length);
  return out;
}
