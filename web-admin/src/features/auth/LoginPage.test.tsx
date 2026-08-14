import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { apiRequest } from '../../api/client'
import { useAuthStore } from '../../auth/store'
import { clearVisibleTestCookies } from '../../test/cookies'
import { renderApp } from '../../test/renderApp'
import { server } from '../../test/server'

async function settlesWithin(promise: Promise<unknown>, milliseconds = 20): Promise<boolean> {
  return Promise.race([
    promise.then(() => true),
    new Promise<false>((resolve) => window.setTimeout(() => resolve(false), milliseconds)),
  ])
}

describe('后台认证', () => {
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

  it('React StrictMode重复effect只启动一次会话恢复', async () => {
    useAuthStore.setState({ status: 'booting', accessToken: null, user: null })
    server.useRefresh({
      access: 'strict-restored-access',
      refresh_expires_at: '2026-08-15T00:00:00Z',
      user: { login_id: 'D000001', role: 'doctor', must_change_password: false },
    })

    renderApp('/doctors', { strict: true })

    expect(await screen.findByRole('heading', { name: '医生管理' })).toBeInTheDocument()
    expect(server.calls('/api/v1/auth/refresh/')).toHaveLength(1)
  })

  it('患者账号不能进入医生后台', async () => {
    server.useLogin({
      access: 'patient-access',
      refresh_expires_at: '2026-08-15T00:00:00Z',
      user: { login_id: 'P000001', role: 'patient', must_change_password: false },
    })
    server.useLogout()
    const user = userEvent.setup()
    renderApp('/login')

    await user.type(screen.getByLabelText('账号'), 'P000001')
    await user.type(screen.getByLabelText('密码'), 'patient-password')
    await user.click(screen.getByRole('button', { name: '登录' }))

    expect(await screen.findByText('患者账号不能登录医生后台')).toBeInTheDocument()
    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', accessToken: null, user: null })
    await waitFor(() => expect(server.calls('/api/v1/auth/logout/')).toHaveLength(1))
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

  it('测试请求记录器在MSW处理请求前完成请求体记录', async () => {
    const probe = server.useLoginWithRecorderProbe({
      access: 'recorded-access',
      refresh_expires_at: '2026-09-15T00:00:00Z',
      user: { login_id: 'recorded-doctor', role: 'doctor', must_change_password: false },
    })

    await useAuthStore.getState().login({
      login_id: 'recorded-doctor', password: 'recorded-password', remember_me: false,
    })

    expect(probe.recordedAtHandler()).toEqual({
      login_id: 'recorded-doctor',
      password: 'recorded-password',
      client_kind: 'web',
      remember_me: false,
    })
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

  it('退出立即失效本地会话且延迟刷新不能恢复或重放原请求', async () => {
    useAuthStore.setState({
      accessToken: 'expired',
      user: { login_id: 'A000001', role: 'system_admin', must_change_password: false },
      status: 'authenticated',
    })
    server.useConcurrentUnauthorizedResources()
    const delayed = server.useDeferredRefresh({
      access: 'stale-refreshed-access',
      refresh_expires_at: '2026-08-15T00:00:00Z',
      user: { login_id: 'A000001', role: 'system_admin', must_change_password: false },
    })
    const trackedLogout = server.useTrackedLogout()
    const outcome = apiRequest('/v1/admin/test-resource/1/').catch((error: unknown) => error)
    await delayed.started

    const logout = useAuthStore.getState().logout()
    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', accessToken: null, user: null })
    expect(await settlesWithin(trackedLogout.started)).toBe(false)
    delayed.release()
    await trackedLogout.started
    await logout

    await expect(outcome).resolves.toMatchObject({ code: 'session_changed' })
    expect(server.calls('/api/v1/admin/test-resource/1/')).toHaveLength(1)
    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', accessToken: null, user: null })
  })

  it('延迟刷新不能覆盖之后完成的新登录', async () => {
    useAuthStore.setState({
      accessToken: 'old-access',
      user: { login_id: 'old-doctor', role: 'doctor', must_change_password: false },
      status: 'authenticated',
    })
    const delayed = server.useDeferredRefresh({
      access: 'stale-access',
      refresh_expires_at: '2026-08-15T00:00:00Z',
      user: { login_id: 'old-doctor', role: 'doctor', must_change_password: false },
    })
    const refreshOutcome = useAuthStore.getState().refresh().catch((error: unknown) => error)
    await delayed.started
    const trackedLogin = server.useTrackedLogin({
      access: 'new-access',
      refresh_expires_at: '2026-09-15T00:00:00Z',
      user: { login_id: 'new-doctor', role: 'doctor', must_change_password: false },
    })

    const login = useAuthStore.getState().login({
      login_id: 'new-doctor', password: 'new-password', remember_me: false,
    })
    expect(await settlesWithin(trackedLogin.started)).toBe(false)
    delayed.release()
    await login
    await refreshOutcome

    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'new-access',
      user: { login_id: 'new-doctor' },
      status: 'authenticated',
    })
  })

  it('旧登录响应不能覆盖之后完成的新登录', async () => {
    const delayed = server.useDeferredLogin({
      access: 'old-access',
      refresh_expires_at: '2026-08-15T00:00:00Z',
      user: { login_id: 'old-doctor', role: 'doctor', must_change_password: false },
    })
    const oldLogin = useAuthStore.getState().login({
      login_id: 'old-doctor', password: 'old-password', remember_me: false,
    })
    await delayed.started
    const trackedLogin = server.useTrackedLogin({
      access: 'new-access',
      refresh_expires_at: '2026-09-15T00:00:00Z',
      user: { login_id: 'new-doctor', role: 'doctor', must_change_password: false },
    })

    const newLogin = useAuthStore.getState().login({
      login_id: 'new-doctor', password: 'new-password', remember_me: false,
    })
    expect(await settlesWithin(trackedLogin.started)).toBe(false)
    delayed.release()
    await newLogin
    await oldLogin

    expect(useAuthStore.getState()).toMatchObject({ accessToken: 'new-access', user: { login_id: 'new-doctor' } })
  })

  it('退出网络失败也不恢复会话或抛出未处理错误', async () => {
    useAuthStore.setState({
      accessToken: 'valid',
      user: { login_id: 'A000001', role: 'system_admin', must_change_password: false },
      status: 'authenticated',
    })
    server.useLogoutNetworkFailure()

    await expect(useAuthStore.getState().logout()).resolves.toBeUndefined()
    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', accessToken: null, user: null })
  })

  it('认证队列中的失败不会阻塞随后登录', async () => {
    useAuthStore.setState({
      accessToken: 'valid',
      user: { login_id: 'old-doctor', role: 'doctor', must_change_password: false },
      status: 'authenticated',
    })
    server.useLogoutNetworkFailure()
    await useAuthStore.getState().logout()
    server.useLogin({
      access: 'new-access',
      refresh_expires_at: '2026-09-15T00:00:00Z',
      user: { login_id: 'new-doctor', role: 'doctor', must_change_password: false },
    })

    await useAuthStore.getState().login({
      login_id: 'new-doctor', password: 'new-password', remember_me: false,
    })

    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'new-access', user: { login_id: 'new-doctor' }, status: 'authenticated',
    })
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

  it('稳定映射网络错误和请求取消', async () => {
    server.useNetworkError()
    await expect(apiRequest('/v1/admin/network-error/')).rejects.toMatchObject({ code: 'network_error' })

    const controller = new AbortController()
    const delayed = server.useDelayedResource()
    const request = apiRequest('/v1/admin/delayed/', { signal: controller.signal })
    controller.abort()
    delayed.release()
    await expect(request).rejects.toMatchObject({ code: 'request_aborted' })
  })

  it('测试隔离会清除认证路径上的所有可见Cookie', () => {
    window.history.replaceState(null, '', '/api/v1/auth/test')
    document.cookie = 'root_cookie=1; path=/'
    document.cookie = 'api_cookie=1; path=/api'
    document.cookie = 'v1_cookie=1; path=/api/v1'
    document.cookie = 'auth_cookie=1; path=/api/v1/auth'
    expect(document.cookie).toContain('auth_cookie=1')

    clearVisibleTestCookies()

    expect(document.cookie).toBe('')
    window.history.replaceState(null, '', '/')
  })

  it('新登录后拒绝在旧会话启动的延迟资源成功响应', async () => {
    useAuthStore.setState({
      accessToken: 'old-access',
      user: { login_id: 'old-doctor', role: 'doctor', must_change_password: false },
      status: 'authenticated',
    })
    const delayed = server.useDelayedResource()
    const resource = apiRequest('/v1/admin/delayed/')
    await delayed.started
    server.useLogin({
      access: 'new-access',
      refresh_expires_at: '2026-09-15T00:00:00Z',
      user: { login_id: 'new-doctor', role: 'doctor', must_change_password: false },
    })

    await useAuthStore.getState().login({
      login_id: 'new-doctor', password: 'new-password', remember_me: false,
    })
    delayed.release()

    await expect(resource).rejects.toMatchObject({ code: 'session_changed' })
  })

  it('退出后拒绝在旧会话启动的延迟资源成功响应', async () => {
    useAuthStore.setState({
      accessToken: 'old-access',
      user: { login_id: 'old-doctor', role: 'doctor', must_change_password: false },
      status: 'authenticated',
    })
    const delayed = server.useDelayedResource()
    server.useLogout()
    const resource = apiRequest('/v1/admin/delayed/')
    await delayed.started

    await useAuthStore.getState().logout()
    delayed.release()

    await expect(resource).rejects.toMatchObject({ code: 'session_changed' })
  })

  it('新登录后拒绝旧会话401刷新重放的延迟成功响应', async () => {
    useAuthStore.setState({
      accessToken: 'expired-access',
      user: { login_id: 'old-doctor', role: 'doctor', must_change_password: false },
      status: 'authenticated',
    })
    server.useRefresh({
      access: 'refreshed-access',
      refresh_expires_at: '2026-08-15T00:00:00Z',
      user: { login_id: 'old-doctor', role: 'doctor', must_change_password: false },
    })
    const delayed = server.useUnauthorizedThenDeferredResource()
    const resource = apiRequest('/v1/admin/replayed/')
    await delayed.replayStarted
    server.useLogin({
      access: 'new-access',
      refresh_expires_at: '2026-09-15T00:00:00Z',
      user: { login_id: 'new-doctor', role: 'doctor', must_change_password: false },
    })

    await useAuthStore.getState().login({
      login_id: 'new-doctor', password: 'new-password', remember_me: false,
    })
    delayed.release()

    await expect(resource).rejects.toMatchObject({ code: 'session_changed' })
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
