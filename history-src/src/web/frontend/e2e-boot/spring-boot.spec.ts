import { expect, test } from "@playwright/test";

test("built Vue uses real Spring Boot CRUD, upload, tasks and persistent preferences", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const origin = process.env.W2L_BOOT_TEST_ORIGIN;
  if (!origin) throw new Error("Spring Boot test server was not started");
  await page.goto(origin);
  await expect(page.getByRole("heading", { name: "把项目部署到 Linux" })).toBeVisible();
  await page.getByRole("button", { name: "服务器", exact: true }).click();
  await page.getByRole("button", { name: "添加服务器", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("名称", { exact: true }).fill("Spring Boot browser test");
  await dialog.getByLabel("主机地址", { exact: true }).fill("test.invalid");
  await dialog.getByLabel("密码", { exact: true }).fill("synthetic-browser-password");
  await dialog.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Spring Boot browser test" })).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "服务器", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Spring Boot browser test" })).toBeVisible();
  const servers = await page.request.get(`${origin}/api/v1/servers`);
  expect(servers.status()).toBe(200);
  expect(await servers.text()).not.toContain("synthetic-browser-password");
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await page.getByRole("button", { name: "深色", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect
    .poll(async () => (await (await page.request.get(`${origin}/api/v1/preferences`)).json()).theme)
    .toBe("dark");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page
    .locator("input[type=file][multiple]:not([webkitdirectory])")
    .setInputFiles({ name: "index.html", mimeType: "text/html", buffer: Buffer.from("<h1>Local Spring Boot</h1>") });
  await expect
    .poll(async () => (await (await page.request.get(`${origin}/api/v1/sources`)).json())[0]?.state)
    .toBe("READY");
  const sources = await (await page.request.get(`${origin}/api/v1/sources`)).json();
  const task = await page.request.post(`${origin}/api/v1/tasks`, {
    headers: { Origin: origin, "X-W2L-Client": "web" },
    data: { kind: "ANALYZE", input: { sourceId: sources[0].id } },
  });
  expect(task.status()).toBe(202);
  const id = (await task.json()).id;
  await expect
    .poll(async () => (await (await page.request.get(`${origin}/api/v1/tasks/${id}`)).json()).state)
    .toBe("SUCCEEDED");
  const events = await page.request.get(`${origin}/api/v1/tasks/${id}/events?after=1`);
  expect(await events.text()).toContain("event: task");
  expect(errors).toEqual([]);
});
