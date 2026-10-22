import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect, type Page } from "@playwright/test";
import { themeVars } from "../../vscode/src/theme";

// Records mode: files too large to load show the root's children a page at a time, read by the host.
// The harness plays the host, making records on demand.

const HERE = dirname(fileURLToPath(import.meta.url));
const DIST = join(HERE, "..", "dist");

async function open(page: Page, count: number, extra: Record<string, unknown> = {}) {
  await page.route("http://xgv.test/**", (route) => {
    const name = new URL(route.request().url()).pathname.slice(1);
    const file = name === "harness.html" ? join(HERE, name) : join(DIST, name);
    const type = name.endsWith(".css") ? "text/css" : name.endsWith(".js") ? "text/javascript" : "text/html";
    return route.fulfill({ body: readFileSync(file), contentType: type });
  });
  await page.goto("http://xgv.test/harness.html");
  await page.evaluate(() => document.documentElement.classList.add("light"));
  await page.waitForFunction(() => (window as unknown as { __ready: boolean }).__ready);
  await page.evaluate((n) => {
    const w = window as unknown as { __recordCount: number; __record(g: string, i: number): string };
    w.__recordCount = n;
    w.__record = (_g, i) =>
      `<event id="e${i}" level="${i % 7 === 3 ? "warn" : "info"}"><ts>2026-10-22T09:${String(Math.floor(i / 60) % 60).padStart(2, "0")}:${String(i % 60).padStart(2, "0")}Z</ts><ms>${(i * 37) % 1000}</ms><x:msg>request ${i} done</x:msg></event>`;
  }, count);
  // Progress while the host indexes, then init.
  await page.evaluate(() => window.postMessage({ type: "recordsProgress", bytes: 300 * 1048576, total: 800 * 1048576 }, "*"));
  await expect(page.getByText("Indexing records… 37% of 800.0 MB")).toBeVisible();
  await page.evaluate(
    ([n, theme, extra]) =>
      (window as unknown as { __init(m: unknown): void }).__init({
        type: "init",
        version: 1,
        fileName: "events.xml",
        text: "",
        theme,
        settings: { readOnly: true },
        records: { rootName: "events", rootStartTag: '<events xmlns:x="urn:x">', groups: [{ name: "event", count: n }], fileSize: 800 * 1048576, ...extra },
      }),
    [count, themeVars(), extra] as const,
  );
}

test("records mode: pages load as they scroll into view", async ({ page }) => {
  await open(page, 100_000);
  await expect(page.locator(".records-title")).toHaveText("events · 100,000 event records · 800 MB · read-only");
  const first = page.locator(".grid-row").first();
  await expect(first.locator(".cell").first()).toHaveText("e0");
  await expect(page.locator(".hcell .hlabel")).toHaveText(["@id", "@level", "ts", "ms", "x:msg"]);
  await expect(page.locator(".filter-btn")).toHaveCount(0);
  await page.mouse.move(0, 0);
  await expect(page).toHaveScreenshot("light-records.png");

  // Find searches every record; Enter steps through the matches.
  await page.locator(".records-find").fill("warn");
  await page.keyboard.press("Enter");
  await expect(page.locator(".records-hits")).toHaveText("1 of 14,286");
  await expect(page.locator(".grid-row.record-hit.current .cell").first()).toHaveText("e3");
  await page.locator(".records-find").press("Enter");
  await expect(page.locator(".records-hits")).toHaveText("2 of 14,286");
  await expect(page.locator(".grid-row.record-hit.current .cell").first()).toHaveText("e10");

  // Double-click opens the record as text (the host gets the request).
  await page.locator(".grid-row.record-hit.current .cell").nth(1).dblclick();
  const log = await page.evaluate(() => (window as unknown as { __hostLog: { type: string; index?: number }[] }).__hostLog);
  expect(log.some((m) => m.type === "openRecord" && m.index === 10)).toBe(true);

  // Selection stats over loaded cells.
  await page.locator(".grid-row").nth(0).locator(".cell").nth(3).click();
  await page.locator(".grid-row").nth(2).locator(".cell").nth(3).click({ modifiers: ["Shift"] });
  await expect(page.locator(".selection-stats")).toHaveText("3 cells · Count 3 · Sum 111 · Avg 37 · Min 0 · Max 74");
});

test("records mode: millions of rows scroll to the end", async ({ page }) => {
  await open(page, 2_000_000);
  await expect(page.locator(".grid-row").first().locator(".cell").first()).toHaveText("e0");
  await page.locator(".grid").evaluate((el) => (el.scrollTop = el.scrollHeight));
  await expect(page.locator(".grid-row .cell", { hasText: /^e1999999$/ })).toBeVisible();
  await expect(page.locator(".rownum", { hasText: /^2000000$/ })).toBeVisible();
  // Keyboard: Home goes back to the first row.
  await page.locator(".grid-row .cell", { hasText: /^e1999999$/ }).click();
  await page.keyboard.press("Control+Home");
  await expect(page.locator(".grid-row .cell", { hasText: /^e0$/ })).toBeVisible();
});

test("records mode: a file that is not well-formed shows the records before the problem", async ({ page }) => {
  await open(page, 10, { error: "End tag </x> does not match <event> at byte 1234" });
  await expect(page.locator(".banner.warning")).toContainText("End tag </x> does not match <event> at byte 1234");
  await expect(page.locator(".grid-row")).toHaveCount(10);
});
