import { spawnSync } from 'node:child_process'
import { expect, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

export const runId = process.env.VOCAEASE_E2E_RUN_ID ?? ''
export const loginId = runId.replace('-', '').toUpperCase()
export const changedPassword = `Qa13-${runId.slice(-8)}-safe`
export const authFile = `/tmp/vocaease-e2e-${runId}-auth.json`
export const screenshotDir = `/tmp/vocaease-visual-${runId}`

export function e2eComposeProject() {
  const project = process.env.VOCAEASE_E2E_COMPOSE_PROJECT ?? ''
  if (!/^vocaease-(?:e2e|qa13)-[a-z0-9-]+$/.test(project)) {
    throw new Error('VOCAEASE_E2E_COMPOSE_PROJECT 必须是独立的 vocaease-e2e-* 或 vocaease-qa13-* project')
  }
  return project
}

export function runQaCommand(args: string[]) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
  const result = spawnSync(
    'docker',
    [
      'compose', '-p', e2eComposeProject(), '-f', 'deploy/compose.yaml', 'exec', '-T', 'server',
      'uv', 'run', '--no-sync', 'python', 'manage.py', 'qa_e2e',
      '--run-id', runId, ...args,
    ],
    { cwd: root, encoding: 'utf8' },
  )
  if (result.status !== 0) throw new Error(`QA 数据命令失败：${result.stderr || result.stdout}`)
  return result.stdout.trim()
}

export function observeConsole(page: Page, options: { allowAnonymousRefresh403?: boolean } = {}) {
  const problems: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') {
      if (
        options.allowAnonymousRefresh403
        && message.type() === 'error'
        && /40[13] \((?:Unauthorized|Forbidden)\)/.test(message.text())
        && message.location().url.includes('/api/v1/auth/refresh/')
      ) return
      problems.push(`${message.type()}: ${message.text()}`)
    }
  })
  page.on('pageerror', (error) => problems.push(`pageerror: ${error.message}`))
  return () => expect(problems, problems.join('\n')).toEqual([])
}

export async function loginChangedAccount(page: Page) {
  await page.goto('/login')
  await page.getByLabel('账号').fill(loginId)
  await page.getByLabel('密码').fill(changedPassword)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/doctors/)
}

export async function navigateWithinAdmin(page: Page, target: string) {
  const destination = new URL(target, page.url())
  await page.evaluate((url) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, `${destination.pathname}${destination.search}${destination.hash}`)
  await expect.poll(() => page.evaluate(() => window.location.pathname)).toBe(destination.pathname)
}

export async function expectNoPageOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
}

export async function resetAllScroll(page: Page) {
  const reset = async () => {
    await page.evaluate(() => {
      window.scrollTo(0, 0)
      for (const element of document.querySelectorAll<HTMLElement>('*')) {
        if (element.scrollTop) element.scrollTop = 0
        if (element.scrollLeft) element.scrollLeft = 0
      }
    })
  }
  // 路由切换后的表格/焦点恢复会延迟写回滚动位置，需等待动画稳定后再归零。
  await page.waitForTimeout(500)
  await reset()
  await page.waitForTimeout(100)
  await reset()
  await expect.poll(() => page.evaluate(() => ({ x: window.scrollX, y: window.scrollY }))).toEqual({ x: 0, y: 0 })
  const brand = page.getByText('VocaEase', { exact: true })
  await expect(brand).toBeVisible()
  await expect.poll(async () => {
    await reset()
    await page.waitForTimeout(50)
    return (await brand.boundingBox())?.y ?? -1
  }).toBeGreaterThanOrEqual(0)
  await expect.poll(async () => (await brand.boundingBox())?.y ?? 999).toBeLessThan(64)
}
