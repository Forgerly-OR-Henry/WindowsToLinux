import { expect, test } from '@playwright/test'

test('shows the initialized frontend skeleton', async ({ page }) => {
  await page.goto('/')

  await expect(
    page.getByRole('heading', { name: 'Web 前端工程骨架' }),
  ).toBeVisible()
  await expect(page.getByText('WindowsToLinux 工程骨架已初始化')).toBeVisible()
})
