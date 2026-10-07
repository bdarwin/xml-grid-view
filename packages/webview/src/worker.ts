/// <reference lib="webworker" />
import {
  XmlModel,
  XmlParser,
  evaluateXPath,
  jsBackend,
  searchDocument,
  searchGrid,
} from "@xmlgridview/core";
import type { LoadResult, WorkerRequest, WorkerResponse } from "./workerTypes";

/**
 * Owns the parsed model. Parsing runs in chunks and yields between them, so a
 * newer `load` cancels an in-flight one; requests for an outdated generation
 * are answered as stale.
 */
export function createWorkerHandler(post: (r: WorkerResponse, transfer?: Transferable[]) => void) {
  let model: XmlModel | null = null;
  let modelText = "";
  let modelGood = false;
  let gen = 0;
  let latestLoad = 0;

  const yieldNow = () => new Promise<void>((r) => setTimeout(r, 0));

  async function load(id: number, text: string) {
    latestLoad = id;
    const parser = new XmlParser(text);
    while (!parser.step()) {
      await yieldNow();
      if (latestLoad !== id) {
        post({ id, ok: false, error: "cancelled", cancelled: true });
        return;
      }
    }
    const doc = parser.result;
    let result: LoadResult;
    if (doc.errors.length && modelGood) {
      // Keep the last good model; report the errors only.
      result = { gen, errors: doc.errors, skeleton: null };
    } else {
      model = new XmlModel(doc);
      modelText = text;
      modelGood = doc.errors.length === 0;
      gen++;
      result = { gen, errors: doc.errors, skeleton: model.skeleton() };
    }
    if (latestLoad !== id) {
      post({ id, ok: false, error: "cancelled", cancelled: true });
      return;
    }
    const sk = result.skeleton;
    const transfer = sk
      ? [sk.parent, sk.firstChild, sk.nextSibling, sk.childCount, sk.index, sk.depth, sk.nameIdx, sk.start, sk.openEnd, sk.end].map(
          (a) => a.buffer as ArrayBuffer,
        )
      : [];
    post({ id, ok: true, result }, transfer);
  }

  return function handle(req: WorkerRequest) {
    try {
      if (req.type === "load") {
        void load(req.id, req.text).catch((e) => post({ id: req.id, ok: false, error: String((e as Error)?.message ?? e) }));
        return;
      }
      if (!model || req.gen !== gen) {
        post({ id: req.id, ok: false, error: "stale", stale: true });
        return;
      }
      switch (req.type) {
        case "table": {
          const t = model.table(req.elementId, req.group);
          // Tables are cached in the model, so send a copy of the typed arrays rather than transferring them.
          post({ id: req.id, ok: true, result: t });
          return;
        }
        case "searchDoc":
          post({ id: req.id, ok: true, result: searchDocument(model, req.query) });
          return;
        case "searchGrid":
          post({ id: req.id, ok: true, result: searchGrid(model.table(req.elementId, req.group), req.query) });
          return;
        case "xpath":
          post({ id: req.id, ok: true, result: evaluateXPath(model, modelText, req.expr, jsBackend) });
          return;
      }
    } catch (e) {
      post({ id: req.id, ok: false, error: String((e as Error)?.message ?? e) });
    }
  };
}

declare const self: DedicatedWorkerGlobalScope;
if (typeof self !== "undefined" && typeof (self as { importScripts?: unknown }).importScripts === "function") {
  const handle = createWorkerHandler((r, transfer) => self.postMessage(r, transfer ?? []));
  self.onmessage = (e: MessageEvent<WorkerRequest>) => handle(e.data);
}
