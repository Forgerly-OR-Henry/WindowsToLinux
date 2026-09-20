export async function verify(page, expect) {
  const config = await (
    await page.request.get(new URL("/runtime-config.json", page.url()).href)
  ).json();
  await page.locator("#project-name").fill("浏览器验收项目");
  await page.getByRole("button", { name: "创建项目", exact: true }).click();
  await expect(page.locator("#project option:checked")).toHaveText(
    "浏览器验收项目",
  );
  async function create(title, dependencies = "") {
    await page.getByRole("link", { name: "新建任务", exact: true }).click();
    await page.locator("#title").fill(title);
    await page.locator("#description").fill("浏览器录入的任务说明");
    await page.locator("#priority").selectOption("3");
    await page.locator("#labels").fill("浏览器,重点");
    await page.locator("#due-date").fill("2030-10-01");
    await page.locator("#dependencies").fill(dependencies);
    const pending = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === "/api/tasks" &&
        r.request().method() === "POST",
    );
    await page.getByRole("button", { name: "保存任务", exact: true }).click();
    const response = await pending;
    expect(response.status()).toBe(200);
    const task = await response.json();
    await expect(page.locator("#detail-title")).toHaveText(title);
    return task;
  }
  const first = await create("浏览器前置任务");
  const second = await create("浏览器后续任务", String(first.id));
  await page.getByRole("button", { name: "开始任务", exact: true }).click();
  await page.getByRole("button", { name: "提交审核", exact: true }).click();
  await page.getByRole("button", { name: "审核完成", exact: true }).click();
  await expect(page.locator("#message")).toContainText("未完成的依赖");
  await page.locator("#dependency-list").getByRole("button").click();
  await expect(page.locator("#detail-title")).toHaveText(first.title);
  for (const name of ["开始任务", "提交审核", "审核完成"])
    await page.getByRole("button", { name, exact: true }).click();
  await expect(page.locator("#detail-meta")).toContainText("完成");
  async function find(title) {
    await page.getByRole("link", { name: "任务列表", exact: true }).click();
    await page.locator("#query").fill(title);
    await page.getByRole("button", { name: "筛选", exact: true }).click();
    const row = page.locator("#rows tr").filter({ hasText: title });
    await expect(row).toHaveCount(1);
    return row;
  }
  await (await find(second.title))
    .getByRole("button", { name: "详情", exact: true })
    .click();
  await page.getByRole("button", { name: "审核完成", exact: true }).click();
  await page.locator("#comment").fill("审核通过，已核对依赖");
  await page.getByRole("button", { name: "发表评论", exact: true }).click();
  await expect(page.locator("#comments")).toContainText("审核通过");
  await page.getByRole("button", { name: "查看操作历史", exact: true }).click();
  await expect(page.locator("#history")).toContainText("transition");
  await expect(page.locator("#history")).toContainText("comment");
  const conflict = await create("浏览器冲突任务");
  await (await find(conflict.title))
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  await page.locator("#title").fill("尚未提交的浏览器修改");
  const members = await (
    await page.request.get(
      `${config.apiBase}/api/members?projectId=${conflict.projectId}`,
    )
  ).json();
  const response = await page.request.patch(
    `${config.apiBase}/api/tasks/${conflict.id}`,
    {
      data: {
        projectId: conflict.projectId,
        title: "其他成员的修改",
        description: conflict.description,
        ownerId: conflict.ownerId,
        priority: conflict.priority,
        labels: conflict.labels,
        dependsOn: [],
        dueDate: conflict.dueDate,
        version: conflict.version,
        actorId: members[1].id,
      },
    },
  );
  expect(response.status()).toBe(200);
  await page.getByRole("button", { name: "保存任务", exact: true }).click();
  await expect(page.locator("#message")).toContainText("其他成员修改");
  await expect(page.locator("#title")).toHaveValue("尚未提交的浏览器修改");
  await page.locator("#reload-edit").click();
  await expect(page.locator("#title")).toHaveValue("其他成员的修改");
  await page.locator("#title").fill("重新加载后提交");
  await page.getByRole("button", { name: "保存任务", exact: true }).click();
  await expect(page.locator("#detail-title")).toHaveText("重新加载后提交");
  await page.getByRole("link", { name: "项目概览", exact: true }).click();
  await expect(page.locator("#stats")).toContainText("共 3 个任务");
  await page.locator("#project").selectOption("2");
  await expect(page.locator("#project option:checked")).toHaveText(
    "实验室建设",
  );
  await page.getByRole("link", { name: "任务列表", exact: true }).click();
  await page.locator("#query").fill("浏览器");
  await page.getByRole("button", { name: "筛选", exact: true }).click();
  await expect(page.locator("#rows tr")).toHaveCount(0);
  await page.route("**/runtime-config.json", async (route) => {
    const response = await route.fetch();
    await route.fulfill({
      response,
      json: { ...(await response.json()), requestTimeoutMs: 250 },
    });
  });
  await page.reload();
  const pattern = "**/api/tasks?**";
  const headers = {
    "Content-Type": "application/json",
    "X-Sample-Protocol": "2",
    "Access-Control-Allow-Origin": new URL(page.url()).origin,
    "Access-Control-Expose-Headers": "X-Sample-Protocol",
  };
  for (const [mode, text] of [
    ["version", "协议版本"],
    ["fields", "必要字段"],
    ["timeout", "超时"],
    ["exit", "不可用"],
  ]) {
    await page.route(pattern, async (route) => {
      if (mode === "exit") {
        await route.abort("failed");
        return;
      }
      if (mode === "timeout") {
        await new Promise((resolve) => setTimeout(resolve, 700));
      }
      await route.fulfill({
        status: 200,
        headers: {
          ...headers,
          "X-Sample-Protocol": mode === "version" ? "1" : "2",
        },
        body: "{}",
      });
    });
    await page.getByRole("button", { name: "筛选", exact: true }).click();
    await expect(page.locator("#message")).toContainText(text);
    await page.unroute(pattern);
  }
  await page.locator("#query").fill("");
  await page.getByRole("button", { name: "筛选", exact: true }).click();
  await expect(page.locator("#rows tr").first()).toBeVisible();
  await expect(page.locator("#message")).toHaveText("");
}
