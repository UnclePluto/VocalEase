import { http, HttpResponse } from 'msw'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { useAuthStore } from '../../auth/store'
import { renderApp } from '../../test/renderApp'
import { server } from '../../test/server'

const admin = { login_id: 'A000001', role: 'system_admin' as const, must_change_password: false }
const doctor = {
  id: '10000000-0000-0000-0000-000000000001',
  user_id: '20000000-0000-0000-0000-000000000001',
  employee_no: 'D0001',
  name: '李静',
  gender: 'female' as const,
  phone: '13800000001',
  department: '消化内科',
  title: '副主任医师',
  status: 'active' as const,
}

function envelope<T>(data: T, requestId = 'doctor-request') {
  return { code: 'ok', message: '', data, request_id: requestId }
}

function doctorList(results = [doctor]) {
  return { count: results.length, page: 1, page_size: 20, results }
}

function authenticate(role: 'system_admin' | 'doctor' | 'patient' = 'system_admin') {
  useAuthStore.setState({
    accessToken: 'valid',
    user: { ...admin, role },
    status: 'authenticated',
  })
}

function useDoctorList(results = [doctor]) {
  server.use(http.get('/api/v1/admin/doctors/', () => HttpResponse.json(envelope(doctorList(results)))))
}

describe('医生管理页面', () => {
  it('把URL筛选作为唯一真相并把非法分页归一化', async () => {
    authenticate()
    useDoctorList()

    renderApp('/doctors?page=0&page_size=999&search=%E6%9D%8E&status=active&department=%E6%B6%88%E5%8C%96%E5%86%85%E7%A7%91')

    expect(await screen.findByText('李静')).toBeInTheDocument()
    const call = server.calls('/api/v1/admin/doctors/').at(-1)
    expect(call?.search).toBe('?page=1&page_size=20&keyword=%E6%9D%8E&status=active&department=%E6%B6%88%E5%8C%96%E5%86%85%E7%A7%91')
    expect(screen.getByLabelText('搜索医生')).toHaveValue('李')
  })

  it('提交真实扩展字段且成功提示固定初始密码与首次改密', async () => {
    authenticate()
    useDoctorList([])
    server.use(http.post('/api/v1/admin/doctors/', () => HttpResponse.json(envelope(doctor), { status: 201 })))
    const user = userEvent.setup()
    renderApp('/doctors')

    await user.click(await screen.findByRole('button', { name: '新增医生' }))
    await user.type(screen.getByLabelText('姓名'), '李静')
    await user.selectOptions(screen.getByLabelText('性别'), 'female')
    await user.type(screen.getByLabelText('手机号'), '13800000001')
    await user.type(screen.getByLabelText('科室'), '消化内科')
    await user.type(screen.getByLabelText('职称'), '副主任医师')
    await user.click(screen.getByRole('button', { name: '确定' }))

    await waitFor(() => expect(server.lastJson('/api/v1/admin/doctors/')).toEqual({
      name: '李静', gender: 'female', phone: '13800000001', department: '消化内科', title: '副主任医师',
    }))
    expect(await screen.findByText(/初始密码为 888888.*首次登录需修改/)).toBeInTheDocument()
  })

  it('字段错误保留输入且提交期间阻止双击', async () => {
    authenticate()
    useDoctorList([])
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    server.use(http.post('/api/v1/admin/doctors/', async () => {
      await gate
      return HttpResponse.json({
        code: 'validation_error', message: '输入有误', data: { phone: ['手机号已存在'] }, request_id: 'doctor-field-error',
      }, { status: 400 })
    }))
    const user = userEvent.setup()
    renderApp('/doctors')

    await user.click(await screen.findByRole('button', { name: '新增医生' }))
    await user.type(screen.getByLabelText('姓名'), '李静')
    await user.selectOptions(screen.getByLabelText('性别'), 'female')
    await user.type(screen.getByLabelText('手机号'), '13800000001')
    await user.type(screen.getByLabelText('科室'), '消化内科')
    await user.type(screen.getByLabelText('职称'), '医师')
    const submit = screen.getByRole('button', { name: '确定' })
    await user.dblClick(submit)
    expect(submit).toBeDisabled()
    expect(server.calls('/api/v1/admin/doctors/').filter((call) => call.method === 'POST')).toHaveLength(1)
    release()

    expect(await screen.findByText('手机号已存在')).toBeInTheDocument()
    expect(screen.getByLabelText('姓名')).toHaveValue('李静')
    expect(screen.getByText('请求编号：doctor-field-error')).toBeInTheDocument()
  })

  it('重置密码走真实账号接口，删除409保留行并展示服务端原因', async () => {
    authenticate('doctor')
    useDoctorList()
    server.use(
      http.post('/api/v1/admin/users/:id/reset-password/', () => HttpResponse.json(envelope({}))),
      http.delete('/api/v1/admin/doctors/:id/', () => HttpResponse.json({
        code: 'doctor_has_active_patients', message: '医生仍有在治患者，无法删除', data: null, request_id: 'doctor-delete-409',
      }, { status: 409 })),
    )
    const user = userEvent.setup()
    renderApp('/doctors')

    await user.click(await screen.findByRole('button', { name: '重置李静密码' }))
    expect(screen.getByText(/密码将恢复为 888888.*首次登录必须修改/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '确认重置' }))
    expect(await screen.findByText(/密码已重置为 888888/)).toBeInTheDocument()
    expect(server.calls(`/api/v1/admin/users/${doctor.user_id}/reset-password/`)).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: '删除李静' }))
    expect(screen.getByText(/停用并隐藏.*历史保留/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '确认删除' }))
    expect(await screen.findByText('医生仍有在治患者，无法删除')).toBeInTheDocument()
    expect(screen.getByText('李静')).toBeInTheDocument()
  }, 20_000)

  it('显示加载、错误请求编号、重试和空态', async () => {
    authenticate()
    let attempt = 0
    server.use(http.get('/api/v1/admin/doctors/', () => {
      attempt += 1
      if (attempt === 1) {
        return HttpResponse.json({ code: 'server_error', message: '暂时无法加载', data: null, request_id: 'load-req-1' }, { status: 500 })
      }
      return HttpResponse.json(envelope(doctorList([])))
    }))
    const user = userEvent.setup()
    renderApp('/doctors')

    expect(screen.getByRole('status', { name: '正在加载医生列表' })).toBeInTheDocument()
    expect(await screen.findByText('暂时无法加载')).toBeInTheDocument()
    expect(screen.getByText('请求编号：load-req-1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('暂无医生')).toBeInTheDocument()
  })

  it('患者角色不能进入后台医生页面', async () => {
    authenticate('patient')
    useDoctorList()

    renderApp('/doctors')

    expect(await screen.findByRole('heading', { name: '登录 VocaEase' })).toBeInTheDocument()
    expect(server.calls('/api/v1/admin/doctors/')).toHaveLength(0)
  })
})
