import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

function compose(args: string[]) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
  const project = process.env.VOCAEASE_E2E_COMPOSE_PROJECT ?? ''
  if (!/^vocaease-(?:e2e|qa13)-[a-z0-9-]+$/.test(project)) {
    throw new Error('VOCAEASE_E2E_COMPOSE_PROJECT 必须指向独立的 E2E Compose project')
  }
  const result = spawnSync(
    'docker',
    ['compose', '-p', project, '-f', 'deploy/compose.yaml', ...args],
    { cwd: root, encoding: 'utf8' },
  )
  if (result.status !== 0) {
    throw new Error(`Compose E2E 准备失败：${result.stderr || result.stdout}`)
  }
  return result.stdout.trim()
}

export default async function globalSetup() {
  const runId = process.env.VOCAEASE_E2E_RUN_ID
  if (!runId) throw new Error('缺少 VOCAEASE_E2E_RUN_ID')
  compose(['exec', '-T', 'server', 'uv', 'run', '--no-sync', 'python', 'manage.py', 'seed_demo'])
  const loginId = compose([
    'exec', '-T', 'server', 'uv', 'run', '--no-sync', 'python', 'manage.py',
    'qa_e2e', '--run-id', runId,
  ])
  if (loginId !== runId.replace('-', '').toUpperCase()) {
    throw new Error(`QA 账号准备结果异常：${loginId}`)
  }
}
