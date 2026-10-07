// Bundles the extension host code and copies the built web view next to it.
import * as esbuild from "esbuild";
import { cpSync, existsSync, mkdirSync, rmSync } from "node:fs";

const webviewDist = new URL("../webview/dist/", import.meta.url);
if (!existsSync(new URL("webview.js", webviewDist))) {
  console.error("packages/webview/dist is missing; build @xmlgridview/webview first (pnpm build does this).");
  process.exit(1);
}
rmSync("dist", { recursive: true, force: true });
mkdirSync("dist/webview", { recursive: true });
for (const f of ["webview.js", "webview.css"]) cpSync(new URL(f, webviewDist), `dist/webview/${f}`);

await esbuild.build({
  entryPoints: ["src/extension.ts"],
  outfile: "dist/extension.js",
  bundle: true,
  platform: "node",
  format: "cjs",
  target: "node18",
  external: ["vscode"],
  minify: true,
  sourcemap: false,
  logLevel: "info",
});
