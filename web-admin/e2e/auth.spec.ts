import { expect, test } from '@playwright/test'

import { authFile, changedPassword, loginId, observeConsole } from './helpers'

test('固定初始密码首次登录后强制改密并重新登录', async ({ page }) => {
  const assertConsoleClean = observeConsole(page, { allowAnonymousRefresh403: true })
  const anonymousRefresh = page.waitForResponse(
    (response) => response.url().includes('/api/v1/auth/refresh/') && response.status() === 403,
  )
  await page.goto('/login')
  await anonymousRefresh
  await expect(page).toHaveTitle(/VocaEase/)
  await expect(page.getByRole('heading', { name: '登录 VocaEase' })).toBeVisible()
  await page.getByLabel('账号').fill(loginId)
  await page.getByLabel('密码').fill('888888')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/change-password$/)

  await page.getByLabel('当前密码').fill('888888')
  await page.getByLabel('新密码', { exact: true }).fill(changedPassword)
  await page.getByLabel('确认新密码').fill(changedPassword)
  await page.getByRole('button', { name: '确认修改' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByText('密码修改成功，请重新登录')).toBeVisible()

  await page.getByLabel('账号').fill(loginId)
  await page.getByLabel('密码').fill(changedPassword)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/doctors/)
  await expect(page.getByRole('heading', { name: '医生管理' })).toBeVisible()
  await page.context().storageState({ path: authFile })
  assertConsoleClean()
})
