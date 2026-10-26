/**
 * Writes expected fixture outputs from the TS core.
 *
 *   pnpm fixtures:generate          write files, list what changed
 *   pnpm fixtures:generate --check  write nothing; exit 1 if anything is stale
 *
 * Generated changes are meant to be reviewed (git diff) before committing; CI only runs --check.
 */
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";
import { HTML_ENTITIES, stableJson } from "../src/index.js";
import { CASES_DIR, expectedChangesOutput, expectedEditsOutput, expectedJsonOutput, expectedOutputs, expectedStructureOutput, listCases, listChangeCases, listEditCases, listJsonCases, listStructureCases } from "./fixtures.js";

const check = process.argv.includes("--check");
const changed: string[] = [];
for (const c of listCases()) {
  for (const [path, content] of expectedOutputs(c)) {
    const current = existsSync(path) ? readFileSync(path, "utf8") : null;
    if (current === content) continue;
    changed.push(relative(CASES_DIR, path));
    if (!check) writeFileSync(path, content);
  }
}
for (const c of listJsonCases()) {
  const content = expectedJsonOutput(c);
  const current = existsSync(c.expected) ? readFileSync(c.expected, "utf8") : null;
  if (current === content) continue;
  changed.push(relative(CASES_DIR, c.expected));
  if (!check) writeFileSync(c.expected, content);
}
for (const c of listEditCases()) {
  const content = expectedEditsOutput(c);
  const current = existsSync(c.edits) ? readFileSync(c.edits, "utf8") : null;
  if (current === content) continue;
  changed.push(relative(CASES_DIR, c.edits));
  if (!check) writeFileSync(c.edits, content);
}
for (const c of listStructureCases()) {
  const content = expectedStructureOutput(c);
  const current = existsSync(c.ops) ? readFileSync(c.ops, "utf8") : null;
  if (current === content) continue;
  changed.push(relative(CASES_DIR, c.ops));
  if (!check) writeFileSync(c.ops, content);
}
for (const c of listChangeCases()) {
  const content = expectedChangesOutput(c);
  const current = existsSync(c.expected) ? readFileSync(c.expected, "utf8") : null;
  if (current === content) continue;
  changed.push(relative(CASES_DIR, c.expected));
  if (!check) writeFileSync(c.expected, content);
}
// The HTML entity table, which the IntelliJ parser's copy is checked against.
{
  const path = join(CASES_DIR, "..", "html-entities.json");
  const content = stableJson(HTML_ENTITIES);
  const current = existsSync(path) ? readFileSync(path, "utf8") : null;
  if (current !== content) {
    changed.push(relative(CASES_DIR, path));
    if (!check) writeFileSync(path, content);
  }
}
if (!changed.length) {
  console.log("Fixtures are up to date.");
} else if (check) {
  console.error(`Stale fixture outputs (run \`pnpm fixtures:generate\` and review the diff):\n  ${changed.join("\n  ")}`);
  process.exit(1);
} else {
  console.log(`Updated ${changed.length} file(s) — review with \`git diff fixtures/\` before committing:\n  ${changed.join("\n  ")}`);
}
