import fs from "node:fs/promises";
export async function verify(page, expect) {
  await page.locator("#folder-name").fill("浏览器文件夹");
  await page.getByRole("button", { name: "创建文件夹", exact: true }).click();
  await expect(page.locator("#folder option:checked")).toHaveText(
    "浏览器文件夹",
  );
  await page.getByRole("link", { name: "上传 / 续传" }).click();
  const file = {
    name: "浏览器 中文.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("版本一：真实浏览器内容"),
  };
  await page
    .locator("#files")
    .setInputFiles([
      file,
      {
        name: "批量.txt",
        mimeType: "text/plain",
        buffer: Buffer.from("批量上传"),
      },
    ]);
  await page.locator("#upload-button").click();
  await expect(page.locator("#progress-text")).toHaveText(
    "批量.txt 已校验并发布",
  );
  await page
    .locator("#files")
    .setInputFiles({ ...file, buffer: Buffer.from("版本二") });
  await page.locator("#upload-button").click();
  await expect(page.locator("#progress-text")).toHaveText(
    "浏览器 中文.txt 已校验并发布",
  );
  await page.getByRole("link", { name: "文件列表", exact: true }).click();
  await page.locator("#query").fill("浏览器 中文");
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect(page.locator("#rows tr")).toHaveCount(1);
  await expect(page.locator("#rows tr td").nth(1)).toHaveText("2");
  await page.getByRole("button", { name: "版本详情", exact: true }).click();
  await expect(page.locator("#versions tr")).toHaveCount(2);
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page
      .locator("#versions tr")
      .nth(1)
      .getByRole("link", { name: "下载" })
      .click(),
  ]);
  if (!file.buffer.equals(await fs.readFile(await download.path())))
    throw new Error("Historical bytes changed");
  await page.getByRole("link", { name: "上传 / 续传" }).click();
  await page.route("**/api/uploads/*/chunks/*", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 200));
    await route.continue();
  });
  await page
    .locator("#files")
    .setInputFiles({
      name: "暂停恢复.bin",
      mimeType: "application/octet-stream",
      buffer: Buffer.alloc(3 * 1048576, 37),
    });
  await page.locator("#upload-button").click();
  await expect(page.locator("#progress-text")).toContainText("1/3 分块已确认");
  await page.locator("#pause").click();
  await expect(page.locator("#upload-button")).toBeEnabled();
  const id = await page.locator("#session-id").inputValue();
  if (!id) throw new Error("Session identity was not retained");
  await page.reload();
  await page.locator("#folder").selectOption({ label: "浏览器文件夹" });
  await expect(page.locator("#session-id")).toHaveValue(id);
  await page
    .locator("#files")
    .setInputFiles({
      name: "错误.bin",
      mimeType: "application/octet-stream",
      buffer: Buffer.from("wrong"),
    });
  await page.locator("#upload-button").click();
  await expect(page.locator("#message")).toContainText("与上传会话不一致");
  await page
    .locator("#files")
    .setInputFiles({
      name: "暂停恢复.bin",
      mimeType: "application/octet-stream",
      buffer: Buffer.alloc(3 * 1048576, 37),
    });
  await page.locator("#upload-button").click();
  await expect(page.locator("#progress-text")).toHaveText(
    "暂停恢复.bin 已校验并发布",
  );
  await page.getByRole("link", { name: "上传历史", exact: true }).click();
  await expect(page.locator("#history tr")).toHaveCount(4);
  await expect(page.locator("#history")).toContainText("completed");
  await page
    .locator("#history tr")
    .first()
    .getByRole("button", { name: "查看 / 恢复" })
    .click();
  await expect(page.locator("#session-detail")).toContainText("published");
}
