export async function verify(page, expect) {
  await page.locator("#file").setInputFiles({
    name: "浏览器 人员.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(
      "id,name,age,date,team\n1,林同学,21,2026-09-19,red\n2,,bad,2026-02-30,green\n1,重复,999,2026-09-19,red\n",
    ),
  });
  await page.getByRole("button", { name: "上传数据集" }).click();
  await expect(page.locator("table")).toContainText("浏览器 人员.csv");
  await page.getByRole("link", { name: "规则模板", exact: true }).click();
  await page.locator("#source").selectOption("required");
  await page.locator("#template-name").fill("浏览器必填规则");
  await page.getByRole("button", { name: "另存规则模板" }).click();
  await expect(page.locator("table")).toContainText("浏览器必填规则");
  await page.getByRole("link", { name: "检查任务", exact: true }).click();
  await page.locator("#dataset").selectOption({ label: "浏览器 人员.csv" });
  await page.locator("#template-id").selectOption("basic");
  await page.getByRole("button", { name: "开始后台检查" }).click();
  await expect(page.locator("#job-status")).toContainText("已完成", {
    timeout: 20000,
  });
  await expect(page.locator("#summary")).toHaveText("已检查 3 行，6 个问题，1 行有效");
  await expect(page.locator("table").first().locator("tbody tr")).toHaveCount(6);
  await expect(page.locator("table").last()).toContainText("completed");
  const first = (await page.locator("#job-id").textContent()).trim();
  const downloaded = page.waitForEvent("download");
  await page.locator("#export").click();
  await downloaded;
  await page.getByRole("link", { name: "检查任务", exact: true }).click();
  await page.locator("#dataset").selectOption({ label: "浏览器 人员.csv" });
  await page.locator("#template-id").selectOption({ label: "浏览器必填规则" });
  await page.getByRole("button", { name: "开始后台检查" }).click();
  await expect(page.locator("#job-status")).toContainText("已完成", {
    timeout: 20000,
  });
  const second = (await page.locator("#job-id").textContent()).trim();
  await expect(page.locator("#summary")).toHaveText("已检查 3 行，1 个问题，2 行有效");
  await page.getByRole("link", { name: "报告比较", exact: true }).click();
  await page.locator("#left").selectOption(first);
  await page.locator("#right").selectOption(second);
  await page.getByRole("button", { name: "比较报告", exact: true }).click();
  await expect(page.locator("#delta")).toHaveText("新增 0 个问题，消除 5 个问题");
  await page.route("**/api/jobs?*", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 400));
    await route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ error: "检查任务暂时不可用" }),
    });
  });
  await page.getByRole("link", { name: "检查任务", exact: true }).click();
  await expect(page.locator("#loading")).toBeVisible();
  await expect(page.locator("#message")).toContainText("检查任务暂时不可用");
  await page.unroute("**/api/jobs?*");
  await page.getByRole("link", { name: "数据集", exact: true }).click();
  await expect(page.locator("table")).toContainText("浏览器 人员.csv");
  const large =
    "id,name,age,date,team\n" +
    Array.from({ length: 20000 }, (_, i) => `${i},人员 ${i},20,2026-09-19,red`).join("\n") +
    "\n";
  await page.locator("#file").setInputFiles({
    name: "浏览器取消重试.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(large),
  });
  await page.getByRole("button", { name: "上传数据集" }).click();
  await expect(page.locator("table")).toContainText("浏览器取消重试.csv");
  await page.getByRole("link", { name: "检查任务", exact: true }).click();
  await page.locator("#dataset").selectOption({ label: "浏览器取消重试.csv" });
  await page.locator("#template-id").selectOption("basic");
  await page.getByRole("button", { name: "开始后台检查" }).click();
  await page.locator("#cancel").click();
  await expect(page.locator("#job-status")).toContainText("已取消", {
    timeout: 20000,
  });
  await expect(page.locator("#export")).toHaveCount(0);
  await page.locator("#retry").click();
  await expect(page.locator("#job-status")).toHaveText("已完成 · 第 2 次尝试", {
    timeout: 20000,
  });
  await expect(page.locator("#summary")).toHaveText("已检查 20000 行，0 个问题，20000 行有效");
}
