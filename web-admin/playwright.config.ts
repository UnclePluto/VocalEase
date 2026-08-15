import { randomBytes } from 'node:crypto'
import { existsSync } from 'node:fs'
import { defineConfig } from '@playwright/test'

process.env.VOCAEASE_E2E_RUN_ID ??= `qa13-${randomBytes(4).toString('hex')}`

const runId = process.env.VOCAEASE_E2E_RUN_ID
const chromiumPath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE

if (chromiumPath && !existsSync(chromiumPath)) {
  throw new Error(`PLAYWRIGHT_CHROMIUM_EXECUTABLE 指向的浏览器不存在：${chromiumPath}`)
}

export default defineConfig({
  testDir: './e2e',
  outputDir: `/tmp/vocaease-playwright-${runId}`,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
  use: {
    baseURL: process.env.VOCAEASE_E2E_BASE_URL ?? 'http://localhost:3000',
    browserName: 'chromium',
    launchOptions: chromiumPath ? { executablePath: chromiumPath } : undefined,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    {
      name: 'auth',
      testMatch: /auth\.spec\.ts/,
      use: { viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'functional',
      dependencies: ['auth'],
      testMatch: /admin-workflows\.spec\.ts|singing-detail\.spec\.ts/,
      use: { viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'visual',
      dependencies: ['auth'],
      testMatch: /visual\.spec\.ts/,
      use: { viewport: { width: 1440, height: 900 } },
    },
  ],
})
