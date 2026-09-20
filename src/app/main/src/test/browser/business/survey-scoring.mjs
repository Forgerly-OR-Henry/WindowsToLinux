import fs from "node:fs/promises";
export async function verify(page, expect) {
  await page.locator("#survey-name").fill("浏览器版本化问卷");
  await page.getByRole("button", { name: "创建草稿", exact: true }).click();
  await expect(page.locator("#edit-title")).toContainText("版本 1");
  const card = page.locator(".question-editor").nth(1);
  await expect(card.locator('[data-field="id"]')).toHaveValue("quality");
  await card.locator('[data-field="weight"]').fill("2");
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.locator("#edit-version")).toContainText("已保存");
  await page.locator("#publish").click();
  await expect(page.locator("#revisions")).toContainText("published");
  await page.getByRole("button", { name: "填写问卷", exact: true }).click();
  await page.locator('[name="participated"][value="no"]').check();
  await expect(page.locator('[data-question="quality"]')).toBeHidden();
  await page.locator('[name="support"][value="docs"]').check();
  await page.locator('[name="friction"]').fill("2");
  await page.locator("#submit").click();
  await expect(page.locator("#score")).toContainText("45.45");
  await expect(page.locator("#hidden-questions")).toContainText("quality");
  await page.locator("#back-survey").click();
  await page.getByRole("button", { name: "填写问卷", exact: true }).click();
  await page.locator('[name="participated"][value="yes"]').check();
  await expect(page.locator('[data-question="quality"]')).toBeVisible();
  await page.locator('[name="quality"]').fill("4");
  await page.locator('[name="support"][value="docs"]').check();
  await page.locator('[name="support"][value="peer"]').check();
  await page.locator('[name="friction"]').fill("2");
  await page.locator("#submit").click();
  await expect(page.locator("#score")).toContainText("84.21");
  await expect(page.locator("#parts")).toContainText("反向计分");
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.locator("#export").click(),
  ]);
  const exported = JSON.parse(await fs.readFile(await download.path(), "utf8"));
  expect(exported.result.score).toBe(84.21);
  expect(exported.revision.number).toBe(1);
  await page.locator("#back-survey").click();
  await page.locator("#clone").click();
  await expect(page.locator("#edit-title")).toContainText("版本 2");
  await page
    .locator(".question-editor")
    .nth(1)
    .locator('[data-field="weight"]')
    .fill("3");
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.locator("#edit-version")).toContainText("已保存");
  await page.locator("#publish").click();
  await expect(page.locator("#revisions tr")).toHaveCount(2);
  const latest = page.locator("#revisions tr").first();
  await latest.getByRole("button", { name: "填写问卷", exact: true }).click();
  await expect(page.locator("#fill-version")).toContainText("发布版本 2");
  await page.locator('[name="participated"][value="yes"]').check();
  await expect(page.locator('[data-question="quality"]')).toBeVisible();
  await page.locator('[name="quality"]').fill("4");
  await page.locator('[name="support"][value="docs"]').check();
  await page.locator('[name="support"][value="peer"]').check();
  await page.locator('[name="friction"]').fill("2");
  await page.locator("#submit").click();
  await expect(page.locator("#score")).toContainText("82.61");
  await expect(page.locator("#result-meta")).toContainText("版本 2");
  await page.locator("#back-survey").click();
  const old = page.locator("#revisions tr").nth(1);
  await old.getByRole("button", { name: "关闭版本", exact: true }).click();
  await expect(page.locator("#revisions tr").nth(1)).toContainText("closed");
  await page
    .locator("#revisions tr")
    .nth(1)
    .getByRole("button", { name: "历史答卷", exact: true })
    .click();
  await expect(page.locator("#history-total")).toContainText("共 2 份");
  await page
    .locator("#history tr")
    .first()
    .getByRole("button", { name: "答案与评分详情" })
    .click();
  await expect(page.locator("#score")).toContainText("84.21");
  await expect(page.locator("#result-meta")).toContainText("版本 1");
  await expect(page.locator("#original-answers")).toContainText("协作质量");
  await page.locator("#back-survey").click();
  await expect(page.locator("#stats")).toContainText("3 份答卷");
  await expect(page.locator("#events")).toContainText("close");
  await page.getByRole("link", { name: "问卷列表", exact: true }).click();
  await page.locator("#survey-name").fill("浏览器版本化问卷");
  await page.getByRole("button", { name: "创建草稿", exact: true }).click();
  await expect(page.locator("#message")).toContainText("已存在");
}
