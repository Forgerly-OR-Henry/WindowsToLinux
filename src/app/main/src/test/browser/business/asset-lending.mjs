export async function verify(page, expect) {
  for (const n of [1, 2]) {
    await page.getByRole("link", { name: "资产登记", exact: true }).click();
    await page.locator("#name").fill("浏览器相机" + n);
    await page.locator("#serial").fill("BROWSER-" + n);
    await page.locator("#category").selectOption("2");
    await page.getByRole("button", { name: "登记资产", exact: true }).click();
    await expect(page.locator('[data-page="assets"]')).toBeVisible();
    await page.locator("#query").fill("浏览器相机");
    await page.getByRole("button", { name: "查询", exact: true }).click();
    await page
      .getByRole("checkbox", { name: "选择 浏览器相机" + n, exact: true })
      .check();
  }
  await expect(page.locator("#cart-count")).toContainText("已选 2 件");
  await page.getByRole("link", { name: "新建借用单", exact: true }).click();
  await page.locator("#purpose").fill("浏览器验收拍摄");
  await page.locator("#due-date").fill("2020-01-01");
  await page.getByRole("button", { name: "保存借用草稿", exact: true }).click();
  await expect(page.locator("#loan-title")).toContainText("草稿");
  await page.getByRole("button", { name: "提交审批", exact: true }).click();
  await expect(page.locator("#loan-title")).toContainText("待审批");
  await page.locator("#actor").selectOption("陈老师");
  await page.getByRole("button", { name: "批准整单", exact: true }).click();
  await expect(page.locator("#loan-title")).toContainText("已批准");
  await page.getByRole("button", { name: "确认领用", exact: true }).click();
  await expect(page.locator("#loan-title")).toContainText("已领用");
  await page
    .getByRole("checkbox", { name: "归还 浏览器相机1", exact: true })
    .check();
  await page.locator("#return-button").click();
  await expect(page.locator("#loan-title")).toContainText("部分归还");
  const second = page
    .locator("#loan-items tr")
    .filter({ hasText: "浏览器相机2" });
  await second.getByRole("checkbox").check();
  await second.locator("select").selectOption("damaged");
  await second.locator('input[type="text"],input:not([type])').fill("镜头损坏");
  await page.locator("#return-button").click();
  await expect(page.locator("#loan-title")).toContainText("已结清");
  await expect(page.locator("#loan-history")).toContainText("returned");
  await page.getByRole("link", { name: "维修工单", exact: true }).click();
  const repair = page.locator("#repairs tr").filter({ hasText: "浏览器相机2" });
  await repair.getByRole("textbox").fill("更换镜头并验收");
  await repair.getByRole("button", { name: "完成维修" }).click();
  await expect(repair).toContainText("completed");
  await page.getByRole("link", { name: "资产列表", exact: true }).click();
  await expect(page.locator("#assets tr")).toHaveCount(2);
  const row = page.locator("#assets tr").filter({ hasText: "浏览器相机2" });
  await expect(row).toContainText("可借");
  await row.getByRole("button", { name: "资产履历" }).click();
  await expect(page.locator("#asset-history")).toContainText(
    "repair_completed",
  );
  await page.getByRole("link", { name: "资产登记", exact: true }).click();
  await page.locator("#name").fill("重复编号");
  await page.locator("#serial").fill("BROWSER-1");
  await page.getByRole("button", { name: "登记资产", exact: true }).click();
  await expect(page.locator("#message")).toContainText("唯一性");
  await page.getByRole("link", { name: "借用单", exact: true }).click();
  await page.locator("#loan-status").selectOption("closed");
  await page.getByRole("button", { name: "查询借用单" }).click();
  await expect(page.locator("#loans")).toContainText("已结清");
  await expect(page.locator("#stats")).toContainText("资产");
}
