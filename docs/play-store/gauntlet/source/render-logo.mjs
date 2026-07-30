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
const page = await browser.newPage({ viewport: { width: 1024, height: 500 }, deviceScaleFactor: 1 });
const source = pathToFileURL(path.join(here, "logo-concepts.html")).href;

for (const concept of ["a", "b", "c"]) {
  await page.goto(`${source}?concept=${concept}`);
  await page.evaluate(() => document.fonts.ready);
  await page.locator(`[data-concept="${concept}"]`).screenshot({
    path: path.join(root, "concepts", `logo-${concept}-${generation}.png`),
    type: "png"
  });
}

await browser.close();
