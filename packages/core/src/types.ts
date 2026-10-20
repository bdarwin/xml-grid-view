/** Source span in the original text, in UTF-16 code units (matches editor offsets). */
export interface Span {
  start: number;
  end: number;
}

export interface XAttr extends Span {
  /** Qualified name as written, e.g. `xsi:type`. */
  name: string;
  local: string;
  prefix: string;
  uri: string;
  value: string;
}

export interface XText extends Span {
  kind: "text" | "cdata";
  value: string;
}

export interface XElement extends Span {
  kind: "element";
  /** Document-order index; also the index into `XDocument.elements`. */
  id: number;
  name: string;
  local: string;
  prefix: string;
  uri: string;
  attrs: XAttr[];
  /** Element, text and CDATA children in document order. */
  children: XChild[];
  /** Element children only (cached for grid building and paths). */
  elements: XElement[];
  parent: XElement | null;
  /** Index among the parent's element children (or among top-level elements). */
  index: number;
  /** End offset of the start tag (position just after `>`). */
  openEnd: number;
  /** Namespace declarations made on this element (prefix → uri, "" for default). */
  nsDecls: Record<string, string>;
}

export type XChild = XElement | XText;

/**
 * A comment, processing instruction or DOCTYPE. Shown in the tree and Flat view among the
 * elements, read-only. `before` is the number of element siblings that precede it (in its parent,
 * or among top-level elements when `parent` is null).
 */
export interface XMisc extends Span {
  kind: "comment" | "pi" | "doctype";
  /** `#comment`, the PI target, or `DOCTYPE`. */
  name: string;
  /** The comment text, PI data or DOCTYPE declaration, trimmed. */
  value: string;
  parent: XElement | null;
  before: number;
}

export interface ParseError {
  message: string;
  offset: number;
  line: number;
  column: number;
}

export interface XDocument {
  /** Top-level elements. Well-formed documents have exactly one. */
  roots: XElement[];
  /** All elements in document order; `elements[e.id] === e`. */
  elements: XElement[];
  errors: ParseError[];
  /** Comments, processing instructions and the DOCTYPE, in document order. */
  misc: XMisc[];
  textLength: number;
}
