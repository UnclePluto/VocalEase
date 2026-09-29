export type ApiEnvelope<T> = {
  code: string
  message: string
  data: T
  request_id: string
}

export type AccountRole = 'system_admin' | 'doctor' | 'patient'

export type AccountSnapshot = {
  login_id: string
  role: AccountRole
  must_change_password: boolean
}

export type AuthPayload = {
  access: string
  refresh_expires_at: string
  user: AccountSnapshot
}

export type FieldErrors = Record<string, string | string[]>
