/** Whether a value deserves an inline "open in inspector" button: long, multi-line, or JSON-looking. */
export function isInspectable(text: string | null | undefined): boolean {
  if (!text) return false;
  if (text.length > 60 || text.includes("\n")) return true;
  const t = text.trimStart();
  return (t.startsWith("{") || t.startsWith("[")) && t.length > 2;
}
