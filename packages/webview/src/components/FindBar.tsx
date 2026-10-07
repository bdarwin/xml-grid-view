import type { SearchOptions, SearchScope, SearchTargets, XPathScalar } from "@xmlgridview/core";
import type { Ref } from "preact";

export interface FindState {
  open: boolean;
  mode: "text" | "xpath";
  query: string;
  xpath: string;
  options: SearchOptions;
  scope: SearchScope;
  targets: SearchTargets;
  showOnlyMatches: boolean;
  resultsOpen: boolean;
}

export interface FindBarProps {
  state: FindState;
  onChange(patch: Partial<FindState>): void;
  count: number;
  /** 0-based current index, -1 for none. */
  index: number;
  truncated: boolean;
  busy: boolean;
  error?: string;
  scalar?: XPathScalar;
  namespaces?: Record<string, string>;
  inputRef: Ref<HTMLInputElement>;
  onNext(): void;
  onPrev(): void;
  onClose(): void;
}

const TARGETS: [keyof SearchTargets, string][] = [
  ["names", "Names"],
  ["attrNames", "Attr names"],
  ["attrValues", "Attr values"],
  ["text", "Text"],
];

function Toggle(p: { on: boolean; label: string; title: string; onClick(): void }) {
  return (
    <button class={"toggle" + (p.on ? " on" : "")} aria-pressed={p.on} title={p.title} onClick={p.onClick}>
      {p.label}
    </button>
  );
}

export function FindBar(p: FindBarProps) {
  const s = p.state;
  const xpath = s.mode === "xpath";
  const value = xpath ? s.xpath : s.query;
  const hasQuery = value.trim() !== "";
  let counter = "";
  if (xpath && p.scalar !== undefined) counter = `= ${String(p.scalar)}`;
  else if (hasQuery && !p.error) counter = !p.count
      ? p.busy ? "Searching…" : "No results"
      : p.index < 0
        ? `${p.count}${p.truncated ? "+" : ""} result${p.count === 1 ? "" : "s"}`
        : `${p.index + 1} of ${p.count}${p.truncated ? "+" : ""}`;

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === "Enter") {
      e.preventDefault();
      if (e.shiftKey) p.onPrev();
      else p.onNext();
    } else if (e.key === "Escape") {
      e.preventDefault();
      p.onClose();
    } else if (e.altKey && !e.ctrlKey && !e.metaKey) {
      // Alt+C / Alt+W / Alt+R mirror the editor's find widget.
      const k = e.key.toLowerCase();
      if (k === "c" || k === "w" || k === "r") {
        e.preventDefault();
        const key = k === "c" ? "caseSensitive" : k === "w" ? "wholeWord" : "regex";
        p.onChange({ options: { ...s.options, [key]: !s.options[key] } });
      }
    }
  };

  return (
    <div class="findbar" role="search">
      <div class="find-row">
        <select
          class="mode"
          value={s.mode}
          title="Search mode"
          aria-label="Search mode"
          onChange={(e) => p.onChange({ mode: (e.target as HTMLSelectElement).value as FindState["mode"] })}
        >
          <option value="text">Find</option>
          <option value="xpath">XPath</option>
        </select>
        <div class={"find-input" + (p.error ? " invalid" : "")}>
          <input
            ref={p.inputRef}
            type="text"
            value={value}
            spellcheck={false}
            placeholder={xpath ? "XPath, e.g. //book[@lang='en']/title" : "Find"}
            aria-label={xpath ? "XPath expression" : "Find"}
            aria-invalid={!!p.error}
            onInput={(e) => {
              const v = (e.target as HTMLInputElement).value;
              p.onChange(xpath ? { xpath: v } : { query: v });
            }}
            onKeyDown={onKeyDown}
          />
          {!xpath && (
            <span class="input-toggles">
              <Toggle on={s.options.caseSensitive} label="Aa" title="Match case (Alt+C)" onClick={() => p.onChange({ options: { ...s.options, caseSensitive: !s.options.caseSensitive } })} />
              <Toggle on={s.options.wholeWord} label="ab" title="Match whole word (Alt+W)" onClick={() => p.onChange({ options: { ...s.options, wholeWord: !s.options.wholeWord } })} />
              <Toggle on={s.options.regex} label=".*" title="Use regular expression (Alt+R)" onClick={() => p.onChange({ options: { ...s.options, regex: !s.options.regex } })} />
            </span>
          )}
        </div>
        <span class={"counter" + (p.busy ? " busy" : "")} aria-live="polite">
          {counter}
        </span>
        <button class="icon" title="Previous match (Shift+Enter, Shift+F3)" aria-label="Previous match" disabled={!p.count} onClick={p.onPrev}>
          ↑
        </button>
        <button class="icon" title="Next match (Enter, F3)" aria-label="Next match" disabled={!p.count} onClick={p.onNext}>
          ↓
        </button>
        <button class="icon" title="Close (Escape)" aria-label="Close find" onClick={p.onClose}>
          ×
        </button>
      </div>
      <div class="find-row options">
        {!xpath && (
          <select
            value={s.scope}
            title="Scope"
            aria-label="Scope"
            onChange={(e) => p.onChange({ scope: (e.target as HTMLSelectElement).value as SearchScope })}
          >
            <option value="grid">Current grid</option>
            <option value="document">Whole document</option>
          </select>
        )}
        {!xpath &&
          TARGETS.map(([k, label]) => (
            <label key={k} class="check">
              <input type="checkbox" checked={s.targets[k]} onChange={() => p.onChange({ targets: { ...s.targets, [k]: !s.targets[k] } })} />
              {label}
            </label>
          ))}
        {(xpath || s.scope === "document") && (
          <label class="check">
            <input type="checkbox" checked={s.showOnlyMatches} onChange={() => p.onChange({ showOnlyMatches: !s.showOnlyMatches })} />
            Show only matches in tree
          </label>
        )}
        {(xpath || s.scope === "document") && (
          <button class="link" onClick={() => p.onChange({ resultsOpen: !s.resultsOpen })} aria-expanded={s.resultsOpen}>
            {s.resultsOpen ? "Hide results" : "Show results"}
          </button>
        )}
      </div>
      {p.error && (
        <div class="find-error" role="alert">
          {p.error}
        </div>
      )}
      {xpath && !p.error && p.namespaces && Object.keys(p.namespaces).length > 0 && (
        <div class="find-hint">
          Prefixes:{" "}
          {Object.entries(p.namespaces).map(([k, v]) => (
            <code key={k} title={v}>
              {k}
            </code>
          ))}
        </div>
      )}
    </div>
  );
}
