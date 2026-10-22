import { elementText } from "./model.js";
import type { XDocument, XElement } from "./types.js";

/**
 * What changed between an earlier version of a document (the baseline, e.g. the last commit) and
 * the current one, as a tree diff: elements are matched by parent, name and content, so a moved
 * line break or reformatting does not count as a change, and the result names elements rather
 * than text ranges.
 */
export interface DocumentChanges {
  /** Current elements that are new (all their descendants are new too and are not listed). */
  added: number[];
  /** Current elements whose own attributes or text changed. */
  modified: ElementChange[];
  /** Elements of the baseline that are gone, by the current parent they were removed from. */
  removed: RemovedElement[];
}

export interface ElementChange {
  id: number;
  /** Attributes that changed, by name: the old value, or null when the attribute is new. */
  attrs?: Record<string, string | null>;
  /** Attributes the element no longer has, with their old values. */
  removedAttrs?: Record<string, string>;
  /** The old text, when the element's own text changed. */
  text?: string;
  /** JSON: the old value type, when it changed (e.g. "41" became 41). */
  type?: string;
}

export interface RemovedElement {
  /** Current parent element id, or -1 for a removed top-level element. */
  parent: number;
  /** Number of current element siblings before the removed one. */
  before: number;
  name: string;
  /** The removed element's text, or a summary of its attributes, shortened. */
  preview: string;
}

const MAX_MATCH_COST = 4_000_000;
const MAX_PAIR_COST = 250_000;

/** Compares two parsed documents. Both must be the same format. */
export function diffDocuments(base: XDocument, cur: XDocument): DocumentChanges {
  const out: DocumentChanges = { added: [], modified: [], removed: [] };
  const baseHash = subtreeHashes(base);
  const curHash = subtreeHashes(cur);
  matchChildren(base.roots, cur.roots, null, baseHash, curHash, out);
  out.added.sort((a, b) => a - b);
  out.modified.sort((a, b) => a.id - b.id);
  return out;
}

/** Hash of each element's name, attributes, text and children, by element id. */
function subtreeHashes(doc: XDocument): Uint32Array {
  const h = new Uint32Array(doc.elements.length);
  // Children have higher ids than their parent, so walking backwards sees them first.
  for (let i = doc.elements.length - 1; i >= 0; i--) {
    const el = doc.elements[i];
    let x = hashString(ownSignature(el));
    for (const c of el.elements) x = Math.imul(x ^ h[c.id], 0x01000193) >>> 0;
    h[i] = x;
  }
  return h;
}

function ownSignature(el: XElement): string {
  let s = el.name + "\u0000" + (el.json ?? "") + "\u0000" + elementText(el);
  for (const a of el.attrs) s += "\u0000" + a.name + "=" + a.value;
  return s;
}

function hashString(s: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 0x01000193);
  return h >>> 0;
}

/**
 * Matches the children of a base and a current element: identical subtrees first (longest common
 * subsequence of hashes), then, between those, same-named elements in order, which are compared
 * recursively. The rest are added or removed.
 */
function matchChildren(bs: XElement[], cs: XElement[], parent: XElement | null, bh: Uint32Array, ch: Uint32Array, out: DocumentChanges) {
  const same = lcs(
    bs.map((e) => bh[e.id]),
    cs.map((e) => ch[e.id]),
  );
  let bi = 0;
  let ci = 0;
  for (const [bj, cj] of [...same, [bs.length, cs.length] as const]) {
    if (bj > bi || cj > ci) matchByName(bs.slice(bi, bj), cs.slice(ci, cj), parent, ci, bh, ch, out);
    bi = bj + 1;
    ci = cj + 1;
  }
}

/**
 * Pairs the unmatched elements between two identical ones: same-named elements, in order,
 * preferring the pairing whose elements share the most attributes, text and children, so a removed
 * record next to an edited one is reported as removed rather than as edited.
 */
function matchByName(
  bs: XElement[],
  cs: XElement[],
  parent: XElement | null,
  ciOffset: number,
  bh: Uint32Array,
  ch: Uint32Array,
  out: DocumentChanges,
) {
  const pairs = bestPairs(bs, cs, bh, ch);
  let bi = 0;
  let ci = 0;
  for (const [bj, cj] of [...pairs, [bs.length, cs.length] as const]) {
    for (; bi < bj; bi++)
      out.removed.push({ parent: parent ? parent.id : -1, before: ciOffset + ci, name: bs[bi].name, preview: removedPreview(bs[bi]) });
    for (; ci < cj; ci++) out.added.push(cs[ci].id);
    if (bj < bs.length) {
      compareElements(bs[bj], cs[cj], bh, ch, out);
      bi = bj + 1;
      ci = cj + 1;
    }
  }
}

/** Order-preserving pairs of same-named elements with the largest total similarity. */
function bestPairs(bs: XElement[], cs: XElement[], bh: Uint32Array, ch: Uint32Array): [number, number][] {
  const n = bs.length;
  const m = cs.length;
  if (!n || !m) return [];
  if (n * m > MAX_PAIR_COST)
    return lcs(
      bs.map((e) => hashString(e.name)),
      cs.map((e) => hashString(e.name)),
    );
  const bf = bs.map((e) => features(e, bh));
  const cf = cs.map((e) => features(e, ch));
  const score = (i: number, j: number) => {
    if (bs[i].name !== cs[j].name) return 0;
    const bk = identity(bs[i]);
    const ck = identity(cs[j]);
    if (bk !== null && ck !== null && bk !== ck) return 0;
    let common = 0;
    for (const f of cf[j]) if (bf[i].has(f)) common++;
    return 1 + common;
  };
  const w = m + 1;
  const dp = new Float64Array((n + 1) * w);
  const pick = new Float64Array(n * m);
  for (let i = n - 1; i >= 0; i--)
    for (let j = m - 1; j >= 0; j--) {
      const s = score(i, j);
      pick[i * m + j] = s;
      dp[i * w + j] = Math.max(dp[(i + 1) * w + j], dp[i * w + j + 1], s ? s + dp[(i + 1) * w + j + 1] : 0);
    }
  const pairs: [number, number][] = [];
  for (let i = 0, j = 0; i < n && j < m;) {
    const s = pick[i * m + j];
    if (s && dp[i * w + j] === s + dp[(i + 1) * w + j + 1]) {
      pairs.push([i, j]);
      i++;
      j++;
    } else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) i++;
    else j++;
  }
  return pairs;
}

const KEY_ATTRS = ["id", "key", "name"];
const KEY_CHILDREN = ["id", "key"];

/**
 * What identifies a record: its id, key or name attribute, else the text of an id or key child
 * without children of its own (e.g. a JSON "id" member). Records with different identities are never
 * paired as edits of each other: one was removed and another added.
 */
function identity(el: XElement): string | null {
  for (const k of KEY_ATTRS) {
    const a = el.attrs.find((x) => x.name === k);
    if (a) return "@" + k + "=" + a.value;
  }
  for (const k of KEY_CHILDREN) {
    const c = el.elements.find((x) => x.name === k && !x.elements.length);
    if (c) return k + "=" + elementText(c);
  }
  return null;
}

/** What an element is made of, for similarity: its attributes, its text and its children's hashes. */
function features(el: XElement, h: Uint32Array): Set<string> {
  const f = new Set<string>();
  for (const a of el.attrs) f.add("@" + a.name + "=" + a.value);
  const t = elementText(el);
  if (t) f.add("#" + t);
  for (const c of el.elements) f.add(String(h[c.id]));
  return f;
}

function compareElements(b: XElement, c: XElement, bh: Uint32Array, ch: Uint32Array, out: DocumentChanges) {
  if (bh[b.id] === ch[c.id] && ownSignature(b) === ownSignature(c)) return;
  const change: ElementChange = { id: c.id };
  const old = new Map(b.attrs.map((a) => [a.name, a.value]));
  for (const a of c.attrs) {
    const was = old.get(a.name);
    if (was === undefined) (change.attrs ??= {})[a.name] = null;
    else if (was !== a.value) (change.attrs ??= {})[a.name] = was;
    old.delete(a.name);
  }
  for (const [name, value] of old) (change.removedAttrs ??= {})[name] = value;
  const bt = elementText(b);
  if (bt !== elementText(c) || b.json !== c.json) change.text = bt;
  if (b.json !== c.json && b.json) change.type = b.json;
  if (change.attrs || change.removedAttrs || change.text !== undefined) out.modified.push(change);
  matchChildren(b.elements, c.elements, c, bh, ch, out);
}

function removedPreview(el: XElement): string {
  const parts = el.attrs.map((a) => a.value);
  const t = elementText(el);
  if (t) parts.push(t);
  for (const c of el.elements) if (!c.elements.length && parts.length < 8) parts.push(elementText(c));
  const s = parts
    .filter((p) => p)
    .join(" · ")
    .replace(/\s+/g, " ")
    .trim();
  return s.length > 80 ? s.slice(0, 79) + "…" : s;
}

/**
 * Index pairs of a longest common subsequence of `a` and `b`, in order. Common prefix and suffix
 * are matched directly; the middle uses dynamic programming, or, when that would be too large,
 * matches greedily (the result is then still a common subsequence, just maybe not the longest).
 */
function lcs(a: ArrayLike<number>, b: ArrayLike<number>): [number, number][] {
  const pre: [number, number][] = [];
  let s = 0;
  while (s < a.length && s < b.length && a[s] === b[s]) pre.push([s, s++]);
  let ea = a.length;
  let eb = b.length;
  const suf: [number, number][] = [];
  while (ea > s && eb > s && a[ea - 1] === b[eb - 1]) suf.push([--ea, --eb]);
  suf.reverse();
  const n = ea - s;
  const m = eb - s;
  const mid: [number, number][] = [];
  if (n && m) {
    if (n * m <= MAX_MATCH_COST) {
      // dp[i][j]: LCS length of a[s+i..ea) and b[s+j..eb).
      const w = m + 1;
      const dp = new Uint32Array((n + 1) * w);
      for (let i = n - 1; i >= 0; i--)
        for (let j = m - 1; j >= 0; j--)
          dp[i * w + j] = a[s + i] === b[s + j] ? dp[(i + 1) * w + j + 1] + 1 : Math.max(dp[(i + 1) * w + j], dp[i * w + j + 1]);
      for (let i = 0, j = 0; i < n && j < m;) {
        if (a[s + i] === b[s + j]) {
          mid.push([s + i, s + j]);
          i++;
          j++;
        } else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) i++;
        else j++;
      }
    } else {
      const next = new Map<number, number[]>();
      for (let j = eb - 1; j >= s; j--) {
        const l = next.get(b[j]);
        if (l) l.push(j);
        else next.set(b[j], [j]);
      }
      let j = s;
      for (let i = s; i < ea; i++) {
        const l = next.get(a[i]);
        while (l?.length && l[l.length - 1] < j) l.pop();
        if (l?.length) {
          j = l.pop()!;
          mid.push([i, j++]);
        }
      }
    }
  }
  return pre.concat(mid, suf);
}
