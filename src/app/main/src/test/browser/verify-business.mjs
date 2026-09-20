import { chromium, expect } from "@playwright/test";
import fs from "node:fs/promises";
import path from "node:path";
const [project, url, artifacts] = process.argv.slice(2);
await fs.mkdir(artifacts, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({
  acceptDownloads: true,
  viewport: { width: 1440, height: 1000 },
});
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
try {
  await page.goto(url);
  await expect(page.locator("h1")).toBeVisible();
  const { verify } = await import(`./business/${project}.mjs`);
  await verify(page, expect, artifacts);
  if (errors.length) throw new Error(errors.join("\n"));
  await page.screenshot({
    path: path.join(artifacts, "completed.png"),
    fullPage: true,
  });
  await fs.writeFile(
    path.join(artifacts, "result.json"),
    JSON.stringify({ project, status: "passed", errors }, null, 2),
  );
} catch (e) {
  await page.screenshot({
    path: path.join(artifacts, "failed.png"),
    fullPage: true,
  });
  await fs.writeFile(path.join(artifacts, "failed.html"), await page.content());
  throw e;
} finally {
  await browser.close();
}
