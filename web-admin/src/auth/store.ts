import { create } from 'zustand'

import { ApiError } from '../api/errors'
import { configureSessionBridge } from '../api/client'
import type { AccountSnapshot, AuthPayload } from '../api/types'
import { changeAccountPassword, loginAccount, logoutAccount, recoverAccount } from './api'

export type AuthStatus = 'booting' | 'anonymous' | 'authenticated'

type LoginInput = { login_id: string; password: string; remember_me: boolean }

type AuthState = {
  accessToken: string | null
  user: AccountSnapshot | null
  status: AuthStatus
  initialize: () => Promise<void>
  login: (input: LoginInput) => Promise<void>
  refresh: () => Promise<void>
  logout: () => Promise<void>
  changePassword: (input: { old_password: string; new_password: string }) => Promise<void>
  reset: () => void
}

const anonymous = { accessToken: null, user: null, status: 'anonymous' as const }

function assertBackofficeUser(payload: AuthPayload) {
  if (payload.user.role === 'patient') {
    throw new ApiError('admin_access_denied', '患者账号不能登录医生后台')
  }
}

export const useAuthStore = create<AuthState>((set, get) => ({
  accessToken: null,
  user: null,
  status: 'booting',
  initialize: async () => {
    if (get().status !== 'booting') return
    try {
      const payload = await recoverAccount()
      assertBackofficeUser(payload)
      set({ accessToken: payload.access, user: payload.user, status: 'authenticated' })
    } catch {
      set(anonymous)
    }
  },
  login: async (input) => {
    const payload = await loginAccount(input)
    try {
      assertBackofficeUser(payload)
    } catch (error) {
      set(anonymous)
      throw error
    }
    set({ accessToken: payload.access, user: payload.user, status: 'authenticated' })
  },
  refresh: async () => {
    const payload = await recoverAccount()
    assertBackofficeUser(payload)
    set({ accessToken: payload.access, user: payload.user, status: 'authenticated' })
  },
  logout: async () => {
    try {
      await logoutAccount()
    } finally {
      set(anonymous)
    }
  },
  changePassword: async (input) => {
    await changeAccountPassword(input)
    set(anonymous)
  },
  reset: () => set(anonymous),
}))

configureSessionBridge({
  getAccessToken: () => useAuthStore.getState().accessToken,
  acceptAuth: (payload) => {
    assertBackofficeUser(payload)
    useAuthStore.setState({
      accessToken: payload.access,
      user: payload.user,
      status: 'authenticated',
    })
  },
  clearSession: () => useAuthStore.setState(anonymous),
})
