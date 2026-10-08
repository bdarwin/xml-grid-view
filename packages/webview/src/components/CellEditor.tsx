import { useLayoutEffect, useRef } from "preact/hooks";

export type EditMove = "none" | "right" | "left";

/** In-place value editor: Enter commits, Tab/Shift+Tab commit and move, Esc cancels, blur commits. */
export function CellEditor(p: { initial: string; selectAll: boolean; onCommit(value: string, move: EditMove): void; onCancel(): void }) {
  const ref = useRef<HTMLInputElement>(null);
  const done = useRef(false);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.focus();
    if (p.selectAll) el.select();
    else el.setSelectionRange(el.value.length, el.value.length);
  }, []);
  const finish = (fn: () => void) => {
    if (done.current) return;
    done.current = true;
    fn();
  };
  return (
    <input
      ref={ref}
      class="cell-editor"
      type="text"
      defaultValue={p.initial}
      spellcheck={false}
      aria-label="Edit value"
      onMouseDown={(e) => e.stopPropagation()}
      onKeyDown={(e) => {
        e.stopPropagation();
        const v = (e.target as HTMLInputElement).value;
        if (e.key === "Enter") {
          e.preventDefault();
          finish(() => p.onCommit(v, "none"));
        } else if (e.key === "Tab") {
          e.preventDefault();
          finish(() => p.onCommit(v, e.shiftKey ? "left" : "right"));
        } else if (e.key === "Escape") {
          e.preventDefault();
          finish(p.onCancel);
        }
      }}
      onBlur={(e) => finish(() => p.onCommit((e.target as HTMLInputElement).value, "none"))}
    />
  );
}
