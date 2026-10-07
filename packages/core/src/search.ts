import type { GridTable, XmlModel } from "./model.js";

export interface SearchOptions {
  caseSensitive: boolean;
  wholeWord: boolean;
  regex: boolean;
}

export interface SearchTargets {
  names: boolean;
  attrNames: boolean;
  attrValues: boolean;
  text: boolean;
}

export type SearchScope = "grid" | "document";

export type MatchTarget = "name" | "attrName" | "attrValue" | "text";

export interface SearchQuery {
  text: string;
  options: SearchOptions;
  targets: SearchTargets;
}

export const DEFAULT_OPTIONS: SearchOptions = { caseSensitive: false, wholeWord: false, regex: false };
export const ALL_TARGETS: SearchTargets = { names: true, attrNames: true, attrValues: true, text: true };

/** One matching field of one element. */
export interface DocMatch {
  elementId: number;
  target: MatchTarget;
  /** Attribute index for attrName/attrValue targets, otherwise -1. */
  attrIndex: number;
  /** The matched field value (truncated for display). */
  snippet: string;
}

export interface DocSearchResult {
  matches: DocMatch[];
  truncated: boolean;
  error?: string;
}

export interface GridSearchResult {
  /** Flat [row, col] pairs in table (unsorted) order. */
  cells: Int32Array;
  error?: string;
}

export interface Matcher {
  /** True when `s` contains at least one non-empty match. */
  test(s: string): boolean;
  /** Non-overlapping [start, end) ranges of matches in `s`. */
  ranges(s: string): [number, number][];
  /**
   * Fast test against a pre-lowercased string; only valid when `usesLower` is true.
   */
  testLower(lower: string): boolean;
  readonly usesLower: boolean;
}

const WORD = "[\\p{L}\\p{N}_]";

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Compiles a query into a matcher. Throws an Error with a readable message for
 * invalid regular expressions. Returns null for an empty query.
 */
export function createMatcher(text: string, options: SearchOptions): Matcher | null {
  if (text === "") return null;

  if (!options.regex && !options.wholeWord && !options.caseSensitive) {
    const needle = text.toLowerCase();
    return {
      usesLower: true,
      testLower: (lower) => lower.includes(needle),
      test: (s) => s.toLowerCase().includes(needle),
      ranges: (s) => {
        const lower = s.toLowerCase();
        const out: [number, number][] = [];
        // Lowercasing may change length for a few characters; fall back to regex then.
        if (lower.length !== s.length) return regexRanges(new RegExp(escapeRegExp(text), "giu"), s);
        for (let i = lower.indexOf(needle); i >= 0; i = lower.indexOf(needle, i + needle.length)) {
          out.push([i, i + needle.length]);
        }
        return out;
      },
    };
  }

  let source = options.regex ? text : escapeRegExp(text);
  const flags = "g" + (options.caseSensitive ? "" : "i");
  let re: RegExp;
  try {
    const src = options.wholeWord ? `(?<!${WORD})(?:${source})(?!${WORD})` : source;
    re = new RegExp(src, flags + "u");
  } catch (e) {
    // Some patterns are valid only without the `u` flag (e.g. `\-`); retry with ASCII word boundaries.
    try {
      if (options.wholeWord) source = `\\b(?:${source})\\b`;
      re = new RegExp(source, flags);
    } catch {
      throw new Error(String((e as Error).message).replace(/^Invalid regular expression: /, ""));
    }
  }
  return {
    usesLower: false,
    testLower: () => {
      throw new Error("testLower not supported");
    },
    test: (s) => regexRanges(re, s, 1).length > 0,
    ranges: (s) => regexRanges(re, s),
  };
}

function regexRanges(re: RegExp, s: string, limit = Infinity): [number, number][] {
  const out: [number, number][] = [];
  re.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(s)) !== null) {
    if (m[0].length === 0) {
      re.lastIndex++;
      if (re.lastIndex > s.length) break;
      continue;
    }
    out.push([m.index, m.index + m[0].length]);
    if (out.length >= limit) break;
  }
  return out;
}

function snippet(s: string): string {
  const t = s.replace(/\s+/g, " ").trim();
  return t.length > 120 ? t.slice(0, 119) + "…" : t;
}

/** Searches every element of the document. Results are in document order. */
export function searchDocument(model: XmlModel, query: SearchQuery, limit = 100_000): DocSearchResult {
  let matcher: Matcher | null;
  try {
    matcher = createMatcher(query.text, query.options);
  } catch (e) {
    return { matches: [], truncated: false, error: (e as Error).message };
  }
  if (!matcher) return { matches: [], truncated: false };

  const { targets } = query;
  const idx = model.index;
  const fast = matcher.usesLower;
  const names = fast ? idx.lower.names : idx.names;
  const attrNames = fast ? idx.lower.attrNames : idx.attrNames;
  const attrValues = fast ? idx.lower.attrValues : idx.attrValues;
  const texts = fast ? idx.lower.texts : idx.texts;
  const test = fast ? matcher.testLower : matcher.test;

  const matches: DocMatch[] = [];
  const n = names.length;
  const push = (m: DocMatch) => {
    matches.push(m);
    return matches.length >= limit;
  };
  for (let i = 0; i < n; i++) {
    if (targets.names && test(names[i])) {
      if (push({ elementId: i, target: "name", attrIndex: -1, snippet: idx.names[i] })) return { matches, truncated: true };
    }
    if (targets.attrNames || targets.attrValues) {
      const an = attrNames[i];
      const av = attrValues[i];
      for (let a = 0; a < an.length; a++) {
        if (targets.attrNames && test(an[a])) {
          if (push({ elementId: i, target: "attrName", attrIndex: a, snippet: idx.attrNames[i][a] }))
            return { matches, truncated: true };
        }
        if (targets.attrValues && test(av[a])) {
          if (push({ elementId: i, target: "attrValue", attrIndex: a, snippet: snippet(idx.attrValues[i][a]) }))
            return { matches, truncated: true };
        }
      }
    }
    if (targets.text && texts[i] && test(texts[i])) {
      if (push({ elementId: i, target: "text", attrIndex: -1, snippet: snippet(idx.texts[i]) }))
        return { matches, truncated: true };
    }
  }
  return { matches, truncated: false };
}

/**
 * Searches the cells of one grid. Attribute cells count as attribute values,
 * leaf and #text cells as text, and complex `{tag ×n}` cells as names.
 */
export function searchGrid(table: GridTable, query: SearchQuery): GridSearchResult {
  let matcher: Matcher | null;
  try {
    matcher = createMatcher(query.text, query.options);
  } catch (e) {
    return { cells: new Int32Array(0), error: (e as Error).message };
  }
  if (!matcher) return { cells: new Int32Array(0) };
  const { targets } = query;
  const cols = table.columns;
  const enabled = cols.map((c) =>
    c.kind === "attr" ? targets.attrValues : c.kind === "complex" ? targets.names : targets.text,
  );
  const out: number[] = [];
  const ncols = cols.length;
  const nrows = table.rowIds.length;
  for (let r = 0; r < nrows; r++) {
    for (let c = 0; c < ncols; c++) {
      if (!enabled[c]) continue;
      const v = table.cells[r * ncols + c];
      if (v === null) continue;
      const s = typeof v === "number" ? cols[c].label : v;
      if (matcher.test(s)) out.push(r, c);
    }
  }
  return { cells: Int32Array.from(out) };
}
