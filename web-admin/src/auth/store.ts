import { create } from 'zustand'

import { ApiError } from '../api/errors'
import { configureSessionBridge, invalidateAuthOperations } from '../api/client'
import type { AccountSnapshot, AuthPayload } from '../api/types'
import { changeAccountPassword, loginAccount, logoutAccount, recoverAccount } from './api'

export type AuthStatus = 'booting' | 'anonymous' | 'authenticated'

type LoginInput = { login_id: string; password: string; remember_me: boolean }

type SessionState = {
  accessToken: string | null
  user: AccountSnapshot | null
  status: AuthStatus
}

type AuthState = {
  accessToken: string | null
  user: AccountSnapshot | null
  status: AuthStatus
  sessionEpoch: number
  initialize: () => Promise<void>
  login: (input: LoginInput) => Promise<void>
  refresh: () => Promise<void>
  logout: () => Promise<void>
  changePassword: (input: { old_password: string; new_password: string }) => Promise<void>
  reset: () => void
}

const anonymous: SessionState = { accessToken: null, user: null, status: 'anonymous' }

function assertBackofficeUser(payload: AuthPayload) {
  if (payload.user.role === 'patient') {
    throw new ApiError('admin_access_denied', '患者账号不能登录医生后台')
  }
}

function bestEffortServerLogout(): Promise<void> {
  return logoutAccount().then(() => undefined).catch(() => undefined)
}

let initializationPromise: Promise<void> | null = null

export const useAuthStore = create<AuthState>((set, get) => {
  const advanceEpoch = (state: SessionState = anonymous) => {
    const epoch = get().sessionEpoch + 1
    set({ ...state, sessionEpoch: epoch })
    invalidateAuthOperations()
    return epoch
  }

  return {
    accessToken: null,
    user: null,
    status: 'booting',
    sessionEpoch: 0,
    initialize: () => {
      if (get().status !== 'booting') return Promise.resolve()
      if (initializationPromise) return initializationPromise
      const epoch = advanceEpoch({ accessToken: null, user: null, status: 'booting' })
      const task = recoverAccount(epoch)
        .then(() => undefined)
        .catch(() => {
          if (get().sessionEpoch === epoch) advanceEpoch()
        })
        .finally(() => {
          if (initializationPromise === task) initializationPromise = null
        })
      initializationPromise = task
      return task
    },
    login: async (input) => {
      const epoch = advanceEpoch()
      const payload = await loginAccount(input)
      if (get().sessionEpoch !== epoch) return
      try {
        assertBackofficeUser(payload)
      } catch (error) {
        advanceEpoch()
        await bestEffortServerLogout()
        throw error
      }
      set({ accessToken: payload.access, user: payload.user, status: 'authenticated' })
    },
    refresh: async () => {
      const epoch = get().sessionEpoch
      await recoverAccount(epoch)
    },
    logout: async () => {
      advanceEpoch()
      await bestEffortServerLogout()
    },
    changePassword: async (input) => {
      const epoch = get().sessionEpoch
      await changeAccountPassword(input)
      if (get().sessionEpoch === epoch) advanceEpoch()
    },
    reset: () => {
      initializationPromise = null
      advanceEpoch()
    },
  }
})

configureSessionBridge({
  getSession: () => {
    const state = useAuthStore.getState()
    return { accessToken: state.accessToken, epoch: state.sessionEpoch }
  },
  acceptAuth: (payload, expectedEpoch) => {
    if (useAuthStore.getState().sessionEpoch !== expectedEpoch) return false
    assertBackofficeUser(payload)
    useAuthStore.setState({
      accessToken: payload.access,
      user: payload.user,
      status: 'authenticated',
    })
    return true
  },
  clearSession: (expectedEpoch) => {
    const state = useAuthStore.getState()
    if (state.sessionEpoch !== expectedEpoch) return false
    useAuthStore.setState({ ...anonymous, sessionEpoch: expectedEpoch + 1 })
    invalidateAuthOperations()
    return true
  },
  rejectServerSession: () => { void bestEffortServerLogout() },
})
