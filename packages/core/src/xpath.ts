import { DOMParser as XmldomParser } from "@xmldom/xmldom";
import * as xpathLib from "xpath";
import type { XmlModel } from "./model.js";
import { elementAtPath, type ElementPath } from "./paths.js";

export type XPathScalar = string | number | boolean;

/** Evaluation result before mapping back to the core model. */
export type RawXPathResult = { nodes: Node[] } | { scalar: XPathScalar };

/**
 * Parses documents and evaluates XPath. The native backend uses the browser's
 * DOMParser and document.evaluate; it is not available inside Web Workers or
 * Node, so the default falls back to an API-compatible pure JS implementation.
 */
export interface XPathBackend {
  parse(text: string): Document;
  evaluate(expr: string, doc: Document, ns: Record<string, string>): RawXPathResult;
}

export const nativeBackend: XPathBackend = {
  parse(text) {
    const doc = new DOMParser().parseFromString(text, "application/xml");
    const err = doc.getElementsByTagName("parsererror")[0];
    if (err) throw new Error(`Document is not well-formed: ${err.textContent?.trim() ?? ""}`);
    return doc;
  },
  evaluate(expr, doc, ns) {
    const resolver = (prefix: string | null) => (prefix !== null ? (ns[prefix] ?? null) : null);
    const r = doc.evaluate(expr, doc, resolver, XPathResult.ANY_TYPE, null);
    switch (r.resultType) {
      case XPathResult.NUMBER_TYPE:
        return { scalar: r.numberValue };
      case XPathResult.STRING_TYPE:
        return { scalar: r.stringValue };
      case XPathResult.BOOLEAN_TYPE:
        return { scalar: r.booleanValue };
      default: {
        const nodes: Node[] = [];
        for (let n = r.iterateNext(); n; n = r.iterateNext()) nodes.push(n);
        return { nodes };
      }
    }
  },
};

export const jsBackend: XPathBackend = {
  parse(text) {
    const errors: string[] = [];
    const parser = new XmldomParser({
      onError: (level, msg) => {
        if (level !== "warning") errors.push(msg);
      },
    });
    let doc: Document;
    try {
      doc = parser.parseFromString(text, "text/xml") as unknown as Document;
    } catch (e) {
      throw new Error(`Document is not well-formed: ${(e as Error).message}`);
    }
    if (errors.length) throw new Error(`Document is not well-formed: ${errors[0]}`);
    return doc;
  },
  evaluate(expr, doc, ns) {
    const resolver = { lookupNamespaceURI: (prefix: string | null) => (prefix !== null ? (ns[prefix] ?? null) : null) };
    const r = xpathLib.selectWithResolver(expr, doc as unknown as Node, resolver as XPathNSResolver);
    if (Array.isArray(r)) return { nodes: r };
    if (r === null || r === undefined) return { nodes: [] };
    if (typeof r === "object") return { nodes: [r] };
    return { scalar: r };
  },
};

export function defaultBackend(): XPathBackend {
  const g = globalThis as { DOMParser?: unknown; document?: { evaluate?: unknown } };
  return typeof g.DOMParser === "function" && typeof g.document?.evaluate === "function" ? nativeBackend : jsBackend;
}

export interface XPathItem {
  elementId: number;
  kind: "element" | "attribute" | "text";
  /** Attribute name for attribute results. */
  attrName?: string;
  /** Display value: attribute value or text content (truncated). */
  value?: string;
}

export interface XPathEvalResult {
  items: XPathItem[];
  scalar?: XPathScalar;
  error?: string;
  /** Prefix → URI bindings that were available, including the default-namespace alias. */
  namespaces: Record<string, string>;
}

/**
 * Collects namespace bindings for XPath: every declared prefix, plus the
 * default namespace under the alias `d` (or `default`, `ns0`, … if taken),
 * since XPath 1.0 has no way to address the default namespace without a prefix.
 */
export function namespaceBindings(model: XmlModel): Record<string, string> {
  const ns: Record<string, string> = {};
  let defaultUri: string | undefined;
  for (const el of model.elements) {
    for (const [p, uri] of Object.entries(el.nsDecls)) {
      if (p === "") defaultUri ??= uri || undefined;
      else if (!(p in ns)) ns[p] = uri;
    }
  }
  if (defaultUri) {
    const alias = ["d", "default", "ns0", "ns1", "ns2"].find((a) => !(a in ns));
    if (alias) ns[alias] = defaultUri;
  }
  return ns;
}

function domElementPath(el: Element): ElementPath {
  const path: number[] = [];
  let e: Element | null = el;
  while (e) {
    let i = 0;
    for (let s = e.previousSibling; s; s = s.previousSibling) if (s.nodeType === 1) i++;
    path.push(i);
    const p: Node | null = e.parentNode;
    e = p && p.nodeType === 1 ? (p as Element) : null;
  }
  return path.reverse();
}

function truncate(s: string): string {
  const t = s.replace(/\s+/g, " ").trim();
  return t.length > 120 ? t.slice(0, 119) + "…" : t;
}

const domCache = new WeakMap<XmlModel, { text: string; doc: Document | Error; backend: XPathBackend }>();

/**
 * Evaluates `expr` against the document text and maps every result node back to
 * the core element it belongs to via its element child-index path.
 */
export function evaluateXPath(
  model: XmlModel,
  text: string,
  expr: string,
  backend: XPathBackend = defaultBackend(),
): XPathEvalResult {
  const namespaces = namespaceBindings(model);
  if (!expr.trim()) return { items: [], namespaces };

  let cached = domCache.get(model);
  if (!cached || cached.text !== text || cached.backend !== backend) {
    let doc: Document | Error;
    try {
      doc = backend.parse(text);
    } catch (e) {
      doc = e as Error;
    }
    cached = { text, doc, backend };
    domCache.set(model, cached);
  }
  if (cached.doc instanceof Error) return { items: [], error: cached.doc.message, namespaces };

  let raw: RawXPathResult;
  try {
    raw = backend.evaluate(expr, cached.doc, namespaces);
  } catch (e) {
    return { items: [], error: cleanError((e as Error).message ?? String(e)), namespaces };
  }
  if ("scalar" in raw) return { items: [], scalar: raw.scalar, namespaces };

  const items: XPathItem[] = [];
  for (const node of raw.nodes) {
    let owner: Element | null = null;
    let item: Omit<XPathItem, "elementId"> | null = null;
    if (node.nodeType === 1) {
      owner = node as Element;
      item = { kind: "element" };
    } else if (node.nodeType === 2) {
      const attr = node as Attr;
      owner = attr.ownerElement;
      item = { kind: "attribute", attrName: attr.name, value: truncate(attr.value) };
    } else if (node.nodeType === 3 || node.nodeType === 4) {
      const p = node.parentNode;
      owner = p && p.nodeType === 1 ? (p as Element) : null;
      item = { kind: "text", value: truncate(node.nodeValue ?? "") };
    }
    if (!owner || !item) continue;
    const el = elementAtPath(model.doc, domElementPath(owner));
    if (el) items.push({ elementId: el.id, ...item });
  }
  return { items: sortItems(model, items), namespaces };
}

function cleanError(msg: string): string {
  return msg.replace(/^Error:\s*/, "").split("\n")[0];
}

const KIND_RANK = { element: 0, attribute: 1, text: 2 } as const;

/** Document order: by element, then element < attributes (in source order) < text; duplicates removed. */
function sortItems(model: XmlModel, items: XPathItem[]): XPathItem[] {
  const attrIdx = (i: XPathItem) =>
    i.kind === "attribute" ? model.elements[i.elementId].attrs.findIndex((a) => a.name === i.attrName) : 0;
  const sorted = items
    .map((i) => ({ i, a: attrIdx(i) }))
    .sort((x, y) => x.i.elementId - y.i.elementId || KIND_RANK[x.i.kind] - KIND_RANK[y.i.kind] || x.a - y.a)
    .map((x) => x.i);
  const out: XPathItem[] = [];
  for (const it of sorted) {
    const prev = out[out.length - 1];
    if (prev && prev.elementId === it.elementId && prev.kind === it.kind && prev.attrName === it.attrName) continue;
    out.push(it);
  }
  return out;
}
