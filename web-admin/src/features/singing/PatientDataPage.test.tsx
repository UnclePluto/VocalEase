import { http, HttpResponse } from 'msw'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '../../auth/store'
import { renderApp } from '../../test/renderApp'
import { server } from '../../test/server'

const id = '30000000-0000-0000-0000-000000000001'
const patient = { id, user_id: 'u', medical_record_no: 'MR1', name: '患者甲', gender: 'female', enrollment_age: 20, phone: '13900000000', primary_doctor: 'd', primary_doctor_name: '王医生', notes: 'n', treatment_plan: { id: 'p', start_date: '2026-01-01', cycle_weeks: 4, target_session_count: 12, status: 'active' } }
const envelope = (data: unknown, requestId = 'request-11') => ({ code: 'ok', message: '', data, request_id: requestId })
const failure = (message: string, requestId: string) => ({ code: 'upstream_unavailable', message, data: {}, request_id: requestId })
const metrics = { metric_version: '1.0', count: 1, page: 1, page_size: 1, results: [{ id, medical_record_no: 'MR1', name: '患者甲', treatment_status: 'active', primary_doctor: '王医生', primary_doctor_id: 'd', treatment_progress: '50.00', completed_count: 6, total_duration_seconds: 360, average_score: '88.50', score_trend: { difference: '5.00', direction: 'up', has_enough_data: true }, burp_improvement: '0.2500', is_mock: true }] }

function authenticate(role: 'system_admin' | 'doctor' | 'patient' = 'system_admin') {
  useAuthStore.setState({ accessToken: 'valid', user: { login_id: 'A', role, must_change_password: false }, status: 'authenticated' })
}

describe('患者数据页', () => {
  beforeEach(() => {
    server.use(http.get('/api/v1/admin/analytics/patients/', () => HttpResponse.json(envelope(metrics))))
  })

  it('独立加载完整授权资料和服务端分页历史，并可进入明细', async () => {
    authenticate()
    let historyUrl = ''
    server.use(
      http.get('/api/v1/admin/patients/:id/', () => HttpResponse.json(envelope(patient))),
      http.get('/api/v1/admin/singing-sessions/', ({ request }) => {
        historyUrl = request.url
        return HttpResponse.json(envelope({ count: 11, page: 1, page_size: 10, results: [{ id: 's1', song: { title: '歌' }, score: 90, burp_count: 1, completed_at: '2026-01-01' }] }))
      }),
      http.get('/api/v1/admin/singing-sessions/s1/', () => HttpResponse.json(envelope({ id: 's1', status: 'completed', score: 90, burp_count: 1, duration_seconds: 10, is_mock: true, patient: {}, song: {}, media: [], analysis_results: [] }))),
    )
    const user = userEvent.setup(); renderApp(`/patients/${id}/data`)
    expect(await screen.findByText('13900000000')).toBeInTheDocument()
    expect(await screen.findByText('筛选结果：11 条')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /查看明细/ }))
    expect(await screen.findByRole('heading', { name: '演唱明细' })).toBeInTheDocument()
    expect(historyUrl).toContain('patient_id=30000000-0000-0000-0000-000000000001')
  })

  it('展示 Task 7 权威演唱汇总与口径版本，不用历史筛选条数冒充统计', async () => {
    authenticate()
    server.use(
      http.get('/api/v1/admin/patients/:id/', () => HttpResponse.json(envelope(patient))),
      http.get('/api/v1/admin/singing-sessions/', () => HttpResponse.json(envelope({ count: 99, page: 1, page_size: 10, results: [] }))),
    )
    renderApp(`/patients/${id}/data`)
    expect(await screen.findByText('已完成演唱：6 次')).toBeInTheDocument()
    expect(screen.getByText('累计时长：6 分钟')).toBeInTheDocument()
    expect(screen.getByText('平均得分：88.50')).toBeInTheDocument()
    expect(screen.getByText('治疗进度：50.00%')).toBeInTheDocument()
    expect(screen.getByText('得分趋势：上升 5.00')).toBeInTheDocument()
    expect(screen.getByText('嗳气改善率：25.00%')).toBeInTheDocument()
    expect(screen.getByText('统计口径：1.0')).toBeInTheDocument()
    expect(screen.getByText('筛选结果：99 条')).toBeInTheDocument()
  })

  it('真实操作日期和分页后把筛选值发送到服务端 URL', async () => {
    authenticate()
    const urls: string[] = []
    server.use(
      http.get('/api/v1/admin/patients/:id/', () => HttpResponse.json(envelope(patient))),
      http.get('/api/v1/admin/singing-sessions/', ({ request }) => {
        urls.push(request.url)
        return HttpResponse.json(envelope({ count: 21, page: Number(new URL(request.url).searchParams.get('page')), page_size: 10, results: [] }))
      }),
    )
    const user = userEvent.setup(); renderApp(`/patients/${id}/data`)
    await screen.findByText('暂无演唱历史')
    const inputs = screen.getAllByLabelText('演唱日期筛选')
    fireEvent.change(inputs[0], { target: { value: '2026-01-01' } }); fireEvent.blur(inputs[0])
    fireEvent.change(inputs[1], { target: { value: '2026-01-31' } }); fireEvent.blur(inputs[1])
    await user.click(await screen.findByTitle('2'))
    await waitFor(() => expect(urls.some((url) => url.includes('page=2'))).toBe(true))
    expect(urls.some((url) => url.includes('created_from=2026-01-01') && url.includes('created_to=2026-01-31'))).toBe(true)
  })

  it('将资料和历史错误分开显示 request_id，并且各自可重试', async () => {
    authenticate()
    let patientAttempts = 0
    let historyAttempts = 0
    server.use(
      http.get('/api/v1/admin/patients/:id/', () => {
        patientAttempts += 1
        return patientAttempts === 1
          ? HttpResponse.json(failure('资料服务暂不可用', 'patient-request'), { status: 503 })
          : HttpResponse.json(envelope(patient))
      }),
      http.get('/api/v1/admin/singing-sessions/', () => {
        historyAttempts += 1
        return historyAttempts === 1
          ? HttpResponse.json(failure('历史服务暂不可用', 'history-request'), { status: 503 })
          : HttpResponse.json(envelope({ count: 0, page: 1, page_size: 10, results: [] }))
      }),
    )
    const user = userEvent.setup(); renderApp(`/patients/${id}/data`)
    expect(await screen.findByText('资料服务暂不可用')).toBeInTheDocument()
    expect(screen.getByText('请求编号：patient-request')).toBeInTheDocument()
    expect(await screen.findByText('历史服务暂不可用')).toBeInTheDocument()
    expect(screen.getByText('请求编号：history-request')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试加载资料' }))
    await user.click(screen.getByRole('button', { name: '重试加载历史' }))
    expect(await screen.findByText('13900000000')).toBeInTheDocument()
    expect(await screen.findByText('暂无演唱历史')).toBeInTheDocument()
  })

  it('权威演唱汇总错误独立展示 request_id 并可重试，不影响资料和历史', async () => {
    authenticate()
    let attempts = 0
    server.use(
      http.get('/api/v1/admin/patients/:id/', () => HttpResponse.json(envelope(patient))),
      http.get('/api/v1/admin/singing-sessions/', () => HttpResponse.json(envelope({ count: 0, page: 1, page_size: 10, results: [] }))),
      http.get('/api/v1/admin/analytics/patients/', () => {
        attempts += 1
        return attempts === 1
          ? HttpResponse.json(failure('统计服务暂不可用', 'metrics-request'), { status: 503 })
          : HttpResponse.json(envelope(metrics))
      }),
    )
    const user = userEvent.setup(); renderApp(`/patients/${id}/data`)
    expect(await screen.findByText('13900000000')).toBeInTheDocument()
    expect(await screen.findByText('暂无演唱历史')).toBeInTheDocument()
    expect(await screen.findByText('统计服务暂不可用')).toBeInTheDocument()
    expect(screen.getByText('请求编号：metrics-request')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试加载汇总' }))
    expect(await screen.findByText('统计口径：1.0')).toBeInTheDocument()
  })

  it('历史为空时不把筛选页数伪装成累计或统计口径', async () => {
    authenticate()
    server.use(
      http.get('/api/v1/admin/patients/:id/', () => HttpResponse.json(envelope(patient))),
      http.get('/api/v1/admin/singing-sessions/', () => HttpResponse.json(envelope({ count: 0, page: 1, page_size: 10, results: [] }))),
    )
    renderApp(`/patients/${id}/data`)
    expect(await screen.findByText('暂无演唱历史')).toBeInTheDocument()
    expect(await screen.findByText(/统计口径：1.0/)).toBeInTheDocument()
    expect(screen.queryByText(/累计演唱/)).not.toBeInTheDocument()
  })

  it('患者角色被路由守卫拒绝，且不会请求管理端详情', async () => {
    authenticate('patient')
    const requested = vi.fn()
    server.use(http.get('/api/v1/admin/patients/:id/', () => { requested(); return HttpResponse.json(envelope(patient)) }))
    renderApp(`/patients/${id}/data`)
    expect(await screen.findByRole('heading', { name: '登录 VocaEase' })).toBeInTheDocument()
    expect(requested).not.toHaveBeenCalled()
  })
})
