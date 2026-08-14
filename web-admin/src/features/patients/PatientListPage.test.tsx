import { http, HttpResponse, delay } from 'msw'
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

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
  phone: '13900000001', primary_doctor: doctor.id, primary_doctor_name: '第101位医生', notes: '每周复诊',
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
    http.get('/api/v1/admin/patients/:id/', () => HttpResponse.json(envelope(patient))),
    http.get('/api/v1/admin/doctors/', () => HttpResponse.json(envelope(list([doctor])))),
    http.get('/api/v1/admin/singing-sessions/', () => HttpResponse.json(envelope({ count: 0, page: 1, page_size: 10, results: [] }))),
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

  it('同一渲染帧重复确认删除也只发送一次请求', async () => {
    authenticate()
    usePatientHandlers()
    server.use(http.delete('/api/v1/admin/patients/:id/', async () => {
      await delay(50)
      return new HttpResponse(null, { status: 204 })
    }))
    const user = userEvent.setup()
    renderApp('/patients')

    await user.click(await screen.findByRole('button', { name: '删除患者甲' }))
    const confirm = screen.getByRole('button', { name: '确认删除' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)

    await waitFor(() => expect(server.calls(`/api/v1/admin/patients/${patient.id}/`)).toHaveLength(1))
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

  it('患者行使用服务端医生姓名快照并导航到稳定患者数据路由', async () => {
    authenticate()
    usePatientHandlers()
    const user = userEvent.setup()
    renderApp('/patients')

    expect(await screen.findByText('第101位医生')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '查看患者甲数据' }))

    expect(await screen.findByRole('heading', { name: '患者数据' })).toBeInTheDocument()
    expect(screen.getByText(patient.id)).toBeInTheDocument()
  }, 20_000)

  it('编辑第101位医生患者时即使当前选项页不含该医生也稳定回显', async () => {
    authenticate()
    usePatientHandlers()
    const user = userEvent.setup()
    renderApp('/patients')

    await user.click(await screen.findByRole('button', { name: '编辑患者甲' }))

    const selected = screen.getByLabelText('主治医生') as HTMLSelectElement
    expect(selected).toHaveValue(patient.primary_doctor)
    expect(selected.selectedOptions[0]).toHaveTextContent('第101位医生')
  }, 20_000)

  it('编辑前读取患者详情并只把详情完整手机号和备注填入表单', async () => {
    authenticate()
    const privatePatient = { ...patient, phone: '13900000001', notes: '完整病情备注' }
    server.use(
      http.get('/api/v1/admin/patients/', () => HttpResponse.json(envelope(list([
        { ...patient, phone: '139****0001', notes: undefined },
      ])))),
      http.get('/api/v1/admin/patients/:id/', () => HttpResponse.json(envelope(privatePatient))),
      http.get('/api/v1/admin/doctors/', () => HttpResponse.json(envelope(list([doctor])))),
    )
    const user = userEvent.setup()
    renderApp('/patients')

    await user.dblClick(await screen.findByRole('button', { name: '编辑患者甲' }))

    expect(server.calls(`/api/v1/admin/patients/${patient.id}/`)).toHaveLength(1)
    expect(await screen.findByRole('dialog', { name: '编辑患者' })).toBeInTheDocument()
    expect(screen.getByLabelText('手机号')).toHaveValue('13900000001')
    expect(screen.getByLabelText('备注')).toHaveValue('完整病情备注')
  }, 20_000)

  it('表单内部显示医生选项错误请求编号并可重试', async () => {
    authenticate()
    let attempt = 0
    server.use(
      http.get('/api/v1/admin/patients/', () => HttpResponse.json(envelope(list([])))),
      http.get('/api/v1/admin/doctors/', () => {
        attempt += 1
        if (attempt === 1) return HttpResponse.json({
          code: 'doctor_options_failed', message: '医生选项加载失败', data: null, request_id: 'modal-options-1',
        }, { status: 500 })
        return HttpResponse.json(envelope(list([doctor])))
      }),
    )
    const user = userEvent.setup()
    renderApp('/patients')

    await user.click(await screen.findByRole('button', { name: '新增患者' }))
    const dialog = screen.getByRole('dialog', { name: '新增患者' })
    expect(within(dialog).getByText('医生选项加载失败')).toBeInTheDocument()
    expect(within(dialog).getByText('请求编号：modal-options-1')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '重试医生选项' }))

    expect(await within(dialog).findByRole('option', { name: /王医生/ })).toBeInTheDocument()
  }, 20_000)

  it('表单内部每次手动加载一页医生且第21位可选择', async () => {
    authenticate()
    server.use(
      http.get('/api/v1/admin/patients/', () => HttpResponse.json(envelope(list([])))),
      http.get('/api/v1/admin/doctors/', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'))
        return HttpResponse.json(envelope({
          count: 21,
          page,
          page_size: 20,
          results: page === 1 ? [doctor] : [{ ...doctor, id: '10000000-0000-0000-0000-000000000021', name: '第21位医生' }],
        }))
      }),
    )
    const user = userEvent.setup()
    renderApp('/patients')

    await user.click(await screen.findByRole('button', { name: '新增患者' }))
    const dialog = screen.getByRole('dialog', { name: '新增患者' })
    await user.click(await within(dialog).findByRole('button', { name: '加载更多医生' }))

    expect(server.calls('/api/v1/admin/doctors/').map((call) => call.search)).toEqual([
      '?page=1&page_size=20&status=active',
      '?page=2&page_size=20&status=active',
    ])
    expect(await within(dialog).findByRole('option', { name: /第21位医生/ })).toBeInTheDocument()
  }, 20_000)

  it('深链医生筛选在患者为空且医生不在第一页时通过详情回显姓名', async () => {
    authenticate()
    const selected = { ...doctor, id: '10000000-0000-0000-0000-000000000021', name: '第21位医生' }
    server.use(
      http.get('/api/v1/admin/patients/', () => HttpResponse.json(envelope(list([])))),
      http.get('/api/v1/admin/doctors/', () => HttpResponse.json(envelope(list([doctor])))),
      http.get('/api/v1/admin/doctors/:id/option/', () => HttpResponse.json(envelope({
        id: selected.id,
        name: selected.name,
        employee_no: selected.employee_no,
      }))),
    )

    renderApp(`/patients?doctor=${selected.id}`)

    const filter = await screen.findByRole('combobox', { name: '主治医生筛选' })
    await waitFor(() => expect(filter).toHaveAccessibleName('主治医生筛选'))
    await waitFor(() => expect(screen.getAllByText(/第21位医生/)).toHaveLength(1))
    expect(screen.queryByText(selected.id)).not.toBeInTheDocument()
    expect(server.calls(`/api/v1/admin/doctors/${selected.id}/option/`)).toHaveLength(1)
    expect(server.calls(`/api/v1/admin/doctors/${selected.id}/`)).toHaveLength(0)
  }, 20_000)

  it('原始搜索词变化时立即隔离旧医生并仅在防抖后请求新关键词', async () => {
    authenticate()
    let releaseSearch: () => void = () => undefined
    const searchGate = new Promise<void>((resolve) => { releaseSearch = resolve })
    server.use(
      http.get('/api/v1/admin/patients/', () => HttpResponse.json(envelope(list([])))),
      http.get('/api/v1/admin/doctors/', async ({ request }) => {
        const keyword = new URL(request.url).searchParams.get('keyword')
        if (keyword === '新') {
          await searchGate
          return HttpResponse.json(envelope(list([{ ...doctor, id: `${doctor.id.slice(0, -1)}2`, name: '新医生' }])))
        }
        return HttpResponse.json(envelope(list([doctor])))
      }),
    )
    const user = userEvent.setup()
    renderApp('/patients')
    await user.click(await screen.findByRole('button', { name: '新增患者' }))
    const dialog = screen.getByRole('dialog', { name: '新增患者' })
    expect(await within(dialog).findByRole('option', { name: /王医生/ })).toBeInTheDocument()

    vi.useFakeTimers()
    fireEvent.change(within(dialog).getByLabelText('搜索主治医生选项'), { target: { value: '新' } })

    expect(within(dialog).queryByRole('option', { name: /王医生/ })).not.toBeInTheDocument()
    expect(within(dialog).getByLabelText('主治医生')).toBeDisabled()
    expect(within(dialog).getByText(/正在加载医生/)).toBeInTheDocument()
    expect(server.calls('/api/v1/admin/doctors/')).toHaveLength(1)
    await act(async () => { vi.advanceTimersByTime(249) })
    expect(server.calls('/api/v1/admin/doctors/')).toHaveLength(1)
    await act(async () => { vi.advanceTimersByTime(1); await Promise.resolve() })
    expect(server.calls('/api/v1/admin/doctors/')).toHaveLength(2)
    expect(within(dialog).getByLabelText('主治医生')).toBeDisabled()

    releaseSearch()
    vi.useRealTimers()
    expect(await within(dialog).findByRole('option', { name: /新医生/ })).toBeInTheDocument()
  }, 20_000)

  it('回切已加载两页的搜索词只重新请求第一页且不会自动恢复第二页', async () => {
    authenticate()
    server.use(
      http.get('/api/v1/admin/patients/', () => HttpResponse.json(envelope(list([])))),
      http.get('/api/v1/admin/doctors/', ({ request }) => {
        const url = new URL(request.url)
        const keyword = url.searchParams.get('keyword') ?? ''
        const page = Number(url.searchParams.get('page'))
        const pageDoctor = { ...doctor, id: `${doctor.id.slice(0, -1)}${page}` }
        return HttpResponse.json(envelope({ count: keyword === '甲' ? 21 : 1, page, page_size: 20, results: [pageDoctor] }))
      }),
    )
    const user = userEvent.setup()
    renderApp('/patients')

    const filter = await screen.findByRole('combobox', { name: '主治医生筛选' })
    await user.click(filter)
    await user.type(filter, '甲')
    await user.click(await screen.findByRole('button', { name: '加载更多医生' }))
    await waitFor(() => expect(server.calls('/api/v1/admin/doctors/').at(-1)?.search).toContain('page=2'))
    await user.clear(filter)
    await user.type(filter, '乙')
    await waitFor(() => expect(server.calls('/api/v1/admin/doctors/').at(-1)?.search).toContain('keyword=%E4%B9%99'))
    await user.clear(filter)
    await user.type(filter, '甲')
    await waitFor(() => expect(server.calls('/api/v1/admin/doctors/').at(-1)?.search).toContain('keyword=%E7%94%B2'))

    const lastTwo = server.calls('/api/v1/admin/doctors/').slice(-2).map((call) => call.search)
    expect(lastTwo).toEqual([
      '?page=1&page_size=20&keyword=%E4%B9%99&status=active',
      '?page=1&page_size=20&keyword=%E7%94%B2&status=active',
    ])
  }, 20_000)

  it('主治医生选项使用服务端搜索且失败时显式提示并可重试', async () => {
    authenticate()
    let doctorAttempt = 0
    server.use(
      http.get('/api/v1/admin/patients/', () => HttpResponse.json(envelope(list([patient])))),
      http.get('/api/v1/admin/doctors/', ({ request }) => {
        doctorAttempt += 1
        if (doctorAttempt === 1) {
          return HttpResponse.json({
            code: 'doctor_options_failed', message: '医生选项加载失败', data: null, request_id: 'doctor-options-1',
          }, { status: 500 })
        }
        const keyword = new URL(request.url).searchParams.get('keyword')
        return HttpResponse.json(envelope(list(keyword === '第101' ? [{ ...doctor, name: '第101位医生' }] : [doctor])))
      }),
    )
    const user = userEvent.setup()
    renderApp('/patients')

    expect(await screen.findByText('医生选项加载失败')).toBeInTheDocument()
    expect(screen.getByText('请求编号：doctor-options-1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试医生选项' }))
    const doctorFilter = screen.getByRole('combobox', { name: '主治医生筛选' })
    await user.click(doctorFilter)
    await user.type(doctorFilter, '第101')

    await waitFor(() => {
      expect(server.calls('/api/v1/admin/doctors/').at(-1)?.search).toBe(
        '?page=1&page_size=20&keyword=%E7%AC%AC101&status=active',
      )
    })
    expect(await screen.findByRole('option', { name: /第101位医生/ })).toBeInTheDocument()
  })

  it('医生选项初始只取一页且搜索第101位不会顺序扫描中间页', async () => {
    authenticate()
    server.use(
      http.get('/api/v1/admin/patients/', () => HttpResponse.json(envelope(list([patient])))),
      http.get('/api/v1/admin/doctors/', ({ request }) => {
        const keyword = new URL(request.url).searchParams.get('keyword')
        return HttpResponse.json(envelope({
          count: keyword ? 1 : 101,
          page: 1,
          page_size: 20,
          results: keyword ? [{ ...doctor, name: '第101位医生' }] : [doctor],
        }))
      }),
    )
    const user = userEvent.setup()
    renderApp('/patients')

    expect(await screen.findByText('患者甲')).toBeInTheDocument()
    await waitFor(() => expect(server.calls('/api/v1/admin/doctors/')).toHaveLength(1))
    expect(server.calls('/api/v1/admin/doctors/')[0]?.search).toBe('?page=1&page_size=20&status=active')

    const doctorFilter = screen.getByRole('combobox', { name: '主治医生筛选' })
    await user.click(doctorFilter)
    await user.type(doctorFilter, '第101')

    await waitFor(() => expect(server.calls('/api/v1/admin/doctors/')).toHaveLength(2))
    expect(server.calls('/api/v1/admin/doctors/')[1]?.search).toBe(
      '?page=1&page_size=20&keyword=%E7%AC%AC101&status=active',
    )
  })

  it('患者角色不能直接进入患者数据占位路由', async () => {
    useAuthStore.setState({
      accessToken: 'valid',
      user: { login_id: 'P000001', role: 'patient', must_change_password: false },
      status: 'authenticated',
    })

    renderApp(`/patients/${patient.id}/data`)

    expect(await screen.findByRole('heading', { name: '登录 VocaEase' })).toBeInTheDocument()
  })

  it('医生选项可逐页请求到第101位且每页请求受限为20条', async () => {
    authenticate()
    server.use(
      http.get('/api/v1/admin/patients/', () => HttpResponse.json(envelope(list([patient])))),
      http.get('/api/v1/admin/doctors/', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'))
        const pageDoctor = {
          ...doctor,
          id: `10000000-0000-0000-0000-${String(page).padStart(12, '0')}`,
          name: page === 6 ? '第101位医生' : `第${page}页医生`,
        }
        return HttpResponse.json(envelope({ count: 101, page, page_size: 20, results: [pageDoctor] }))
      }),
    )
    const user = userEvent.setup()
    renderApp('/patients')

    const doctorFilter = await screen.findByRole('combobox', { name: '主治医生筛选' })
    await user.click(doctorFilter)
    for (let page = 2; page <= 6; page += 1) {
      await user.click(await screen.findByRole('button', { name: '加载更多医生' }))
      await waitFor(() => expect(server.calls('/api/v1/admin/doctors/').at(-1)?.search).toContain(`page=${page}&page_size=20`))
    }
    expect(server.calls('/api/v1/admin/doctors/').at(-1)?.search).toBe(
      '?page=6&page_size=20&status=active',
    )
  }, 20_000)

  it('390px视口以更多菜单保留患者数据入口和行操作', async () => {
    window.innerWidth = 390
    authenticate()
    usePatientHandlers()
    const user = userEvent.setup()

    renderApp('/patients')

    const more = await screen.findByRole('button', { name: '更多患者甲操作' })
    expect(screen.queryByRole('button', { name: '编辑患者甲' })).not.toBeInTheDocument()
    await user.click(more)
    expect(await screen.findByRole('menuitem', { name: '查看患者数据' })).toBeInTheDocument()
  })
})
