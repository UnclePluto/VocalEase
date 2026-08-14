import { http, HttpResponse, delay } from 'msw'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { useAuthStore } from '../../auth/store'
import { renderApp } from '../../test/renderApp'
import { server } from '../../test/server'

const admin = { login_id: 'A000001', role: 'system_admin' as const, must_change_password: false }
const doctor = {
  id: '10000000-0000-0000-0000-000000000001', user_id: '20000000-0000-0000-0000-000000000001',
  employee_no: 'D0001', name: '王医生', gender: 'female', phone: '13800000001',
  department: '消化内科', title: '主任医师', status: 'active',
}
const patient = {
  id: '30000000-0000-0000-0000-000000000001', user_id: '40000000-0000-0000-0000-000000000001',
  medical_record_no: 'P000001', name: '患者甲', gender: 'female', enrollment_age: 32,
  phone: '13900000001', primary_doctor: doctor.id, notes: '每周复诊',
  treatment_plan: { id: '50000000-0000-0000-0000-000000000001', start_date: '2026-08-01', cycle_weeks: 4, target_session_count: 12, status: 'active' },
}

function envelope<T>(data: T, requestId = 'patient-request') {
  return { code: 'ok', message: '', data, request_id: requestId }
}

function list<T>(results: T[]) {
  return { count: results.length, page: 1, page_size: 20, results }
}

function authenticate() {
  useAuthStore.setState({ accessToken: 'valid', user: admin, status: 'authenticated' })
}

function usePatientHandlers(results = [patient]) {
  server.use(
    http.get('/api/v1/admin/patients/', () => HttpResponse.json(envelope(list(results)))),
    http.get('/api/v1/admin/doctors/', () => HttpResponse.json(envelope(list([doctor])))),
  )
}

describe('病人管理页面', () => {
  it('把患者筛选映射为真实查询并在筛选变化时重置页码', async () => {
    authenticate()
    usePatientHandlers()
    const user = userEvent.setup()
    renderApp(`/patients?page=3&page_size=20&search=%E7%94%B2&status=active&doctor=${doctor.id}`)

    const patientName = await screen.findByText('患者甲')
    expect(within(patientName.closest('tr') as HTMLTableRowElement).getByText('进行中')).toBeInTheDocument()
    expect(server.calls('/api/v1/admin/patients/').at(-1)?.search).toBe(
      `?page=3&page_size=20&keyword=%E7%94%B2&status=active&primary_doctor=${doctor.id}`,
    )
    await user.clear(screen.getByLabelText('搜索患者'))
    await user.type(screen.getByLabelText('搜索患者'), '乙')
    await user.click(screen.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(server.calls('/api/v1/admin/patients/').at(-1)?.search).toContain('page=1'))
    expect(server.calls('/api/v1/admin/patients/').at(-1)?.search).toContain('keyword=%E4%B9%99')
  })

  it('新增患者提交真实档案与当前治疗计划字段', async () => {
    authenticate()
    usePatientHandlers([])
    server.use(http.post('/api/v1/admin/patients/', () => HttpResponse.json(envelope(patient), { status: 201 })))
    const user = userEvent.setup()
    renderApp('/patients')

    await user.click(await screen.findByRole('button', { name: '新增患者' }))
    expect(screen.getByLabelText('病历号')).toBeDisabled()
    await user.type(screen.getByLabelText('姓名'), '患者甲')
    await user.selectOptions(screen.getByLabelText('性别'), 'female')
    await user.type(screen.getByLabelText('入组年龄'), '32')
    await user.type(screen.getByLabelText('手机号'), '13900000001')
    await user.selectOptions(screen.getByLabelText('主治医生'), doctor.id)
    await user.type(screen.getByLabelText('治疗开始日期'), '2026-08-01')
    await user.type(screen.getByLabelText('治疗周期（周）'), '4')
    await user.type(screen.getByLabelText('备注'), '每周复诊')
    await user.click(screen.getByRole('button', { name: '确定' }))

    await waitFor(() => expect(server.lastJson('/api/v1/admin/patients/')).toEqual({
      name: '患者甲', gender: 'female', enrollment_age: 32, phone: '13900000001',
      primary_doctor: doctor.id, start_date: '2026-08-01', cycle_weeks: 4, notes: '每周复诊',
    }))
    expect(await screen.findByText(/初始密码为 888888.*首次登录需修改/)).toBeInTheDocument()
  })

  it('编辑只更新当前计划字段且服务端字段错误保留输入', async () => {
    authenticate()
    usePatientHandlers()
    server.use(http.patch('/api/v1/admin/patients/:id/', () => HttpResponse.json({
      code: 'validation_error', message: '输入有误', data: { cycle_weeks: ['周期超出范围'] }, request_id: 'patient-field-error',
    }, { status: 400 })))
    const user = userEvent.setup()
    renderApp('/patients')

    await user.click(await screen.findByRole('button', { name: '编辑患者甲' }))
    expect(screen.getByLabelText('病历号')).toHaveValue('P000001')
    await user.clear(screen.getByLabelText('治疗周期（周）'))
    await user.type(screen.getByLabelText('治疗周期（周）'), '6')
    await user.click(screen.getByRole('button', { name: '确定' }))

    expect(await screen.findByText('周期超出范围')).toBeInTheDocument()
    expect(screen.getByLabelText('治疗周期（周）')).toHaveValue('6')
    expect(screen.getByText('请求编号：patient-field-error')).toBeInTheDocument()
    expect(server.lastJson(`/api/v1/admin/patients/${patient.id}/`)).toMatchObject({ cycle_weeks: 6 })
  }, 20_000)

  it('删除确认明确保留历史，成功后再从列表移除', async () => {
    authenticate()
    usePatientHandlers()
    server.use(http.delete('/api/v1/admin/patients/:id/', () => new HttpResponse(null, { status: 204 })))
    const user = userEvent.setup()
    renderApp('/patients')

    await user.click(await screen.findByRole('button', { name: '删除患者甲' }))
    expect(screen.getByText(/历史治疗和演唱数据将保留/)).toBeInTheDocument()
    expect(screen.getByText(/停用并隐藏/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '确认删除' }))

    await waitFor(() => expect(server.calls(`/api/v1/admin/patients/${patient.id}/`)).toHaveLength(1))
    expect(await screen.findByText('患者已停用并隐藏，历史数据已保留')).toBeInTheDocument()
  }, 20_000)

  it('取消旧查询并避免乱序响应覆盖新筛选结果', async () => {
    authenticate()
    let oldAborted = false
    server.use(
      http.get('/api/v1/admin/doctors/', () => HttpResponse.json(envelope(list([doctor])))),
      http.get('/api/v1/admin/patients/', async ({ request }) => {
        const keyword = new URL(request.url).searchParams.get('keyword')
        if (keyword === '旧') {
          request.signal.addEventListener('abort', () => { oldAborted = true })
          await delay(250)
          return HttpResponse.json(envelope(list([{ ...patient, name: '旧患者' }])))
        }
        return HttpResponse.json(envelope(list([{ ...patient, name: '新患者' }])))
      }),
    )
    const user = userEvent.setup()
    renderApp('/patients?search=%E6%97%A7')

    await user.clear(screen.getByLabelText('搜索患者'))
    await user.type(screen.getByLabelText('搜索患者'), '新')
    await user.click(screen.getByRole('button', { name: '搜索' }))

    expect(await screen.findByText('新患者')).toBeInTheDocument()
    await waitFor(() => expect(oldAborted).toBe(true))
    expect(screen.queryByText('旧患者')).not.toBeInTheDocument()
  })
})
