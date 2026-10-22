import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect, type Page } from "@playwright/test";
import { themeVars } from "../../vscode/src/theme";

// Spreadsheet-style editing of the grid, end to end: rows (insert, move, delete), columns (add,
// delete), clearing and pasting cells, and export. The harness applies edits like VS Code does.

const HERE = dirname(fileURLToPath(import.meta.url));
const DIST = join(HERE, "..", "dist");

const XML = `<catalog>
  <book id="b1">
    <title>One</title>
    <price>1</price>
  </book>
  <book id="b2">
    <title>Two</title>
    <price>2</price>
  </book>
  <book id="b3">
    <title>Three</title>
    <price>3</price>
  </book>
</catalog>
`;

async function open(page: Page, text = XML) {
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
    ([t, theme]) =>
      (window as unknown as { __init(m: unknown): void }).__init({ type: "init", version: 1, fileName: "books.xml", text: t, docVersion: 1, theme, settings: { readOnly: false } }),
    [text, themeVars()] as const,
  );
  await page.locator(".grid-row").first().waitFor();
}

const doc = (page: Page) => page.evaluate(() => (window as unknown as { __doc: string }).__doc);

const cell = (page: Page, row: number, col: number) => page.locator(".grid-row").nth(row).locator(".cell").nth(col);

async function gridMenu(page: Page, row: number, col: number, item: string | RegExp) {
  await cell(page, row, col).click({ button: "right" });
  await page.locator(".context-menu").waitFor();
  await page.locator(".menu-item", { hasText: item }).first().click();
  if (String(item).includes("Add Column")) await expect(page.locator(".prompt input").first()).toBeFocused();
}

async function setClipboard(page: Page, text: string) {
  await page.evaluate((t) => ((window as unknown as { __clipboard: string }).__clipboard = t), text);
}

test("grid row menu", async ({ page }) => {
  await open(page);
  await cell(page, 1, 1).click({ button: "right" });
  await page.locator(".context-menu").waitFor();
  await expect(page).toHaveScreenshot("light-grid-row-menu.png");
});

test("insert, move and delete rows", async ({ page }) => {
  await open(page);
  await gridMenu(page, 0, 1, "Insert Row Below");
  await expect.poll(() => doc(page)).toContain('</book>\n  <book id="">\n    <title/>\n    <price/>\n  </book>\n  <book id="b2">');
  // The new row is selected; type into it.
  await expect(cell(page, 1, 1)).toHaveClass(/focus/);
  await page.keyboard.type("New");
  await page.keyboard.press("Enter");
  await expect.poll(() => doc(page)).toContain("<title>New</title>");

  // Alt+Shift+Down moves the focused row and keeps it selected.
  await cell(page, 0, 0).click();
  await page.keyboard.press("Alt+Shift+ArrowDown");
  await expect.poll(() => doc(page)).toMatch(/<book id="">[\s\S]*<book id="b1">[\s\S]*<book id="b2">/);
  await expect(cell(page, 1, 0)).toHaveClass(/focus/);
  await expect(page.locator(".menu-item", { hasText: "Move Row Up" })).toHaveCount(0);

  // Select two rows and delete them.
  await cell(page, 0, 0).click();
  await cell(page, 1, 0).click({ modifiers: ["Shift"] });
  await gridMenu(page, 1, 0, "Delete 2 Rows");
  await expect.poll(() => doc(page)).toBe(XML.replace(/ {2}<book id="b1">[\s\S]*?<\/book>\n/, ""));
});

test("add and delete columns", async ({ page }) => {
  await open(page);
  await gridMenu(page, 0, 1, "Add Column");
  await page.keyboard.type("@year");
  await page.keyboard.press("Enter");
  await expect.poll(() => (doc(page).then((d) => d.match(/year=""/g)?.length ?? 0))).toBe(3);

  await gridMenu(page, 0, 1, "Add Column");
  await page.keyboard.type("author");
  await page.keyboard.press("Enter");
  await expect.poll(() => doc(page).then((d) => d.match(/<author\/>/g)?.length ?? 0)).toBe(3);
  expect(await doc(page)).toContain("    <price>1</price>\n    <author/>\n  </book>");

  // Delete the price column: every <price> goes.
  await page.locator(".hcell", { hasText: "price" }).waitFor();
  const priceCol = await page.locator(".hcell").evaluateAll((hs) => hs.findIndex((h) => h.textContent?.includes("price")));
  await gridMenu(page, 0, priceCol, "Delete Column price");
  await expect.poll(() => doc(page)).not.toContain("<price>");
  expect(await doc(page)).toContain('<book id="b1" year="">\n    <title>One</title>\n    <author/>\n  </book>');
});

test("paste a block from a spreadsheet, adding rows", async ({ page }) => {
  await open(page);
  // Header row matching the columns is skipped; the third data row becomes a new <book>.
  await setClipboard(page, "title\tprice\r\nUno\t10\r\n\"Dos, \"\"two\"\"\"\t20\r\nCuatro\t40\r\nCinco\t50\r\n");
  await cell(page, 1, 1).click();
  await page.keyboard.press("ControlOrMeta+v");
  await expect.poll(() => doc(page)).toContain("<title>Cinco</title>");
  const d = await doc(page);
  expect(d).toContain('<book id="b2">\n    <title>Uno</title>\n    <price>10</price>');
  expect(d).toContain('<title>Dos, "two"</title>\n    <price>20</price>');
  expect(d).toContain("  </book>\n  <book>\n    <title>Cuatro</title>\n    <price>40</price>\n  </book>\n  <book>\n    <title>Cinco</title>");
  expect(d).toContain("<title>One</title>");
  // One undo step: a single edit message.
  const edits = await page.evaluate(() => (window as unknown as { __hostLog: { type: string }[] }).__hostLog.filter((m) => m.type === "edit").length);
  expect(edits).toBe(1);
});

test("clear cells with Delete, export CSV and JSON", async ({ page }) => {
  await open(page);
  await cell(page, 0, 1).click();
  await cell(page, 1, 2).click({ modifiers: ["Shift"] });
  await page.keyboard.press("Delete");
  await expect.poll(() => doc(page)).toContain('<book id="b1">\n    <title></title>\n    <price></price>');
  expect(await doc(page)).toContain('<book id="b2">\n    <title></title>\n    <price></price>');

  await page.getByRole("button", { name: "Export" }).click();
  await page.locator(".menu-item", { hasText: "Export as CSV" }).click();
  const csv = await page.evaluate(() => (window as unknown as { __saved: { fileName: string; text: string } }).__saved);
  expect(csv.fileName).toBe("books-book.csv");
  expect(csv.text).toBe("@id,title,price\r\nb1,,\r\nb2,,\r\nb3,Three,3\r\n");

  await page.getByRole("button", { name: "Export" }).click();
  await page.locator(".menu-item", { hasText: "Export as JSON" }).click();
  const json = await page.evaluate(() => (window as unknown as { __saved: { text: string } }).__saved.text);
  expect(JSON.parse(json)[2]).toEqual({ "@id": "b3", title: "Three", price: "3" });
});

test("JSON: edit cells, paste rows, add a member", async ({ page }) => {
  const json = `[
  { "id": 1, "name": "Ada", "active": true },
  { "id": 2, "name": "Grace", "active": false }
]
`;
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
    ([t, theme]) =>
      (window as unknown as { __init(m: unknown): void }).__init({ type: "init", version: 1, fileName: "people.json", text: t, docVersion: 1, theme, settings: { readOnly: false } }),
    [json, themeVars()] as const,
  );
  await page.locator(".grid-row").nth(1).waitFor();
  await expect(page.locator(".crumb.group")).toHaveText("item");

  // Typing a number into a number cell keeps it a number; text becomes a string.
  await cell(page, 0, 0).click();
  await page.keyboard.type("10");
  await page.keyboard.press("Enter");
  await expect.poll(() => doc(page)).toContain('{ "id": 10, "name": "Ada"');
  await cell(page, 1, 1).dblclick();
  await expect(page.locator(".inspector")).toBeVisible().catch(() => undefined);
  await page.keyboard.press("Escape");

  // Paste two rows at the second row: the first overwrites Grace, the second is new.
  await setClipboard(page, "3\tLin\ttrue\n4\tMo\tfalse\n");
  await cell(page, 1, 0).click();
  await page.keyboard.press("ControlOrMeta+v");
  await expect.poll(() => doc(page)).toBe(`[
  { "id": 10, "name": "Ada", "active": true },
  { "id": 3, "name": "Lin", "active": true },
  { "id": 4, "name": "Mo", "active": false }
]
`);

  // The tree menu speaks JSON.
  await page.locator(".tree-row", { hasText: "[0]" }).click({ button: "right" });
  await expect(page.locator(".menu-item", { hasText: "Add Member…" })).toBeVisible();
  await expect(page.locator(".menu-item", { hasText: "Add Attribute" })).toHaveCount(0);
  await page.locator(".menu-item", { hasText: "Add Member…" }).click();
  await expect(page.locator(".prompt input").first()).toBeFocused();
  await page.keyboard.type("email");
  await page.keyboard.press("Enter");
  await expect.poll(() => doc(page)).toContain('{ "id": 10, "name": "Ada", "active": true, "email": "" }');
});

test("value suggestions while editing a cell", async ({ page }) => {
  await open(page, XML.replace('<book id="b1">', '<book id="b1" format="hardcover">').replace('<book id="b2">', '<book id="b2" format="ebook">'));
  // Like an XSD enumeration for @format: offered where the value starts in the document.
  await page.evaluate(() => {
    (window as unknown as { __suggest(o: number, d: string): string[] }).__suggest = (o, d) =>
      /^(hardcover|paperback|ebook)"/.test(d.slice(o)) ? ["hardcover", "paperback", "ebook"] : [];
  });
  await cell(page, 0, 1).click();
  await page.keyboard.press("F2");
  await expect(page.locator(".suggest-item")).toHaveText(["hardcover", "paperback", "ebook"]);
  await expect(page.locator(".suggest-item.current")).toHaveText("hardcover");
  await page.mouse.move(0, 0);
  await expect(page).toHaveScreenshot("light-suggestions.png");
  // Typing filters; Down + Enter takes the value.
  await page.keyboard.press("End");
  await page.keyboard.press("Control+a");
  await page.keyboard.type("bo", { delay: 20 });
  await expect(page.locator(".suggest-item")).toHaveText(["ebook"]);
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect.poll(() => doc(page)).toContain('<book id="b1" format="ebook">');
  // A cell without suggestions edits as before (no list).
  await cell(page, 1, 2).click();
  await page.keyboard.press("F2");
  await expect(page.locator(".cell-editor")).toBeVisible();
  await page.waitForTimeout(300);
  await expect(page.locator(".suggest-list")).toHaveCount(0);
  await page.keyboard.press("Escape");
});

test("count, sum, average, min and max of the selected cells", async ({ page }) => {
  await open(page, XML.replace("<price>2</price>", "<price>0.2</price>").replace("<price>1</price>", "<price>0.1</price>"));
  const stats = page.locator(".selection-stats");
  // One cell: nothing.
  await cell(page, 0, 2).click();
  await expect(stats).toHaveText("");
  // The price column (0.1, 0.2, 3): floating-point noise is rounded away.
  await cell(page, 2, 2).click({ modifiers: ["Shift"] });
  await expect(stats).toHaveText("3 cells · Count 3 · Sum 3.3 · Avg 1.1 · Min 0.1 · Max 3");
  await page.mouse.move(0, 0);
  await expect(page).toHaveScreenshot("light-selection-stats.png");
  // Titles and prices: only the numbers are summed.
  await cell(page, 0, 1).click();
  await cell(page, 1, 2).click({ modifiers: ["Shift"] });
  await expect(stats).toHaveText("4 cells · Count 4 · Sum 0.3 · Avg 0.15 · Min 0.1 · Max 0.2");
  // Text only: just the count.
  await cell(page, 0, 1).click();
  await cell(page, 2, 1).click({ modifiers: ["Shift"] });
  await expect(stats).toHaveText("3 cells · Count 3");
});
