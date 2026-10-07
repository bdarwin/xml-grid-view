import type { TreeSkeleton } from "./model.js";
import type { XDocument, XElement } from "./types.js";

/**
 * An element child-index path: the first entry is the index among top-level
 * elements, each following entry the index among the parent's element children.
 */
export type ElementPath = number[];

export function pathOfElement(el: XElement): ElementPath {
  const path: number[] = [];
  for (let e: XElement | null = el; e; e = e.parent) path.push(e.index);
  return path.reverse();
}

export function elementAtPath(doc: XDocument, path: ElementPath): XElement | null {
  if (!path.length) return null;
  let el: XElement | undefined = doc.roots[path[0]];
  for (let i = 1; el && i < path.length; i++) el = el.elements[path[i]];
  return el ?? null;
}

export function pathOfId(sk: TreeSkeleton, id: number): ElementPath {
  const path: number[] = [];
  for (let e = id; e >= 0; e = sk.parent[e]) path.push(sk.index[e]);
  return path.reverse();
}

/** Resolves a path in a skeleton; returns -1 when it no longer exists. */
export function idAtPath(sk: TreeSkeleton, path: ElementPath): number {
  if (!path.length) return -1;
  let id = sk.firstRoot;
  for (let k = 0; k < path[0] && id >= 0; k++) id = sk.nextSibling[id];
  for (let i = 1; id >= 0 && i < path.length; i++) {
    id = sk.firstChild[id];
    for (let k = 0; k < path[i] && id >= 0; k++) id = sk.nextSibling[id];
  }
  return id;
}

/** Path encoded as a string key, e.g. "0/3/1". */
export function pathKey(path: ElementPath): string {
  return path.join("/");
}

export function parsePathKey(key: string): ElementPath {
  return key === "" ? [] : key.split("/").map(Number);
}
