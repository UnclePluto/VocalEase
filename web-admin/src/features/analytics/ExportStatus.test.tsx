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
})
