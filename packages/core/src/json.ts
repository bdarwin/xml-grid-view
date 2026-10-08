/**
 * JSON support for the value inspector: a strict parser that keeps number
 * literals exactly as written, a pretty printer, path helpers, and a grid model
 * that follows the same ideas as the XML grid (see fixtures/SCHEMA.md,
 * "JSON value grids").
 */
import type { GridColumn, GridTable } from "./model.js";

/** A JSON number, kept as its source literal so large or precise values display exactly. */
export class JsonNumber {
  constructor(readonly text: string) {}
}

/** Objects are Maps: insertion order is kept and keys like `__proto__` are plain data. */
export type JsonObject = Map<string, JsonValue>;
export type JsonValue = null | boolean | string | JsonNumber | JsonValue[] | JsonObject;
export type JsonPath = (string | number)[];

export interface JsonSyntaxError {
  message: string;
  /** Offset into the trimmed text, and 1-based line/column. */
  offset: number;
  line: number;
  column: number;
}

export type JsonDetection = { kind: "json"; value: JsonValue; pretty: string } | { kind: "text"; jsonError?: JsonSyntaxError };

/** Pseudo column keys of JSON grids. */
export const JSON_KEY_COLUMN = "(key)";
export const JSON_VALUE_COLUMN = "(value)";

export function isJsonObject(v: JsonValue | undefined): v is JsonObject {
  return v instanceof Map;
}

export function isJsonContainer(v: JsonValue | undefined): v is JsonValue[] | JsonObject {
  return Array.isArray(v) || v instanceof Map;
}

class ParseFailure extends Error {
  constructor(
    message: string,
    readonly offset: number,
  ) {
    super(message);
  }
}

const MAX_DEPTH = 1000;

/** Strict RFC 8259 parser. Throws ParseFailure with an offset on error. */
function parseStrict(s: string): JsonValue {
  let i = 0;
  const fail = (msg: string): never => {
    throw new ParseFailure(i >= s.length ? "Unexpected end of JSON" : msg, i);
  };
  const ws = () => {
    for (;;) {
      const c = s.charCodeAt(i);
      if (c === 32 || c === 9 || c === 10 || c === 13) i++;
      else break;
    }
  };
  const value = (depth: number): JsonValue => {
    if (depth > MAX_DEPTH) fail("Nesting is too deep");
    ws();
    const c = s[i];
    if (c === "{") {
      i++;
      const obj: JsonObject = new Map();
      ws();
      if (s[i] === "}") {
        i++;
        return obj;
      }
      for (;;) {
        ws();
        if (s[i] !== '"') fail("Expected a property name in double quotes");
        const key = str();
        ws();
        if (s[i] !== ":") fail("Expected ':' after property name");
        i++;
        obj.set(key, value(depth + 1));
        ws();
        if (s[i] === ",") {
          i++;
          continue;
        }
        if (s[i] === "}") {
          i++;
          return obj;
        }
        fail("Expected ',' or '}'");
      }
    }
    if (c === "[") {
      i++;
      const arr: JsonValue[] = [];
      ws();
      if (s[i] === "]") {
        i++;
        return arr;
      }
      for (;;) {
        arr.push(value(depth + 1));
        ws();
        if (s[i] === ",") {
          i++;
          continue;
        }
        if (s[i] === "]") {
          i++;
          return arr;
        }
        fail("Expected ',' or ']'");
      }
    }
    if (c === '"') return str();
    if (s.startsWith("true", i)) {
      i += 4;
      return true;
    }
    if (s.startsWith("false", i)) {
      i += 5;
      return false;
    }
    if (s.startsWith("null", i)) {
      i += 4;
      return null;
    }
    const m = /-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/y;
    m.lastIndex = i;
    const r = m.exec(s);
    if (r && r[0].length) {
      i += r[0].length;
      return new JsonNumber(r[0]);
    }
    return i >= s.length ? fail("Unexpected end of JSON") : fail(`Unexpected character '${c}'`);
  };
  const str = (): string => {
    i++; // opening quote
    let out = "";
    let start = i;
    for (;;) {
      if (i >= s.length) fail("Unterminated string");
      const c = s.charCodeAt(i);
      if (c === 34) {
        out += s.slice(start, i);
        i++;
        return out;
      }
      if (c < 0x20) fail("Control character in string");
      if (c === 92) {
        out += s.slice(start, i);
        const e = s[i + 1];
        const simple: Record<string, string> = { '"': '"', "\\": "\\", "/": "/", b: "\b", f: "\f", n: "\n", r: "\r", t: "\t" };
        if (e in simple) {
          out += simple[e];
          i += 2;
        } else if (e === "u") {
          const hex = s.slice(i + 2, i + 6);
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) fail("Invalid \\u escape");
          out += String.fromCharCode(parseInt(hex, 16));
          i += 6;
        } else {
          fail("Invalid escape");
        }
        start = i;
        continue;
      }
      i++;
    }
  };
  const v = value(0);
  ws();
  if (i < s.length) fail("Unexpected text after the JSON value");
  return v;
}

function lineCol(s: string, offset: number) {
  let line = 1;
  let column = 1;
  for (let i = 0; i < offset && i < s.length; i++) {
    if (s.charCodeAt(i) === 10) {
      line++;
      column = 1;
    } else column++;
  }
  return { line, column };
}

/** Parses JSON strictly; returns the value or a syntax error with its location. */
export function parseJson(text: string): { value: JsonValue } | { error: JsonSyntaxError } {
  try {
    return { value: parseStrict(text) };
  } catch (e) {
    if (!(e instanceof ParseFailure)) throw e;
    return { error: { message: e.message, offset: e.offset, ...lineCol(text, e.offset) } };
  }
}

/**
 * Treats text as JSON when, trimmed, it starts with `{` or `[` and parses.
 * Text that looks like JSON but fails to parse reports where parsing stopped.
 */
export function detectJson(text: string): JsonDetection {
  const t = text.trim();
  if (!(t.startsWith("{") || t.startsWith("["))) return { kind: "text" };
  const r = parseJson(t);
  if ("error" in r) return { kind: "text", jsonError: r.error };
  return { kind: "json", value: r.value, pretty: prettyJson(r.value) };
}

/** Pretty prints with 2-space indentation; numbers keep their source literal. */
export function prettyJson(v: JsonValue, indent = ""): string {
  if (Array.isArray(v)) {
    if (!v.length) return "[]";
    const inner = indent + "  ";
    return "[\n" + v.map((x) => inner + prettyJson(x, inner)).join(",\n") + "\n" + indent + "]";
  }
  if (v instanceof Map) {
    if (!v.size) return "{}";
    const inner = indent + "  ";
    return (
      "{\n" + [...v].map(([k, x]) => `${inner}${JSON.stringify(k)}: ${prettyJson(x, inner)}`).join(",\n") + "\n" + indent + "}"
    );
  }
  return v instanceof JsonNumber ? v.text : JSON.stringify(v);
}

/** Display text of a primitive: strings as-is, numbers as written, `true`/`false`/`null`. */
export function jsonPrimitiveText(v: JsonValue): string {
  if (typeof v === "string") return v;
  if (v instanceof JsonNumber) return v.text;
  return String(v);
}

/** Short label of a container, e.g. `{ 3 keys }` or `[ 5 items ]`. */
export function jsonContainerLabel(v: JsonValue[] | JsonObject): string {
  const n = Array.isArray(v) ? v.length : v.size;
  return Array.isArray(v) ? `[ ${n} item${n === 1 ? "" : "s"} ]` : `{ ${n} key${n === 1 ? "" : "s"} }`;
}

export function jsonAt(root: JsonValue, path: JsonPath): JsonValue | undefined {
  let v: JsonValue | undefined = root;
  for (const seg of path) {
    if (Array.isArray(v)) v = v[seg as number];
    else if (v instanceof Map) v = v.get(String(seg));
    else return undefined;
    if (v === undefined) return undefined;
  }
  return v;
}

/** JSON Pointer (RFC 6901) for a path; "" is the root. */
export function jsonPointer(path: JsonPath): string {
  return path.map((s) => "/" + String(s).replace(/~/g, "~0").replace(/\//g, "~1")).join("");
}

/** A JSON grid in the same shape as an XML GridTable, so the same grid UI can show it. */
export interface JsonTable extends GridTable {
  /** Row keys: array indices or object keys, as strings. */
  rowKeys: string[];
  /** Child path segment per row (number for arrays, string for objects). */
  rowSegments: (string | number)[];
  /** Display labels for drill cells (row-major, null elsewhere). */
  labels: (string | null)[];
  /** Container kind per drill cell (row-major, null elsewhere). */
  drillKinds: ("object" | "array" | null)[];
}

/**
 * Grid for a JSON value:
 * - array: one row per item. Object items contribute their keys as columns (union, first-seen order);
 *   primitive or array items go in a leading `(value)` column.
 * - object whose values are all objects (two or more entries): a "map" table, one row per entry, with a
 *   leading `(key)` column followed by the union of the values' keys.
 * - any other object: one row per entry with `(key)` and `(value)` columns.
 * - primitive: a single `(value)` row.
 * Cells: absent = null; primitives as text; nested containers as drill cells (count of items/keys).
 */
export function jsonTable(node: JsonValue): JsonTable {
  type Row = { key: string; seg: string | number; item: JsonValue; spread: boolean };
  const rows: Row[] = [];
  let hasKeyCol = false;
  if (Array.isArray(node)) {
    node.forEach((item, i) => rows.push({ key: String(i), seg: i, item, spread: isJsonObject(item) }));
  } else if (node instanceof Map) {
    hasKeyCol = true;
    const entries = [...node];
    const map = entries.length >= 2 && entries.every(([, v]) => isJsonObject(v));
    for (const [k, v] of entries) rows.push({ key: k, seg: k, item: v, spread: map });
  } else {
    rows.push({ key: "", seg: "", item: node, spread: false });
  }

  const keyOrder: string[] = [];
  const seen = new Set<string>();
  let hasValueCol = false;
  for (const r of rows) {
    if (r.spread) {
      for (const k of (r.item as JsonObject).keys()) {
        if (!seen.has(k)) {
          seen.add(k);
          keyOrder.push(k);
        }
      }
    } else hasValueCol = true;
  }

  const columns: GridColumn[] = [];
  if (hasKeyCol) columns.push({ key: JSON_KEY_COLUMN, label: JSON_KEY_COLUMN, kind: "leaf" });
  if (hasValueCol) columns.push({ key: JSON_VALUE_COLUMN, label: JSON_VALUE_COLUMN, kind: "leaf" });
  for (const k of keyOrder) columns.push({ key: k, label: k, kind: "leaf" });

  const ncols = columns.length;
  const cells: (string | number | null)[] = new Array(rows.length * ncols).fill(null);
  const labels: (string | null)[] = new Array(rows.length * ncols).fill(null);
  const drillKinds: ("object" | "array" | null)[] = new Array(rows.length * ncols).fill(null);
  const put = (r: number, c: number, v: JsonValue) => {
    const i = r * ncols + c;
    if (isJsonContainer(v)) {
      cells[i] = Array.isArray(v) ? v.length : v.size;
      labels[i] = jsonContainerLabel(v);
      drillKinds[i] = Array.isArray(v) ? "array" : "object";
      columns[c].kind = "complex";
    } else {
      cells[i] = jsonPrimitiveText(v);
    }
  };
  // Columns are addressed by position, so an object key literally named "(key)" or "(value)"
  // still gets its own column.
  const keyCol = hasKeyCol ? 0 : undefined;
  const valueCol = hasValueCol ? (hasKeyCol ? 1 : 0) : undefined;
  const firstKeyCol = (hasKeyCol ? 1 : 0) + (hasValueCol ? 1 : 0);
  const keyColIndex = new Map(keyOrder.map((k, i) => [k, firstKeyCol + i]));
  rows.forEach((row, r) => {
    if (keyCol !== undefined) cells[r * ncols + keyCol] = row.key;
    if (row.spread) for (const [k, v] of row.item as JsonObject) put(r, keyColIndex.get(k)!, v);
    else put(r, valueCol!, row.item);
  });

  return {
    elementId: -1,
    group: null,
    groups: [],
    columns,
    rowIds: Int32Array.from(rows.map((_, i) => i)),
    cells,
    spans: new Int32Array(rows.length * ncols * 2).fill(-1),
    rowKeys: rows.map((r) => r.key),
    rowSegments: rows.map((r) => r.seg),
    labels,
    drillKinds,
  };
}

/** Visits every container in document order with its path (the root first). */
export function forEachJsonContainer(root: JsonValue, fn: (node: JsonValue[] | JsonObject, path: JsonPath) => void) {
  const walk = (v: JsonValue, path: JsonPath) => {
    if (!isJsonContainer(v)) return;
    fn(v, path);
    if (Array.isArray(v)) v.forEach((x, i) => walk(x, [...path, i]));
    else for (const [k, x] of v) walk(x, [...path, k]);
  };
  walk(root, []);
}
