import type { Matcher } from "@xmlgridview/core";

/** Renders `text` with matcher hits wrapped in <mark>. */
export function Highlight({ text, matcher, current }: { text: string; matcher: Matcher | null; current?: boolean }) {
  if (!matcher || !text) return <>{text}</>;
  const ranges = matcher.ranges(text.length > 2000 ? text.slice(0, 2000) : text);
  if (!ranges.length) return <>{text}</>;
  const out: (string | preact.JSX.Element)[] = [];
  let last = 0;
  for (const [s, e] of ranges) {
    if (s > last) out.push(text.slice(last, s));
    out.push(
      <mark key={s} class={current ? "match current" : "match"}>
        {text.slice(s, e)}
      </mark>,
    );
    last = e;
  }
  if (last < text.length) out.push(text.slice(last));
  return <>{out}</>;
}
