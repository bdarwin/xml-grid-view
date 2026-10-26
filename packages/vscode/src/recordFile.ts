import { createReadStream, promises as fs } from "node:fs";
import { RecordScanner, xmlEncoding, type RecordScan } from "@xmlgridview/core/records";
import type { RecordsInfo } from "@xmlgridview/core/protocol";

const CHUNK = 4 * 1024 * 1024;
/** Search reads this many bytes of records at a time. */
const SEARCH_BATCH = 8 * 1024 * 1024;

/**
 * A large XML file opened as records (the root's children): indexed once by streaming through it,
 * then read a page of records at a time. Nothing but the index stays in memory.
 */
export class RecordFile {
  private readonly byGroup = new Map<string, Uint32Array>();

  private constructor(
    readonly path: string,
    readonly size: number,
    private readonly scan: RecordScan,
    private readonly decoder: { decode(bytes: Uint8Array): string },
  ) {}

  /** Indexes the file; `progress` gets the bytes read so far. Rejects when `cancelled()` turns true. */
  static async open(path: string, progress: (bytes: number, total: number) => void, cancelled: () => boolean): Promise<RecordFile> {
    const size = (await fs.stat(path)).size;
    const head = Buffer.alloc(512);
    const fh = await fs.open(path, "r");
    try {
      await fh.read(head, 0, head.length, 0);
    } finally {
      await fh.close();
    }
    // Records are found byte by byte, which needs an ASCII-compatible encoding.
    const encoding = xmlEncoding(head);
    if (encoding.startsWith("utf-16")) throw new Error("UTF-16 files can't be opened as records; save the file as UTF-8.");
    const scanner = new RecordScanner(encoding);
    let last = 0;
    await new Promise<void>((resolve, reject) => {
      const stream = createReadStream(path, { highWaterMark: CHUNK });
      stream.on("data", (chunk) => {
        if (cancelled()) {
          stream.destroy();
          reject(new Error("cancelled"));
          return;
        }
        scanner.feed(chunk as Buffer);
        if (scanner.offset - last >= 32 * 1024 * 1024) {
          last = scanner.offset;
          progress(scanner.offset, size);
        }
        // Stop reading at the first error: the records before it are shown.
        if (scanner.failed) {
          stream.destroy();
          resolve();
        }
      });
      stream.on("end", () => resolve());
      stream.on("error", reject);
    });
    progress(size, size);
    return new RecordFile(path, size, scanner.finish(), new TextDecoder(encoding));
  }

  get info(): RecordsInfo {
    const counts = new Map<string, number>();
    for (let i = 0; i < this.scan.count; i++) {
      const n = this.scan.names[this.scan.nameIdx[i]];
      counts.set(n, (counts.get(n) ?? 0) + 1);
    }
    return {
      rootName: this.scan.rootName,
      rootStartTag: this.scan.rootStartTag,
      doctype: this.scan.doctype || undefined,
      groups: [...counts].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count),
      fileSize: this.size,
      error: this.scan.error ? `${this.scan.error.message} at byte ${this.scan.error.offset}` : undefined,
    };
  }

  /** Record indices of a group (tag), in file order. */
  private group(name: string): Uint32Array {
    let g = this.byGroup.get(name);
    if (!g) {
      const idx = this.scan.names.indexOf(name);
      const out: number[] = [];
      for (let i = 0; i < this.scan.count; i++) if (this.scan.nameIdx[i] === idx) out.push(i);
      g = Uint32Array.from(out);
      this.byGroup.set(name, g);
    }
    return g;
  }

  groupSize(name: string): number {
    return this.group(name).length;
  }

  /** The XML of records `start` to `start + count` of a group. */
  async read(groupName: string, start: number, count: number): Promise<string[]> {
    const g = this.group(groupName);
    const end = Math.min(g.length, start + count);
    if (start >= end) return [];
    const out: string[] = [];
    const fh = await fs.open(this.path, "r");
    try {
      // Records of one group are usually contiguous: read the span once and slice it.
      let i = start;
      while (i < end) {
        const from = this.scan.starts[g[i]];
        let j = i;
        while (j + 1 < end && this.scan.ends[g[j + 1]] - from <= SEARCH_BATCH) j++;
        const to = this.scan.ends[g[j]];
        const buf = Buffer.alloc(to - from);
        await fh.read(buf, 0, buf.length, from);
        for (let k = i; k <= j; k++) out.push(this.decoder.decode(buf.subarray(this.scan.starts[g[k]] - from, this.scan.ends[g[k]] - from)));
        i = j + 1;
      }
    } finally {
      await fh.close();
    }
    return out;
  }

  /**
   * Finds the records of a group whose XML contains `query`, reading the file in batches. Calls
   * `found` with the matches so far (the first `limit`) and the total, after each batch and at the end.
   */
  async search(
    groupName: string,
    query: string,
    caseSensitive: boolean,
    limit: number,
    found: (matches: number[], total: number, done: boolean) => void,
    cancelled: () => boolean,
  ): Promise<void> {
    const g = this.group(groupName);
    const needle = caseSensitive ? query : query.toLowerCase();
    const matches: number[] = [];
    let total = 0;
    const batch = 2000;
    for (let i = 0; i < g.length && !cancelled(); i += batch) {
      const texts = await this.read(groupName, i, batch);
      texts.forEach((t, k) => {
        if ((caseSensitive ? t : t.toLowerCase()).includes(needle)) {
          total++;
          if (matches.length < limit) matches.push(i + k);
        }
      });
      found(matches, total, false);
    }
    found(matches, total, true);
  }
}
