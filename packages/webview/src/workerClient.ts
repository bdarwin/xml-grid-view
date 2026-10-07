import { createWorkerHandler } from "./worker";
import type { WorkerRequest, WorkerResponse, WorkerResults } from "./workerTypes";

declare const __WORKER_SOURCE__: string;

type Pending = { resolve: (v: unknown) => void; reject: (e: unknown) => void };

export class StaleError extends Error {
  constructor(readonly reason: "stale" | "cancelled") {
    super(reason);
  }
}

type DistributiveOmit<T, K extends keyof never> = T extends unknown ? Omit<T, K> : never;

/**
 * Promise-based client for the model worker. Falls back to running the same
 * handler on the UI thread when Workers are unavailable (e.g. a CSP without
 * `worker-src blob:`).
 */
export class WorkerClient {
  private nextId = 1;
  private readonly pending = new Map<number, Pending>();
  private readonly send: (req: WorkerRequest) => void;
  readonly inline: boolean;

  constructor() {
    const onResponse = (r: WorkerResponse) => {
      const p = this.pending.get(r.id);
      if (!p) return;
      this.pending.delete(r.id);
      if (r.ok) p.resolve(r.result);
      else if (r.stale || r.cancelled) p.reject(new StaleError(r.stale ? "stale" : "cancelled"));
      else p.reject(new Error(r.error));
    };
    let worker: Worker | null = null;
    try {
      const url = URL.createObjectURL(new Blob([__WORKER_SOURCE__], { type: "text/javascript" }));
      worker = new Worker(url);
      worker.onmessage = (e: MessageEvent<WorkerResponse>) => onResponse(e.data);
    } catch {
      worker = null;
    }
    if (worker) {
      const w = worker;
      this.inline = false;
      this.send = (req) => w.postMessage(req);
    } else {
      this.inline = true;
      const handle = createWorkerHandler((r) => setTimeout(() => onResponse(r), 0));
      this.send = (req) => setTimeout(() => handle(structuredClone(req)), 0);
    }
  }

  request<K extends keyof WorkerResults>(req: DistributiveOmit<Extract<WorkerRequest, { type: K }>, "id">): Promise<WorkerResults[K]> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve: resolve as (v: unknown) => void, reject });
      this.send({ ...req, id } as WorkerRequest);
    });
  }
}
