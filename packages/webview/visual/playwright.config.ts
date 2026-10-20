import { defineConfig } from "@playwright/test";

// Screenshot tests of the web view. Run them in the Playwright Docker image (pnpm visual) so
// fonts and rendering match CI; baselines live in visual/__screenshots__.
export default defineConfig({
  testDir: ".",
  outputDir: "../test-results/visual",
  snapshotPathTemplate: "{testDir}/__screenshots__/{arg}{ext}",
  reporter: process.env.CI ? [["list"], ["html", { open: "never", outputFolder: "../test-results/visual-report" }]] : "list",
  fullyParallel: true,
  use: {
    browserName: "chromium",
    viewport: { width: 1100, height: 620 },
    deviceScaleFactor: 1,
    colorScheme: "light",
  },
  expect: {
    toHaveScreenshot: { maxDiffPixelRatio: 0.002, animations: "disabled", caret: "hide" },
  },
});
