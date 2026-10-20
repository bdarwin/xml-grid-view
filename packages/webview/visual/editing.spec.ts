import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect, type Page } from "@playwright/test";
import { themeVars } from "../../vscode/src/theme";

// Structural editing through the context menu, end to end: the harness applies the edits like VS
// Code does, so each test checks the resulting XML as well as how the menu and dialogs look.

const HERE = dirname(fileURLToPath(import.meta.url));
const DIST = join(HERE, "..", "dist");

const XML = `<catalog>
  <book id="bk101">
    <title>XML Developer's Guide</title>
    <price>44.95</price>
  </book>
  <book id="bk102">
    <title>Midnight Rain</title>
    <price>5.95</price>
  </book>
</catalog>
`;

async function open(page: Page, settings: Record<string, unknown> = {}) {
  await page.route("http://xgv.test/**", (route) => {
    const name = new URL(route.request().url()).pathname.slice(1);
    const file = name === "harness.html" ? join(HERE, name) : join(DIST, name);
    const type = name.endsWith(".css") ? "text/css" : name.endsWith(".js") ? "text/javascript" : "text/html";
    return route.fulfill({ body: readFileSync(file), contentType: type });
  });
  await page.goto("http://xgv.test/harness.html");
  await page.evaluate(() => document.documentElement.classList.add("light"));
  await page.waitForFunction(() => (window as unknown as { __ready: boolean }).__ready);
  await page.evaluate(
    ([text, theme, s]) =>
      (window as unknown as { __init(m: unknown): void }).__init({ type: "init", version: 1, fileName: "books.xml", text, docVersion: 1, theme, settings: s }),
    [XML, themeVars(), { readOnly: false, ...settings }] as const,
  );
  await page.locator(".tree-row").first().waitFor();
}

const doc = (page: Page) => page.evaluate(() => (window as unknown as { __doc: string }).__doc);

function treeRow(page: Page, text: string) {
  return page.locator(".tree-row", { hasText: text }).first();
}

async function menuItem(page: Page, row: ReturnType<typeof treeRow>, item: string | RegExp) {
  await row.click({ button: "right" });
  await page.locator(".context-menu").waitFor();
  await page.locator(".menu-item", { hasText: item }).click();
  if (String(item).includes("…") || /Insert|Rename|Add/.test(String(item))) await expect(page.locator(".prompt input").first()).toBeFocused();
}

test("context menu", async ({ page }) => {
  await open(page);
  await treeRow(page, "bk101").click({ button: "right" });
  await page.locator(".context-menu").waitFor();
  await page.mouse.move(0, 0);
  await expect(page).toHaveScreenshot("light-context-menu.png");
});

test("insert a child element", async ({ page }) => {
  await open(page);
  await menuItem(page, treeRow(page, "bk101"), "Insert Child Element");
  await expect(page.locator(".prompt")).toBeVisible();
  await page.keyboard.type("author");
  await expect(page).toHaveScreenshot("light-prompt.png");
  await page.keyboard.press("Enter");
  await expect.poll(() => doc(page)).toContain("<price>44.95</price>\n    <author/>\n  </book>");
  // The new element is selected in the tree.
  await expect(page.locator(".tree-row.selected", { hasText: "author" })).toBeVisible();
});

test("invalid names are rejected in the dialog", async ({ page }) => {
  await open(page);
  await menuItem(page, treeRow(page, "bk101"), "Rename");
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.type("bad name");
  await expect(page.locator(".prompt .find-error")).toHaveText("Not a valid XML name.");
  await expect(page.locator(".prompt button", { hasText: "Rename" })).toBeDisabled();
  await page.keyboard.press("Escape");
  await expect(page.locator(".prompt")).toHaveCount(0);
  expect(await doc(page)).toBe(XML);
});

test("rename, add attribute, duplicate and delete", async ({ page }) => {
  await open(page);
  await menuItem(page, treeRow(page, "bk102"), "Rename");
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.type("magazine");
  await page.keyboard.press("Enter");
  await expect.poll(() => doc(page)).toContain('<magazine id="bk102">');
  expect(await doc(page)).toContain("</magazine>");

  await menuItem(page, treeRow(page, "magazine"), "Add Attribute");
  await page.keyboard.type("lang");
  await page.keyboard.press("Tab");
  await page.keyboard.type("en & fr");
  await page.keyboard.press("Enter");
  await expect.poll(() => doc(page)).toContain('<magazine id="bk102" lang="en &amp; fr">');

  await menuItem(page, treeRow(page, "bk101"), "Duplicate");
  await expect.poll(async () => (await doc(page)).split('<book id="bk101">').length - 1).toBe(2);

  await menuItem(page, treeRow(page, "magazine"), /^Delete <magazine>$/);
  await expect.poll(() => doc(page)).not.toContain("magazine");
  expect(await doc(page)).toBe(
    XML.replace(/ {2}<book id="bk102">[\s\S]*?<\/book>\n/, "").replace(
      "  </book>\n",
      '  </book>\n  <book id="bk101">\n    <title>XML Developer\'s Guide</title>\n    <price>44.95</price>\n  </book>\n',
    ),
  );
});

test("delete an attribute from the Flat view", async ({ page }) => {
  await open(page);
  await page.getByRole("tab", { name: "Flat" }).click();
  await page.locator(".flat-row", { hasText: "@id" }).first().click({ button: "right" });
  await page.locator(".menu-item", { hasText: "Delete Attribute @id" }).click();
  await expect.poll(() => doc(page)).toContain("<book>\n    <title>XML Developer's Guide</title>");
});

test("read-only documents show the menu disabled", async ({ page }) => {
  await open(page, { readOnly: true });
  await treeRow(page, "bk101").click({ button: "right" });
  await expect(page.locator(".menu-note")).toHaveText("This document is read-only.");
  await expect(page.locator(".menu-item.disabled")).toHaveCount(await page.locator(".menu-item").count());
});
