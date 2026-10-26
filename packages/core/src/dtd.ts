import { HTML_ENTITIES } from "./htmlEntities.js";

/**
 * What a DOCTYPE declares for entity references. DTDs are never loaded, so:
 * - general entities declared in the internal subset (`<!ENTITY name "value">`) are decoded;
 * - when the DOCTYPE references an external DTD (SYSTEM/PUBLIC) or the internal subset uses
 *   parameter entities, other names may be declared there: references to them are not errors
 *   (XML 1.0, WFC: Entity Declared). The HTML 4 names (`&uuml;`, `&nbsp;`, …) are decoded, as
 *   those DTDs declare them; other names are kept as written.
 * The IntelliJ parser applies the same rules (DtdEntities.java).
 */
export interface DtdEntities {
  /** Entity name → replacement text. */
  entities: Record<string, string>;
  /** True when undeclared names may be declared somewhere that isn't read. */
  external: boolean;
}

const NAME = "[A-Za-z_:][-A-Za-z0-9_.:\\u00B7-\\uFFFF]*";
const EXTERNAL_ID = new RegExp(`^\\s*${NAME}\\s+(SYSTEM|PUBLIC)\\b`);
const ENTITY_DECL = new RegExp(`<!ENTITY\\s+(${NAME})\\s+(?:"([^"]*)"|'([^']*)')\\s*>`, "g");
const PE_REF = new RegExp(`%${NAME};`);
// No prototype: names such as "constructor" must not match.
const PREDEFINED: Record<string, string> = Object.assign(Object.create(null) as Record<string, string>, { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" });

/** Parses the text of a DOCTYPE declaration (everything between `<!DOCTYPE` and the final `>`). */
export function dtdEntities(doctype: string): DtdEntities {
  const open = doctype.indexOf("[");
  const subset = open < 0 ? "" : doctype.slice(open + 1, doctype.lastIndexOf("]") > open ? doctype.lastIndexOf("]") : doctype.length);
  const external = EXTERNAL_ID.test(open < 0 ? doctype : doctype.slice(0, open)) || PE_REF.test(subset);
  const declared: Record<string, string> = {};
  for (const m of subset.matchAll(ENTITY_DECL)) {
    const name = m[1];
    // The first declaration of a name wins.
    if (Object.prototype.hasOwnProperty.call(declared, name) || name in PREDEFINED) continue;
    declared[name] = decodeValue(m[2] ?? m[3] ?? "", declared, external);
  }
  const entities: Record<string, string> = external ? { ...HTML_ENTITIES, ...declared } : declared;
  return { entities, external };
}

/**
 * Decodes character references and references to entities declared before this one (and, with
 * an external DTD, the HTML names).
 */
function decodeValue(value: string, declared: Record<string, string>, external: boolean): string {
  return value.replace(/&(#x[0-9a-fA-F]+|#[0-9]+|[^\s&;]+);/g, (whole, ref: string) => {
    if (ref.startsWith("#")) {
      const cp = ref[1] === "x" || ref[1] === "X" ? parseInt(ref.slice(2), 16) : parseInt(ref.slice(1), 10);
      return cp > 0 && cp <= 0x10ffff ? String.fromCodePoint(cp) : whole;
    }
    if (PREDEFINED[ref] !== undefined) return PREDEFINED[ref];
    if (Object.prototype.hasOwnProperty.call(declared, ref)) return declared[ref];
    return external && Object.prototype.hasOwnProperty.call(HTML_ENTITIES, ref) ? HTML_ENTITIES[ref] : whole;
  });
}
