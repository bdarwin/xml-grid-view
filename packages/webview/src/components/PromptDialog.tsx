import { useEffect, useRef, useState } from "preact/hooks";

export interface PromptField {
  label: string;
  initial?: string;
  /** Returns an error message, or null when the value is acceptable. */
  validate?(value: string): string | null;
}

export interface PromptDialogProps {
  title: string;
  fields: PromptField[];
  okLabel?: string;
  onSubmit(values: string[]): void;
  onCancel(): void;
}

/** A small modal form (element or attribute name, value): Enter submits, Escape cancels. */
export function PromptDialog(p: PromptDialogProps) {
  const [values, setValues] = useState(() => p.fields.map((f) => f.initial ?? ""));
  const first = useRef<HTMLInputElement>(null);
  useEffect(() => {
    first.current?.focus();
    first.current?.select();
  }, []);
  const errors = p.fields.map((f, i) => f.validate?.(values[i]) ?? null);
  const ok = errors.every((e) => e === null);
  const submit = () => ok && p.onSubmit(values);

  return (
    <div class="overlay" onMouseDown={(e) => e.target === e.currentTarget && p.onCancel()}>
      <div
        class="prompt"
        role="dialog"
        aria-modal="true"
        aria-label={p.title}
        onKeyDown={(e) => {
          if (e.key === "Escape") p.onCancel();
          else if (e.key === "Enter") submit();
          else return;
          e.preventDefault();
          e.stopPropagation();
        }}
      >
        <div class="prompt-title">{p.title}</div>
        {p.fields.map((f, i) => (
          <label key={f.label} class="prompt-field">
            <span>{f.label}</span>
            <input
              type="text"
              ref={i === 0 ? first : undefined}
              autoFocus={i === 0}
              class={values[i] && errors[i] ? "invalid" : ""}
              value={values[i]}
              spellcheck={false}
              onInput={(e) => {
                const v = [...values];
                v[i] = (e.target as HTMLInputElement).value;
                setValues(v);
              }}
            />
            {values[i] !== "" && errors[i] && <span class="find-error">{errors[i]}</span>}
          </label>
        ))}
        <div class="popup-actions">
          <span class="spacer" />
          <button class="secondary" onClick={p.onCancel}>
            Cancel
          </button>
          <button disabled={!ok} onClick={submit}>
            {p.okLabel ?? "OK"}
          </button>
        </div>
      </div>
    </div>
  );
}
