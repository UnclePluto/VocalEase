import { ApiError } from './errors'
import type { ApiEnvelope, AuthPayload, FieldErrors } from './types'

type SessionSnapshot = { accessToken: string | null; epoch: number }

type SessionBridge = {
  getSession: () => SessionSnapshot
  acceptAuth: (payload: AuthPayload, expectedEpoch: number) => boolean
  clearSession: (expectedEpoch: number) => boolean
  rejectServerSession: () => void
}

type RefreshFlight = {
  epoch: number
  promise: Promise<AuthPayload>
}

const API_PREFIX = '/api'
const AUTH_PREFIX = '/v1/auth/'
let bridge: SessionBridge = {
  getSession: () => ({ accessToken: null, epoch: 0 }),
  acceptAuth: () => false,
  clearSession: () => false,
  rejectServerSession: () => undefined,
}
let refreshFlight: RefreshFlight | null = null
let authMutationTail: Promise<void> = Promise.resolve()
let authMutationGeneration = 0

export function configureSessionBridge(nextBridge: SessionBridge) {
  bridge = nextBridge
}

export function invalidateAuthOperations() {
  refreshFlight = null
}

export function resetApiClientForTests() {
  invalidateAuthOperations()
  authMutationGeneration += 1
  authMutationTail = Promise.resolve()
}

function sessionChanged(): ApiError {
  return new ApiError('session_changed', '登录状态已变更')
}

export function enqueueWebAuthMutation<T>(operation: () => Promise<T>): Promise<T> {
  const generation = authMutationGeneration
  const result = authMutationTail
    .catch(() => undefined)
    .then(() => {
      if (generation !== authMutationGeneration) throw sessionChanged()
      return operation()
    })
  authMutationTail = result.then(() => undefined, () => undefined)
  return result
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

export type FencedResponse = {
  readonly status: number
  readonly ok: boolean
  readonly headers: Headers
  readonly contentType: string
  readonly requestId: string
  json: <T = unknown>() => Promise<T>
  blob: () => Promise<Blob>
  arrayBuffer: () => Promise<ArrayBuffer>
  text: () => Promise<string>
}

function fencedResponse(response: Response, signal: AbortSignal | null | undefined, assertSessionIsCurrent: () => void): FencedResponse {
  const readBody = async <T>(reader: () => Promise<T>): Promise<T> => {
    assertSessionIsCurrent()
    try {
      const body = await reader()
      assertSessionIsCurrent()
      return body
    } catch (error) {
      assertSessionIsCurrent()
      if (signal?.aborted || (error instanceof DOMException && error.name === 'AbortError')) {
        throw new ApiError('request_aborted', '请求已取消')
      }
      if (error instanceof TypeError) throw new ApiError('network_error', '网络连接失败，请稍后重试')
      throw error
    }
  }
  return {
    status: response.status,
    ok: response.ok,
    headers: new Headers(response.headers),
    contentType: response.headers.get('content-type') ?? '',
    requestId: response.headers.get('x-request-id') ?? '',
    json: <T = unknown>() => readBody(() => response.json() as Promise<T>),
    blob: () => readBody(() => response.blob()),
    arrayBuffer: () => readBody(() => response.arrayBuffer()),
    text: () => readBody(() => response.text()),
  }
}

async function parseResponse<T>(response: FencedResponse): Promise<T> {
  const requestId = response.requestId
  const contentType = response.contentType
  if (!contentType.toLowerCase().includes('application/json')) {
    if (response.ok) return undefined as T
    throw new ApiError('http_error', '服务暂时不可用', requestId, undefined, response.status)
  }

  let payload: ApiEnvelope<T>
  try {
    payload = await response.json<ApiEnvelope<T>>()
  } catch (error) {
    if (error instanceof ApiError) throw error
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

async function fetchApiResponse(
  path: string,
  init: RequestInit,
  accessToken: string | null,
  sessionFence?: () => void,
): Promise<FencedResponse> {
  let response: Response
  try {
    response = await fetch(`${API_PREFIX}${path}`, {
      ...init,
      credentials: 'include',
      headers: requestHeaders(init, accessToken),
    })
  } catch (error) {
    if (init.signal?.aborted || (error instanceof DOMException && error.name === 'AbortError')) {
      throw new ApiError('request_aborted', '请求已取消')
    }
    throw new ApiError('network_error', '网络连接失败，请稍后重试')
  }
  const assertSessionIsCurrent = sessionFence ?? (() => undefined)
  assertSessionIsCurrent()
  return fencedResponse(response, init.signal, assertSessionIsCurrent)
}

async function refreshOnce(expectedEpoch: number): Promise<AuthPayload> {
  if (bridge.getSession().epoch !== expectedEpoch) throw sessionChanged()
  if (refreshFlight?.epoch === expectedEpoch) return refreshFlight.promise
  invalidateAuthOperations()

  const flight = {} as RefreshFlight
  flight.epoch = expectedEpoch
  flight.promise = enqueueWebAuthMutation(() => fetchApiResponse(
    `${AUTH_PREFIX}refresh/`,
    {
      method: 'POST',
      headers: csrfHeaders(),
      body: JSON.stringify({ client_kind: 'web' }),
    },
    null,
  ).then(async (response) => parseResponse<AuthPayload>(response)))
    .then((payload) => {
      try {
        if (!bridge.acceptAuth(payload, expectedEpoch)) throw sessionChanged()
      } catch (error) {
        if (error instanceof ApiError && error.code === 'admin_access_denied') {
          bridge.clearSession(expectedEpoch)
          bridge.rejectServerSession()
        }
        throw error
      }
      return payload
    })
    .catch((error: unknown) => {
      if (bridge.getSession().epoch !== expectedEpoch) throw sessionChanged()
      bridge.clearSession(expectedEpoch)
      throw error
    })
    .finally(() => {
      if (refreshFlight === flight) refreshFlight = null
    })
  refreshFlight = flight
  return flight.promise
}

export async function apiRawRequest(path: string, init: RequestInit = {}): Promise<FencedResponse> {
  const started = bridge.getSession()
  const requiresSessionFence = !path.startsWith(AUTH_PREFIX)
  const assertSessionIsCurrent = () => {
    if (requiresSessionFence && bridge.getSession().epoch !== started.epoch) throw sessionChanged()
  }
  try {
    const response = await fetchApiResponse(path, init, started.accessToken, assertSessionIsCurrent)
    if (!response.ok) await parseResponse(response)
    assertSessionIsCurrent()
    return response
  } catch (error) {
    assertSessionIsCurrent()
    const canRefresh =
      error instanceof ApiError &&
      error.status === 401 &&
      !path.startsWith(AUTH_PREFIX) &&
      bridge.getSession().epoch === started.epoch
    if (!canRefresh) throw error

    await refreshOnce(started.epoch)
    const refreshed = bridge.getSession()
    if (refreshed.epoch !== started.epoch) throw sessionChanged()

    try {
      const response = await fetchApiResponse(path, init, refreshed.accessToken, assertSessionIsCurrent)
      if (!response.ok) await parseResponse(response)
      assertSessionIsCurrent()
      return response
    } catch (replayError) {
      if (replayError instanceof ApiError && replayError.status === 401) {
        bridge.clearSession(started.epoch)
      }
      throw replayError
    }
  }
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const started = bridge.getSession()
  const response = await apiRawRequest(path, init)
  const data = await parseResponse<T>(response)
  if (!path.startsWith(AUTH_PREFIX) && bridge.getSession().epoch !== started.epoch) throw sessionChanged()
  return data
}

export async function sessionFencedFetch(url: string, init: RequestInit = {}): Promise<FencedResponse> {
  const startedEpoch = bridge.getSession().epoch
  const assertSessionIsCurrent = () => {
    if (bridge.getSession().epoch !== startedEpoch) throw sessionChanged()
  }
  const headers = new Headers(init.headers)
  headers.delete('authorization')
  headers.delete('cookie')
  headers.delete('x-csrftoken')
  let response: Response
  try {
    response = await fetch(url, { ...init, credentials: 'omit', headers })
  } catch (error) {
    assertSessionIsCurrent()
    if (init.signal?.aborted || (error instanceof DOMException && error.name === 'AbortError')) {
      throw new ApiError('request_aborted', '请求已取消')
    }
    throw new ApiError('network_error', '网络连接失败，请稍后重试')
  }
  assertSessionIsCurrent()
  return fencedResponse(response, init.signal, assertSessionIsCurrent)
}

export async function refreshSession(expectedEpoch: number): Promise<AuthPayload> {
  return refreshOnce(expectedEpoch)
}

export function csrfRequestHeaders(): HeadersInit {
  return csrfHeaders()
}
