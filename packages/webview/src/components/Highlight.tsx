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

/** Tree preview `name="value" … text`, with attribute names and values coloured. */
export function PreviewText({ text, matcher }: { text: string; matcher: Matcher | null }) {
  const out: preact.JSX.Element[] = [];
  const re = /([^\s="]+)="([^"]*)"/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let k = 0;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) out.push(<span key={k++} class="pv-text"><Highlight text={text.slice(last, m.index)} matcher={matcher} /></span>);
    out.push(
      <span key={k++} class="pv-attr">
        <span class="pv-name"><Highlight text={m[1]} matcher={matcher} /></span>
        <span class="pv-eq">=</span>
        <span class="pv-value"><Highlight text={m[2]} matcher={matcher} /></span>
      </span>,
    );
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push(<span key={k++} class="pv-text"><Highlight text={text.slice(last)} matcher={matcher} /></span>);
  return <>{out}</>;
}
