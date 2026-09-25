import { expect, test, type Page } from "@playwright/test";

async function fixture(page: Page, decisionKind = "HOST_KEY") {
  const mutations: { path: string; body: Record<string, unknown> }[] = [];
  let servers = [
    {
      id: "s1",
      name: "Ubuntu test",
      host: "192.0.2.10",
      port: 22,
      username: "root",
      version: 1,
      observation: {},
      credentialConfigured: true,
    },
  ];
  const task = {
    id: "t1",
    kind: "DEPLOY",
    state: "WAITING_DECISION",
    createdAt: "2026-09-17T00:00:00Z",
    updatedAt: "2026-09-17T00:00:00Z",
    errorCode: null,
    result: null,
    decision: {
      id: "d1",
      kind: decisionKind,
      expiresAt: "2099-01-01T00:00:00Z",
      prompt:
        decisionKind === "HOST_KEY"
          ? { host: "192.0.2.10", fingerprint: "SHA256:synthetic" }
          : { code: "backup.password" },
    },
  };
  let tasks: unknown[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname.slice("/api/v1/".length),
      method = req.method();
    const binary = req.headers()["content-type"] === "application/octet-stream";
    const body = method === "GET" || binary ? {} : (req.postDataJSON() as Record<string, unknown>);
    if (method !== "GET") mutations.push({ path, body });
    let result: unknown = {};
    if (path === "preferences") result = { theme: "light", language: "zh-CN", navigationCollapsed: "false" };
    else if (path === "servers") {
      if (method === "POST") servers = [...servers, { ...servers[0]!, ...body, id: "s2" }];
      result = method === "GET" ? servers : servers.at(-1);
    } else if (path === "sources")
      result =
        method === "POST"
          ? { id: "source-1", name: "demo", state: "UPLOADING" }
          : [{ id: "source-1", name: "demo", state: "READY", kind: "UPLOAD" }];
    else if (["applications", "ai/profiles", "backups"].includes(path)) result = [];
    else if (path === "tasks") {
      if (method === "POST") tasks = [task];
      result = method === "GET" ? tasks : { id: "t1" };
    } else if (path === "tasks/t1") result = task;
    else if (path === "tasks/t1/events") {
      await route.fulfill({
        contentType: "text/event-stream",
        body:
          "id: 1\nevent: task\ndata: " +
          JSON.stringify({
            sequence: 1,
            kind: "PROGRESS",
            message: "SOURCE_ANALYZING",
            details: {},
            createdAt: task.createdAt,
          }) +
          "\n\n",
      });
      return;
    } else if (path === "secrets")
      result = body.environment
        ? { identifier: "s-test.env.api-token", revision: 1 }
        : { secretId: "secret-reference" };
    else if (path === "tasks/t1/decisions/d1") {
      task.state = "SUCCEEDED";
      result = task;
    }
    await route.fulfill({ contentType: "application/json", body: JSON.stringify(result) });
  });
  await page.goto("/");
  return mutations;
}

test("navigation preserves inputs, settings and responsive layout", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await fixture(page);
  await expect(page.getByRole("heading", { name: "把项目部署到 Linux" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "服务器", exact: true })).toBeHidden();
  await page.getByLabel("项目名称（字母、数字或短横线）").fill("my-project");
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await page.getByRole("button", { name: "深色", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByLabel("语言", { exact: true }).selectOption("en");
  await page.getByRole("button", { name: "Deploy", exact: true }).click();
  await expect(page.getByLabel("Project name (letters, numbers or hyphens)")).toHaveValue("my-project");
  await page.screenshot({ path: testInfo.outputPath("desktop-dark.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("mobile.png"), fullPage: true });
  expect(errors).toEqual([]);
});
test("server credentials are cleared when the editor reopens", async ({ page }) => {
  const calls = await fixture(page);
  await page.getByRole("button", { name: "服务器", exact: true }).click();
  await page.getByRole("button", { name: "添加服务器", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("名称", { exact: true }).fill("Debian test");
  await dialog.getByLabel("主机地址", { exact: true }).fill("192.0.2.11");
  await dialog.getByLabel("密码", { exact: true }).fill("synthetic-password");
  await dialog.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Debian test" })).toBeVisible();
  expect(calls.find((call) => call.path === "servers")?.body.password).toBe("synthetic-password");
  await page.getByRole("button", { name: "添加服务器", exact: true }).click();
  await expect(dialog.getByLabel("密码", { exact: true })).toHaveValue("");
});
test("upload, deployment and fingerprint decision use the API", async ({ page }) => {
  const calls = await fixture(page);
  await page
    .locator("input[type=file][multiple]:not([webkitdirectory])")
    .setInputFiles({ name: "index.html", mimeType: "text/html", buffer: Buffer.from("<h1>test</h1>") });
  await expect(page.getByLabel("项目", { exact: true })).toHaveValue("source-1");
  await page.getByRole("combobox", { name: "目标服务器", exact: true }).selectOption("s1");
  await page.getByRole("button", { name: "开始部署", exact: true }).click();
  await expect(page.getByText("SHA256:synthetic")).toBeVisible();
  await expect(page.getByText("静态分析项目", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "确认并继续", exact: true }).click();
  await expect.poll(() => calls.find((call) => call.path.endsWith("/decisions/d1"))?.body).toEqual({ accepted: true });
  expect(calls.find((call) => call.path === "tasks")?.body).toMatchObject({
    kind: "DEPLOY",
    input: { serverId: "s1", sourceId: "source-1" },
  });
});
test("secret decision records only the encrypted reference", async ({ page }) => {
  const calls = await fixture(page, "SECRET");
  await page.getByLabel("项目", { exact: true }).selectOption("source-1");
  await page.getByRole("combobox", { name: "目标服务器", exact: true }).selectOption("s1");
  await page.getByRole("button", { name: "开始部署", exact: true }).click();
  await page.getByLabel("独立备份密码", { exact: true }).fill("synthetic-backup-password");
  await page.getByRole("button", { name: "确认并继续", exact: true }).click();
  await expect
    .poll(() => calls.find((call) => call.path.endsWith("/decisions/d1"))?.body)
    .toEqual({ secretId: "secret-reference" });
  expect(calls.find((call) => call.path === "secrets")?.body).toEqual({ value: "synthetic-backup-password" });
});
test("advanced secrets use the shared revision notation", async ({ page }) => {
  const calls = await fixture(page);
  await page.getByRole("button", { name: "高级选项", exact: true }).click();
  await page.getByLabel("环境变量名称", { exact: true }).fill("API_TOKEN");
  await page.getByLabel("秘密值", { exact: true }).fill("synthetic-token");
  await page.getByRole("button", { name: "加密保存并填入引用", exact: true }).click();
  await expect(page.getByLabel("秘密引用", { exact: true })).toHaveValue("s-test.env.api-token:1");
  await expect(page.getByLabel("秘密值", { exact: true })).toHaveValue("");
  expect(calls.find((call) => call.path === "secrets")?.body.environment).toBe("API_TOKEN");
});

test("AI verification submits the selected model and clears its key", async ({ page }) => {
  const calls = await fixture(page);
  await page.route("**/api/v1/ai/profiles", async (route) => {
    const request = route.request();
    if (request.method() === "POST") calls.push({ path: "ai/profiles", body: request.postDataJSON() });
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(request.method() === "POST" ? { id: "t1" } : []),
    });
  });
  await page.getByRole("button", { name: "AI 模型", exact: true }).click();
  await page.getByRole("button", { name: "添加模型", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("名称", { exact: true }).fill("New model");
  await dialog.getByLabel("Chat Completions 地址", { exact: true }).fill("https://new.invalid/v1/chat/completions");
  await dialog.getByLabel("模型名称", { exact: true }).fill("selected-model");
  await dialog.getByLabel("API Key", { exact: true }).fill("synthetic-key");
  await dialog.getByRole("button", { name: "测试并保存", exact: true }).click();
  await expect.poll(() => calls.find((call) => call.path === "ai/profiles")?.body.model).toBe("selected-model");
  await page.getByRole("button", { name: "AI 模型", exact: true }).click();
  await page.getByRole("button", { name: "添加模型", exact: true }).click();
  await expect(dialog.getByLabel("API Key", { exact: true })).toHaveValue("");
});

test("application refresh and backup preflight send exact resource IDs", async ({ page }) => {
  const calls = await fixture(page);
  await page.route("**/api/v1/applications", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify([
        {
          id: "app-1",
          name: "Website",
          serverId: "s1",
          kind: "MANAGED",
          version: 1,
          category: "WEBSITE",
          accessUrl: "https://site.invalid",
          deployedAt: "2026-09-17T00:00:00Z",
          types: ["STATIC_SITE"],
          observation: { state: "RUNNING", observedAt: "2026-09-17T00:00:00Z" },
        },
      ]),
    }),
  );
  await page.route("**/api/v1/backups", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify([
        {
          id: "backup-1",
          name: "Portable backup",
          createdAt: "2026-09-17T00:00:00Z",
          byteCount: 1024,
          digest: "a".repeat(64),
          inspection: { components: 1, database: "NONE", canRestore: true },
        },
      ]),
    }),
  );
  await page.getByRole("button", { name: "已部署应用", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Website", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "刷新状态", exact: true }).click();
  expect(calls.find((call) => call.path === "tasks")?.body).toEqual({
    kind: "LIFECYCLE",
    input: { applicationId: "app-1", action: "REFRESH_STATUS" },
  });
  await page.getByRole("button", { name: "备份与迁移", exact: true }).click();
  await page.getByRole("button", { name: "恢复备份", exact: true }).first().click();
  await page.getByRole("combobox", { name: "备份归档", exact: true }).selectOption("backup-1");
  await page.getByRole("combobox", { name: "目标服务器", exact: true }).selectOption("s1");
  await page.getByRole("button", { name: "只检查兼容性", exact: true }).click();
  expect(calls.filter((call) => call.path === "tasks").at(-1)?.body).toEqual({
    kind: "RESTORE_PREFLIGHT",
    input: { backupId: "backup-1", serverId: "s1" },
  });
});
