import { http, HttpResponse } from 'msw'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '../../auth/store'
import { renderApp } from '../../test/renderApp'
import { server } from '../../test/server'

const id = '30000000-0000-0000-0000-000000000001'
const patient = { id, user_id: 'u', medical_record_no: 'MR1', name: '患者甲', gender: 'female', enrollment_age: 20, phone: '13900000000', primary_doctor: 'd', primary_doctor_name: '王医生', notes: 'n', treatment_plan: { id: 'p', start_date: '2026-01-01', cycle_weeks: 4, target_session_count: 12, status: 'active' } }
const envelope = (data: unknown, requestId = 'request-11') => ({ code: 'ok', message: '', data, request_id: requestId })
const failure = (message: string, requestId: string) => ({ code: 'upstream_unavailable', message, data: {}, request_id: requestId })

function authenticate(role: 'system_admin' | 'doctor' | 'patient' = 'system_admin') {
  useAuthStore.setState({ accessToken: 'valid', user: { login_id: 'A', role, must_change_password: false }, status: 'authenticated' })
}

describe('患者数据页', () => {
  it('独立加载完整授权资料和服务端分页历史，并可按日期筛选进入明细', async () => {
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

  it('历史为空时不把筛选页数伪装成累计或统计口径', async () => {
    authenticate()
    server.use(
      http.get('/api/v1/admin/patients/:id/', () => HttpResponse.json(envelope(patient))),
      http.get('/api/v1/admin/singing-sessions/', () => HttpResponse.json(envelope({ count: 0, page: 1, page_size: 10, results: [] }))),
    )
    renderApp(`/patients/${id}/data`)
    expect(await screen.findByText('暂无演唱历史')).toBeInTheDocument()
    expect(screen.getByText(/演唱汇总以数据管理页返回的服务端统计口径为准/)).toBeInTheDocument()
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
