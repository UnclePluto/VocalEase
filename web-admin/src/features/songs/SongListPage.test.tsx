import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '../../auth/store'
import { renderApp } from '../../test/renderApp'
import { server } from '../../test/server'
import type { Song } from './types'

const song = {
  id: '10000000-0000-0000-0000-000000000010', title: '甜蜜蜜', artist: '邓丽君', genre: '流行', language: '中文', duration_seconds: 180,
  source_asset: '20000000-0000-0000-0000-000000000010', analysis_status: 'succeeded' as const, publication_status: 'draft' as const, uploaded_at: '2026-08-13T00:00:00Z',
}
const admin = { login_id: 'A000001', role: 'system_admin' as const, must_change_password: false }
function envelope<T>(data: T, requestId = 'songs-request') { return { code: 'ok', message: '', data, request_id: requestId } }
function list(results: Song[] = [song], page = 1) { return { count: results.length, page, page_size: 20, results } }
function authenticate() { useAuthStore.setState({ accessToken: 'valid', user: admin, status: 'authenticated' }) }
function useList(results: Song[] = [song]) { server.use(http.get('/api/v1/admin/songs/', ({ request }) => HttpResponse.json(envelope(list(results, Number(new URL(request.url).searchParams.get('page'))))))) }
async function actionsForSong() {
  const row = (await screen.findByText(song.title)).closest('tr')
  if (!row) throw new Error('歌曲行不存在')
  return within(row)
}
async function actionForSong(label: string) {
  const rowActions = await actionsForSong()
  const directAction = rowActions.queryByText(label)
  if (directAction) return directAction
  fireEvent.click(rowActions.getByText('更多'))
  return screen.findByText(label)
}

describe('曲库管理页面', () => {
  beforeEach(() => { window.innerWidth = 1440 })
  it('把分页搜索筛选转成服务端查询并展示空态', async () => {
    authenticate(); useList([])
    renderApp('/songs?page=2&page_size=20&search=%E7%94%9C&analysis_status=failed&publication_status=draft')
    expect(await screen.findByText('暂无歌曲，请上传首首治疗歌曲')).toBeInTheDocument()
    expect(server.calls('/api/v1/admin/songs/').at(-1)?.search).toBe('?page=2&page_size=20&sort=-created_at&keyword=%E7%94%9C&analysis_status=failed&publication_status=draft')
  })

  it('发布失败显示 request_id 且同步双击只发一次', async () => {
    authenticate(); useList()
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    server.use(http.post(`/api/v1/admin/songs/${song.id}/publish/`, async () => { await gate; return HttpResponse.json({ code: 'song_not_publishable', message: '仅分析成功歌曲可发布', data: null, request_id: 'publish-409' }, { status: 409 }) }))
    renderApp('/songs')
    const publish = await actionForSong('发布')
    act(() => { fireEvent.click(publish); fireEvent.click(publish) })
    await waitFor(() => expect(server.calls(`/api/v1/admin/songs/${song.id}/publish/`)).toHaveLength(1))
    await act(async () => { release() })
    expect(await screen.findByText('仅分析成功歌曲可发布')).toBeInTheDocument()
    expect(screen.getByText('请求编号：publish-409')).toBeInTheDocument()
  })

  it('重新分析网络不确定性重试复用幂等键且每次同步双击只请求一次', async () => {
    authenticate(); useList()
    let attempt = 0
    server.use(http.post(`/api/v1/admin/songs/${song.id}/reanalyze/`, () => {
      attempt += 1
      return attempt === 1 ? HttpResponse.error() : HttpResponse.json(envelope({ task_id: 'task-1', status: 'pending', is_mock: true }), { status: 202 })
    }))
    renderApp('/songs')
    const reanalyze = await actionForSong('重新分析')
    act(() => { fireEvent.click(reanalyze); fireEvent.click(reanalyze) })
    await waitFor(() => expect(server.calls(`/api/v1/admin/songs/${song.id}/reanalyze/`)).toHaveLength(1))
    expect(await screen.findByText('网络连接失败，请稍后重试')).toBeInTheDocument()
    fireEvent.click(await actionForSong('重新分析'))
    await waitFor(() => expect(server.calls(`/api/v1/admin/songs/${song.id}/reanalyze/`)).toHaveLength(2))
    const bodies = server.calls(`/api/v1/admin/songs/${song.id}/reanalyze/`).map((call) => call.json as { idempotency_key: string })
    expect(bodies[0].idempotency_key).toBe(bodies[1].idempotency_key)
  })

  it('重新分析确定性 4xx 失败后新尝试会轮换幂等键', async () => {
    authenticate(); useList()
    let attempt = 0
    server.use(http.post(`/api/v1/admin/songs/${song.id}/reanalyze/`, () => {
      attempt += 1
      return attempt === 1
        ? HttpResponse.json({ code: 'analysis_conflict', message: '当前状态不可重新分析', data: null, request_id: 'reanalyze-409' }, { status: 409 })
        : HttpResponse.json(envelope({ task_id: 'task-2', status: 'pending', is_mock: true }), { status: 202 })
    }))
    renderApp('/songs')
    fireEvent.click(await actionForSong('重新分析'))
    expect(await screen.findByText('当前状态不可重新分析')).toBeInTheDocument()
    expect(screen.getByText('请求编号：reanalyze-409')).toBeInTheDocument()
    fireEvent.click(await actionForSong('重新分析'))
    await waitFor(() => expect(server.calls(`/api/v1/admin/songs/${song.id}/reanalyze/`)).toHaveLength(2))
    const bodies = server.calls(`/api/v1/admin/songs/${song.id}/reanalyze/`).map((call) => call.json as { idempotency_key: string })
    expect(bodies[0].idempotency_key).not.toBe(bodies[1].idempotency_key)
  })

  it('逻辑删除明确保留历史并防双确认', async () => {
    authenticate(); useList()
    server.use(http.delete(`/api/v1/admin/songs/${song.id}/`, () => new HttpResponse(null, { status: 204 })))
    const user = userEvent.setup()
    renderApp('/songs')
    await user.click((await actionsForSong()).getByText('删除'))
    expect(screen.getByText(/历史演唱记录和审计历史将保留/)).toBeInTheDocument()
    const confirm = screen.getByRole('button', { name: '确认删除' })
    act(() => { fireEvent.click(confirm); fireEvent.click(confirm) })
    await waitFor(() => expect(server.calls(`/api/v1/admin/songs/${song.id}/`)).toHaveLength(1))
    expect(await screen.findByText('歌曲已下架并隐藏，历史演唱记录已保留')).toBeInTheDocument()
  })

  it('删除冲突在确认框中显示 request_id 并允许重试', async () => {
    authenticate(); useList()
    let attempt = 0
    server.use(http.delete(`/api/v1/admin/songs/${song.id}/`, () => {
      attempt += 1
      return attempt === 1
        ? HttpResponse.json({ code: 'song_in_use', message: '歌曲正在处理中', data: null, request_id: 'delete-409' }, { status: 409 })
        : new HttpResponse(null, { status: 204 })
    }))
    const user = userEvent.setup()
    renderApp('/songs')
    await user.click((await actionsForSong()).getByText('删除'))
    await user.click(screen.getByLabelText('确认删除'))
    expect(await screen.findByText('歌曲正在处理中')).toBeInTheDocument()
    expect(screen.getByText('请求编号：delete-409')).toBeInTheDocument()
    await user.click(screen.getByLabelText('确认删除'))
    await waitFor(() => expect(attempt).toBe(2))
  })

  it('390px 紧凑菜单仍可触达编辑、发布、重分析和删除', async () => {
    window.innerWidth = 390; authenticate(); useList()
    server.use(http.post(`/api/v1/admin/songs/${song.id}/reanalyze/`, () => HttpResponse.json(envelope({ task_id: 'compact-task', status: 'pending', is_mock: true }), { status: 202 })))
    renderApp('/songs')
    fireEvent.click(await screen.findByRole('button', { name: `更多${song.title}操作` }))
    const menu = await screen.findByRole('menu')
    for (const label of ['编辑', '发布', '重新分析', '删除']) expect(within(menu).getByText(label)).toBeInTheDocument()
    fireEvent.click(within(menu).getByText('重新分析'))
    await waitFor(() => expect(server.calls(`/api/v1/admin/songs/${song.id}/reanalyze/`)).toHaveLength(1))
  })

  it('分析请求失败显示 request_id，可重试恢复为失败终态并停止轮询', async () => {
    authenticate(); useList([{ ...song, analysis_status: 'processing' }])
    let attempt = 0
    server.use(http.get(`/api/v1/admin/songs/${song.id}/analysis/`, () => {
      attempt += 1
      if (attempt === 1) return HttpResponse.json({ code: 'analysis_unavailable', message: '分析状态加载失败', data: null, request_id: 'analysis-error-1' }, { status: 503 })
      return HttpResponse.json(envelope({ results: [{ id: 'task-1', task_type: 'vocal_separation', protocol_version: '1.0', executor: 'mock_song', status: 'failed', attempt: 1, result: {}, error_code: 'mock_failed', error_summary: '模拟执行失败', created_at: '2026-08-13T00:00:00Z', completed_at: '2026-08-13T00:01:00Z' }] }))
    }))
    renderApp('/songs')
    await waitFor(() => expect(screen.getByText('分析状态加载失败')).toBeInTheDocument())
    expect(screen.getByText('请求编号：analysis-error-1')).toBeInTheDocument()
    act(() => { fireEvent.click(screen.getByLabelText(`重试${song.title}分析状态`)) })
    await waitFor(() => expect(screen.getByText('模拟执行失败')).toBeInTheDocument())
    expect(screen.getByText('错误代码：mock_failed')).toBeInTheDocument()
    await waitFor(() => expect(server.calls('/api/v1/admin/songs/').length).toBeGreaterThan(1))
    const terminalCount = attempt
    vi.useFakeTimers()
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    expect(attempt).toBe(terminalCount)
  })

  it('初始失败行只查询一次任务详情并显示脱敏错误', async () => {
    authenticate(); useList([{ ...song, analysis_status: 'failed' }])
    let attempts = 0
    server.use(http.get(`/api/v1/admin/songs/${song.id}/analysis/`, () => {
      attempts += 1
      return HttpResponse.json(envelope({ results: [{ id: 'task-failed', task_type: 'vocal_separation', protocol_version: '1.0', executor: 'mock_song', status: 'failed', attempt: 2, result: {}, error_code: 'worker_timeout', error_summary: '分析服务暂时不可用', created_at: '2026-08-13T00:00:00Z', completed_at: '2026-08-13T00:01:00Z' }] }))
    }))
    renderApp('/songs')
    await waitFor(() => expect(screen.getByText('分析服务暂时不可用')).toBeInTheDocument())
    expect(screen.getByText('错误代码：worker_timeout')).toBeInTheDocument()
    vi.useFakeTimers()
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    expect(attempts).toBe(1)
  })

  it('活动任务按间隔轮询，收到终态后刷新列表且不再请求', async () => {
    vi.useFakeTimers()
    authenticate(); useList([{ ...song, analysis_status: 'processing' }])
    let attempts = 0
    server.use(http.get(`/api/v1/admin/songs/${song.id}/analysis/`, () => {
      attempts += 1
      const status = attempts === 1 ? 'processing' : 'succeeded'
      return HttpResponse.json(envelope({ results: [{ id: 'task-poll', task_type: 'vocal_separation', protocol_version: '1.0', executor: 'mock_song', status, attempt: 1, result: { is_mock: true, artifacts: [] }, error_code: '', error_summary: '', created_at: '2026-08-13T00:00:00Z', completed_at: status === 'succeeded' ? '2026-08-13T00:01:00Z' : null }] }))
    }))
    renderApp('/songs')
    const advanceUntil = async (assertion: () => void) => {
      let failure: unknown
      for (let index = 0; index < 20; index += 1) {
        await act(async () => { await vi.advanceTimersByTimeAsync(index === 0 ? 0 : 10) })
        try { assertion(); return } catch (error) { failure = error }
      }
      throw failure
    }
    await advanceUntil(() => expect(attempts).toBe(1))
    await act(async () => { await vi.advanceTimersByTimeAsync(1_999) })
    expect(attempts).toBe(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    await advanceUntil(() => expect(attempts).toBe(2))
    await advanceUntil(() => expect(server.calls('/api/v1/admin/songs/').length).toBeGreaterThan(1))
    const terminalCount = attempts
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
    expect(attempts).toBe(terminalCount)
  })

  it('patient 访问曲库时前端守卫拒绝且不请求歌曲接口', async () => {
    useAuthStore.setState({ accessToken: 'patient-token', user: { login_id: 'P000001', role: 'patient', must_change_password: false }, status: 'authenticated' })
    renderApp('/songs')
    await waitFor(() => expect(useAuthStore.getState().status).toBe('anonymous'))
    expect(server.calls('/api/v1/admin/songs/')).toHaveLength(0)
  })
})


it('原唱音高入口使用当前人声指纹生成且同步双击只发一次', async () => {
  authenticate()
  const withVoice: Song = { ...song, vocal_asset: 'voice', vocal_fingerprint: 'receipt', artifacts: { vocal: true }, reference_pitch: { status: 'missing', version: null, note_count: 0 } }
  useList([withVoice])
  server.use(http.get(`/api/v1/admin/songs/${song.id}/`, () => HttpResponse.json(envelope(withVoice))))
  let release: () => void = () => undefined
  const gate = new Promise<void>((resolve) => { release = resolve })
  server.use(http.post(`/api/v1/admin/songs/${song.id}/reference-pitch/generate/`, async () => { await gate; return HttpResponse.json(envelope({ status: 'pending', version: 'pitch' }), { status: 202 }) }))
  renderApp('/songs')
  fireEvent.click(await actionForSong('原唱音高'))
  const dialog = await screen.findByRole('dialog')
  const generate = await within(dialog).findByRole('button', { name: '生成音高' })
  await waitFor(() => expect(generate).toBeEnabled())
  act(() => { fireEvent.click(generate); fireEvent.click(generate) })
  await waitFor(() => expect(server.calls(`/api/v1/admin/songs/${song.id}/reference-pitch/generate/`)).toHaveLength(1))
  expect(server.lastJson(`/api/v1/admin/songs/${song.id}/reference-pitch/generate/`)).toEqual({ expected_fingerprint: 'receipt', force: false })
  await act(async () => { release() })
})

it('已有真实原唱音高显示音符数量且重新生成明确提交force', async () => {
  authenticate()
  const prepared: Song = { ...song, vocal_asset: 'voice', vocal_fingerprint: 'receipt', artifacts: { vocal: true }, reference_pitch: { status: 'ready', version: 'pitch', note_count: 234 } }
  useList([prepared])
  server.use(http.get(`/api/v1/admin/songs/${song.id}/`, () => HttpResponse.json(envelope(prepared))), http.post(`/api/v1/admin/songs/${song.id}/reference-pitch/generate/`, () => HttpResponse.json(envelope({status:'pending',version:'next'}),{status:202})))
  renderApp('/songs')
  fireEvent.click(await actionForSong('原唱音高'))
  const dialog=await screen.findByRole('dialog')
  expect(await within(dialog).findByText('已保存 234 个音符片段')).toBeInTheDocument()
  fireEvent.click(await within(dialog).findByRole('button',{name:'重新生成'}))
  await waitFor(()=>expect(server.lastJson(`/api/v1/admin/songs/${song.id}/reference-pitch/generate/`)).toEqual({expected_fingerprint:'receipt',force:true}))
})
