import type {
  DocSearchResult,
  FlatValues,
  GridSearchResult,
  GridTable,
  ParseError,
  SearchQuery,
  TreeSkeleton,
  XPathEvalResult,
} from "@xmlgridview/core";

/** Requests from the UI thread to the worker. `gen` identifies the model generation. */
export type WorkerRequest =
  | { id: number; type: "load"; text: string }
  | { id: number; type: "table"; gen: number; elementId: number; group: string | null }
  | { id: number; type: "searchDoc"; gen: number; query: SearchQuery }
  | { id: number; type: "searchGrid"; gen: number; elementId: number; group: string | null; query: SearchQuery }
  | { id: number; type: "xpath"; gen: number; expr: string }
  | { id: number; type: "flatValues"; gen: number; ids: number[] };

export interface LoadResult {
  /** Generation of the model now held by the worker. */
  gen: number;
  errors: ParseError[];
  /**
   * New skeleton, or null when the text was malformed and the worker kept the
   * previous good model (the UI keeps showing it).
   */
  skeleton: TreeSkeleton | null;
}

export interface WorkerResults {
  load: LoadResult;
  table: GridTable;
  searchDoc: DocSearchResult;
  searchGrid: GridSearchResult;
  xpath: XPathEvalResult;
  flatValues: FlatValues[];
}

export type WorkerResponse =
  | { id: number; ok: true; result: unknown }
  | { id: number; ok: false; error: string; stale?: boolean; cancelled?: boolean };
