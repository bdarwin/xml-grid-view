import { JsonNumber, isJsonContainer, jsonContainerLabel, jsonPointer, type JsonPath, type JsonValue, type Matcher } from "@xmlgridview/core";
import { useEffect, useMemo } from "preact/hooks";
import { Highlight } from "./Highlight";
import { scrollRowIntoView, useViewport } from "./useViewport";

const H = 22;

export interface JsonNode {
  path: JsonPath;
  pointer: string;
  key: string | null;
  value: JsonValue;
  depth: number;
}

/** Every node in document order (used for search). */
export function allJsonNodes(root: JsonValue): JsonNode[] {
  const out: JsonNode[] = [];
  const walk = (v: JsonValue, path: JsonPath, key: string | null, depth: number) => {
    out.push({ path, pointer: jsonPointer(path), key, value: v, depth });
    if (Array.isArray(v)) v.forEach((x, i) => walk(x, [...path, i], String(i), depth + 1));
    else if (v instanceof Map) for (const [k, x] of v) walk(x, [...path, k], k, depth + 1);
  };
  walk(root, [], null, 0);
  return out;
}

/** Visible nodes given the expanded set of pointers. */
function visibleNodes(root: JsonValue, expanded: Set<string>): JsonNode[] {
  const out: JsonNode[] = [];
  const walk = (v: JsonValue, path: JsonPath, key: string | null, depth: number) => {
    const pointer = jsonPointer(path);
    out.push({ path, pointer, key, value: v, depth });
    if (!isJsonContainer(v) || !expanded.has(pointer)) return;
    if (Array.isArray(v)) v.forEach((x, i) => walk(x, [...path, i], String(i), depth + 1));
    else for (const [k, x] of v) walk(x, [...path, k], k, depth + 1);
  };
  walk(root, [], null, 0);
  return out;
}

export function primitiveClass(v: JsonValue): string {
  if (typeof v === "string") return "json-string";
  if (v instanceof JsonNumber) return "json-number";
  if (v === null) return "json-null";
  return "json-bool";
}

export function JsonTree(p: {
  root: JsonValue;
  expanded: Set<string>;
  selected: string;
  matcher: Matcher | null;
  hits: Set<string>;
  onToggle(pointer: string, expand?: boolean): void;
  onSelect(pointer: string): void;
  onCopy(node: JsonNode): void;
}) {
  const rows = useMemo(() => visibleNodes(p.root, p.expanded), [p.root, p.expanded]);
  const [ref, vp] = useViewport<HTMLDivElement>();
  const selIndex = rows.findIndex((n) => n.pointer === p.selected);

  useEffect(() => {
    if (selIndex >= 0) scrollRowIntoView(ref.current, selIndex, H);
  }, [selIndex, ref]);

  const first = Math.max(0, Math.floor(vp.top / H) - 10);
  const last = Math.min(rows.length, Math.ceil((vp.top + vp.height) / H) + 10);
  const page = Math.max(1, Math.floor(vp.height / H) - 1);

  const onKeyDown = (e: KeyboardEvent) => {
    const i = selIndex;
    const n = rows[i];
    const move = (to: number) => rows.length && p.onSelect(rows[Math.max(0, Math.min(rows.length - 1, to))].pointer);
    switch (e.key) {
      case "ArrowDown":
        move(i + 1);
        break;
      case "ArrowUp":
        move(i - 1);
        break;
      case "PageDown":
        move(i + page);
        break;
      case "PageUp":
        move(i - page);
        break;
      case "Home":
        move(0);
        break;
      case "End":
        move(rows.length - 1);
        break;
      case "ArrowRight":
        if (n && isJsonContainer(n.value) && !p.expanded.has(n.pointer)) p.onToggle(n.pointer, true);
        else move(i + 1);
        break;
      case "ArrowLeft":
        if (n && isJsonContainer(n.value) && p.expanded.has(n.pointer)) p.onToggle(n.pointer, false);
        else if (n && n.path.length) p.onSelect(jsonPointer(n.path.slice(0, -1)));
        break;
      case "Enter":
        if (n && isJsonContainer(n.value)) p.onToggle(n.pointer);
        break;
      case "c":
      case "C":
        if (!(e.ctrlKey || e.metaKey) || !n) return;
        p.onCopy(n);
        break;
      default:
        return;
    }
    e.preventDefault();
    e.stopPropagation();
  };

  const items = [];
  for (let i = first; i < last; i++) {
    const n = rows[i];
    const container = isJsonContainer(n.value);
    const open = container && p.expanded.has(n.pointer);
    items.push(
      <div
        key={n.pointer}
        class={"tree-row json-row" + (n.pointer === p.selected ? " selected" : "") + (p.hits.has(n.pointer) ? " hit" : "")}
        style={{ top: i * H, paddingLeft: `calc(${n.depth} * var(--xgv-tree-indent) + 4px)` }}
        onMouseDown={(e) => {
          if ((e.target as HTMLElement).classList.contains("twisty")) return;
          p.onSelect(n.pointer);
        }}
        onDblClick={() => container && p.onToggle(n.pointer)}
      >
        <span
          class={"twisty" + (container ? (open ? " open" : " closed") : "")}
          onMouseDown={(e) => {
            e.preventDefault();
            if (container) p.onToggle(n.pointer, !open);
          }}
        />
        {n.key !== null && (
          <span class="json-key">
            <Highlight text={n.key} matcher={p.matcher} />
            <span class="muted">:</span>
          </span>
        )}
        {container ? (
          <span class="muted">{jsonContainerLabel(n.value as JsonValue[])}</span>
        ) : (
          <span class={primitiveClass(n.value)}>
            <Highlight
              text={typeof n.value === "string" ? JSON.stringify(n.value).slice(0, 400) : n.value instanceof JsonNumber ? n.value.text : String(n.value)}
              matcher={p.matcher}
            />
          </span>
        )}
      </div>,
    );
  }
  return (
    <div class="tree json-tree" ref={ref} tabIndex={0} role="tree" aria-label="JSON tree" onKeyDown={onKeyDown}>
      <div class="tree-spacer" style={{ height: rows.length * H }}>
        {items}
      </div>
    </div>
  );
}
