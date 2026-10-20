/**
 * Structural edits: delete, insert, duplicate and rename elements, add and delete
 * attributes. Each operation becomes a few text replacements on the source (in
 * descending offset order), applied by hosts as one undoable edit. Formatting is
 * kept: new lines copy the surrounding indentation. See fixtures/SCHEMA.md,
 * "Structural edits".
 */
import type { XmlModel } from "./model.js";
import type { XElement } from "./types.js";
import { elementAtPath, type ElementPath } from "./paths.js";
import { escapeAttr, type TextEdit } from "./edits.js";

export type StructureOp =
  | { kind: "deleteElement"; path: ElementPath }
  | { kind: "deleteAttr"; path: ElementPath; name: string }
  | { kind: "addAttr"; path: ElementPath; name: string; value: string }
  | { kind: "renameElement"; path: ElementPath; name: string }
  | { kind: "insertElement"; path: ElementPath; where: "before" | "after" | "child"; name: string }
  | { kind: "duplicateElement"; path: ElementPath };

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
  }
}

/** Applies edits given in any order (for tests and previews). */
export function applyEdits(source: string, edits: TextEdit[]): string {
  let out = source;
  for (const e of [...edits].sort((a, b) => b.offset - a.offset)) out = out.slice(0, e.offset) + e.text + out.slice(e.offset + e.length);
  return out;
}
