import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'

import { useAuthStore } from '../auth/store'
import { renderApp } from '../test/renderApp'
import { server } from '../test/server'

const admin = { login_id: 'A000001', role: 'system_admin' as const, must_change_password: false }

describe('后台布局', () => {
  beforeEach(() => { server.useEmptyDoctorList(); server.useEmptySongList() })
  it('呈现视觉稿中的六个导航标签', async () => {
    useAuthStore.setState({ accessToken: 'valid', user: admin, status: 'authenticated' })
    renderApp('/doctors')

    const navigation = await screen.findByRole('navigation', { name: '后台主导航' })
    for (const label of ['账号管理', '医生管理', '病人管理', '曲库管理', '病人数据', '数据管理']) {
      expect(within(navigation).getByText(label)).toBeInTheDocument()
    }
  })

  it('从导航进入曲库管理页并显示选中状态', async () => {
    useAuthStore.setState({ accessToken: 'valid', user: admin, status: 'authenticated' })
    const user = userEvent.setup()
    renderApp('/doctors')

    await user.click(await screen.findByRole('link', { name: /曲库管理/ }))

    expect(await screen.findByRole('heading', { name: '曲库管理' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /曲库管理/ })).toHaveAttribute('aria-current', 'page')
  })

  it('可从顶部退出并返回登录页', async () => {
    useAuthStore.setState({ accessToken: 'valid', user: admin, status: 'authenticated' })
    server.useLogout()
    const user = userEvent.setup()
    renderApp('/doctors')

    await user.click(await screen.findByRole('button', { name: '退出登录' }))

    expect(await screen.findByRole('heading', { name: '登录 VocaEase' })).toBeInTheDocument()
    expect(useAuthStore.getState().status).toBe('anonymous')
  })

  it('离开移动断点时关闭抽屉且返回移动端不会自动重开', async () => {
    window.innerWidth = 390
    useAuthStore.setState({ accessToken: 'valid', user: admin, status: 'authenticated' })
    const user = userEvent.setup()
    renderApp('/doctors')
    await user.click(await screen.findByRole('button', { name: '打开导航菜单' }))
    expect(await screen.findByRole('navigation', { name: '移动端后台主导航' })).toBeInTheDocument()

    act(() => {
      window.innerWidth = 1024
      fireEvent(window, new Event('resize'))
    })
    await waitFor(() => expect(screen.queryByRole('navigation', { name: '移动端后台主导航' })).not.toBeInTheDocument())
    act(() => {
      window.innerWidth = 390
      fireEvent(window, new Event('resize'))
    })

    expect(await screen.findByRole('button', { name: '打开导航菜单' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: '移动端后台主导航' })).not.toBeInTheDocument()
  })
})
