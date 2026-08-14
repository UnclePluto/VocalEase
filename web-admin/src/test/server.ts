import { http, HttpResponse, type RequestHandler } from 'msw'
import { setupServer } from 'msw/node'

import type { AuthPayload } from '../api/types'

type RecordedCall = {
  method: string
  path: string
  search: string
  headers: Headers
  json?: unknown
}

const calls: RecordedCall[] = []
const mockServer = setupServer()
let interceptedFetch: typeof globalThis.fetch | null = null

function envelope<T>(data: T, requestId = 'test-request') {
  return { code: 'ok', message: '', data, request_id: requestId }
}

function apiError(status: number, code: string, message: string) {
  return HttpResponse.json(
    { code, message, data: null, request_id: 'test-request' },
    { status },
  )
}

async function recordRequest(input: RequestInfo | URL, init?: RequestInit) {
  const target = input instanceof Request
    ? new Request(input, init)
    : new Request(new URL(String(input), window.location.href), init)
  const entry: RecordedCall = {
    method: target.method,
    path: new URL(target.url).pathname,
    search: new URL(target.url).search,
    headers: new Headers(target.headers),
  }
  const body = await target.clone().text()
  if (body) {
    try { entry.json = JSON.parse(body) as unknown } catch { entry.json = body }
  }
  calls.push(entry)
}

let exportStates: unknown[] = []
let lastExportState: unknown = { status: 'pending' }

export const server = {
  listen() {
    mockServer.listen({ onUnhandledRequest: 'error' })
    interceptedFetch = globalThis.fetch
    globalThis.fetch = async (input, init) => {
      await recordRequest(input, init)
      if (!interceptedFetch) throw new Error('MSW fetch recorder is not active')
      return interceptedFetch(input, init)
    }
  },
  close() {
    if (interceptedFetch) globalThis.fetch = interceptedFetch
    mockServer.close()
    interceptedFetch = null
  },
  use(...handlers: RequestHandler[]) {
    mockServer.use(...handlers)
  },
  reset() {
    mockServer.resetHandlers()
    calls.splice(0)
    exportStates = []
    lastExportState = { status: 'pending' }
  },
  calls(path?: string) {
    return path ? calls.filter((call) => call.path === path) : [...calls]
  },
  lastJson(path: string) {
    return calls.filter((call) => call.path === path && call.json !== undefined).at(-1)?.json
  },
  queueExportStates(...states: unknown[]) {
    exportStates.push(...states)
    if (states.length) lastExportState = states.at(-1)
    mockServer.use(
      http.get('/api/v1/admin/analytics/exports/:id/', () => {
        const next = exportStates.shift() ?? lastExportState
        return HttpResponse.json(envelope(next))
      }),
    )
  },
  useLogin(payload: AuthPayload) {
    mockServer.use(http.post('/api/v1/auth/login/', () => HttpResponse.json(envelope(payload))))
  },
  useLoginWithRecorderProbe(payload: AuthPayload) {
    let recordedAtHandler: unknown
    mockServer.use(http.post('/api/v1/auth/login/', () => {
      recordedAtHandler = calls.filter((call) => call.path === '/api/v1/auth/login/').at(-1)?.json
      return HttpResponse.json(envelope(payload))
    }))
    return { recordedAtHandler: () => recordedAtHandler }
  },
  useRefresh(payload: AuthPayload) {
    mockServer.use(http.post('/api/v1/auth/refresh/', () => HttpResponse.json(envelope(payload))))
  },
  useDeferredRefresh(payload: AuthPayload) {
    let markStarted: () => void = () => undefined
    const started = new Promise<void>((resolve) => { markStarted = resolve })
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    mockServer.use(http.post('/api/v1/auth/refresh/', async () => {
      markStarted()
      await gate
      return HttpResponse.json(envelope(payload))
    }))
    return { started, release }
  },
  useDeferredLogin(payload: AuthPayload) {
    let markStarted: () => void = () => undefined
    const started = new Promise<void>((resolve) => { markStarted = resolve })
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    mockServer.use(http.post('/api/v1/auth/login/', async () => {
      markStarted()
      await gate
      return HttpResponse.json(envelope(payload))
    }))
    return { started, release }
  },
  useTrackedLogin(payload: AuthPayload) {
    let markStarted: () => void = () => undefined
    const started = new Promise<void>((resolve) => { markStarted = resolve })
    mockServer.use(http.post('/api/v1/auth/login/', () => {
      markStarted()
      return HttpResponse.json(envelope(payload))
    }))
    return { started }
  },
  useRefreshFailure() {
    mockServer.use(http.post('/api/v1/auth/refresh/', () => apiError(401, 'token_not_valid', '刷新令牌无效')))
  },
  useLogout() {
    mockServer.use(http.post('/api/v1/auth/logout/', () => HttpResponse.json(envelope({}))))
  },
  useTrackedLogout() {
    let markStarted: () => void = () => undefined
    const started = new Promise<void>((resolve) => { markStarted = resolve })
    mockServer.use(http.post('/api/v1/auth/logout/', () => {
      markStarted()
      return HttpResponse.json(envelope({}))
    }))
    return { started }
  },
  useLogoutNetworkFailure() {
    mockServer.use(http.post('/api/v1/auth/logout/', () => HttpResponse.error()))
  },
  useChangePassword() {
    mockServer.use(http.post('/api/v1/auth/change-password/', () => HttpResponse.json(envelope({}))))
  },
  useConcurrentUnauthorizedResources() {
    const attempts = new Map<string, number>()
    mockServer.use(
      http.get('/api/v1/admin/test-resource/:id/', ({ params }) => {
        const id = String(params.id)
        const attempt = (attempts.get(id) ?? 0) + 1
        attempts.set(id, attempt)
        return attempt === 1
          ? apiError(401, 'token_not_valid', '访问令牌失效')
          : HttpResponse.json(envelope({ id: Number(id) }))
      }),
    )
  },
  useAlwaysUnauthorizedResource() {
    mockServer.use(
      http.get('/api/v1/admin/test-resource/', () => apiError(401, 'token_not_valid', '访问令牌失效')),
    )
  },
  useNonJsonError() {
    mockServer.use(
      http.get('/api/v1/admin/non-json/', () => new HttpResponse('服务暂时不可用', {
        status: 503,
        statusText: 'Service Unavailable',
        headers: { 'Content-Type': 'text/plain', 'X-Request-ID': 'request-from-header' },
      })),
    )
  },
  useNetworkError() {
    mockServer.use(http.get('/api/v1/admin/network-error/', () => HttpResponse.error()))
  },
  useDelayedResource() {
    let markStarted: () => void = () => undefined
    const started = new Promise<void>((resolve) => { markStarted = resolve })
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    mockServer.use(http.get('/api/v1/admin/delayed/', async () => {
      markStarted()
      await gate
      return HttpResponse.json(envelope({ ok: true }))
    }))
    return { started, release }
  },
  useUnauthorizedThenDeferredResource() {
    let attempt = 0
    let markReplayStarted: () => void = () => undefined
    const replayStarted = new Promise<void>((resolve) => { markReplayStarted = resolve })
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    mockServer.use(http.get('/api/v1/admin/replayed/', async () => {
      attempt += 1
      if (attempt === 1) return apiError(401, 'token_not_valid', '访问令牌失效')
      markReplayStarted()
      await gate
      return HttpResponse.json(envelope({ source: 'stale-replay' }))
    }))
    return { replayStarted, release }
  },
}
