import { mkdirSync } from 'node:fs'
import { expect, test } from '@playwright/test'

import { expectNoPageOverflow, loginChangedAccount, navigateWithinAdmin, observeConsole, resetAllScroll, screenshotDir } from './helpers'

test('七页 1440 截图与 390 可操作性验收', async ({ browser, page }) => {
  mkdirSync(screenshotDir, { recursive: true })
  const assertConsoleClean = observeConsole(page, { allowAnonymousRefresh403: true })
  const anonymous = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const login = await anonymous.newPage()
  const assertLoginConsoleClean = observeConsole(login, { allowAnonymousRefresh403: true })
  await login.goto(`${process.env.VOCAEASE_E2E_BASE_URL ?? 'http://localhost:3000'}/login`)
  await expect(login.getByRole('heading', { name: '登录 VocaEase' })).toBeVisible()
  await login.screenshot({ path: `${screenshotDir}/01-login-1440.png` })
  assertLoginConsoleClean()
  await anonymous.close()

  await loginChangedAccount(page)
  await expect(page.getByRole('heading', { name: '医生管理' })).toBeVisible()
  await resetAllScroll(page)
  await page.screenshot({ path: `${screenshotDir}/02-doctors-1440.png` })

  await navigateWithinAdmin(page, '/patients')
  await expect(page.getByText('演示患者甲')).toBeVisible()
  await resetAllScroll(page)
  await page.screenshot({ path: `${screenshotDir}/03-patients-1440.png` })
  await page.getByRole('button', { name: '查看演示患者甲数据' }).click()
  await expect(page.getByRole('heading', { name: '患者数据' })).toBeVisible()
  await expect(page.getByText('演示患者甲').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '查看明细' }).first()).toBeVisible()
  const patientDataUrl = page.url()
  await resetAllScroll(page)
  await page.screenshot({ path: `${screenshotDir}/05-patient-data-1440.png` })
  await page.getByRole('button', { name: '查看明细' }).first().click()
  await expect(page.getByRole('heading', { name: '演唱明细' })).toBeVisible()
  const singingDetailUrl = page.url()
  await resetAllScroll(page)
  await page.screenshot({ path: `${screenshotDir}/06-singing-detail-1440.png` })

  await navigateWithinAdmin(page, '/songs')
  await expect(page.getByRole('heading', { name: '曲库管理' })).toBeVisible()
  await expect(page.getByText('VocaEase 演示歌曲一')).toBeVisible()
  const fixedActionCell = await page.getByRole('button', { name: '删除' }).first().locator('xpath=ancestor::td').evaluate((cell) => ({
    clientWidth: cell.clientWidth,
    scrollWidth: cell.scrollWidth,
  }))
  expect(fixedActionCell.scrollWidth, `曲库操作列内容宽度 ${fixedActionCell.scrollWidth}px 不应超过可见宽度 ${fixedActionCell.clientWidth}px`).toBeLessThanOrEqual(fixedActionCell.clientWidth)
  await resetAllScroll(page)
  await page.screenshot({ path: `${screenshotDir}/04-songs-1440.png` })
  await navigateWithinAdmin(page, '/analytics')
  await expect(page.getByRole('heading', { name: '数据管理' })).toBeVisible()
  await expect(page.getByText('在治患者')).toBeVisible()
  await expect(page.getByText('演示患者甲')).toBeVisible()
  await resetAllScroll(page)
  await page.screenshot({ path: `${screenshotDir}/07-analytics-1440.png` })

  await page.setViewportSize({ width: 390, height: 844 })
  for (const url of ['/doctors', '/patients', '/songs', patientDataUrl, singingDetailUrl, '/analytics']) {
    await navigateWithinAdmin(page, url)
    await expectNoPageOverflow(page)
    await expect(page.getByRole('button', { name: '打开导航菜单' }), `移动路由 ${url} 应保持登录并显示导航`).toBeVisible()
  }
  await page.getByRole('button', { name: '打开导航菜单' }).click()
  const mobileNavigation = page.getByRole('navigation', { name: '移动端后台主导航' })
  await expect(mobileNavigation).toBeVisible()
  await expect.poll(async () => (await mobileNavigation.boundingBox())?.x ?? -1).toBeGreaterThanOrEqual(0)
  await page.screenshot({ path: `${screenshotDir}/08-mobile-390.png` })
  assertConsoleClean()
})
