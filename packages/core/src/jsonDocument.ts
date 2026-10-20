/**
 * JSON as an element tree, so every view works on JSON files too: the root value is an element
 * named `root`, an object member is a child element named after its key, an array's items are
 * children named `item`, and a string, number, boolean or null is the element's text. Each
 * element records the JSON type and source offsets (UTF-16), so edits can rewrite exactly the
 * right tokens. Comments and trailing commas (JSONC) are accepted. See fixtures/SCHEMA.md, "JSON".
 */
import type { JsonType, ParseError, XDocument, XElement } from "./types.js";

export const JSON_ROOT = "root";
export const JSON_ITEM = "item";

class JsonParseError extends Error {
  constructor(
    message: string,
    readonly offset: number,
  ) {
    super(message);
  }
}

export function parseJsonDocument(text: string): XDocument {
  const doc: XDocument = { roots: [], elements: [], errors: [], misc: [], textLength: text.length, format: "json" };
  let pos = 0;
  const open: XElement[] = [];

  const ws = () => {
    for (;;) {
      const c = text.charCodeAt(pos);
      if (c === 0x20 || c === 0x09 || c === 0x0a || c === 0x0d || c === 0xfeff) pos++;
      else if (c === 0x2f && text.charCodeAt(pos + 1) === 0x2f) {
        while (pos < text.length && text[pos] !== "\n" && text[pos] !== "\r") pos++;
      } else if (c === 0x2f && text.charCodeAt(pos + 1) === 0x2a) {
        const end = text.indexOf("*/", pos + 2);
        if (end < 0) throw new JsonParseError("Unterminated comment", pos);
        pos = end + 2;
      } else return;
    }
  };

  const element = (name: string, start: number, parent: XElement | null, json: JsonType): XElement => {
    const el: XElement = {
      kind: "element",
      id: doc.elements.length,
      name,
      local: name,
      prefix: "",
      uri: "",
      attrs: [],
      children: [],
      elements: [],
      parent,
      index: parent ? parent.elements.length : doc.roots.length,
      start,
      end: start,
      openEnd: start,
      nsDecls: {},
      json,
    };
    doc.elements.push(el);
    if (parent) {
      parent.elements.push(el);
      parent.children.push(el);
    } else doc.roots.push(el);
    return el;
  };

  const string = (): string => {
    // pos is at the opening quote.
    const start = pos;
    pos++;
    let out = "";
    let from = pos;
    for (;;) {
      if (pos >= text.length) throw new JsonParseError("Unterminated string", start);
      const c = text.charCodeAt(pos);
      if (c === 0x22) {
        out += text.slice(from, pos);
        pos++;
        return out;
      }
      if (c === 0x5c) {
        out += text.slice(from, pos);
        const e = text[pos + 1];
        pos += 2;
        switch (e) {
          case '"':
          case "\\":
          case "/":
            out += e;
            break;
          case "b":
            out += "\b";
            break;
          case "f":
            out += "\f";
            break;
          case "n":
            out += "\n";
            break;
          case "r":
            out += "\r";
            break;
          case "t":
            out += "\t";
            break;
          case "u": {
            const hex = text.slice(pos, pos + 4);
            if (!/^[0-9a-fA-F]{4}$/.test(hex)) throw new JsonParseError("Invalid \\u escape", pos - 2);
            out += String.fromCharCode(parseInt(hex, 16));
            pos += 4;
            break;
          }
          default:
            throw new JsonParseError("Invalid escape", pos - 2);
        }
        from = pos;
        continue;
      }
      if (c === 0x0a || c === 0x0d) throw new JsonParseError("Unterminated string", start);
      pos++;
    }
  };

  /** Parses a value at pos as element `name` (start = the member's key, or the value itself). */
  const value = (name: string, start: number, parent: XElement | null): void => {
    ws();
    const vStart = pos;
    const c = text[pos];
    if (c === "{" || c === "[") {
      const el = element(name, start, parent, c === "{" ? "object" : "array");
      open.push(el);
      pos++;
      el.openEnd = pos;
      const close = c === "{" ? "}" : "]";
      ws();
      let first = true;
      while (text[pos] !== close) {
        if (pos >= text.length) throw new JsonParseError(`Expected '${close}'`, pos);
        if (!first) {
          if (text[pos] !== ",") throw new JsonParseError(`Expected ',' or '${close}'`, pos);
          pos++;
          ws();
          if (text[pos] === close) break; // trailing comma (JSONC)
        }
        first = false;
        if (c === "{") {
          if (text[pos] !== '"') throw new JsonParseError("Expected a property name in double quotes", pos);
          const keyStart = pos;
          const key = string();
          ws();
          if (text[pos] !== ":") throw new JsonParseError("Expected ':'", pos);
          pos++;
          value(key, keyStart, el);
        } else {
          ws();
          value(JSON_ITEM, pos, el);
        }
        ws();
      }
      pos++;
      el.end = pos;
      open.pop();
      return;
    }
    let type: JsonType;
    let v: string;
    if (c === '"') {
      type = "string";
      v = string();
    } else {
      const m = /^(?:-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null)/.exec(text.slice(pos, pos + 400));
      if (!m) throw new JsonParseError(pos >= text.length ? "Unexpected end of input" : `Unexpected '${text[pos]}'`, pos);
      v = m[0];
      type = v === "true" || v === "false" ? "boolean" : v === "null" ? "null" : "number";
      pos += v.length;
    }
    const el = element(name, start, parent, type);
    el.openEnd = vStart;
    el.end = pos;
    el.children.push({ kind: "text", value: v, start: vStart, end: pos });
  };

  try {
    ws();
    if (pos >= text.length) throw new JsonParseError("The document is empty", pos);
    value(JSON_ROOT, pos, null);
    ws();
    if (pos < text.length) throw new JsonParseError(`Unexpected '${text[pos]}' after the end of the document`, pos);
  } catch (e) {
    if (!(e instanceof JsonParseError)) throw e;
    doc.errors.push(error(text, e.message, e.offset));
    for (const el of open) el.end = text.length;
  }
  return doc;
}

function error(text: string, message: string, offset: number): ParseError {
  let line = 1;
  let lineStart = 0;
  for (let i = 0; i < offset && i < text.length; i++) {
    if (text[i] === "\n") {
      line++;
      lineStart = i + 1;
    }
  }
  return { message, offset, line, column: offset - lineStart + 1 };
}

/** Whether a file name looks like JSON (by extension). */
export function isJsonFileName(name: string): boolean {
  return /\.(json|jsonc|json5|geojson|har|webmanifest|babelrc|eslintrc)$/i.test(name) || /(^|[\\/])\.?(babelrc|eslintrc)$/i.test(name);
}
