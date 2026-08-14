import { http, HttpResponse } from 'msw'
import { screen, waitFor } from '@testing-library/react'
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
  )
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
    expect(server.calls('/api/v1/admin/analytics/patients/').at(-1)?.search).toBe(
      `?page=3&page_size=20&name=%E7%94%B2&medical_record_no=P202501&treatment_status=active&primary_doctor=${doctorId}`,
    )

    await user.clear(screen.getByLabelText('患者姓名'))
    await user.type(screen.getByLabelText('患者姓名'), '乙')
    await user.click(screen.getByRole('button', { name: '查询' }))
    await waitFor(() => expect(server.calls('/api/v1/admin/analytics/patients/').at(-1)?.search).toContain('page=1'))
    expect(server.calls('/api/v1/admin/analytics/patients/').at(-1)?.search).toContain('name=%E4%B9%99')
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

  it('患者角色被守卫拒绝，且不请求 analytics 管理端接口', async () => {
    authenticate('patient')
    const requested = vi.fn()
    server.use(http.get('/api/v1/admin/analytics/dashboard/', () => { requested(); return HttpResponse.json(envelope({})) }))
    renderApp('/analytics')
    expect(await screen.findByRole('heading', { name: '登录 VocaEase' })).toBeInTheDocument()
    expect(requested).not.toHaveBeenCalled()
  })
})
