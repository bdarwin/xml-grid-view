import { SaxesParser, type SaxesTagNS, type SaxesAttributeNS } from "saxes";
import type { ParseError, XAttr, XDocument, XElement, XText } from "./types.js";

/** Characters fed to saxes per step of the incremental parser. */
const DEFAULT_CHUNK = 1 << 20;

/** Converts an offset to a 1-based line and column. */
export function offsetToLineCol(text: string, offset: number): { line: number; column: number } {
  let line = 1;
  let lineStart = 0;
  const end = Math.min(offset, text.length);
  for (let i = 0; i < end; i++) {
    const c = text.charCodeAt(i);
    if (c === 10) {
      line++;
      lineStart = i + 1;
    } else if (c === 13 && text.charCodeAt(i + 1) !== 10) {
      line++;
      lineStart = i + 1;
    }
  }
  return { line, column: end - lineStart + 1 };
}

/**
 * Incremental XML parser that records source offsets for every element,
 * attribute and text node. Comments and processing instructions are skipped.
 * Malformed input never throws: parsing continues past errors where saxes can,
 * and whatever was built is returned together with the errors.
 */
export class XmlParser {
  private readonly sax: SaxesParser<{ xmlns: true; position: true }>;
  private readonly stack: XElement[] = [];
  /** saxes tag objects parallel to `stack`, used to match close tags by identity. */
  private readonly tags: object[] = [];
  private readonly doc: XDocument;
  private pos = 0;
  /** End of the most recent markup construct; text nodes start here. */
  private cursor = 0;
  private finished = false;

  constructor(
    private readonly text: string,
    private readonly chunkSize = DEFAULT_CHUNK,
  ) {
    this.doc = { roots: [], elements: [], errors: [], textLength: text.length };
    this.sax = new SaxesParser({ xmlns: true, position: true });
    this.wire();
  }

  /** Parses the next chunk. Returns true when the whole text has been consumed. */
  step(): boolean {
    if (this.finished) return true;
    if (this.pos >= this.text.length) {
      this.finish();
      return true;
    }
    let end = Math.min(this.text.length, this.pos + this.chunkSize);
    // Never split a surrogate pair or a CRLF across chunks.
    if (end < this.text.length) {
      const c = this.text.charCodeAt(end - 1);
      if ((c >= 0xd800 && c <= 0xdbff) || c === 13) end++;
    }
    this.sax.write(this.text.slice(this.pos, end));
    this.pos = end;
    if (this.pos >= this.text.length) {
      this.finish();
      return true;
    }
    return false;
  }

  /** Parses everything that remains and returns the document. */
  run(): XDocument {
    while (!this.step()) {
      /* keep going */
    }
    return this.doc;
  }

  get result(): XDocument {
    return this.doc;
  }

  private finish() {
    if (this.finished) return;
    this.finished = true;
    try {
      this.sax.close();
    } catch (e) {
      this.addError(String((e as Error).message ?? e), this.text.length);
    }
    // Close anything saxes left open (unclosed tags in malformed input).
    this.tags.length = 0;
    while (this.stack.length) {
      const el = this.stack.pop()!;
      el.end = this.text.length;
      if (el.openEnd < 0) el.openEnd = this.text.length;
    }
  }

  private addError(raw: string, offset: number) {
    // saxes prefixes messages with "line:col: "; recompute from our own offset.
    const message = raw.replace(/^\d+:\d+:\s*/, "").replace(/\.$/, "");
    const off = Math.max(0, Math.min(offset, this.text.length));
    const { line, column } = offsetToLineCol(this.text, off);
    const err: ParseError = { message, offset: off, line, column };
    this.doc.errors.push(err);
  }

  private wire() {
    const sax = this.sax;
    const text = this.text;
    let pending: XElement | null = null;

    sax.on("error", (e) => this.addError(e.message, sax.position));

    sax.on("opentagstart", (tag) => {
      const start = text.lastIndexOf("<", sax.position - 1);
      const parent = this.stack.length ? this.stack[this.stack.length - 1] : null;
      const el: XElement = {
        kind: "element",
        id: this.doc.elements.length,
        name: tag.name,
        local: tag.name,
        prefix: "",
        uri: "",
        attrs: [],
        children: [],
        elements: [],
        parent,
        index: parent ? parent.elements.length : this.doc.roots.length,
        start: start < 0 ? 0 : start,
        end: -1,
        openEnd: -1,
        nsDecls: {},
      };
      this.doc.elements.push(el);
      if (parent) {
        parent.children.push(el);
        parent.elements.push(el);
      } else {
        this.doc.roots.push(el);
      }
      pending = el;
    });

    sax.on("attribute", (attr) => {
      if (!pending) return;
      const a = attr as SaxesAttributeNS;
      const end = sax.position;
      // Locate the attribute name start by walking back over the quoted value and `=`.
      const quote = text[end - 1];
      let i = end - 1;
      if (quote === '"' || quote === "'") {
        const open = text.lastIndexOf(quote, end - 2);
        i = open - 1;
        while (i > 0 && /\s/.test(text[i])) i--;
        if (text[i] === "=") i--;
        while (i > 0 && /\s/.test(text[i])) i--;
      }
      const start = Math.max(0, i + 1 - a.name.length);
      const xa: XAttr = {
        name: a.name,
        local: a.local ?? a.name,
        prefix: a.prefix ?? "",
        uri: a.uri ?? "",
        value: a.value,
        start,
        end,
      };
      // Namespace declarations are not attributes in the model; they are kept in nsDecls.
      if (a.name === "xmlns" || a.name.startsWith("xmlns:")) {
        pending.nsDecls[a.name === "xmlns" ? "" : a.name.slice(6)] = a.value;
        return;
      }
      pending.attrs.push(xa);
    });

    sax.on("opentag", (tag) => {
      const t = tag as SaxesTagNS;
      const el = pending ?? this.doc.elements[this.doc.elements.length - 1];
      pending = null;
      if (!el) return;
      el.local = t.local ?? t.name;
      el.prefix = t.prefix ?? "";
      el.uri = t.uri ?? "";
      el.openEnd = sax.position;
      for (const xa of el.attrs) {
        const src = t.attributes[xa.name] as SaxesAttributeNS | undefined;
        if (src) {
          xa.local = src.local ?? xa.local;
          xa.prefix = src.prefix ?? xa.prefix;
          xa.uri = src.uri ?? xa.uri;
        }
      }
      this.cursor = sax.position;
      // Self-closing tags get their end from the closetag event saxes emits right after.
      this.stack.push(el);
      this.tags.push(tag);
    });

    sax.on("closetag", (tag) => {
      const at = this.tags.lastIndexOf(tag);
      if (at < 0) return;
      // Anything opened after the matching tag was left unclosed; it ends here too.
      while (this.stack.length > at) {
        const el = this.stack.pop()!;
        this.tags.pop();
        if (el.end < sax.position) el.end = sax.position;
      }
      this.cursor = sax.position;
    });

    const addText = (kind: XText["kind"], value: string, start: number, end: number) => {
      const parent = this.stack.length ? this.stack[this.stack.length - 1] : null;
      if (!parent) return; // text outside the root element (whitespace or garbage) is ignored
      const last = parent.children[parent.children.length - 1];
      if (kind === "text" && last && last.kind === "text" && last.end === start) {
        last.value += value;
        last.end = end;
        return;
      }
      const node: XText = { kind, value, start, end };
      parent.children.push(node);
    };

    sax.on("text", (value) => {
      let end = sax.position;
      if (text[end - 1] === "<") end--;
      addText("text", value, Math.min(this.cursor, end), end);
      this.cursor = end;
    });

    sax.on("cdata", (value) => {
      const end = sax.position;
      const start = text.lastIndexOf("<![CDATA[", end);
      addText("cdata", value, start < 0 ? this.cursor : start, end);
      this.cursor = end;
    });

    const skip = () => {
      this.cursor = sax.position;
    };
    sax.on("comment", skip);
    sax.on("processinginstruction", skip);
    sax.on("doctype", skip);
    sax.on("xmldecl", skip);
  }
}

/** Parses the whole text synchronously. */
export function parseXml(text: string): XDocument {
  return new XmlParser(text).run();
}
