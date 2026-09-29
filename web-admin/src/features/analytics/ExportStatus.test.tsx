import { http, HttpResponse } from 'msw'
import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '../../auth/store'
import { renderApp } from '../../test/renderApp'
import { server } from '../../test/server'

const jobId = '70000000-0000-0000-0000-000000000001'
const envelope = (data: unknown, requestId = 'export-request') => ({ code: 'ok', message: '', data, request_id: requestId })

function authenticate() {
  useAuthStore.setState({ accessToken: 'valid', user: { login_id: 'A000001', role: 'system_admin', must_change_password: false }, status: 'authenticated' })
}

function dashboardHandlers() {
  return [
    http.get('/api/v1/admin/analytics/dashboard/', () => HttpResponse.json(envelope({ metric_version: '1.0', active_patient_count: 0, completed_session_count: 0, average_score: null, average_burp_count: null, is_mock: false }))),
    http.get('/api/v1/admin/analytics/patients/', () => HttpResponse.json(envelope({ metric_version: '1.0', count: 0, page: 1, page_size: 20, results: [] }))),
    http.get('/api/v1/admin/doctors/', () => HttpResponse.json(envelope({ count: 0, page: 1, page_size: 20, results: [] }))),
  ]
}

function readyJob(overrides: Record<string, unknown> = {}) {
  return { id: jobId, format: 'csv', status: 'ready', count: 1, failure_reason: '', expires_at: '2026-09-01T00:00:00Z', result_asset_id: 'asset', ...overrides }
}

function deferredBody() {
  let release!: () => void
  const wait = new Promise<void>((resolve) => { release = resolve })
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      await wait
      controller.enqueue(new TextEncoder().encode('export-bytes'))
      controller.close()
    },
  })
  return { stream, release }
}

describe('异步导出状态', () => {
  it('仅轮询 pending/processing，ready 后获取私有地址并下载，同时从 URL 恢复 job', async () => {
    authenticate()
    const createUrl = vi.fn(() => 'blob:private-export')
    const revokeUrl = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createUrl })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeUrl })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const states = ['pending', 'processing', 'ready']
    server.use(
      http.get('/api/v1/admin/doctors/', () => HttpResponse.json(envelope({ count: 0, page: 1, page_size: 20, results: [] }))),
      http.get('/api/v1/admin/analytics/dashboard/', () => HttpResponse.json(envelope({ metric_version: '1.0', active_patient_count: 0, completed_session_count: 0, average_score: null, average_burp_count: null, is_mock: false }))),
      http.get('/api/v1/admin/analytics/patients/', () => HttpResponse.json(envelope({ metric_version: '1.0', count: 0, page: 1, page_size: 20, results: [] }))),
      http.get(`/api/v1/admin/analytics/exports/${jobId}/`, () => HttpResponse.json(envelope({ id: jobId, format: 'xlsx', status: states.shift() ?? 'ready', count: 1001, failure_reason: '', expires_at: '2026-09-01T00:00:00Z', result_asset_id: 'asset' }))),
      http.post(`/api/v1/admin/analytics/exports/${jobId}/private-url/`, () => HttpResponse.json(envelope({ url: 'https://private.example/file.xlsx', expires_at: '2026-08-16T00:00:00Z' }))),
      http.get('https://private.example/file.xlsx', () => new HttpResponse('xlsx-bytes', { headers: { 'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' } })),
    )
    const user = userEvent.setup()
    renderApp(`/analytics?export_job=${jobId}`)

    expect(await screen.findByText('导出任务处理中')).toBeInTheDocument()
    await act(async () => { await new Promise((resolve) => window.setTimeout(resolve, 1_600)) })
    await act(async () => { await new Promise((resolve) => window.setTimeout(resolve, 1_600)) })
    expect(await screen.findByRole('button', { name: '下载文件' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '下载文件' }))
    await waitFor(() => expect(createUrl).toHaveBeenCalledOnce())
    expect(click).toHaveBeenCalledOnce()
    expect(server.calls(`/api/v1/admin/analytics/exports/${jobId}/`)).toHaveLength(3)
    click.mockRestore()
  }, 10_000)

  it('failed 与 expired 保持不可下载，并将私有地址错误独立显示 request_id 后允许重试', async () => {
    authenticate()
    let privateAttempts = 0
    server.use(
      http.get('/api/v1/admin/doctors/', () => HttpResponse.json(envelope({ count: 0, page: 1, page_size: 20, results: [] }))),
      http.get('/api/v1/admin/analytics/dashboard/', () => HttpResponse.json(envelope({ metric_version: '1.0', active_patient_count: 0, completed_session_count: 0, average_score: null, average_burp_count: null, is_mock: false }))),
      http.get('/api/v1/admin/analytics/patients/', () => HttpResponse.json(envelope({ metric_version: '1.0', count: 0, page: 1, page_size: 20, results: [] }))),
      http.get(`/api/v1/admin/analytics/exports/${jobId}/`, () => HttpResponse.json(envelope({ id: jobId, format: 'csv', status: 'ready', count: 1, failure_reason: '', expires_at: '2026-09-01T00:00:00Z', result_asset_id: 'asset' }))),
      http.post(`/api/v1/admin/analytics/exports/${jobId}/private-url/`, () => {
        privateAttempts += 1
        return privateAttempts === 1
          ? HttpResponse.json({ code: 'url_failed', message: '授权失败', data: null, request_id: 'private-request' }, { status: 503 })
          : HttpResponse.json(envelope({ url: 'https://private.example/file.csv', expires_at: '2026-08-16T00:00:00Z' }))
      }),
    )
    const user = userEvent.setup(); renderApp(`/analytics?export_job=${jobId}`)
    expect(await screen.findByText('授权失败')).toBeInTheDocument()
    expect(screen.getByText('请求编号：private-request')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试获取下载地址' }))
    expect(await screen.findByRole('button', { name: '下载文件' })).toBeInTheDocument()
  })

  it('外部文件响应体读取期间换账号后拒绝旧结果，且请求不携带认证与 Cookie', async () => {
    authenticate()
    const body = deferredBody()
    const createUrl = vi.fn(() => 'blob:must-not-exist')
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createUrl })
    server.use(
      ...dashboardHandlers(),
      http.get(`/api/v1/admin/analytics/exports/${jobId}/`, () => HttpResponse.json(envelope(readyJob()))),
      http.post(`/api/v1/admin/analytics/exports/${jobId}/private-url/`, () => HttpResponse.json(envelope({ url: 'https://private.example/delayed.csv', expires_at: '2026-08-16T00:00:00Z' }))),
      http.get('https://private.example/delayed.csv', ({ request }) => {
        expect(request.headers.get('authorization')).toBeNull()
        expect(request.headers.get('cookie')).toBeNull()
        return new HttpResponse(body.stream, { headers: { 'content-type': 'text/csv' } })
      }),
    )
    const user = userEvent.setup(); renderApp(`/analytics?export_job=${jobId}`)
    await user.click(await screen.findByRole('button', { name: '下载文件' }))
    act(() => {
      const current = useAuthStore.getState()
      useAuthStore.setState({ accessToken: 'new-session', user: { login_id: 'A000002', role: 'system_admin', must_change_password: false }, status: 'authenticated', sessionEpoch: current.sessionEpoch + 1 })
      body.release()
    })
    expect(await screen.findByText('登录状态已变更')).toBeInTheDocument()
    expect(createUrl).not.toHaveBeenCalled()
  })

  it.each([401, 403, 410])('下载地址 %i 时只重签一次，并用新地址重试下载', async (expiredStatus) => {
    authenticate()
    let privateAttempts = 0
    const createUrl = vi.fn(() => 'blob:resigned')
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createUrl })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    server.use(
      ...dashboardHandlers(),
      http.get(`/api/v1/admin/analytics/exports/${jobId}/`, () => HttpResponse.json(envelope(readyJob()))),
      http.post(`/api/v1/admin/analytics/exports/${jobId}/private-url/`, () => {
        privateAttempts += 1
        return HttpResponse.json(envelope({ url: `https://private.example/${privateAttempts === 1 ? 'old' : 'fresh'}.csv`, expires_at: '2026-08-16T00:00:00Z' }))
      }),
      http.get('https://private.example/old.csv', () => new HttpResponse(null, { status: expiredStatus })),
      http.get('https://private.example/fresh.csv', () => new HttpResponse('fresh', { headers: { 'content-type': 'text/csv' } })),
    )
    const user = userEvent.setup(); renderApp(`/analytics?export_job=${jobId}`)
    await user.click(await screen.findByRole('button', { name: '下载文件' }))
    await waitFor(() => expect(createUrl).toHaveBeenCalledOnce())
    expect(privateAttempts).toBe(2)
    expect(server.calls('/old.csv')).toHaveLength(1)
    expect(server.calls('/fresh.csv')).toHaveLength(1)
  })

  it('重签发现任务已经过期时刷新任务并显示 expired，不继续下载', async () => {
    authenticate()
    let jobAttempts = 0
    let privateAttempts = 0
    const createUrl = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createUrl })
    server.use(
      ...dashboardHandlers(),
      http.get(`/api/v1/admin/analytics/exports/${jobId}/`, () => {
        jobAttempts += 1
        return HttpResponse.json(envelope(readyJob(jobAttempts === 1 ? {} : { status: 'expired', failure_reason: '文件已过期' })))
      }),
      http.post(`/api/v1/admin/analytics/exports/${jobId}/private-url/`, () => {
        privateAttempts += 1
        return privateAttempts === 1
          ? HttpResponse.json(envelope({ url: 'https://private.example/expired.csv', expires_at: '2026-08-16T00:00:00Z' }))
          : HttpResponse.json({ code: 'export_expired', message: '导出已过期', data: null, request_id: 'expired-request' }, { status: 410 })
      }),
      http.get('https://private.example/expired.csv', () => new HttpResponse(null, { status: 410 })),
    )
    const user = userEvent.setup(); renderApp(`/analytics?export_job=${jobId}`)
    await user.click(await screen.findByRole('button', { name: '下载文件' }))
    expect(await screen.findByText('导出文件已过期')).toBeInTheDocument()
    expect(jobAttempts).toBe(2)
    expect(createUrl).not.toHaveBeenCalled()
  })

  it('旧地址失效且重签失败后禁用旧地址，手动重试只获取新地址', async () => {
    authenticate()
    let privateAttempts = 0
    const createUrl = vi.fn(() => 'blob:recovered-address')
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createUrl })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    server.use(
      ...dashboardHandlers(),
      http.get(`/api/v1/admin/analytics/exports/${jobId}/`, () => HttpResponse.json(envelope(readyJob()))),
      http.post(`/api/v1/admin/analytics/exports/${jobId}/private-url/`, () => {
        privateAttempts += 1
        if (privateAttempts === 1) return HttpResponse.json(envelope({ url: 'https://private.example/stale.csv', expires_at: '2026-08-16T00:00:00Z' }))
        if (privateAttempts === 2) return HttpResponse.json({ code: 'resign_failed', message: '重签服务不可用', data: null, request_id: 'resign-request' }, { status: 503 })
        return HttpResponse.json(envelope({ url: 'https://private.example/recovered.csv', expires_at: '2026-08-16T00:00:00Z' }))
      }),
      http.get('https://private.example/stale.csv', () => new HttpResponse(null, { status: 410 })),
      http.get('https://private.example/recovered.csv', () => new HttpResponse('fresh', { headers: { 'content-type': 'text/csv' } })),
    )
    const user = userEvent.setup(); renderApp(`/analytics?export_job=${jobId}`)

    await user.click(await screen.findByRole('button', { name: '下载文件' }))
    expect(await screen.findByText('重签服务不可用')).toBeInTheDocument()
    expect(screen.getByText('请求编号：resign-request')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下载文件' })).not.toBeInTheDocument()
    expect(server.calls('/stale.csv')).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: '重试获取下载地址' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '下载文件' })).toBeInTheDocument())
    expect(privateAttempts).toBe(3)
    expect(server.calls('/stale.csv')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: '下载文件' }))
    await waitFor(() => expect(createUrl).toHaveBeenCalledOnce())
    expect(server.calls('/stale.csv')).toHaveLength(1)
    expect(server.calls('/recovered.csv')).toHaveLength(1)
  })

  it('网络/CORS 失败稳定显示错误，手动重试会先重签且不会复用旧地址', async () => {
    authenticate()
    let privateAttempts = 0
    const createUrl = vi.fn(() => 'blob:manual-retry')
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createUrl })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    server.use(
      ...dashboardHandlers(),
      http.get(`/api/v1/admin/analytics/exports/${jobId}/`, () => HttpResponse.json(envelope(readyJob()))),
      http.post(`/api/v1/admin/analytics/exports/${jobId}/private-url/`, () => {
        privateAttempts += 1
        return HttpResponse.json(envelope({ url: `https://private.example/${privateAttempts === 1 ? 'cors' : 'manual-fresh'}.csv`, expires_at: '2026-08-16T00:00:00Z' }))
      }),
      http.get('https://private.example/cors.csv', () => HttpResponse.error()),
      http.get('https://private.example/manual-fresh.csv', () => new HttpResponse('fresh', { headers: { 'content-type': 'text/csv' } })),
    )
    const user = userEvent.setup(); renderApp(`/analytics?export_job=${jobId}`)
    await user.click(await screen.findByRole('button', { name: '下载文件' }))
    expect(await screen.findByText('网络连接失败，请稍后重试')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试下载文件' }))
    await waitFor(() => expect(createUrl).toHaveBeenCalledOnce())
    expect(privateAttempts).toBe(2)
    expect(server.calls('/cors.csv')).toHaveLength(1)
    expect(server.calls('/manual-fresh.csv')).toHaveLength(1)
  })
})
