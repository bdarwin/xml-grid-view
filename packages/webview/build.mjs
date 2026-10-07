// Bundles the view into dist/webview.js + dist/webview.css. The worker is
// bundled first and inlined into the main bundle as a string, so hosts only
// serve two files and start the worker from a Blob URL.
import * as esbuild from "esbuild";
import { mkdirSync, copyFileSync } from "node:fs";

const watch = process.argv.includes("--watch");
const prod = !watch;
mkdirSync("dist", { recursive: true });

const common = {
  bundle: true,
  minify: prod,
  sourcemap: prod ? false : "inline",
  target: ["es2022", "chrome110"],
  logLevel: "info",
  legalComments: "none",
};

async function buildWorker() {
  const r = await esbuild.build({
    ...common,
    entryPoints: ["src/worker.ts"],
    format: "iife",
    write: false,
    // xmldom touches `process` in a few places; keep it inert in the worker.
    define: { "process.env.NODE_ENV": '"production"' },
  });
  return r.outputFiles[0].text;
}

async function buildMain(workerSource) {
  const opts = {
    ...common,
    entryPoints: { webview: "src/main.tsx" },
    outdir: "dist",
    format: "iife",
    jsx: "automatic",
    jsxImportSource: "preact",
    loader: { ".css": "css" },
    define: { __WORKER_SOURCE__: JSON.stringify(workerSource), "process.env.NODE_ENV": '"production"' },
  };
  if (watch) {
    const ctx = await esbuild.context(opts);
    await ctx.watch();
  } else {
    await esbuild.build(opts);
  }
}

await buildMain(await buildWorker());
copyFileSync("dev/index.html", "dist/index.html");
