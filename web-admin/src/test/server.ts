import { http, HttpResponse, type RequestHandler } from 'msw'
import { setupServer } from 'msw/node'

import type { AuthPayload } from '../api/types'

type RecordedCall = {
  method: string
  path: string
  headers: Headers
  json?: unknown
}

const calls: RecordedCall[] = []
const mockServer = setupServer()

function envelope<T>(data: T, requestId = 'test-request') {
  return { code: 'ok', message: '', data, request_id: requestId }
}

function apiError(status: number, code: string, message: string) {
  return HttpResponse.json(
    { code, message, data: null, request_id: 'test-request' },
    { status },
  )
}

mockServer.events.on('request:start', async ({ request }) => {
  const entry: RecordedCall = {
    method: request.method,
    path: new URL(request.url).pathname,
    headers: new Headers(request.headers),
  }
  const body = await request.clone().text()
  if (body) {
    try { entry.json = JSON.parse(body) as unknown } catch { entry.json = body }
  }
  calls.push(entry)
})

let exportStates: unknown[] = []
let lastExportState: unknown = { status: 'pending' }

export const server = {
  listen() {
    mockServer.listen({ onUnhandledRequest: 'error' })
  },
  close() {
    mockServer.close()
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
    return calls.filter((call) => call.path === path).at(-1)?.json
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
  useRefresh(payload: AuthPayload) {
    mockServer.use(http.post('/api/v1/auth/refresh/', () => HttpResponse.json(envelope(payload))))
  },
  useDeferredRefresh(payload: AuthPayload) {
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    mockServer.use(http.post('/api/v1/auth/refresh/', async () => {
      await gate
      return HttpResponse.json(envelope(payload))
    }))
    return { release }
  },
  useDeferredLogin(payload: AuthPayload) {
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    mockServer.use(http.post('/api/v1/auth/login/', async () => {
      await gate
      return HttpResponse.json(envelope(payload))
    }))
    return { release }
  },
  useRefreshFailure() {
    mockServer.use(http.post('/api/v1/auth/refresh/', () => apiError(401, 'token_not_valid', '刷新令牌无效')))
  },
  useLogout() {
    mockServer.use(http.post('/api/v1/auth/logout/', () => HttpResponse.json(envelope({}))))
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
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    mockServer.use(http.get('/api/v1/admin/delayed/', async () => {
      await gate
      return HttpResponse.json(envelope({ ok: true }))
    }))
    return { release }
  },
}
