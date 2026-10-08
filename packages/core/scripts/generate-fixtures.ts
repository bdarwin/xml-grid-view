/**
 * Writes expected fixture outputs from the TS core.
 *
 *   pnpm fixtures:generate          write files, list what changed
 *   pnpm fixtures:generate --check  write nothing; exit 1 if anything is stale
 *
 * Generated changes are meant to be reviewed (git diff) before committing; CI only runs --check.
 */
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { relative } from "node:path";
import { CASES_DIR, expectedEditsOutput, expectedJsonOutput, expectedOutputs, listCases, listEditCases, listJsonCases } from "./fixtures.js";

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
if (!changed.length) {
  console.log("Fixtures are up to date.");
} else if (check) {
  console.error(`Stale fixture outputs (run \`pnpm fixtures:generate\` and review the diff):\n  ${changed.join("\n  ")}`);
  process.exit(1);
} else {
  console.log(`Updated ${changed.length} file(s) — review with \`git diff fixtures/\` before committing:\n  ${changed.join("\n  ")}`);
}
