/**
 * Value edits: turns "set this attribute / this leaf element's text to V" into a
 * minimal text replacement on the source, so hosts can apply it through their
 * own undoable edit APIs. See fixtures/SCHEMA.md, "Value edits".
 */
import type { XmlModel } from "./model.js";
import type { XElement } from "./types.js";
import { elementAtPath, type ElementPath } from "./paths.js";

export type EditTarget =
  | { kind: "attr"; path: ElementPath; name: string }
  | { kind: "text"; path: ElementPath };

/** Replace `length` characters at `offset` (UTF-16 units) with `text`. */
export interface TextEdit {
  offset: number;
  length: number;
  text: string;
}

export type EditResult = { edit: TextEdit } | { error: string };

/** Escapes an attribute value for the given quote character; whitespace controls become character references. */
export function escapeAttr(value: string, quote: '"' | "'"): string {
  let out = "";
  for (const ch of value) {
    switch (ch) {
      case "&":
        out += "&amp;";
        break;
      case "<":
        out += "&lt;";
        break;
      case '"':
        out += quote === '"' ? "&quot;" : ch;
        break;
      case "'":
        out += quote === "'" ? "&apos;" : ch;
        break;
      case "\t":
        out += "&#9;";
        break;
      case "\n":
        out += "&#10;";
        break;
      case "\r":
        out += "&#13;";
        break;
      default:
        out += ch;
    }
  }
  return out;
}

/** Escapes element text: `&`, `<` and `>` (the latter so `]]>` can never appear). */
export function escapeText(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/** Wraps a value in CDATA, splitting any `]]>` across sections. */
export function cdata(value: string): string {
  return "<![CDATA[" + value.replace(/]]>/g, "]]]]><![CDATA[>") + "]]>";
}

/** Whether an element's value can be edited as text (no element children). */
export function isTextEditable(el: XElement): boolean {
  return el.elements.length === 0;
}

/**
 * Computes the replacement that sets a value. The source text must be the text the
 * model was parsed from. Returns an error (and no edit) for anything that would
 * need a structural change.
 */
export function computeValueEdit(source: string, model: XmlModel, target: EditTarget, value: string): EditResult {
  const el = elementAtPath(model.doc, target.path);
  if (!el) return { error: "The element no longer exists." };
  if (model.errors.length) return { error: "The document is not well-formed; fix it before editing values." };

  if (target.kind === "attr") {
    const attr = el.attrs.find((a) => a.name === target.name);
    if (!attr) return { error: `Attribute ${target.name} does not exist on <${el.name}>.` };
    const raw = source.slice(attr.start, attr.end);
    const eq = raw.indexOf("=");
    const quote = raw[raw.length - 1];
    if (eq < 0 || (quote !== '"' && quote !== "'")) return { error: "Could not locate the attribute value in the source." };
    const open = raw.indexOf(quote, eq);
    const offset = attr.start + open + 1;
    const length = attr.end - 1 - offset;
    return { edit: { offset, length, text: escapeAttr(value, quote) } };
  }

  if (!isTextEditable(el)) return { error: `<${el.name}> has child elements; only leaf elements' text can be edited.` };
  const selfClosing = el.end === el.openEnd;
  if (selfClosing) {
    // <tag .../>  ->  <tag ...>value</tag>
    const slash = source.lastIndexOf("/", el.openEnd - 1);
    if (slash < el.start || source[el.openEnd - 1] !== ">") return { error: "Could not locate the empty-element tag." };
    if (value === "") return { edit: { offset: el.openEnd, length: 0, text: "" } };
    return { edit: { offset: slash, length: el.openEnd - slash, text: `>${escapeText(value)}</${el.name}>` } };
  }
  const closeStart = source.lastIndexOf("</", el.end - 1);
  if (closeStart < el.openEnd) return { error: "Could not locate the end tag." };
  const content = source.slice(el.openEnd, closeStart);
  if (content.includes("<!--") || content.includes("<?")) {
    return { error: "The value contains comments or processing instructions; edit it in the text editor." };
  }
  // Keep CDATA when the current value is a single CDATA section (ignoring surrounding whitespace).
  const t = content.trim();
  const isCdata = t.startsWith("<![CDATA[") && t.endsWith("]]>") && t.indexOf("<![CDATA[", 1) < 0;
  return { edit: { offset: el.openEnd, length: closeStart - el.openEnd, text: isCdata ? cdata(value) : escapeText(value) } };
}

/** Applies an edit to a string (for tests and previews). */
export function applyEdit(source: string, edit: TextEdit): string {
  return source.slice(0, edit.offset) + edit.text + source.slice(edit.offset + edit.length);
}
