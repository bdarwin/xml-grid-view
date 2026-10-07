import type { ComponentChildren } from "preact";
import { useRef } from "preact/hooks";

/** Horizontal resizable split; `ratio` is the left pane's share (0..1). */
export function Split(p: { ratio: number; onRatio(r: number): void; left: ComponentChildren; right: ComponentChildren }) {
  const ref = useRef<HTMLDivElement>(null);
  const start = (e: MouseEvent) => {
    e.preventDefault();
    const rect = ref.current!.getBoundingClientRect();
    const move = (ev: MouseEvent) => p.onRatio(Math.min(0.85, Math.max(0.1, (ev.clientX - rect.left) / rect.width)));
    const up = () => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
      document.body.classList.remove("resizing");
    };
    document.body.classList.add("resizing");
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
  };
  const onKey = (e: KeyboardEvent) => {
    if (e.key === "ArrowLeft") p.onRatio(Math.max(0.1, p.ratio - 0.02));
    else if (e.key === "ArrowRight") p.onRatio(Math.min(0.85, p.ratio + 0.02));
    else return;
    e.preventDefault();
  };
  return (
    <div class="split" ref={ref}>
      <div class="pane left" style={{ flexBasis: `${p.ratio * 100}%` }}>
        {p.left}
      </div>
      <div
        class="splitter"
        role="separator"
        aria-orientation="vertical"
        aria-valuenow={Math.round(p.ratio * 100)}
        tabIndex={0}
        onMouseDown={start}
        onKeyDown={onKey}
      />
      <div class="pane right">{p.right}</div>
    </div>
  );
}
