import { useEffect, useLayoutEffect, useRef, useState } from "preact/hooks";

export type EditMove = "none" | "right" | "left";

export interface CellEditorProps {
  initial: string;
  selectAll: boolean;
  onCommit(value: string, move: EditMove): void;
  onCancel(): void;
  /** Values the editor suggests here (e.g. a schema's enumeration), shown as a list under the input. */
  suggest?: () => Promise<string[]>;
}

/**
 * In-place value editor: Enter commits, Tab/Shift+Tab commit and move, Esc cancels, blur commits.
 * With suggestions, a list opens under the input: Up/Down pick, Enter or a click takes the value.
 */
export function CellEditor(p: CellEditorProps) {
  const ref = useRef<HTMLInputElement>(null);
  const done = useRef(false);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [value, setValue] = useState(p.initial);
  const [active, setActive] = useState(-1);
  const [rect, setRect] = useState<DOMRect | null>(null);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.focus();
    if (p.selectAll) el.select();
    else el.setSelectionRange(el.value.length, el.value.length);
  }, []);
  useEffect(() => {
    let live = true;
    void p.suggest?.().then((items) => {
      if (!live || !items.length) return;
      setSuggestions(items);
      setRect(ref.current?.getBoundingClientRect() ?? null);
    });
    return () => {
      live = false;
    };
  }, []);
  // All values while the text is unchanged (to see the choices), else the ones containing it.
  const shown = value === p.initial ? suggestions : suggestions.filter((s) => s.toLowerCase().includes(value.toLowerCase()));
  const finish = (fn: () => void) => {
    if (done.current) return;
    done.current = true;
    fn();
  };
  return (
    <>
      <input
        ref={ref}
        class="cell-editor"
        type="text"
        defaultValue={p.initial}
        spellcheck={false}
        aria-label="Edit value"
        aria-autocomplete={suggestions.length ? "list" : undefined}
        onMouseDown={(e) => e.stopPropagation()}
        onInput={(e) => {
          setValue((e.target as HTMLInputElement).value);
          setActive(-1);
        }}
        onKeyDown={(e) => {
          e.stopPropagation();
          const v = (e.target as HTMLInputElement).value;
          if (shown.length && (e.key === "ArrowDown" || e.key === "ArrowUp")) {
            e.preventDefault();
            const n = shown.length;
            setActive((a) => (e.key === "ArrowDown" ? (a + 1) % n : a <= 0 ? n - 1 : a - 1));
          } else if (e.key === "Enter") {
            e.preventDefault();
            const picked = active >= 0 && active < shown.length ? shown[active] : v;
            finish(() => p.onCommit(picked, "none"));
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
      {shown.length > 0 && rect && (
        // Fixed, so the grid's clipping doesn't cut it off.
        <div
          class="suggest-list"
          role="listbox"
          style={{ left: rect.left, top: rect.bottom + 1, minWidth: rect.width }}
          onMouseDown={(e) => {
            // Keep focus in the input (a blur would commit what is typed).
            e.preventDefault();
            e.stopPropagation();
          }}
        >
          {shown.slice(0, 200).map((s, i) => (
            <div
              key={s}
              role="option"
              aria-selected={i === active}
              class={"suggest-item" + (i === active ? " active" : "") + (s === value ? " current" : "")}
              onMouseEnter={() => setActive(i)}
              onClick={() => finish(() => p.onCommit(s, "none"))}
            >
              {s}
            </div>
          ))}
        </div>
      )}
    </>
  );
}
