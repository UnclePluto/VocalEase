import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'

import { SongUploadModal } from './SongUploadModal'
import { useAuthStore } from '../../auth/store'
import { server } from '../../test/server'

vi.mock('./audioDuration', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./audioDuration')>()),
  useAudioDuration: (file?: File | null) => ({ file: file ?? undefined, seconds: file ? 97 : undefined, loading: false }),
}))

const songId = '10000000-0000-0000-0000-000000000010'
const assetId = '20000000-0000-0000-0000-000000000010'

function envelope<T>(data: T) {
  return { code: 'ok', message: '', data, request_id: 'song-upload-test' }
}

const createdSong = {
  id: songId, title: '甜蜜蜜', artist: '邓丽君', genre: '流行', language: '中文', duration_seconds: 97,
  source_asset: assetId, analysis_status: 'pending', publication_status: 'draft', uploaded_at: '2026-08-13T00:00:00Z',
}

async function fillUpload(user: ReturnType<typeof userEvent.setup>, name = 'song.mp3', type = 'audio/mpeg') {
  await user.upload(screen.getByLabelText('上传歌曲', { selector: 'input' }), new File(['audio'], name, { type }))
  await user.type(screen.getByLabelText('歌曲名称'), '甜蜜蜜')
  await user.type(screen.getByLabelText('歌手'), '邓丽君')
}

function configureLocalUpload({ failCreate = false } = {}) {
  server.use(
    http.post('/api/v1/admin/songs/upload-grants/', () => HttpResponse.json(envelope({
      song_id: songId, asset_id: assetId, object_key: 'songs/test.mp3',
      upload_url: `/api/v1/media/local-upload/${assetId}/?signature=local-token`, upload_token: '', fields: {},
    }), { status: 201 })),
    http.put(`/api/v1/media/local-upload/${assetId}/`, () => new HttpResponse(null, { status: 204 })),
    http.post(`/api/v1/admin/media/${assetId}/complete/`, () => HttpResponse.json(envelope({ id: assetId, status: 'ready' }))),
    http.post('/api/v1/admin/songs/', () => failCreate
      ? HttpResponse.json({ code: 'validation_error', message: '歌名重复', data: { title: ['歌名已存在'] }, request_id: 'song-create-error' }, { status: 400 })
      : HttpResponse.json(envelope({ id: songId }), { status: 201 })),
  )
}

describe('SongUploadModal', () => {
  it('创建凭证、直传、确认后才创建歌曲', async () => {
    configureLocalUpload()
    const user = userEvent.setup({ applyAccept: false })
    const onDone = vi.fn()
    render(<SongUploadModal open onCancel={vi.fn()} onDone={onDone} />)

    await user.upload(screen.getByLabelText('上传歌曲', { selector: 'input' }), new File(['audio'], 'song.mp3', { type: 'audio/mpeg' }))
    await user.type(screen.getByLabelText('歌曲名称'), '甜蜜蜜')
    await user.type(screen.getByLabelText('歌手'), '邓丽君')
    await user.click(screen.getByRole('button', { name: '开始上传' }))

    await waitFor(() => expect(onDone).toHaveBeenCalledWith(songId))
    // XHR 直传不经过 fetch recorder；随后的确认与创建成功证明存储端 204 已被消费。
    expect(server.calls().map((call) => call.path)).toEqual(expect.arrayContaining([
      '/api/v1/admin/songs/upload-grants/', `/api/v1/admin/media/${assetId}/complete/`, '/api/v1/admin/songs/',
    ]))
    expect(server.lastJson('/api/v1/admin/songs/')).toMatchObject({ id: songId, source_asset: assetId, title: '甜蜜蜜', duration_seconds: 97 })
  })

  it('创建歌曲失败时保留表单且可以重试，不会双重提交', async () => {
    configureLocalUpload({ failCreate: true })
    const user = userEvent.setup({ applyAccept: false })
    render(<SongUploadModal open onCancel={vi.fn()} onDone={vi.fn()} />)
    await user.upload(screen.getByLabelText('上传歌曲', { selector: 'input' }), new File(['audio'], 'song.mp3', { type: 'audio/mpeg' }))
    await user.type(screen.getByLabelText('歌曲名称'), '甜蜜蜜')
    await user.type(screen.getByLabelText('歌手'), '邓丽君')
    const submit = screen.getByRole('button', { name: '开始上传' })
    fireEvent.click(submit)
    fireEvent.click(submit)

    expect(await screen.findByText('歌名重复')).toBeInTheDocument()
    const titleInput = screen.getByLabelText('歌曲名称')
    expect(screen.getByText('歌名已存在')).toBeInTheDocument()
    expect(titleInput).toHaveValue('甜蜜蜜')
    expect(server.calls('/api/v1/admin/songs/')).toHaveLength(1)

    server.use(http.post('/api/v1/admin/songs/', () => HttpResponse.json(envelope({ id: songId }), { status: 201 })))
    await user.click(screen.getByRole('button', { name: '重试创建歌曲' }))
    await waitFor(() => expect(server.calls('/api/v1/admin/songs/')).toHaveLength(2))
  })

  it('拒绝不支持的格式和超过 50MB 的文件', async () => {
    const user = userEvent.setup({ applyAccept: false })
    render(<SongUploadModal open onCancel={vi.fn()} onDone={vi.fn()} />)
    await user.upload(screen.getByLabelText('上传歌曲', { selector: 'input' }), new File(['video'], 'song.mp4', { type: 'audio/mp4' }))
    expect(await screen.findByText('仅支持 MP3、WAV 或 FLAC 音频')).toBeInTheDocument()
    const large = new File([new Uint8Array(1)], 'large.mp3', { type: 'audio/mpeg' })
    Object.defineProperty(large, 'size', { value: 50 * 1024 * 1024 + 1 })
    await user.upload(screen.getByLabelText('上传歌曲', { selector: 'input' }), large)
    expect(await screen.findByText('音频不能超过 50MB')).toBeInTheDocument()
  })

  it.each([
    ['empty.mp3', ''], ['legacy.wav', 'audio/x-wav'], ['legacy.flac', 'audio/x-flac'],
  ])('允许合法扩展名并把 MIME 规范化：%s', async (name, type) => {
    let grantMime = ''
    configureLocalUpload()
    server.use(http.post('/api/v1/admin/songs/upload-grants/', async ({ request }) => {
      grantMime = String((await request.json() as { mime: string }).mime)
      return HttpResponse.json(envelope({ song_id: songId, asset_id: assetId, object_key: name, upload_url: `/api/v1/media/local-upload/${assetId}/?signature=token`, upload_token: '', fields: {} }), { status: 201 })
    }))
    const user = userEvent.setup({ applyAccept: false })
    render(<SongUploadModal open onCancel={vi.fn()} onDone={vi.fn()} />)
    await fillUpload(user, name, type)
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    await waitFor(() => expect(server.calls('/api/v1/admin/songs/')).toHaveLength(1))
    expect(grantMime).toBe(name.endsWith('.mp3') ? 'audio/mpeg' : name.endsWith('.wav') ? 'audio/wav' : 'audio/flac')
  })

  it('非法选择会清除旧文件，不能误传之前的合法文件', async () => {
    const user = userEvent.setup({ applyAccept: false })
    render(<SongUploadModal open onCancel={vi.fn()} onDone={vi.fn()} />)
    await user.upload(screen.getByLabelText('上传歌曲', { selector: 'input' }), new File(['ok'], 'ok.mp3', { type: 'audio/mpeg' }))
    await user.upload(screen.getByLabelText('上传歌曲', { selector: 'input' }), new File(['bad'], 'bad.exe', { type: 'application/octet-stream' }))
    expect(await screen.findByText('仅支持 MP3、WAV 或 FLAC 音频')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '开始上传' })).toBeDisabled()
  })

  it('创建响应丢失后用同一 song_id 查询并仅在源资产一致时收敛成功', async () => {
    configureLocalUpload()
    let createAttempt = 0
    server.use(
      http.post('/api/v1/admin/songs/', () => {
        createAttempt += 1
        return createAttempt === 1 ? HttpResponse.error() : HttpResponse.json({ code: 'song_id_exists', message: '歌曲标识已存在', data: null, request_id: 'create-conflict' }, { status: 409 })
      }),
      http.get(`/api/v1/admin/songs/${songId}/`, () => HttpResponse.json(envelope(createdSong))),
    )
    const user = userEvent.setup()
    const onDone = vi.fn()
    render(<SongUploadModal open onCancel={vi.fn()} onDone={onDone} />)
    await fillUpload(user)
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    await screen.findByText('网络连接失败，请稍后重试')
    await user.click(screen.getByRole('button', { name: '重试创建歌曲' }))
    await waitFor(() => expect(onDone).toHaveBeenCalledWith(songId))
    expect(server.calls(`/api/v1/admin/songs/${songId}/`)).toHaveLength(1)
  })

  it('响应丢失后修改表单时不会把旧歌曲误判成本次提交成功', async () => {
    configureLocalUpload()
    let createAttempt = 0
    server.use(
      http.post('/api/v1/admin/songs/', () => {
        createAttempt += 1
        return createAttempt === 1 ? HttpResponse.error() : HttpResponse.json({ code: 'song_id_exists', message: '歌曲标识已存在', data: null, request_id: 'create-conflict' }, { status: 409 })
      }),
      http.get(`/api/v1/admin/songs/${songId}/`, () => HttpResponse.json(envelope(createdSong))),
    )
    const user = userEvent.setup()
    const onDone = vi.fn()
    render(<SongUploadModal open onCancel={vi.fn()} onDone={onDone} />)
    await fillUpload(user)
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    await screen.findByText('网络连接失败，请稍后重试')
    await user.clear(screen.getByLabelText('歌曲名称'))
    await user.type(screen.getByLabelText('歌曲名称'), '月亮代表我的心')
    await user.click(screen.getByRole('button', { name: '重试创建歌曲' }))
    expect(await screen.findByText('已有歌曲与本次提交内容不一致')).toBeInTheDocument()
    expect(onDone).not.toHaveBeenCalled()
  })

  it('连续打开上传两首歌时清空文件和表单并申请不同歌曲标识', async () => {
    let index = 0
    const ids = [songId, '10000000-0000-0000-0000-000000000011']
    const assets = [assetId, '20000000-0000-0000-0000-000000000011']
    server.use(
      http.post('/api/v1/admin/songs/upload-grants/', () => { const current = index++; return HttpResponse.json(envelope({ song_id: ids[current], asset_id: assets[current], object_key: `songs/${current}.mp3`, upload_url: `/api/v1/media/local-upload/${assets[current]}/?signature=token`, upload_token: '', fields: {} }), { status: 201 }) }),
      http.put('/api/v1/media/local-upload/:id/', () => new HttpResponse(null, { status: 204 })),
      http.post('/api/v1/admin/media/:id/complete/', () => HttpResponse.json(envelope({ status: 'ready' }))),
      http.post('/api/v1/admin/songs/', async ({ request }) => HttpResponse.json(envelope({ ...createdSong, ...(await request.json() as object) }), { status: 201 })),
    )
    const user = userEvent.setup()
    const onDone = vi.fn()
    const view = render(<SongUploadModal open onCancel={vi.fn()} onDone={onDone} />)
    await fillUpload(user)
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    await waitFor(() => expect(onDone).toHaveBeenCalledWith(ids[0]))
    view.rerender(<SongUploadModal open={false} onCancel={vi.fn()} onDone={onDone} />)
    view.rerender(<SongUploadModal open onCancel={vi.fn()} onDone={onDone} />)
    expect(screen.getByLabelText('歌曲名称')).toHaveValue('')
    expect(screen.getByLabelText('上传歌曲', { selector: 'input' })).toHaveValue('')
    await fillUpload(user, 'second.mp3')
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    await waitFor(() => expect(onDone).toHaveBeenLastCalledWith(ids[1]))
    expect(server.calls('/api/v1/admin/songs/upload-grants/').map((call) => call.json)).toEqual([
      expect.not.objectContaining({ song_id: expect.anything() }), expect.not.objectContaining({ song_id: expect.anything() }),
    ])
  })

  it.each([
    ['错误资产', envelope({ asset_id: '20000000-0000-0000-0000-000000000099', status: 'ready' })],
    ['非 ready', envelope({ asset_id: assetId, status: 'uploading' })],
    ['坏 JSON', 'not-json'],
  ])('七牛 %s 回执失败且不创建歌曲', async (_name, responseBody) => {
    server.use(
      http.post('/api/v1/admin/songs/upload-grants/', () => HttpResponse.json(envelope({ song_id: songId, asset_id: assetId, object_key: 'songs/qiniu.mp3', upload_url: 'https://upload.example/', upload_token: 'token', fields: { key: 'songs/qiniu.mp3', token: 'token' } }), { status: 201 })),
      http.post('https://upload.example/', () => typeof responseBody === 'string' ? new HttpResponse(responseBody, { status: 200 }) : HttpResponse.json(responseBody)),
    )
    const user = userEvent.setup()
    render(<SongUploadModal open onCancel={vi.fn()} onDone={vi.fn()} />)
    await fillUpload(user)
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    expect(await screen.findByText('七牛上传完成回执无效')).toBeInTheDocument()
    expect(server.calls('/api/v1/admin/songs/')).toHaveLength(0)
    expect(server.calls(`/api/v1/admin/media/${assetId}/complete/`)).toHaveLength(0)
  })

  it('七牛真实 XHR 合法回执后直接创建且不调用 complete', async () => {
    server.use(
      http.post('/api/v1/admin/songs/upload-grants/', () => HttpResponse.json(envelope({ song_id: songId, asset_id: assetId, object_key: 'songs/qiniu.mp3', upload_url: 'https://upload.example/', upload_token: 'token', fields: { key: 'songs/qiniu.mp3', token: 'token' } }), { status: 201 })),
      http.post('https://upload.example/', () => HttpResponse.json(envelope({ asset_id: assetId, status: 'ready' }))),
      http.post('/api/v1/admin/songs/', () => HttpResponse.json(envelope(createdSong), { status: 201 })),
    )
    const user = userEvent.setup()
    const onDone = vi.fn()
    render(<SongUploadModal open onCancel={vi.fn()} onDone={onDone} />)
    await fillUpload(user)
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    await waitFor(() => expect(onDone).toHaveBeenCalledWith(songId))
    expect(server.calls(`/api/v1/admin/media/${assetId}/complete/`)).toHaveLength(0)
  })

  it('上传中会话代际变化会中止且不确认、不创建歌曲', async () => {
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    let started: () => void = () => undefined
    const uploadStarted = new Promise<void>((resolve) => { started = resolve })
    configureLocalUpload()
    server.use(http.put(`/api/v1/media/local-upload/${assetId}/`, async () => { started(); await gate; return new HttpResponse(null, { status: 204 }) }))
    const user = userEvent.setup()
    render(<SongUploadModal open onCancel={vi.fn()} onDone={vi.fn()} />)
    await fillUpload(user)
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    await uploadStarted
    useAuthStore.getState().reset()
    release()
    expect(await screen.findByText('登录状态已变更')).toBeInTheDocument()
    expect(server.calls(`/api/v1/admin/media/${assetId}/complete/`)).toHaveLength(0)
    expect(server.calls('/api/v1/admin/songs/')).toHaveLength(0)
  })

  it.each(['grant', 'upload', 'confirm', 'create'] as const)('在 %s 阶段点击取消会中止且不进入后续步骤', async (stage) => {
    let markStarted: () => void = () => undefined
    const started = new Promise<void>((resolve) => { markStarted = resolve })
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    const waitAt = async <T,>(current: typeof stage, value: T) => {
      if (stage === current) { markStarted(); await gate }
      return value
    }
    let uploadCalls = 0
    server.use(
      http.post('/api/v1/admin/songs/upload-grants/', async () => waitAt('grant', HttpResponse.json(envelope({ song_id: songId, asset_id: assetId, object_key: 'songs/test.mp3', upload_url: `/api/v1/media/local-upload/${assetId}/?signature=token`, upload_token: '', fields: {} }), { status: 201 }))),
      http.put(`/api/v1/media/local-upload/${assetId}/`, async () => { uploadCalls += 1; return waitAt('upload', new HttpResponse(null, { status: 204 })) }),
      http.post(`/api/v1/admin/media/${assetId}/complete/`, async () => waitAt('confirm', HttpResponse.json(envelope({ id: assetId, status: 'ready' })))),
      http.post('/api/v1/admin/songs/', async () => waitAt('create', HttpResponse.json(envelope(createdSong), { status: 201 }))),
    )
    const user = userEvent.setup()
    const onCancel = vi.fn()
    const onDone = vi.fn()
    render(<SongUploadModal open onCancel={onCancel} onDone={onDone} />)
    await fillUpload(user)
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    await started
    await user.click(screen.getByRole('button', { name: '取消上传' }))
    expect(onCancel).toHaveBeenCalledOnce()
    release()
    await waitFor(() => {
      if (stage === 'grant') expect(uploadCalls).toBe(0)
      if (stage === 'grant' || stage === 'upload') expect(server.calls(`/api/v1/admin/media/${assetId}/complete/`)).toHaveLength(0)
      if (stage !== 'create') expect(server.calls('/api/v1/admin/songs/')).toHaveLength(0)
      expect(onDone).not.toHaveBeenCalled()
    })
  })
})
