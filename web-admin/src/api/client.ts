import { ApiError } from './errors'
import type { ApiEnvelope, AuthPayload, FieldErrors } from './types'

type SessionBridge = {
  getAccessToken: () => string | null
  acceptAuth: (payload: AuthPayload) => void
  clearSession: () => void
}

const API_PREFIX = '/api'
const AUTH_PREFIX = '/v1/auth/'
let bridge: SessionBridge = {
  getAccessToken: () => null,
  acceptAuth: () => undefined,
  clearSession: () => undefined,
}
let refreshPromise: Promise<AuthPayload> | null = null

export function configureSessionBridge(nextBridge: SessionBridge) {
  bridge = nextBridge
}

function readCookie(name: string): string {
  const prefix = `${encodeURIComponent(name)}=`
  const part = document.cookie.split('; ').find((item) => item.startsWith(prefix))
  return part ? decodeURIComponent(part.slice(prefix.length)) : ''
}

function csrfHeaders(): HeadersInit {
  const csrf = readCookie('refresh_csrf_token')
  return csrf ? { 'X-CSRFToken': csrf } : {}
}

function requestHeaders(init: RequestInit, accessToken: string | null): Headers {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (accessToken && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }
  return headers
}

function fieldErrorsFrom(data: unknown): FieldErrors | undefined {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return undefined
  return data as FieldErrors
}

async function parseResponse<T>(response: Response): Promise<T> {
  const requestId = response.headers.get('x-request-id') ?? ''
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.toLowerCase().includes('application/json')) {
    if (response.ok) return undefined as T
    throw new ApiError('http_error', '服务暂时不可用', requestId, undefined, response.status)
  }

  let payload: ApiEnvelope<T>
  try {
    payload = (await response.json()) as ApiEnvelope<T>
  } catch {
    throw new ApiError('invalid_response', '服务响应格式异常', requestId, undefined, response.status)
  }

  if (!response.ok) {
    throw new ApiError(
      payload.code || 'http_error',
      payload.message || '请求处理失败',
      payload.request_id || requestId,
      fieldErrorsFrom(payload.data),
      response.status,
    )
  }
  return payload.data
}

async function fetchApi<T>(path: string, init: RequestInit, accessToken: string | null): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${API_PREFIX}${path}`, {
      ...init,
      credentials: 'include',
      headers: requestHeaders(init, accessToken),
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiError('request_aborted', '请求已取消')
    }
    throw new ApiError('network_error', '网络连接失败，请稍后重试')
  }
  return parseResponse<T>(response)
}

async function refreshOnce(): Promise<AuthPayload> {
  if (!refreshPromise) {
    refreshPromise = fetchApi<AuthPayload>(
      `${AUTH_PREFIX}refresh/`,
      {
        method: 'POST',
        headers: csrfHeaders(),
        body: JSON.stringify({ client_kind: 'web' }),
      },
      null,
    )
      .then((payload) => {
        bridge.acceptAuth(payload)
        return payload
      })
      .catch((error: unknown) => {
        bridge.clearSession()
        throw error
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

export async function apiRequest<T>(path: string, init: RequestInit = {}, hasReplayed = false): Promise<T> {
  try {
    return await fetchApi<T>(path, init, bridge.getAccessToken())
  } catch (error) {
    const canRefresh =
      error instanceof ApiError &&
      error.status === 401 &&
      !path.startsWith(AUTH_PREFIX) &&
      !hasReplayed
    if (!canRefresh) throw error
    await refreshOnce()
    return apiRequest<T>(path, init, true).catch((replayError: unknown) => {
      if (replayError instanceof ApiError && replayError.status === 401) bridge.clearSession()
      throw replayError
    })
  }
}

export async function refreshSession(): Promise<AuthPayload> {
  return refreshOnce()
}

export function csrfRequestHeaders(): HeadersInit {
  return csrfHeaders()
}
