import { expect, test } from '@playwright/test'

import { loginChangedAccount, observeConsole } from './helpers'

test('从病人列表进入真实患者数据和模拟演唱明细', async ({ page }) => {
  const assertConsoleClean = observeConsole(page, { allowAnonymousRefresh403: true })
  await loginChangedAccount(page)
  await page.goto('/patient-data')
  await expect(page.getByText('演示患者甲')).toBeVisible()
  await page.getByRole('button', { name: '查看数据' }).first().click()
  await expect(page.getByRole('heading', { name: '患者数据' })).toBeVisible()
  await expect(page.getByText('已完成演唱：4 次')).toBeVisible()
  await page.getByRole('button', { name: '查看明细' }).first().click()
  await expect(page.getByRole('heading', { name: '演唱明细' })).toBeVisible()
  await expect(page.getByText('模拟分析结果，不用于临床诊断或现场监测')).toBeVisible()
  await expect(page.getByRole('button', { name: '准备回放' })).toBeVisible()
  await expect(page.getByRole('heading',{name:'演唱回放',exact:true})).toHaveCount(0)
  await page.getByRole('button',{name:'返回',exact:true}).click()
  await expect(page.getByRole('heading',{name:'患者数据'})).toBeVisible()
  await page.getByRole('button',{name:'返回患者列表'}).click()
  await expect(page.getByRole('heading',{name:'病人数据'})).toBeVisible()
  assertConsoleClean()
})
