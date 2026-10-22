/**
 * Finds the records of a large XML file — the children of the root element — as byte ranges, while
 * the file streams through in chunks, without decoding or holding it. The view then reads and parses
 * only the records it shows. Comments, CDATA sections, processing instructions, the DOCTYPE (with
 * an internal subset) and quoted attribute values containing ">" are skipped correctly, and end
 * tags must match; anything malformed ends the scan with an error at that offset.
 */

export interface RecordScan {
  /** The root element's start tag as written, decoded (empty when there is no root). */
  rootStartTag: string;
  rootName: string;
  /** Byte offsets of each record's "<" and just past its end. */
  starts: Float64Array;
  ends: Float64Array;
  /** Each record's tag name, as an index into `names`. */
  nameIdx: Uint32Array;
  names: string[];
  count: number;
  /** Set when the file is not well-formed (scanning stopped there). */
  error?: { message: string; offset: number };
}

const enum S {
  Text,
  Lt, // after "<"
  Bang, // after "<!"
  BangDash, // after "<!-"
  Comment,
  CommentDash, // "-" inside a comment
  CommentDashDash, // "--" inside a comment
  Cdata, // inside <![CDATA[ ... (after the "[CDATA[" prefix)
  CdataBr,
  CdataBrBr,
  CdataPrefix, // matching "[CDATA["
  Decl, // <!DOCTYPE ... or another declaration
  DeclQuote,
  Pi,
  PiQ,
  StartName,
  StartTag,
  StartQuote,
  StartSlash, // "/" inside a start tag
  EndName,
  EndTag,
}

const CDATA = "[CDATA[";

function isNameByte(b: number): boolean {
  // Name characters; bytes >= 0x80 are parts of UTF-8 sequences (non-ASCII names).
  return (
    b === 0x2d ||
    b === 0x2e ||
    b === 0x3a ||
    b === 0x5f ||
    (b >= 0x30 && b <= 0x39) ||
    (b >= 0x41 && b <= 0x5a) ||
    (b >= 0x61 && b <= 0x7a) ||
    b >= 0x80
  );
}

function isSpace(b: number): boolean {
  return b === 0x20 || b === 0x09 || b === 0x0a || b === 0x0d;
}

/** Incremental scanner: feed chunks in order, then call finish(). */
export class RecordScanner {
  private state = S.Text;
  private pos = 0;
  private depth = 0;
  private quote = 0;
  private prefixAt = 0;
  private declDepth = 0;
  private tagStart = 0;
  private name: number[] = [];
  /** Names of the open elements, as bytes, for matching end tags. */
  private readonly stack: number[][] = [];
  private readonly nameIndex = new Map<string, number>();
  private names: string[] = [];
  private rootName = "";
  private rootTag: number[] = [];
  private capturingRoot = false;
  private starts = new Float64Array(1024);
  private ends = new Float64Array(1024);
  private nameIdx = new Uint32Array(1024);
  private count = 0;
  private open = -1;
  private sawRoot = false;
  private error: { message: string; offset: number } | undefined;
  private readonly decoder = new TextDecoder("utf-8");

  /** Bytes scanned so far. */
  get offset(): number {
    return this.pos;
  }

  get failed(): boolean {
    return this.error !== undefined;
  }

  feed(chunk: Uint8Array): void {
    if (this.error) return;
    for (let i = 0; i < chunk.length; i++, this.pos++) {
      const b = chunk[i];
      if (this.capturingRoot && this.rootTag.length < 65536) this.rootTag.push(b);
      switch (this.state) {
        case S.Text: {
          if (b !== 0x3c) {
            // Skip to the next "<" (most of a file is text between tags).
            const next = chunk.indexOf(0x3c, i + 1);
            const to = next < 0 ? chunk.length : next;
            this.pos += to - i - 1;
            i = to - 1;
            break;
          }
          this.tagStart = this.pos;
          this.state = S.Lt;
          break;
        }
        case S.Lt:
          if (b === 0x21) this.state = S.Bang;
          else if (b === 0x3f) this.state = S.Pi;
          else if (b === 0x2f) {
            this.name = [];
            this.state = S.EndName;
          } else if (isNameByte(b)) {
            this.name = [b];
            this.state = S.StartName;
            if (this.depth === 0 && !this.sawRoot) {
              this.capturingRoot = true;
              this.rootTag = [0x3c, b];
            }
          } else return this.fail("Unexpected character after '<'");
          break;
        case S.Bang:
          if (b === 0x2d) this.state = S.BangDash;
          else if (b === 0x5b) {
            this.prefixAt = 1;
            this.state = S.CdataPrefix;
          } else {
            this.declDepth = 0;
            this.state = S.Decl;
          }
          break;
        case S.BangDash:
          if (b !== 0x2d) return this.fail("Malformed comment");
          this.state = S.Comment;
          break;
        case S.Comment:
          if (b === 0x2d) this.state = S.CommentDash;
          break;
        case S.CommentDash:
          this.state = b === 0x2d ? S.CommentDashDash : S.Comment;
          break;
        case S.CommentDashDash:
          if (b === 0x3e) this.state = S.Text;
          else if (b !== 0x2d) this.state = S.Comment;
          break;
        case S.CdataPrefix:
          if (b !== CDATA.charCodeAt(this.prefixAt)) return this.fail("Malformed CDATA section");
          if (++this.prefixAt === CDATA.length) this.state = S.Cdata;
          break;
        case S.Cdata:
          if (b === 0x5d) this.state = S.CdataBr;
          break;
        case S.CdataBr:
          this.state = b === 0x5d ? S.CdataBrBr : S.Cdata;
          break;
        case S.CdataBrBr:
          if (b === 0x3e) this.state = S.Text;
          else if (b !== 0x5d) this.state = S.Cdata;
          break;
        case S.Decl:
          // <!DOCTYPE name [ <!ENTITY x "a>b"> ... ]>: brackets nest the internal subset.
          if (b === 0x22 || b === 0x27) {
            this.quote = b;
            this.state = S.DeclQuote;
          } else if (b === 0x5b) this.declDepth++;
          else if (b === 0x5d) this.declDepth--;
          else if (b === 0x3e && this.declDepth <= 0) this.state = S.Text;
          break;
        case S.DeclQuote:
          if (b === this.quote) this.state = S.Decl;
          break;
        case S.Pi:
          if (b === 0x3f) this.state = S.PiQ;
          break;
        case S.PiQ:
          if (b === 0x3e) this.state = S.Text;
          else if (b !== 0x3f) this.state = S.Pi;
          break;
        case S.StartName:
          if (isNameByte(b)) this.name.push(b);
          else {
            this.state = S.StartTag;
            this.onStartName();
            i--; // the byte belongs to the tag
            this.pos--;
            if (this.capturingRoot) this.rootTag.pop();
          }
          break;
        case S.StartTag:
          if (b === 0x22 || b === 0x27) {
            this.quote = b;
            this.state = S.StartQuote;
          } else if (b === 0x2f) this.state = S.StartSlash;
          else if (b === 0x3e) this.onStartTagEnd(false);
          else if (b === 0x3c) return this.fail("'<' inside a start tag");
          break;
        case S.StartQuote:
          if (b === this.quote) this.state = S.StartTag;
          break;
        case S.StartSlash:
          if (b === 0x3e) this.onStartTagEnd(true);
          else if (isSpace(b)) this.state = S.StartSlash;
          else return this.fail("Expected '>' after '/'");
          break;
        case S.EndName:
          if (isNameByte(b)) this.name.push(b);
          else if (isSpace(b)) this.state = S.EndTag;
          else if (b === 0x3e) this.onEndTag();
          else return this.fail("Malformed end tag");
          break;
        case S.EndTag:
          if (b === 0x3e) this.onEndTag();
          else if (!isSpace(b)) return this.fail("Malformed end tag");
          break;
      }
    }
  }

  private onStartName(): void {
    if (this.depth === 0 && this.sawRoot) return this.fail("Content after the root element");
  }

  private onStartTagEnd(selfClosing: boolean): void {
    this.state = S.Text;
    const d = this.depth;
    if (d === 0) {
      this.sawRoot = true;
      this.rootName = this.decoder.decode(Uint8Array.from(this.name));
      this.capturingRoot = false;
      if (selfClosing) return;
    }
    if (d === 1) {
      this.open = this.count;
      this.push(this.tagStart, this.decoder.decode(Uint8Array.from(this.name)));
      if (selfClosing) {
        this.ends[this.open] = this.pos + 1;
        this.open = -1;
      }
    }
    if (!selfClosing) {
      this.stack.push(this.name);
      this.depth++;
    }
  }

  private onEndTag(): void {
    this.state = S.Text;
    if (this.depth === 0) return this.fail("Unexpected end tag");
    const open = this.stack.pop()!;
    if (open.length !== this.name.length || open.some((b, k) => b !== this.name[k])) {
      return this.fail(
        `End tag </${this.decoder.decode(Uint8Array.from(this.name))}> does not match <${this.decoder.decode(Uint8Array.from(open))}>`,
      );
    }
    this.depth--;
    if (this.depth === 1 && this.open >= 0) {
      this.ends[this.open] = this.pos + 1;
      this.open = -1;
    }
  }

  private push(start: number, name: string): void {
    if (this.count === this.starts.length) {
      const grow = <T extends Float64Array | Uint32Array>(a: T, make: (n: number) => T): T => {
        const b = make(a.length * 2);
        b.set(a);
        return b;
      };
      this.starts = grow(this.starts, (n) => new Float64Array(n));
      this.ends = grow(this.ends, (n) => new Float64Array(n));
      this.nameIdx = grow(this.nameIdx, (n) => new Uint32Array(n));
    }
    let idx = this.nameIndex.get(name);
    if (idx === undefined) {
      idx = this.names.length;
      this.names.push(name);
      this.nameIndex.set(name, idx);
    }
    this.starts[this.count] = start;
    this.nameIdx[this.count] = idx;
    this.count++;
  }

  private fail(message: string): void {
    this.error ??= { message, offset: this.pos };
  }

  finish(): RecordScan {
    if (!this.error) {
      if (!this.sawRoot) this.fail("No root element");
      else if (this.depth > 0 || this.state !== S.Text) this.fail("The file ends inside an element");
    }
    // A record cut off by an error or the end of the file is dropped.
    const n = this.open >= 0 ? this.open : this.count;
    return {
      rootStartTag: this.decoder.decode(Uint8Array.from(this.rootTag)),
      rootName: this.rootName,
      starts: this.starts.slice(0, n),
      ends: this.ends.slice(0, n),
      nameIdx: this.nameIdx.slice(0, n),
      names: this.names,
      count: n,
      error: this.error,
    };
  }
}

/** Scans a whole buffer (tests and small inputs). */
export function scanRecords(bytes: Uint8Array): RecordScan {
  const s = new RecordScanner();
  s.feed(bytes);
  return s.finish();
}
