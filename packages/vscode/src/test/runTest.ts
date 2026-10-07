import * as path from "node:path";
import { runTests } from "@vscode/test-electron";

async function main() {
  const extensionDevelopmentPath = path.resolve(__dirname, "../..");
  const extensionTestsPath = path.resolve(__dirname, "suite/index.js");
  const workspace = path.resolve(extensionDevelopmentPath, "test-fixtures");
  await runTests({
    version: process.env.VSCODE_TEST_VERSION ?? "stable",
    extensionDevelopmentPath,
    extensionTestsPath,
    launchArgs: [workspace, "--disable-extensions", "--skip-welcome", "--skip-release-notes"],
  });
}

main().catch((err) => {
  console.error("Failed to run tests:", err);
  process.exit(1);
});
