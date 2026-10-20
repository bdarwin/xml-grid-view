import type { Diagnostic } from "@xmlgridview/core";

/** CSS class for the worst severity among `ds`, or "" when there are none. */
export function problemClass(ds: Diagnostic[] | undefined): string {
  if (!ds?.length) return "";
  return ds.some((d) => d.severity === "error") ? " problem-error" : " problem-warning";
}

/** Tooltip text: one problem per line. */
export function problemTitle(ds: Diagnostic[] | undefined): string | undefined {
  return ds?.length ? ds.map((d) => (d.source ? `${d.message} (${d.source})` : d.message)).join("\n") : undefined;
}
