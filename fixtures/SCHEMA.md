# Golden fixture format

Every `cases/<name>.xml` is a test case shared by the TypeScript core (vitest)
and the IntelliJ Java model (JUnit). The TypeScript core is the reference
implementation: expected files are written by

```sh
pnpm fixtures:generate        # rewrite expected files, then review `git diff fixtures/`
pnpm fixtures:check           # CI: fail if any expected file is stale (never writes)
```

Generated changes must be reviewed by a person before they are committed. CI
never regenerates or accepts them.

## Input files

- `cases/<name>.xml`: UTF-8 with **LF line endings only** (IntelliJ documents
  normalize line separators, so CRLF would shift offsets between hosts). The
  generator rejects files that contain `\r`. `.gitattributes` keeps files at `eol=lf`.
- Names starting with `malformed-` are malformed-input cases (see below).
- `cases/<name>.flat.json` is generated for every well-formed case.
- `cases/<name>.search.json` (optional) lists search and XPath queries. The
  inputs are written by hand; the `expected` members are filled in by the generator.

## Common definitions

**Offsets** are in UTF-16 code units from the start of the file, as both
editors report them. An emoji such as 😀 counts as 2.

- Element `start` is the offset of `<`. Element `end` is the offset just after
  the closing `>` of its end tag, or of the `/>` for an empty-element tag.
- Attribute `start` is the first character of the name. Attribute `end` is just
  after the closing quote.

**Path** (`childIndexPath`): element indices joined with `/`. The first index
counts top-level elements, so the root is `"0"`. Each further index counts only
the parent's **element** children (text, comments and PIs are skipped), starting
at 0. Example: `"0/2/1"`.

**Names** are qualified names exactly as written (`media:thumbnail`). Element
`ns` is the resolved namespace URI, or `""` if there is none.

**Attributes** keep source order. Namespace declarations (`xmlns`, `xmlns:*`)
are not attributes. They don't appear in `attrs`, grid columns or search.

**Element text** is the concatenation, in document order, of the element's
direct text and CDATA children. Entity and character references are decoded,
comments and PIs contribute nothing, and child elements contribute nothing.
The result is then trimmed of XML whitespace only (U+0020, U+0009, U+000A,
U+000D). Interior whitespace is kept as-is, and non-XML spaces such as U+00A0
are not trimmed. Example: `<p>Hello <b>x</b> again</p>` has text `"Hello  again"`
(two spaces).

**Leaf element**: an element with no element children and no attributes.

## `<name>.tree.json`

The root element as a recursive node:

```json
{
  "tag": "book", "ns": "", "path": "0/1", "start": 120, "end": 260,
  "attrs": [{ "name": "id", "value": "b2", "start": 126, "end": 133 }],
  "text": "",
  "children": [ ... ]
}
```

All keys are always present. `text` is the element text (`""` if there is
none), and `children` holds element children only.

## `<name>.grids.json`

An object keyed by path, with one entry for every element that has at least one
element child:

```json
{
  "0": {
    "default": "book",
    "groups": [{ "tag": "book", "count": 3 }, { "tag": "note", "count": 1 }],
    "grids": {
      ".": { ... },
      "book": {
        "columns": [{ "key": "@id", "kind": "attr" }, { "key": "title", "kind": "leaf" }],
        "rows": [["b1", "The Cat"], ["b2", null]]
      },
      "note": { ... }
    }
  }
}
```

- **groups**: the distinct tags of the element children, in order of first
  appearance, with counts. Every group has a grid.
- **`.` (fields grid)**: one more grid whose single row is the element itself,
  so its attributes and children become columns (same column rules). `.` can
  never be a tag name.
- **default**: the grid shown when the element is selected: the first group
  whose count is greater than 1, otherwise `.`. For example, a `<book>` whose
  children are all distinct leaves opens as one row of fields.
- **rows**: the element children with that tag, in document order (for `.`,
  the element itself).
- **columns**, in this order:
  1. Attribute columns, `key` `@<name>`, kind `attr`. Attribute names are
     collected across all rows in first-seen order (row by row, attributes in
     source order).
  2. Child-element columns, `key` `<tag>`. Child tags are collected across all
     rows in first-seen order. The kind is `leaf` if every occurrence of the
     tag in every row is a leaf element and it occurs at most once per row.
     Otherwise the kind is `complex`.
  3. `#text`, kind `text`, present only if at least one row has non-empty
     element text.
- **cells**:
  - `null`: absent (no such attribute or child, or empty element text in `#text`).
  - `""`: present but empty (empty attribute value, or a leaf child with empty text).
  - string: the attribute value, the leaf child's element text, or the row's
    element text for `#text`.
  - `{ "drill": "<tag>", "count": n }`: a complex column, where `n` is the
    number of children with that tag in the row (n ≥ 1).

## `<name>.flat.json`

These are the Flat view's rows: an outline sheet of the whole document, fully
expanded, with one row per element and per attribute:

```json
[
  { "depth": 0, "kind": "element", "name": "catalog", "value": "", "path": "0" },
  { "depth": 1, "kind": "element", "name": "book", "value": "", "path": "0/0" },
  { "depth": 2, "kind": "attr", "name": "@id", "value": "bk101", "path": "0/0" },
  { "depth": 2, "kind": "element", "name": "author", "value": "Gambardella, Matthew", "path": "0/0/0" }
]
```

- **Order:** document order. Each element row comes first, then its attribute
  rows (in source order), then its element children, recursively.
- **depth:** 0 for the root. An element's attribute and child rows have
  `depth + 1`.
- **name:** the qualified tag for elements, or `@` plus the qualified name for
  attributes.
- **value:** the element text for elements (as defined above: trimmed, interior
  whitespace kept), and the attribute value for attributes. An element with no
  text has `""`, even when it has children. The UI may collapse whitespace when
  displaying a value, but the canonical value is never collapsed.
- **path:** the element's path. For an attribute row, it's the owner element's
  path.
- **Comments, processing instructions and the DOCTYPE** get rows too, with
  `kind` `"comment"`, `"pi"` or `"doctype"`. They sit among their parent's
  element children in document order (after the parent's attribute rows), at
  the children's depth; top-level ones have depth 0 and path `""`, others the
  parent's path. `name` is `"#comment"`, the PI target, or `"DOCTYPE"`. `value`
  is the comment text, the PI data, or the declaration after `<!DOCTYPE`
  (including any internal subset), trimmed of XML whitespace, with line ends
  normalized to LF. The XML declaration `<?xml …?>` is not a PI and gets no row.

## `<name>.search.json`

```json
{
  "cases": [
    { "query": "cat",
      "options": { "caseSensitive": false, "wholeWord": false, "regex": false },
      "scope": "document", "target": "all",
      "expected": [{ "path": "0/0/0", "target": "text", "attr": null, "ranges": [[4, 7]] }] },
    { "query": "e", "options": { ... }, "scope": "grid", "target": "all",
      "grid": { "path": "0", "group": "book" },
      "expected": [{ "row": 0, "column": "title", "ranges": [[2, 3]] }] }
  ],
  "xpath": [
    { "expr": "//book/@id", "expected": { "nodes": [{ "path": "0/0", "kind": "attribute", "attr": "id" }] } },
    { "expr": "count(//book)", "expected": { "scalar": 3 } },
    { "expr": "//book[", "expected": { "error": true } }
  ]
}
```

### Matching

- An empty query matches nothing (`expected: []`).
- Plain query, case-insensitive: substring match after lowercasing both sides.
- `caseSensitive`: exact case.
- `regex`: the query is a regular expression. Fixtures only use syntax that
  behaves the same in JavaScript and `java.util.regex`. An invalid pattern gives
  `expected: { "error": true }`.
- `wholeWord`: the match must not be preceded or followed by a word character
  (`[\p{L}\p{N}_]`).
- Zero-length regex matches are ignored.
- `ranges` are the non-overlapping `[start, end)` matches, in UTF-16 units,
  found left to right in the matched field.

### Targets

`target` is one of `all`, `names`, `attrNames`, `attrValues`, `text`.

### Document scope

Elements are visited in document order. Each element produces at most one hit
per field, in this order:

1. its name (`target: "name"`)
2. for each attribute in source order, the attribute name (`attrName`), then the
   attribute value (`attrValue`)
3. its element text (`text`), if non-empty

`attr` is the attribute's qualified name for `attrName`/`attrValue` hits, and
`null` otherwise. `ranges` refer to that field's string.

### Grid scope

`grid` gives the owner element's path and the tag group. Cells are visited row
by row, column by column (table order, unsorted and unfiltered). Which cells
are searched depends on the target:

- attribute cells: `attrValues`
- leaf and `#text` cells: `text`
- complex cells: `names`, matched against the column tag

`ranges` refer to the cell string, or to the tag for complex cells.

### XPath

XPath 1.0 is evaluated on a namespace-aware DOM of the file text.

- **Prefixes**: every prefix declared anywhere in the document is bound to its
  URI (first declaration in document order wins). If the document declares a
  default namespace, its first such URI is also bound to the alias `d` (or to
  `default`, `ns0`, `ns1`, `ns2` if those are taken).
- **Results**:
  - A node set becomes `nodes`. Each node maps to the element it belongs to, by
    path: an element maps to itself, an attribute to its owner element (with
    `attr` set to the attribute's qualified name), and a text or CDATA node to
    its parent element. Other node kinds are dropped.
  - Nodes are sorted by element (document order). Within one element the order
    is element, then attributes in source order, then text. Duplicates (same
    element, kind and attr) are removed.
  - A number, string or boolean result becomes `scalar`. Numbers are compared
    numerically.
  - A syntax error, an unbound prefix, or a document that isn't well-formed gives
    `{ "error": true }`.

## Malformed cases: `malformed-<name>.errors.json`

```json
{ "hasErrors": true, "firstError": { "line": 3, "column": 9 } }
```

Line and column are 1-based. Parsers recover differently, so tree and grid
equality are not checked for these cases. Tests assert that there are errors
and that the first error is on the same `line`. The `column` is the reference
parser's (saxes), which reports the position just after the offending
character. The vitest suite compares it exactly. Other implementations compare
only `line`, because each parser picks its own column for the same error.

## JSON value inspector: `json/<name>.txt` → `json/<name>.json`

The value inspector shows the full text of a node (element text or attribute
value). When the text is JSON, it also shows a tree, grids and a pretty-printed
form. Each `.txt` file is a node's text; the `.json` next to it is the
canonical result:

```json
{ "kind": "text", "hasError": false }
{ "kind": "json", "pretty": "…", "grids": { "": { "columns": […], "keys": […], "rows": […] }, "/lines": { … } } }
```

### Detection

- **JSON:** the text, trimmed of XML whitespace, starts with `{` or `[` and
  parses as strict RFC 8259 JSON. That means no comments, no trailing commas,
  no single quotes, no unquoted keys, and nothing after the value.
- **Text with `hasError: true`:** it starts with `{` or `[` but doesn't parse.
  The UI shows the error location; the fixtures don't compare it.
- **Text with `hasError: false`:** anything else.

### Values

- **Numbers** keep their source literal exactly: `12345678901234567890`,
  `1.50` and `1e3` display as written and are never converted to a double.
- **Objects** keep key order.
- **Duplicate keys:** the last value wins but stays at the key's first
  position.
- **Special-looking keys:** `__proto__` and keys like `(value)` are ordinary
  data.

### `pretty`

- 2-space indentation, `"key": value`, one member or item per line.
- An empty object prints as `{}` and an empty array as `[]`.
- Strings are escaped the way `JSON.stringify` escapes them:
  - `"`, `\` and `\b \f \n \r \t` use their short escapes.
  - Any other code point below U+0020 becomes `\u00XX` with lowercase hex.
  - `/` and non-ASCII characters are not escaped.

### Primitive display text

- Strings as-is.
- Numbers as their literal.
- `true`, `false` and `null` as those words. JSON `null` is the string
  `"null"`, which is distinct from an absent cell.

### Grids

`grids` has one entry per object or array in the value, keyed by JSON Pointer
(RFC 6901; `""` is the root), in document order. `keys` holds the row keys:
array indices as strings, or object keys.

- **Array:** one row per item.
  - Object items contribute their keys as columns (union, first-seen order).
  - Primitive and array items go in a leading `(value)` column, which is
    present only if at least one item is not an object.
- **Object with two or more entries whose values are all objects (a "map"):**
  one row per entry, with a leading `(key)` column and then the union of the
  values' keys.
- **Any other object:** one row per entry, with `(key)` and `(value)` columns.
- **Columns:** the order is `(key)` (objects only), then `(value)` (if
  present), then the union of keys. Columns are positional, so a data key
  literally named `(value)` gets its own column.
- **Column kinds:** `complex` if any cell in the column is a container,
  otherwise `leaf`.
- **Cells:**
  - `null`: absent.
  - A string: a primitive's display text, or the row key in `(key)`.
  - `{ "drill": "object" | "array", "count": n }`: a nested container, where
    `n` is its number of keys or items. The UI labels these `{ n keys }` and
    `[ n items ]`, with singular forms for 1.

## Value edits: `edits/<name>.xml` + `edits/<name>.edits.json`

Editing a value means setting an existing attribute's value, or the text of a
leaf element (an element with no element children). Each edit becomes **one
text replacement** `{ offset, length, text }` on the source, in UTF-16 units.
Hosts apply it as a single undoable edit.

```json
{ "target": { "kind": "attr", "path": [0, 0], "name": "id" }, "value": "A & B",
  "expected": { "edit": { "offset": 44, "length": 5, "text": "A &amp; B" }, "readBack": "A & B", "wellFormed": true } }
```

Inputs are `target` and `value`. `kind` is `attr` (with the attribute's
qualified `name`) or `text`. `path` is the element's childIndexPath as an
array. The generator fills in `expected`. Implementations must produce the same
`edit`, or `{ "error": true }` when the edit is refused. `readBack` (the value
parsed back after applying the edit) and `wellFormed` are self-checks of the
reference implementation.

### Refused (error)

- The element or attribute doesn't exist.
- The document has parse errors.
- A `text` edit on an element that has element children.
- A `text` edit whose current content contains a comment (`<!--`) or a
  processing instruction (`<?`). Replacing it would silently drop them.

### Attribute values

The edit replaces the characters between the attribute's quotes. The original
quote character is kept. The new value is escaped as follows:

| Character | Written as |
| --- | --- |
| `&` | `&amp;` |
| `<` | `&lt;` |
| the quote character in use | `&quot;` (for `"`) or `&apos;` (for `'`) |
| tab, LF, CR | `&#9;`, `&#10;`, `&#13;` (so attribute-value normalization keeps them) |

Everything else, including the other quote character and `>`, is written
literally.

### Element text

- **Normal element:** the edit replaces everything between the end of the
  start tag and the `</` of the end tag. The new text is escaped as `&` →
  `&amp;`, `<` → `&lt;`, `>` → `&gt;`.
- **CDATA kept:** if the current content, trimmed of whitespace, is a single
  CDATA section (`<![CDATA[…]]>` with no second `<![CDATA[`), the new value is
  written as one CDATA section, unescaped. Every `]]>` in it becomes
  `]]]]><![CDATA[>`.
- **Empty-element tag `<t …/>`:**
  - With a non-empty value, the edit replaces the closing `/>` (2
    characters) with `>` + escaped text + `</t>`. Whitespace before the `/`
    is kept, so `<t />` becomes `<t >value</t>`.
  - With an empty value, it is a no-op edit: offset is the tag's end, length
    0, empty text.

## Structural edits: `structure/<name>.xml` + `structure/<name>.ops.json`

Structural edits add, remove, copy and rename things. Each operation becomes
**one or more text replacements** `{ offset, length, text }` on the source, in
descending offset order. Hosts apply all of them as one undoable edit.
Formatting is kept: inserted lines copy the indentation around them, and a
document that uses CRLF gets CRLF line breaks.

```json
{ "op": { "kind": "insertElement", "path": [0, 0], "where": "child", "name": "year" },
  "expected": { "edits": [{ "offset": 237, "length": 0, "text": "\n    <year/>" }], "result": "…", "wellFormed": true } }
```

`op` is the input. The generator fills in `expected`. Implementations must
produce the same `edits`, or `{ "error": true }` when the operation is refused.
`result` (the whole document after the edit) is there for review, and
`wellFormed` is a self-check of the reference implementation.

### Operations

| `kind` | Fields | Effect |
| --- | --- | --- |
| `deleteElement` | `path` | Removes the element. |
| `deleteAttr` | `path`, `name` | Removes the attribute and the whitespace before it. |
| `addAttr` | `path`, `name`, `value` | Adds ` name="value"` at the end of the start tag. |
| `renameElement` | `path`, `name` | Renames the start tag and the end tag. |
| `insertElement` | `path`, `where`, `name` | Inserts an empty element `<name/>` before or after the element, or as its last child. |
| `duplicateElement` | `path` | Inserts a copy of the element's source right after it. |

### Shared terms

- **Own line:** an element is on its own line when only spaces and tabs come
  before its `<` on that line. Its **indent** is those spaces and tabs.
- **Line break:** CRLF if the document contains CRLF anywhere, else LF.
- **Indent unit:** taken from the document. It is the extra indentation of
  the first element (in document order) that is on its own line, below a
  parent that is on its own line, and indented deeper than that parent. With
  no such element it is two spaces.

### Rules

- **`deleteElement`:** if the element is on its own line and only spaces and
  tabs follow its end on that line, the whole line or lines are removed,
  including the line break. Otherwise only the element's span is removed.
- **`deleteAttr`:** the removal starts at the first whitespace character of the
  run that comes before the attribute's name.
- **`addAttr`:** the text goes before the start tag's closing `>` or `/>`,
  before any whitespace that comes ahead of it. So `<spaced />` becomes
  `<spaced x="1" />`. The value is escaped like an attribute value edit, with
  `"` quotes.
- **`renameElement`:** replaces the name in the start tag and, if there is
  one, the end tag. Renaming to the same name produces no edits.
- **`insertElement` / `duplicateElement` after an element:** if the element is
  on its own line, the new text goes on a new line after it, with the
  element's indent. Otherwise it goes directly after the element.
- **`insertElement` before an element:** if the element is on its own line,
  the new element is followed by a line break and the element's indent.
  Otherwise it goes directly before the element.
- **`insertElement` as a child:**
  - **Element has element children:** inserted after the last element child,
    following the after-an-element rule.
  - **Empty-element tag `<a …/>`:** the `/>` becomes `>`, then the new
    element, then `</a>`. If the element is on its own line, the child goes
    on its own line, indented one unit deeper, and `</a>` goes on the next
    line at the element's indent. Whitespace before the `/` is kept.
  - **Otherwise:** the content between the tags must be empty or whitespace,
    and it is replaced in the same way.

### Refused (error)

- The element doesn't exist, or the document has parse errors.
- `deleteElement`, `duplicateElement`, or `insertElement` before or after, on
  the root element.
- A name that isn't a valid QName, or whose prefix isn't declared on the
  element or its ancestors (`xml` is always declared). For an insert before or
  after, the check is made at the element's parent.
- `addAttr` with an attribute name the element already has, or with
  `xmlns`/`xmlns:*`.
- `deleteAttr` with an attribute that doesn't exist.
- `insertElement` as a child of an element that has text and no element
  children, which would create mixed content.

## JSON documents: `jsondoc/<name>.json`

JSON files use the same outputs as XML cases (`<name>.tree.json`,
`<name>.grids.json`, `<name>.flat.json`, or `<name>.errors.json` for
`malformed-*` cases), from the JSON element model:

- The root value is an element named `root`. An object member is a child
  element named by its key (any string, including `""` and names that aren't
  XML names). An array's items are children named `item`.
- A string's decoded value, or a number, `true`, `false` or `null` as
  written, is the element's text. Text is **not** trimmed. Objects and arrays
  have no text.
- Tree nodes get `"type"`: `object`, `array`, `string`, `number`, `boolean`
  or `null` (XML nodes have no `type`).
- `start` is the member's key (its opening quote), or the value itself for
  items and the root; `end` is just after the value.
- `//` and `/* */` comments and trailing commas are accepted (JSONC) and
  ignored. Errors stop the parse; elements left open end at the end of the
  text. Error lines and columns are compared exactly (both parsers are ours).

## JSON edits: `jsonedit/<name>.json` + `<name>.ops.json` / `<name>.edits.json`

The structural operations and value edits above, on JSON documents, with the
same file formats. JSON specifics:

- **Values:** a string stays a string. A number, boolean or null is replaced
  by the new value as written when it reads as a number, `true`, `false` or
  `null`, else by a JSON string. Containers and attributes are refused.
- **Separators** keep the document's style: a new entry goes after the last
  one with `,` and the last entry's indentation (or `, ` when entries share a
  line). An empty container on several lines gets the new entry on its own
  line, indented one level (the indent unit, as for XML); `{}` / `[]` on one
  line stay on one line (`{ "k": "" }`). The key/value separator is taken
  from the first member in the document (default `": "`).
- **Deleting** a run of consecutive entries removes from its first entry up
  to the next kept entry; a run at the end removes from the previous entry's
  end; deleting every entry leaves `{}` / `[]` (inner whitespace removed).
- **insertElement** adds `"name": ""` to an object or `""` to an array (the
  name is ignored for arrays). **renameElement** changes a member's key;
  items have no name. **duplicateElement** and **newRow** apply to array
  items; **newRow** writes the item with every value emptied: `""`, `0`,
  `false`, `null`, `[]`, objects recursively.
- **Grid operations** work on arrays (`group` is `item`): **addColumn** adds
  `"name": ""` to each object row without it; **deleteColumn** removes the
  member from each; **setCells** sets existing members (as value edits) or
  adds missing ones; **insertRows** appends objects laid out like the last
  row. New values are written typed when another row of the array has that
  key as a number, boolean or null; `#text` is the value of a scalar item.
- `deleteAttr` and `addAttr` are refused.
