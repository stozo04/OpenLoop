import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const generation = process.env.GENERATION || "g1";
const browser = await chromium.launch({
  headless: true,
  executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
});
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 1 });

async function capture(url, output, selector, width, height) {
  await page.setViewportSize({ width, height });
  await page.goto(url);
  await page.evaluate(() => document.fonts.ready);
  await page.locator(selector).screenshot({ path: output, type: "png" });
}

await fs.mkdir(path.join(root, "concepts"), { recursive: true });
await fs.mkdir(path.join(root, "references"), { recursive: true });

const designsUrl = pathToFileURL(path.join(here, "designs.html")).href;
for (const concept of ["a", "b", "c"]) {
  await capture(
    `${designsUrl}?concept=${concept}`,
    path.join(root, "concepts", `concept-${concept}-${generation}.png`),
    `[data-concept="${concept}"]`,
    1024,
    500
  );
}

await capture(
  pathToFileURL(path.join(here, "reference-board.html")).href,
  path.join(root, "references", "marketplace-reference-board.png"),
  "body",
  1400,
  900
);

await browser.close();
