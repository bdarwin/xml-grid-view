import { useEffect, useRef, useState } from "preact/hooks";

export type MenuItem = { label: string; onClick(): void; disabled?: boolean; danger?: boolean } | "separator";

export interface ContextMenuProps {
  x: number;
  y: number;
  items: MenuItem[];
  /** Shown greyed out at the top when every action is unavailable (e.g. read-only). */
  note?: string;
  onClose(): void;
}

/** A small popup menu at (x, y): arrow keys move, Enter picks, Escape or a click outside closes. */
export function ContextMenu(p: ContextMenuProps) {
  const ref = useRef<HTMLDivElement>(null);
  const actions = p.items.filter((i): i is Exclude<MenuItem, "separator"> => i !== "separator");
  const [active, setActive] = useState(() => actions.findIndex((a) => !a.disabled));
  const [pos, setPos] = useState({ left: p.x, top: p.y });

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.focus();
    // Keep the menu inside the window.
    const r = el.getBoundingClientRect();
    setPos({ left: Math.max(0, Math.min(p.x, window.innerWidth - r.width - 4)), top: Math.max(0, Math.min(p.y, window.innerHeight - r.height - 4)) });
    const close = (e: MouseEvent) => {
      if (!el.contains(e.target as Node)) p.onClose();
    };
    window.addEventListener("mousedown", close, true);
    window.addEventListener("blur", p.onClose);
    return () => {
      window.removeEventListener("mousedown", close, true);
      window.removeEventListener("blur", p.onClose);
    };
  }, []);

  const pick = (a: Exclude<MenuItem, "separator">) => {
    if (a.disabled) return;
    p.onClose();
    a.onClick();
  };

  const move = (d: number) => {
    if (!actions.some((a) => !a.disabled)) return;
    let i = active;
    do i = (i + d + actions.length) % actions.length;
    while (actions[i].disabled);
    setActive(i);
  };

  let k = -1;
  return (
    <div
      class="context-menu"
      ref={ref}
      role="menu"
      tabIndex={-1}
      style={{ left: pos.left, top: pos.top }}
      onContextMenu={(e) => e.preventDefault()}
      onKeyDown={(e) => {
        if (e.key === "Escape") p.onClose();
        else if (e.key === "ArrowDown") move(1);
        else if (e.key === "ArrowUp") move(-1);
        else if (e.key === "Enter" && actions[active]) pick(actions[active]);
        else return;
        e.preventDefault();
        e.stopPropagation();
      }}
    >
      {p.note && <div class="menu-note">{p.note}</div>}
      {p.items.map((item, i) => {
        if (item === "separator") return <div key={i} class="menu-sep" role="separator" />;
        const idx = ++k;
        return (
          <div
            key={i}
            role="menuitem"
            aria-disabled={item.disabled || undefined}
            class={"menu-item" + (idx === active ? " active" : "") + (item.disabled ? " disabled" : "") + (item.danger ? " danger" : "")}
            onMouseEnter={() => !item.disabled && setActive(idx)}
            onClick={() => pick(item)}
          >
            {item.label}
          </div>
        );
      })}
    </div>
  );
}
