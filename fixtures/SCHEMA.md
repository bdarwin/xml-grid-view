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
    "groups": [{ "tag": "book", "count": 3 }, { "tag": "note", "count": 1 }],
    "grids": {
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
- **rows**: the element children with that tag, in document order.
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
