import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect, type Page } from "@playwright/test";
import { themeVars } from "../../vscode/src/theme";

// Screenshot tests of the web view as VS Code shows it (Light+ and Dark+ variables, real theme
// mapping). Each scene drives the UI the way a user would, then compares against a baseline.
// Update baselines with `pnpm visual:update` and review the PNG diff before committing.

const HERE = dirname(fileURLToPath(import.meta.url));
const DIST = join(HERE, "..", "dist");
const FIXTURES = join(HERE, "..", "..", "..", "fixtures", "cases");
const sample = readFileSync(join(HERE, "sample.xml"), "utf8");

type Theme = "light" | "dark";

async function open(page: Page, opts: { theme?: Theme; text?: string; settings?: Record<string, unknown>; width?: number; fileName?: string } = {}) {
  await page.route("http://xgv.test/**", (route) => {
    const name = new URL(route.request().url()).pathname.slice(1);
    const file = name === "harness.html" ? join(HERE, name) : join(DIST, name);
    const type = name.endsWith(".css") ? "text/css" : name.endsWith(".js") ? "text/javascript" : "text/html";
    return route.fulfill({ body: readFileSync(file), contentType: type });
  });
  if (opts.width) await page.setViewportSize({ width: opts.width, height: 620 });
  await page.goto("http://xgv.test/harness.html");
  await page.evaluate((t) => document.documentElement.classList.add(t), opts.theme ?? "light");
  await page.waitForFunction(() => (window as unknown as { __ready: boolean }).__ready);
  await page.evaluate(
    ([text, theme, settings, fileName]) =>
      (window as unknown as { __init(m: unknown): void }).__init({
        type: "init",
        version: 1,
        fileName,
        text,
        docVersion: 1,
        theme,
        settings,
      }),
    [opts.text ?? sample, themeVars(), { readOnly: false, ...opts.settings }, opts.fileName ?? "sample.xml"] as const,
  );
}

async function ready(page: Page) {
  await page.locator(".tree-row").first().waitFor();
  await page.locator(".grid-row, .grid-empty").first().waitFor();
}

async function shot(page: Page, name: string) {
  await page.mouse.move(0, 0); // no hover effects
  await expect(page).toHaveScreenshot(`${name}.png`);
}

function treeRow(page: Page, text: string) {
  return page.locator(".tree-row", { hasText: text }).first();
}

for (const theme of ["light", "dark"] as const) {
  test.describe(theme, () => {
    test("grid of repeating children", async ({ page }) => {
      await open(page, { theme });
      await ready(page);
      await shot(page, `${theme}-grid`);
    });

    test("fields grid of one element", async ({ page }) => {
      await open(page, { theme });
      await ready(page);
      await treeRow(page, "bk102").click();
      await page.locator(".grid-row").first().waitFor();
      await shot(page, `${theme}-grid-fields`);
    });

    test("flat sheet with section colours", async ({ page }) => {
      await open(page, { theme });
      await ready(page);
      await page.getByRole("tab", { name: "Flat" }).click();
      await page.locator(".flat-row").first().waitFor();
      await shot(page, `${theme}-flat`);
    });

    test("find with highlighted matches", async ({ page }) => {
      await open(page, { theme });
      await ready(page);
      await page.locator(".grid").click();
      await page.keyboard.press("ControlOrMeta+f");
      await page.locator(".find-input input").fill("fantasy");
      await page.locator(".counter").filter({ hasText: /\d/ }).waitFor();
      await shot(page, `${theme}-find`);
    });

    test("value inspector with JSON", async ({ page }) => {
      await open(page, { theme });
      await ready(page);
      await treeRow(page, "config").click();
      await page.locator(".grid-row .cell").first().dblclick();
      await page.locator(".inspector").waitFor();
      await shot(page, `${theme}-inspector`);
    });
  });
}

test("drill into a nested repeat", async ({ page }) => {
  await open(page);
  await ready(page);
  await page.locator(".grid-row").first().locator(".drill").first().click();
  await page.locator(".crumb-sep").nth(1).waitFor();
  await shot(page, "light-grid-drill");
});

test("sorted and filtered grid", async ({ page }) => {
  await open(page);
  await ready(page);
  await page.locator(".hcell", { hasText: "price" }).locator(".hlabel").click();
  await page.locator(".quick-filter").fill("Corets");
  await expect(page.locator(".grid-row")).toHaveCount(2);
  await shot(page, "light-grid-sorted-filtered");
});

test("flat sheet collapsed", async ({ page }) => {
  await open(page, { theme: "dark" });
  await ready(page);
  await page.getByRole("tab", { name: "Flat" }).click();
  await page.getByRole("button", { name: "Collapse all" }).click();
  await shot(page, "dark-flat-collapsed");
});

test("tree filter box", async ({ page }) => {
  await open(page);
  await ready(page);
  await page.locator(".tree-filter input").fill("Corets");
  await expect(page.locator(".tree-row", { hasText: "bk101" })).toHaveCount(0);
  await page.locator(".tree-row", { hasText: "bk103" }).waitFor();
  await shot(page, "light-tree-filter");
});

test("malformed document banner", async ({ page }) => {
  await open(page, { text: readFileSync(join(FIXTURES, "malformed-unclosed.xml"), "utf8") });
  await page.locator(".banner, .notice").first().waitFor();
  await shot(page, "light-malformed");
});

test("large file notice", async ({ page }) => {
  await open(page, { settings: { largeFileThreshold: 1024 } });
  await page.getByRole("button", { name: "Load anyway" }).waitFor();
  await shot(page, "light-large-notice");
});

test("narrow window", async ({ page }) => {
  await open(page, { width: 640 });
  await ready(page);
  await shot(page, "light-narrow");
});

test("namespaces and attributes-only records", async ({ page }) => {
  await open(page, { text: readFileSync(join(FIXTURES, "namespaces.xml"), "utf8") });
  await ready(page);
  await shot(page, "light-namespaces");
});

test("element summary expanded, with hover", async ({ page }) => {
  await open(page, { theme: "dark" });
  await ready(page);
  await page.locator(".grid-row").first().locator(".drill").first().click();
  await page.locator(".crumb-sep").nth(1).waitFor();
  await page.locator(".summary-more").click();
  await expect(page.locator(".summary-more")).toHaveText("less");
  await page.locator(".summary-item", { hasText: "description" }).hover();
  await expect(page).toHaveScreenshot("dark-summary-expanded.png");
});

test("element summary switches groups", async ({ page }) => {
  await open(page);
  await ready(page);
  await treeRow(page, "bk101").click();
  await page.getByRole("tab", { name: /^tags/ }).click();
  await expect(page.locator(".crumb.group")).toHaveText("tags");
  await page.getByRole("tab", { name: "(fields)" }).click();
  await expect(page.locator(".crumb.group")).toHaveText("(fields)");
  // Fields view: the grid already shows the values, so only the chips remain.
  await expect(page.locator(".summary-item")).toHaveCount(0);
  await shot(page, "light-summary-fields");
});

test("comments, processing instructions and DOCTYPE", async ({ page }) => {
  const text = `<?xml version="1.0"?>
<!DOCTYPE catalog SYSTEM "catalog.dtd">
<!-- Exported 2026-10-20 -->
<?xml-stylesheet type="text/xsl" href="catalog.xsl"?>
<catalog>
  <!-- first shelf -->
  <book id="b1"><title>One</title></book>
  <?page break?>
  <book id="b2"><title>Two</title><!-- out of print --></book>
</catalog>
`;
  await open(page, { text });
  await ready(page);
  await expect(page.locator(".tree-row.misc")).toHaveCount(5);
  await shot(page, "light-misc-tree");
  await page.getByRole("tab", { name: "Flat" }).click();
  await page.locator(".flat-row.misc").first().waitFor();
  await expect(page.locator(".flat-row.misc")).toHaveCount(6);
  await shot(page, "light-misc-flat");
});

test("problems from the editor in tree, grid and Flat", async ({ page }) => {
  await open(page);
  await ready(page);
  const at = (s: string, from = 0) => sample.indexOf(s, from);
  const items = [
    // An attribute value, a leaf child of a row, a whole row, and something deep inside a book.
    { offset: at('"paperback"'), length: 11, severity: "error", message: 'Value "paperback" is not in the enumeration.', source: "xml" },
    { offset: at("<price", at("bk103")) + 1, length: 5, severity: "warning", message: "Element price is deprecated.", source: "xml" },
    { offset: at("<book", at("bk103") - 20) + 1, length: 4, severity: "error", message: "Missing required element isbn.", source: "xml" },
    { offset: at("<tag>", at("bk101")) + 1, length: 3, severity: "error", message: "Invalid tag.", source: "xml" },
  ];
  await page.evaluate((its) => window.postMessage({ type: "diagnostics", items: its }, "*"), items);
  await expect(page.locator(".problems-btn")).toHaveText("✖ 3⚠ 1");
  await expect(page.locator(".cell.problem-error")).toHaveCount(2);
  await expect(page.locator(".cell.problem-warning")).toHaveCount(1);
  await expect(page.locator(".tree-row.problem-within")).toHaveCount(1);
  await shot(page, "light-problems-grid");

  // F8 goes to the first problem: book bk101 (its <tag>), shown as a row of the tags grid.
  await page.locator(".grid").focus();
  await page.keyboard.press("F8");
  await expect(page.locator(".toast")).toContainText("Error 1/4: Invalid tag.");
  await expect(page.locator(".crumb.group")).toHaveText("tag");

  await page.getByRole("tab", { name: "Flat" }).click();
  await page.locator(".flat-row").first().waitFor();
  await expect(page.locator(".flat-row.attr.problem-error")).toHaveCount(1);
  await page.mouse.move(0, 0);
  await page.locator(".toast").click();
  await expect(page).toHaveScreenshot("light-problems-flat.png");
});

const JSON_SAMPLE = `{
  "store": "Books & more",
  "books": [
    { "id": 1, "title": "XML Developer's Guide", "price": 44.95, "tags": ["xml", "reference"], "inStock": true },
    { "id": 2, "title": "Midnight Rain", "price": 5.95, "tags": ["fantasy"], "inStock": false },
    { "id": 3, "title": "Maeve Ascendant", "price": 5.95, "author": null }
  ],
  "meta": { "count": 3, "updated": "2026-10-20" }
}
`;

test("JSON document as tree and grid", async ({ page }) => {
  await open(page, { text: JSON_SAMPLE, fileName: "books.json" });
  await ready(page);
  const books = page.locator(".tree-row").filter({ has: page.locator(".tag", { hasText: /^books$/ }) });
  await books.click();
  await page.locator(".grid-row").nth(2).waitFor();
  await books.locator(".twisty").dispatchEvent("mousedown");
  await expect(page.locator(".tree-row", { hasText: "[0]" })).toBeVisible();
  await shot(page, "light-json-grid");
  await page.getByRole("tab", { name: "Flat" }).click();
  await page.locator(".flat-row").first().waitFor();
  await shot(page, "light-json-flat");
});
