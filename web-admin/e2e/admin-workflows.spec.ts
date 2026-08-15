import { readFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'

import { expect, test } from '@playwright/test'

import { loginChangedAccount, navigateWithinAdmin, observeConsole, runId, runQaCommand } from './helpers'

function wavFixture() {
  const frameCount = 8_000
  const buffer = Buffer.alloc(44 + frameCount, 128)
  buffer.write('RIFF', 0)
  buffer.writeUInt32LE(36 + frameCount, 4)
  buffer.write('WAVE', 8)
  buffer.write('fmt ', 12)
  buffer.writeUInt32LE(16, 16)
  buffer.writeUInt16LE(1, 20)
  buffer.writeUInt16LE(1, 22)
  buffer.writeUInt32LE(8_000, 24)
  buffer.writeUInt32LE(8_000, 28)
  buffer.writeUInt16LE(1, 32)
  buffer.writeUInt16LE(8, 34)
  buffer.write('data', 36)
  buffer.writeUInt32LE(frameCount, 40)
  for (let index = 44; index < buffer.length; index += 1) buffer[index] = 112 + (index % 32)
  return buffer
}

test('真实管理、歌曲上传分析及同步/异步导出均可闭环', async ({ page }) => {
  test.setTimeout(180_000)
  const assertConsoleClean = observeConsole(page, { allowAnonymousRefresh403: true })
  const suffix = runId.slice(-8)
  const phoneSuffix = String(Number.parseInt(suffix, 16) % 100_000_000).padStart(8, '0')
  const doctorName = `QA医生${suffix}`
  const updatedDoctorName = `${doctorName}改`
  const patientName = `QA患者${suffix}`
  const updatedPatientName = `${patientName}改`
  const songTitle = `QA13-${runId}-歌曲`

  await loginChangedAccount(page)
  await expect(page.getByRole('heading', { name: '医生管理' })).toBeVisible()
  await expect(page.getByText('演示医生')).toBeVisible()

  await page.getByRole('button', { name: '新增医生' }).click()
  const doctorDialog = page.getByRole('dialog', { name: '新增医生' })
  await doctorDialog.getByLabel('姓名').fill(doctorName)
  await doctorDialog.getByLabel('性别').selectOption('female')
  await doctorDialog.getByLabel('手机号').fill(`138${phoneSuffix}`)
  await doctorDialog.getByLabel('科室').fill('QA康复科')
  await doctorDialog.getByLabel('职称').fill('QA医师')
  await doctorDialog.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('医生创建成功')).toBeVisible()
  await expect(page.getByText(doctorName)).toBeVisible()

  await page.getByRole('button', { name: `编辑${doctorName}` }).click()
  const editDialog = page.getByRole('dialog', { name: '编辑医生' })
  await editDialog.getByLabel('姓名').fill(updatedDoctorName)
  await editDialog.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('医生信息已更新')).toBeVisible()
  await expect(page.getByText(updatedDoctorName)).toBeVisible()

  await page.getByRole('button', { name: `停用${updatedDoctorName}` }).click()
  await page.getByRole('button', { name: '确认停用' }).click()
  await expect(page.getByText('医生已停用')).toBeVisible()
  await page.getByRole('button', { name: `启用${updatedDoctorName}` }).click()
  await page.getByRole('button', { name: '确认启用' }).click()
  await expect(page.getByText('医生已启用')).toBeVisible()
  await page.getByRole('button', { name: `更多${updatedDoctorName}操作` }).click()
  await page.getByRole('menuitem', { name: '删除' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page.getByText('医生已停用并隐藏')).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: updatedDoctorName })).toHaveCount(0)

  await navigateWithinAdmin(page, '/patients')
  await expect(page.getByRole('heading', { name: '病人管理' })).toBeVisible()
  await expect(page.getByText('演示患者甲')).toBeVisible()
  await page.getByRole('button', { name: '新增患者' }).click()
  const patientDialog = page.getByRole('dialog', { name: '新增患者' })
  await patientDialog.getByLabel('姓名').fill(patientName)
  await patientDialog.getByLabel('性别').selectOption('female')
  await patientDialog.getByLabel('入组年龄').fill('36')
  await patientDialog.getByLabel('手机号').fill(`137${phoneSuffix}`)
  await patientDialog.getByLabel('主治医生', { exact: true }).selectOption({ label: '演示医生 · DDEMO001' })
  await patientDialog.getByLabel('治疗开始日期').fill('2026-08-15')
  await patientDialog.getByLabel('治疗周期（周）').fill('8')
  await patientDialog.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('患者创建成功，初始密码为 888888，首次登录需修改')).toBeVisible()
  await expect(page.getByText(patientName)).toBeVisible()

  await page.getByRole('button', { name: `编辑${patientName}` }).click()
  const editPatientDialog = page.getByRole('dialog', { name: '编辑患者' })
  await editPatientDialog.getByLabel('姓名').fill(updatedPatientName)
  await editPatientDialog.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('患者信息已更新')).toBeVisible()
  await expect(page.getByText(updatedPatientName)).toBeVisible()

  const deletePatientResponse = page.waitForResponse(
    (response) => response.url().includes('/api/v1/admin/patients/') && response.request().method() === 'DELETE',
  )
  await page.getByRole('button', { name: `删除${updatedPatientName}` }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  expect((await deletePatientResponse).status()).toBe(204)
  await expect(page.getByText('患者已停用并隐藏，历史数据已保留')).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: updatedPatientName })).toHaveCount(0)

  await navigateWithinAdmin(page, '/songs')
  await expect(page.getByRole('heading', { name: '曲库管理' })).toBeVisible()
  await page.getByRole('button', { name: '上传歌曲' }).click()
  const songDialog = page.getByRole('dialog', { name: '上传歌曲' })
  await songDialog.getByLabel('上传歌曲').setInputFiles({
    name: `${runId}.wav`,
    mimeType: 'audio/wav',
    buffer: wavFixture(),
  })
  await songDialog.getByLabel('歌曲名称').fill(songTitle)
  await songDialog.getByLabel('歌手').fill('QA歌手')
  await songDialog.getByRole('button', { name: '开始上传' }).click()
  await expect(page.getByText('歌曲上传成功')).toBeVisible({ timeout: 30_000 })
  const songRow = page.getByRole('row').filter({ hasText: songTitle })
  await expect(songRow).toBeVisible()
  await expect(songRow.getByText('分析完成')).toBeVisible({ timeout: 45_000 })
  await songRow.getByRole('button', { name: `试听${songTitle}` }).click()
  const previewDialog = page.getByRole('dialog', { name: `试听：${songTitle}` })
  await previewDialog.getByRole('button', { name: '原唱试听' }).click()
  await expect(previewDialog.getByLabel('正在试听原唱')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(previewDialog).toBeHidden()

  await navigateWithinAdmin(page, '/analytics')
  await expect(page.getByRole('heading', { name: '数据管理' })).toBeVisible()
  const syncDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: /导出报告/ }).click()
  await page.getByRole('menuitem', { name: 'CSV' }).click()
  const csvFile = await syncDownload
  expect(await csvFile.suggestedFilename()).toMatch(/\.csv$/)
  const csvPath = await csvFile.path()
  expect(csvPath).not.toBeNull()
  expect(await readFile(csvPath as string, 'utf8')).toContain('病历号')

  expect(runQaCommand(['--prepare-analytics-load'])).toBe('1001')
  const metricsResponsePromise = page.waitForResponse(
    (response) => response.url().includes('/api/v1/admin/analytics/patients/') && response.status() === 200,
  )
  await page.reload()
  const metricsEnvelope = await (await metricsResponsePromise).json()
  expect(metricsEnvelope.data.count).toBeGreaterThan(1000)

  const createExportResponse = page.waitForResponse(
    (response) => response.url().endsWith('/api/v1/admin/analytics/exports/') && response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: /导出报告/ }).click()
  await page.getByRole('menuitem', { name: 'Excel' }).click()
  const exportResponse = await createExportResponse
  expect(exportResponse.status()).toBe(202)
  expect((await exportResponse.json()).data.count).toBeGreaterThan(1000)
  await expect(page.getByText('导出任务处理中')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByText('导出文件已准备好')).toBeVisible({ timeout: 90_000 })
  const asyncDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载文件' }).click()
  const xlsxFile = await asyncDownload
  expect(xlsxFile.suggestedFilename()).toMatch(/\.xlsx$/)
  const xlsxPath = await xlsxFile.path()
  expect(xlsxPath).not.toBeNull()
  const workbookCheck = spawnSync(
    '../server/.venv/bin/python',
    [
      '-c',
      `import json, sys
from openpyxl import load_workbook
with open(sys.argv[1], 'rb') as source:
    sheet = load_workbook(source, read_only=True, data_only=True).active
    rows = sheet.iter_rows(values_only=True)
    headers = list(next(rows))
    prefix = sys.argv[2]
    count = 1
    found = False
    for row in rows:
        count += 1
        found = found or any(str(value).startswith(prefix) for value in row if value is not None)
print(json.dumps({'headers': headers, 'found': found, 'rows': count}, ensure_ascii=False))`,
      xlsxPath as string,
      `Q${suffix.toUpperCase()}`,
    ],
    { encoding: 'utf8' },
  )
  expect(workbookCheck.status, workbookCheck.stderr).toBe(0)
  const workbook = JSON.parse(workbookCheck.stdout) as { headers: string[]; found: boolean; rows: number }
  expect(workbook.headers).toContain('病历号')
  expect(workbook.rows).toBeGreaterThan(1001)
  expect(workbook.found).toBe(true)

  expect(runQaCommand(['--cleanup-artifacts'])).toBe(runId.replace('-', '').toUpperCase())
  assertConsoleClean()
})
