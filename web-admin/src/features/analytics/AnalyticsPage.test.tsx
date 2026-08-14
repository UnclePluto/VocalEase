import { http, HttpResponse } from 'msw'
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '../../auth/store'
import { renderApp } from '../../test/renderApp'
import { server } from '../../test/server'

const doctorId = '10000000-0000-0000-0000-000000000001'
const patient = {
  id: '30000000-0000-0000-0000-000000000001', medical_record_no: 'P202501', name: '患者甲',
  treatment_status: 'active', primary_doctor: '王医生', primary_doctor_id: doctorId,
  treatment_progress: '50.00', completed_count: 6, total_duration_seconds: 360,
  average_score: '88.50', score_trend: { difference: null, direction: 'flat', has_enough_data: false },
  burp_improvement: null, is_mock: true,
}

const envelope = (data: unknown, requestId = 'analytics-request') => ({ code: 'ok', message: '', data, request_id: requestId })

function authenticate(role: 'system_admin' | 'doctor' | 'patient' = 'system_admin') {
  useAuthStore.setState({ accessToken: 'valid', user: { login_id: 'A000001', role, must_change_password: false }, status: 'authenticated' })
}

function useAnalyticsHandlers() {
  server.use(
    http.get('/api/v1/admin/analytics/dashboard/', () => HttpResponse.json(envelope({
      metric_version: '1.0', active_patient_count: 0, completed_session_count: 0,
      average_score: null, average_burp_count: null, is_mock: true,
    }))),
    http.get('/api/v1/admin/analytics/patients/', ({ request }) => HttpResponse.json(envelope({
      metric_version: '1.0', count: 1, page: Number(new URL(request.url).searchParams.get('page') ?? 1), page_size: 20, results: [patient],
    }))),
    http.get('/api/v1/admin/doctors/', () => HttpResponse.json(envelope({ count: 1, page: 1, page_size: 20, results: [{ id: doctorId, employee_no: 'D001', name: '王医生', status: 'active' }] }))),
  )
}

function deferredBody(body: string, headers: Record<string, string>) {
  let release: () => void = () => undefined
  let markRead: () => void = () => undefined
  const readStarted = new Promise<void>((resolve) => { markRead = resolve })
  const gate = new Promise<void>((resolve) => { release = resolve })
  const stream = new ReadableStream<Uint8Array>({
    async pull(controller) {
      markRead()
      await gate
      controller.enqueue(new TextEncoder().encode(body))
      controller.close()
    },
  })
  return { response: new HttpResponse(stream, { status: 200, headers }), readStarted, release }
}

describe('数据管理页面', () => {
  it('展示四项统计、null 指标语义并把筛选和分页规范化到 URL 与列表请求', async () => {
    authenticate(); useAnalyticsHandlers()
    const user = userEvent.setup()
    renderApp(`/analytics?page=3&page_size=20&name=%E7%94%B2&medical_record_no=P202501&treatment_status=active&primary_doctor=${doctorId}`)

    expect(await screen.findByText('在治患者')).toBeInTheDocument()
    expect(screen.getByText('累计完成演唱')).toBeInTheDocument()
    expect(screen.getAllByText('平均得分')).not.toHaveLength(0)
    expect(screen.getByText('平均嗳气次数')).toBeInTheDocument()
    expect(screen.getAllByText('0')).toHaveLength(2)
    expect(screen.getAllByText('暂无数据')).toHaveLength(2)
    expect(screen.getByText('统计口径：1.0')).toBeInTheDocument()
    expect(screen.getByText('模拟统计')).toBeInTheDocument()
    expect(screen.getByText('数据不足')).toBeInTheDocument()
    expect(screen.getByText('暂无趋势')).toBeInTheDocument()
    expect(within(screen.getByRole('row', { name: /P202501/ })).getByText('模拟')).toBeInTheDocument()
    expect(server.calls('/api/v1/admin/analytics/patients/').at(-1)?.search).toBe(
      `?page=3&page_size=20&name=%E7%94%B2&medical_record_no=P202501&treatment_status=active&primary_doctor=${doctorId}`,
    )

    await user.clear(screen.getByLabelText('患者姓名'))
    await user.type(screen.getByLabelText('患者姓名'), '乙')
    await user.click(screen.getByRole('button', { name: '查询' }))
    await waitFor(() => expect(server.calls('/api/v1/admin/analytics/patients/').at(-1)?.search).toContain('page=1'))
    expect(server.calls('/api/v1/admin/analytics/patients/').at(-1)?.search).toContain('name=%E4%B9%99')
  })

  it('切换账号后 dashboard 与患者列表不瞬显旧账号缓存', async () => {
    authenticate()
    let releaseSecond: () => void = () => undefined
    const secondGate = new Promise<void>((resolve) => { releaseSecond = resolve })
    server.use(
      http.get('/api/v1/admin/doctors/', () => HttpResponse.json(envelope({ count: 0, page: 1, page_size: 20, results: [] }))),
      http.get('/api/v1/admin/analytics/dashboard/', async ({ request }) => {
        if (request.headers.get('authorization') === 'Bearer second') await secondGate
        return HttpResponse.json(envelope({ metric_version: '1.0', active_patient_count: request.headers.get('authorization') === 'Bearer second' ? 2 : 1, completed_session_count: 0, average_score: null, average_burp_count: null, is_mock: false }))
      }),
      http.get('/api/v1/admin/analytics/patients/', async ({ request }) => {
        const second = request.headers.get('authorization') === 'Bearer second'
        if (second) await secondGate
        return HttpResponse.json(envelope({ metric_version: '1.0', count: 1, page: 1, page_size: 20, results: [{ ...patient, id: second ? '30000000-0000-0000-0000-000000000002' : patient.id, name: second ? '新账号患者' : '旧账号患者' }] }))
      }),
    )
    renderApp('/analytics')
    expect(await screen.findByText('旧账号患者')).toBeInTheDocument()

    act(() => {
      const current = useAuthStore.getState()
      useAuthStore.setState({ accessToken: 'second', user: { login_id: 'A000002', role: 'system_admin', must_change_password: false }, status: 'authenticated', sessionEpoch: current.sessionEpoch + 1 })
    })

    expect(screen.queryByText('旧账号患者')).not.toBeInTheDocument()
    expect(screen.queryByText('新账号患者')).not.toBeInTheDocument()
    releaseSecond()
    expect(await screen.findByText('新账号患者')).toBeInTheDocument()
  })

  it('主治医生使用远程选择器并把选中的真实 UUID 发给列表和导出', async () => {
    authenticate(); useAnalyticsHandlers()
    const user = userEvent.setup(); renderApp('/analytics')

    const select = await screen.findByRole('combobox', { name: '主治医生筛选' })
    await user.click(select)
    await user.click(await screen.findByText('王医生 · D001'))
    await user.click(screen.getByRole('button', { name: '查询' }))
    await waitFor(() => expect(server.calls('/api/v1/admin/analytics/patients/').at(-1)?.search).toContain(`primary_doctor=${doctorId}`))
  })

  it('医生选项错误独立显示 request_id，可重试并支持搜索与加载更多', async () => {
    authenticate(); useAnalyticsHandlers()
    let attempt = 0
    server.use(http.get('/api/v1/admin/doctors/', ({ request }) => {
      attempt += 1
      if (attempt === 1) return HttpResponse.json({ code: 'doctor_failed', message: '医生选项失败', data: null, request_id: 'doctor-options-request' }, { status: 503 })
      const page = Number(new URL(request.url).searchParams.get('page'))
      return HttpResponse.json(envelope({ count: 21, page, page_size: 20, results: page === 1 ? [{ id: doctorId, employee_no: 'D001', name: '王医生', status: 'active' }] : [{ id: '10000000-0000-0000-0000-000000000021', employee_no: 'D021', name: '末页医生', status: 'active' }] }))
    }))
    const user = userEvent.setup(); renderApp('/analytics')

    expect(await screen.findByText('医生选项失败')).toBeInTheDocument()
    expect(screen.getByText('请求编号：doctor-options-request')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试医生选项' }))
    expect(await screen.findByRole('button', { name: '加载更多医生' })).toBeInTheDocument()
    const doctorFilter = screen.getByRole('combobox', { name: '主治医生筛选' })
    await user.click(doctorFilter)
    await user.type(doctorFilter, '王')
    await waitFor(() => expect(server.calls('/api/v1/admin/doctors/').at(-1)?.search).toContain('keyword=%E7%8E%8B'))
    expect(await screen.findByRole('option', { name: /王医生/ })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '加载更多医生' }))
    await waitFor(() => expect(server.calls('/api/v1/admin/doctors/').at(-1)?.search).toContain('page=2'))
  })

  it('忽略非法状态、UUID、日期与反向日期，不把它们发送给 API', async () => {
    authenticate(); useAnalyticsHandlers()
    renderApp('/analytics?treatment_status=bogus&primary_doctor=not-a-uuid&created_from=2026-12-31&created_to=2025-01-01&page=oops&page_size=999')

    await screen.findByText('患者甲')
    expect(server.calls('/api/v1/admin/analytics/patients/').at(-1)?.search).toBe('?page=1&page_size=20')
  })

  it('非法日历日期不会进入患者指标请求', async () => {
    authenticate(); useAnalyticsHandlers()
    renderApp('/analytics?created_from=2026-02-30&created_to=2025-02-30')

    await screen.findByText('患者甲')
    expect(server.calls('/api/v1/admin/analytics/patients/').at(-1)?.search).toBe('?page=1&page_size=20')
  })

  it('只导出当前页真实选中患者，并安全保存同步 CSV 后及时回收 Blob URL', async () => {
    authenticate(); useAnalyticsHandlers()
    const createUrl = vi.fn(() => 'blob:analytics-export')
    const revokeUrl = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createUrl })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeUrl })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    server.use(http.post('/api/v1/admin/analytics/exports/', () => new HttpResponse('name,score\n患者甲,88.5', {
      status: 200,
      headers: { 'content-type': 'text/csv', 'content-disposition': "attachment; filename*=UTF-8''report%20ok.csv" },
    })))
    const user = userEvent.setup(); renderApp('/analytics?name=%E7%94%B2')

    await user.click(await screen.findByRole('checkbox', { name: '选择 P202501' }))
    await user.click(screen.getByRole('button', { name: '导出报告 (1)' }))
    await user.click(screen.getByRole('menuitem', { name: 'CSV' }))

    await waitFor(() => expect(server.lastJson('/api/v1/admin/analytics/exports/')).toMatchObject({
      format: 'csv', selected_ids: [patient.id], filters: { name: '甲' }, idempotency_key: expect.any(String),
    }))
    expect(createUrl).toHaveBeenCalledOnce()
    expect(click).toHaveBeenCalledOnce()
    await waitFor(() => expect(revokeUrl).toHaveBeenCalledWith('blob:analytics-export'))
    click.mockRestore()
  })

  it('同步文件 body 读取期间切换账号会拒绝旧文件且不触发下载', async () => {
    authenticate(); useAnalyticsHandlers()
    const createUrl = vi.fn(() => 'blob:stale-export')
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createUrl })
    const delayed = deferredBody('stale,csv', { 'content-type': 'text/csv', 'content-disposition': 'attachment; filename=stale.csv' })
    server.use(http.post('/api/v1/admin/analytics/exports/', () => delayed.response))
    const user = userEvent.setup(); renderApp('/analytics')

    await screen.findByText('患者甲')
    await user.click(screen.getByRole('button', { name: '导出报告 (0)' }))
    await user.click(screen.getByRole('menuitem', { name: 'CSV' }))
    await delayed.readStarted
    act(() => {
      const current = useAuthStore.getState()
      useAuthStore.setState({ accessToken: 'new-session', user: { login_id: 'A000002', role: 'system_admin', must_change_password: false }, status: 'authenticated', sessionEpoch: current.sessionEpoch + 1 })
    })
    delayed.release()

    await waitFor(() => expect(screen.getByText('登录状态已变更')).toBeInTheDocument())
    expect(createUrl).not.toHaveBeenCalled()
  })

  it('异步 Job JSON body 读取期间退出不会把旧 Job ID 写入 URL', async () => {
    authenticate(); useAnalyticsHandlers()
    const delayed = deferredBody(JSON.stringify(envelope({ id: '70000000-0000-0000-0000-000000000099', metric_version: '1.0', format: 'xlsx', status: 'pending', count: 1001, failure_reason: '', expires_at: '2026-09-01T00:00:00Z', result_asset_id: null })), { 'content-type': 'application/json' })
    server.use(http.post('/api/v1/admin/analytics/exports/', () => delayed.response))
    const user = userEvent.setup(); renderApp('/analytics')

    await screen.findByText('患者甲')
    await user.click(screen.getByRole('button', { name: '导出报告 (0)' }))
    await user.click(screen.getByRole('menuitem', { name: 'Excel' }))
    await delayed.readStarted
    act(() => useAuthStore.getState().reset())
    delayed.release()

    expect(await screen.findByRole('heading', { name: '登录 VocaEase' })).toBeInTheDocument()
    expect(window.location.search).not.toContain('export_job=')
  })

  it('创建导出失败提供明确重试且同一签名复用幂等键，并防止重复提交', async () => {
    authenticate(); useAnalyticsHandlers()
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    let attempts = 0
    server.use(http.post('/api/v1/admin/analytics/exports/', async () => {
      attempts += 1
      if (attempts === 1) return HttpResponse.json({ code: 'export_unknown', message: '导出结果未知', data: null, request_id: 'export-retry-request' }, { status: 503 })
      await gate
      return new HttpResponse('csv', { headers: { 'content-type': 'text/csv' } })
    }))
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn(() => 'blob:retry') })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const user = userEvent.setup(); renderApp('/analytics')

    await screen.findByText('患者甲')
    await user.click(screen.getByRole('button', { name: '导出报告 (0)' }))
    await user.click(screen.getByRole('menuitem', { name: 'CSV' }))
    expect(await screen.findByText('导出结果未知')).toBeInTheDocument()
    expect(screen.getByText('请求编号：export-retry-request')).toBeInTheDocument()
    const firstKey = (server.lastJson('/api/v1/admin/analytics/exports/') as { idempotency_key: string }).idempotency_key
    const retry = screen.getByRole('button', { name: '重试创建导出' })
    fireEvent.click(retry); fireEvent.click(retry)
    await waitFor(() => expect(server.calls('/api/v1/admin/analytics/exports/')).toHaveLength(2))
    expect((server.lastJson('/api/v1/admin/analytics/exports/') as { idempotency_key: string }).idempotency_key).toBe(firstKey)
    release()
  })

  it.each([400, 409])('创建导出收到确定性 %i 后，重试使用新的幂等键', async (status) => {
    authenticate(); useAnalyticsHandlers()
    let attempts = 0
    server.use(http.post('/api/v1/admin/analytics/exports/', () => {
      attempts += 1
      return attempts === 1
        ? HttpResponse.json({ code: 'export_rejected', message: '导出请求无效', data: null, request_id: `export-${status}-request` }, { status })
        : new HttpResponse('csv', { headers: { 'content-type': 'text/csv' } })
    }))
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn(() => 'blob:deterministic-retry') })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const user = userEvent.setup(); renderApp('/analytics')

    await screen.findByText('患者甲')
    await user.click(screen.getByRole('button', { name: '导出报告 (0)' }))
    await user.click(screen.getByRole('menuitem', { name: 'CSV' }))
    expect(await screen.findByText('导出请求无效')).toBeInTheDocument()
    const firstKey = (server.lastJson('/api/v1/admin/analytics/exports/') as { idempotency_key: string }).idempotency_key

    await user.click(screen.getByRole('button', { name: '重试创建导出' }))
    await waitFor(() => expect(server.calls('/api/v1/admin/analytics/exports/')).toHaveLength(2))
    expect((server.lastJson('/api/v1/admin/analytics/exports/') as { idempotency_key: string }).idempotency_key).not.toBe(firstKey)
  })

  it('创建导出的请求内容变化时使用新的幂等键', async () => {
    authenticate(); useAnalyticsHandlers()
    server.use(http.post('/api/v1/admin/analytics/exports/', () => HttpResponse.json({ code: 'export_unknown', message: '导出结果未知', data: null, request_id: 'export-signature-request' }, { status: 503 })))
    const user = userEvent.setup(); renderApp('/analytics')

    await screen.findByText('患者甲')
    await user.click(screen.getByRole('button', { name: '导出报告 (0)' }))
    await user.click(screen.getByRole('menuitem', { name: 'CSV' }))
    expect(await screen.findByText('导出结果未知')).toBeInTheDocument()
    const firstRequest = server.lastJson('/api/v1/admin/analytics/exports/') as { format: string; idempotency_key: string }

    await user.click(screen.getByRole('button', { name: '导出报告 (0)' }))
    await user.click(screen.getByRole('menuitem', { name: 'Excel' }))
    await waitFor(() => expect(server.calls('/api/v1/admin/analytics/exports/')).toHaveLength(2))
    const secondRequest = server.lastJson('/api/v1/admin/analytics/exports/') as { format: string; idempotency_key: string }
    expect(secondRequest.format).toBe('xlsx')
    expect(secondRequest.idempotency_key).not.toBe(firstRequest.idempotency_key)
  })

  it('患者角色被守卫拒绝，且不请求 analytics 管理端接口', async () => {
    authenticate('patient')
    const requested = vi.fn()
    server.use(http.get('/api/v1/admin/analytics/dashboard/', () => { requested(); return HttpResponse.json(envelope({})) }))
    renderApp('/analytics')
    expect(await screen.findByRole('heading', { name: '登录 VocaEase' })).toBeInTheDocument()
    expect(requested).not.toHaveBeenCalled()
  })
})
