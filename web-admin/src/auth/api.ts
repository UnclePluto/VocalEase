import { apiRequest, csrfRequestHeaders, refreshSession } from '../api/client'
import type { AuthPayload } from '../api/types'

export function loginAccount(input: { login_id: string; password: string; remember_me: boolean }) {
  return apiRequest<AuthPayload>('/v1/auth/login/', {
    method: 'POST',
    body: JSON.stringify({ ...input, client_kind: 'web' }),
  })
}

export function recoverAccount() {
  return refreshSession()
}

export function logoutAccount() {
  return apiRequest<Record<string, never>>('/v1/auth/logout/', {
    method: 'POST',
    headers: csrfRequestHeaders(),
    body: JSON.stringify({ client_kind: 'web' }),
  })
}

export function changeAccountPassword(input: { old_password: string; new_password: string }) {
  return apiRequest<Record<string, never>>('/v1/auth/change-password/', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}
