import { rmSync } from 'node:fs'

import { runQaCommand } from './helpers'

export default async function globalTeardown() {
  const runId = process.env.VOCAEASE_E2E_RUN_ID
  if (!runId) return
  try {
    runQaCommand(['--cleanup'])
  } finally {
    rmSync(`/tmp/vocaease-e2e-${runId}-auth.json`, { force: true })
    if (process.env.VOCAEASE_E2E_KEEP_ARTIFACTS !== '1') {
      rmSync(`/tmp/vocaease-playwright-${runId}`, { recursive: true, force: true })
      rmSync(`/tmp/vocaease-visual-${runId}`, { recursive: true, force: true })
    }
  }
}
