import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'

import { apiRequest } from '../../api/client'
import { useAuthStore } from '../../auth/store'
import { renderApp } from '../../test/renderApp'
import { server } from '../../test/server'

describe('后台认证', () => {
  afterEach(() => {
    act(() => useAuthStore.getState().reset())
    server.reset()
    window.localStorage.clear()
    window.sessionStorage.clear()
  })

  it('首次登录用户只能进入修改初始密码', async () => {
    useAuthStore.setState({
      accessToken: 'first-login-token',
      user: { login_id: 'D000001', role: 'doctor', must_change_password: true },
      status: 'authenticated',
    })

    renderApp('/doctors')

    expect(await screen.findByRole('heading', { name: '修改初始密码' })).toBeInTheDocument()
  })

  it('未登录访问后台时跳转到登录页', async () => {
    useAuthStore.setState({ status: 'anonymous', accessToken: null, user: null })
    renderApp('/doctors')
    expect(await screen.findByRole('heading', { name: '登录 VocaEase' })).toBeInTheDocument()
  })

  it('启动时通过刷新Cookie恢复医生后台会话', async () => {
    useAuthStore.setState({ status: 'booting', accessToken: null, user: null })
    server.useRefresh({
      access: 'restored-access',
      refresh_expires_at: '2026-08-15T00:00:00Z',
      user: { login_id: 'D000001', role: 'doctor', must_change_password: false },
    })

    renderApp('/doctors')

    expect(await screen.findByRole('heading', { name: '医生管理' })).toBeInTheDocument()
    expect(server.calls('/api/v1/auth/refresh/')).toHaveLength(1)
  })

  it('患者账号不能进入医生后台', async () => {
    server.useLogin({
      access: 'patient-access',
      refresh_expires_at: '2026-08-15T00:00:00Z',
      user: { login_id: 'P000001', role: 'patient', must_change_password: false },
    })
    const user = userEvent.setup()
    renderApp('/login')

    await user.type(screen.getByLabelText('账号'), 'P000001')
    await user.type(screen.getByLabelText('密码'), 'patient-password')
    await user.click(screen.getByRole('button', { name: '登录' }))

    expect(await screen.findByText('患者账号不能登录医生后台')).toBeInTheDocument()
    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', accessToken: null, user: null })
  })

  it('登录后发送web客户端类型和记住我语义且不持久化access token', async () => {
    server.useLogin({
      access: 'memory-only-access',
      refresh_expires_at: '2026-09-13T00:00:00Z',
      user: { login_id: 'A000001', role: 'system_admin', must_change_password: false },
    })
    const user = userEvent.setup()
    renderApp('/login')

    await user.type(screen.getByLabelText('账号'), 'A000001')
    await user.type(screen.getByLabelText('密码'), 'secure-password')
    await user.click(screen.getByRole('checkbox', { name: '记住我' }))
    await user.click(screen.getByRole('button', { name: '登录' }))

    await screen.findByRole('heading', { name: '医生管理' })
    expect(server.lastJson('/api/v1/auth/login/')).toEqual({
      login_id: 'A000001',
      password: 'secure-password',
      client_kind: 'web',
      remember_me: true,
    })
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })

  it('并发401只刷新一次且每个请求最多重放一次', async () => {
    useAuthStore.setState({
      accessToken: 'expired',
      user: { login_id: 'A000001', role: 'system_admin', must_change_password: false },
      status: 'authenticated',
    })
    server.useConcurrentUnauthorizedResources()
    server.useRefresh({
      access: 'renewed',
      refresh_expires_at: '2026-08-15T00:00:00Z',
      user: { login_id: 'A000001', role: 'system_admin', must_change_password: false },
    })

    const [first, second] = await Promise.all([
      apiRequest<{ id: number }>('/v1/admin/test-resource/1/'),
      apiRequest<{ id: number }>('/v1/admin/test-resource/2/'),
    ])

    expect(first.id).toBe(1)
    expect(second.id).toBe(2)
    expect(server.calls('/api/v1/auth/refresh/')).toHaveLength(1)
    expect(server.calls('/api/v1/admin/test-resource/1/')).toHaveLength(2)
    expect(server.calls('/api/v1/admin/test-resource/2/')).toHaveLength(2)
  })

  it('刷新失败时原子清空会话且不循环刷新认证端点', async () => {
    useAuthStore.setState({
      accessToken: 'expired',
      user: { login_id: 'A000001', role: 'system_admin', must_change_password: false },
      status: 'authenticated',
    })
    server.useAlwaysUnauthorizedResource()
    server.useRefreshFailure()

    await expect(apiRequest('/v1/admin/test-resource/')).rejects.toMatchObject({ code: 'token_not_valid' })
    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', accessToken: null, user: null })
    expect(server.calls('/api/v1/auth/refresh/')).toHaveLength(1)
  })

  it('refresh和logout都携带CSRF头', async () => {
    document.cookie = 'refresh_csrf_token=csrf-value; path=/'
    useAuthStore.setState({
      accessToken: 'valid',
      user: { login_id: 'A000001', role: 'system_admin', must_change_password: false },
      status: 'authenticated',
    })
    server.useRefresh({
      access: 'new',
      refresh_expires_at: '2026-08-15T00:00:00Z',
      user: { login_id: 'A000001', role: 'system_admin', must_change_password: false },
    })
    server.useLogout()

    await useAuthStore.getState().refresh()
    await useAuthStore.getState().logout()

    expect(server.calls('/api/v1/auth/refresh/')[0]?.headers.get('x-csrftoken')).toBe('csrf-value')
    expect(server.calls('/api/v1/auth/logout/')[0]?.headers.get('x-csrftoken')).toBe('csrf-value')
  })

  it('保留非JSON错误的请求追踪信息', async () => {
    server.useNonJsonError()
    await expect(apiRequest('/v1/admin/non-json/')).rejects.toEqual(
      expect.objectContaining({
        code: 'http_error',
        message: '服务暂时不可用',
        requestId: 'request-from-header',
      }),
    )
  })

  it('改密成功后清空会话并要求重新登录', async () => {
    useAuthStore.setState({
      accessToken: 'first-login-token',
      user: { login_id: 'D000001', role: 'doctor', must_change_password: true },
      status: 'authenticated',
    })
    server.useChangePassword()
    const user = userEvent.setup()
    renderApp('/change-password')

    await user.type(screen.getByLabelText('当前密码'), '888888')
    await user.type(screen.getByLabelText('新密码'), 'new-secure-password')
    await user.type(screen.getByLabelText('确认新密码'), 'new-secure-password')
    await user.click(screen.getByRole('button', { name: '确认修改' }))

    await screen.findByRole('heading', { name: '登录 VocaEase' })
    await waitFor(() => expect(useAuthStore.getState().status).toBe('anonymous'))
    expect(server.lastJson('/api/v1/auth/change-password/')).toEqual({
      old_password: '888888',
      new_password: 'new-secure-password',
    })
  })
})
