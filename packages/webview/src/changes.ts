import type { DocumentChanges, ElementChange, GridTable, RemovedElement, TreeSkeleton } from "@xmlgridview/core";

export type ChangeKind = "added" | "modified" | "removed";

export interface ChangeMark {
  kind: ChangeKind;
  title: string;
}

/** Changes against the baseline, mapped onto the current skeleton. */
export interface ChangeMarks {
  /** What the baseline is: "the last commit", "when the file was opened". */
  label: string;
  /** Added elements (with all their descendants) and elements whose own attributes or text changed. */
  byElement: Map<number, ChangeMark>;
  /** Ancestors of every change (marked while collapsed). */
  within: Set<number>;
  /** Removed elements by current parent id (-1: top level). */
  removed: Map<number, RemovedElement[]>;
  /** Removed elements by the current element that now follows them (their next sibling). */
  removedBefore: Map<number, RemovedElement[]>;
  /** Where each change is, for next/previous, in document order. A removal is at its parent's grid row gap. */
  list: ChangeStop[];
  added: number;
  modified: number;
  removedCount: number;
}

const short = (s: string) => {
  const t = s.replace(/\s+/g, " ").trim();
  return t.length > 200 ? t.slice(0, 199) + "…" : t || "(empty)";
};

export interface ChangeStop {
  /** The changed element; for a removal, the element after the gap, or the parent when it was last. */
  id: number;
  /** For a removal: the parent and what was removed there. */
  removed?: { parent: number; items: RemovedElement[] };
}

export function modifiedTitle(c: ElementChange, label: string): string {
  const lines = [`Changed since ${label}`];
  for (const [name, was] of Object.entries(c.attrs ?? {})) lines.push(was === null ? `@${name}: new` : `@${name}: was ${short(was)}`);
  for (const [name, was] of Object.entries(c.removedAttrs ?? {})) lines.push(`@${name}: removed (was ${short(was)})`);
  if (c.text !== undefined) lines.push(c.type ? `Was: ${short(c.text)} (${c.type})` : `Was: ${short(c.text)}`);
  return lines.join("\n");
}

export function removedTitle(rs: RemovedElement[], label: string): string {
  return [`Removed since ${label}:`, ...rs.map((r) => (r.preview ? `${r.name}: ${r.preview}` : r.name))].join("\n");
}

export function changeMarks(sk: TreeSkeleton, changes: DocumentChanges, label: string): ChangeMarks {
  const byElement = new Map<number, ChangeMark>();
  const within = new Set<number>();
  const removed = new Map<number, RemovedElement[]>();
  const removedBefore = new Map<number, RemovedElement[]>();
  // Stops keyed by document position: an element's id, or a gap just before id k (k - 0.5).
  const nav = new Map<number, ChangeStop>();
  const markWithin = (id: number) => {
    for (let a = sk.parent[id]; a >= 0 && !within.has(a); a = sk.parent[a]) within.add(a);
  };
  const addedTitle = `Added since ${label}`;
  for (const id of changes.added) {
    if (id >= sk.count) continue;
    // Descendants follow their ancestor in document order.
    for (let j = id; j < sk.count && (j === id || sk.start[j] < sk.end[id]); j++) byElement.set(j, { kind: "added", title: addedTitle });
    markWithin(id);
    nav.set(id, { id });
  }
  for (const c of changes.modified) {
    if (c.id >= sk.count || byElement.has(c.id)) continue;
    byElement.set(c.id, { kind: "modified", title: modifiedTitle(c, label) });
    markWithin(c.id);
    nav.set(c.id, { id: c.id });
  }
  for (const r of changes.removed) {
    if (r.parent >= sk.count) continue;
    const l = removed.get(r.parent);
    if (l) l.push(r);
    else removed.set(r.parent, [r]);
    let next = r.parent < 0 ? sk.firstRoot : sk.firstChild[r.parent];
    for (let i = 0; next >= 0 && i < r.before; i++) next = sk.nextSibling[next];
    if (next >= 0) {
      const b = removedBefore.get(next);
      if (b) b.push(r);
      else removedBefore.set(next, [r]);
    }
    if (r.parent >= 0) {
      within.add(r.parent);
      markWithin(r.parent);
    }
    // After the parent's last descendant when nothing follows within it.
    let key = next;
    if (key < 0) for (key = r.parent + 1; key < sk.count && sk.start[key] < sk.end[r.parent]; key++);
    const stop = nav.get(key - 0.5);
    if (stop) stop.removed!.items.push(r);
    else nav.set(key - 0.5, { id: next >= 0 ? next : Math.max(r.parent, 0), removed: { parent: r.parent, items: [r] } });
  }
  return {
    label,
    byElement,
    within,
    removed,
    removedBefore,
    list: [...nav.entries()].sort((a, b) => a[0] - b[0]).map((e) => e[1]),
    added: changes.added.length,
    modified: changes.modified.length,
    removedCount: changes.removed.length,
  };
}

/** CSS class for an element's change, or "". */
export const changeClass = (m: ChangeMark | undefined) => (m ? " change-" + m.kind : "");

export interface GridChanges {
  /** By cell (table coordinates r * ncols + c). */
  cells: Map<number, ChangeMark>;
  /** By table row: the row's own change ("added", or "modified" when anything in it changed). */
  rows: Map<number, ChangeMark>;
  /** Rows removed just above table row r (r = row count: below the last row). */
  removedAbove: Map<number, string>;
}

/**
 * Changes on the grid: a new row; a changed attribute or text in its column; a new, changed or
 * removed child in the child's column; and rows removed between the current ones.
 */
export function gridChanges(t: GridTable, sk: TreeSkeleton, m: ChangeMarks, details: Map<number, ElementChange>): GridChanges {
  const ncols = t.columns.length;
  const colOf = new Map(t.columns.map((c, i) => [c.key, i]));
  const cells = new Map<number, ChangeMark>();
  const rows = new Map<number, ChangeMark>();
  const removedAbove = new Map<number, string>();
  const changedTitle = `Changed since ${m.label}`;
  t.rowIds.forEach((e, r) => {
    const own = m.byElement.get(e);
    if (own?.kind === "added") {
      rows.set(r, own);
      return;
    }
    let any = false;
    const set = (key: string, mark: ChangeMark) => {
      const c = colOf.get(key);
      if (c !== undefined && !cells.has(r * ncols + c)) cells.set(r * ncols + c, mark);
      any = true;
    };
    const d = details.get(e);
    if (d) {
      for (const [name, was] of Object.entries(d.attrs ?? {}))
        set("@" + name, was === null ? { kind: "added", title: `New since ${m.label}` } : { kind: "modified", title: `Was: ${short(was)}` });
      for (const [name, was] of Object.entries(d.removedAttrs ?? {}))
        set("@" + name, { kind: "removed", title: `Removed since ${m.label} (was ${short(was)})` });
      if (d.text !== undefined) set("#text", { kind: "modified", title: `Was: ${short(d.text)}` });
    }
    // In the fields grid of an element the "row" is the element itself and the columns are its children.
    for (let ch = sk.firstChild[e]; ch >= 0; ch = sk.nextSibling[ch]) {
      const mark = m.byElement.get(ch);
      const key = sk.names[sk.nameIdx[ch]];
      if (mark?.kind === "added") set(key, mark);
      else if (mark) {
        const cd = details.get(ch);
        set(key, { kind: "modified", title: cd?.text !== undefined && !cd.attrs && !cd.removedAttrs ? `Was: ${short(cd.text)}` : mark.title });
      } else if (m.within.has(ch)) set(key, { kind: "modified", title: changedTitle });
    }
    for (const rm of m.removed.get(e) ?? []) {
      const c = colOf.get(rm.name);
      any = true;
      if (c === undefined) continue;
      const k = r * ncols + c;
      if (!cells.has(k))
        cells.set(
          k,
          t.cells[k] === null ? { kind: "removed", title: removedTitle([rm], m.label) } : { kind: "modified", title: removedTitle([rm], m.label) },
        );
    }
    if (own || any || m.within.has(e)) rows.set(r, { kind: "modified", title: own?.title ?? changedTitle });
  });
  // Removed rows: removed children of the table's element with the rows' tag, placed by sibling index.
  if (t.group !== null) {
    const gone = (m.removed.get(t.elementId) ?? []).filter((rm) => rm.name === t.group);
    for (const rm of gone) {
      let r = 0;
      while (r < t.rowIds.length && sk.index[t.rowIds[r]] < rm.before) r++;
      const prev = removedAbove.get(r);
      const line = rm.preview ? `${rm.name}: ${rm.preview}` : rm.name;
      removedAbove.set(r, prev ? prev + "\n" + line : `Removed since ${m.label}:\n${line}`);
    }
  }
  return { cells, rows, removedAbove };
}

/** Label of a removed element in the tree: its name (JSON array items: "[removed]"). */
export function removedName(sk: TreeSkeleton, parent: number, r: RemovedElement): string {
  if (sk.json && parent >= 0 && sk.json[parent] === 2) return "[removed]";
  return r.name;
}
