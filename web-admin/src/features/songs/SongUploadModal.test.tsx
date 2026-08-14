import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'

import { SongUploadModal } from './SongUploadModal'
import { server } from '../../test/server'

const songId = '10000000-0000-0000-0000-000000000010'
const assetId = '20000000-0000-0000-0000-000000000010'

function envelope<T>(data: T) {
  return { code: 'ok', message: '', data, request_id: 'song-upload-test' }
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
    expect(server.lastJson('/api/v1/admin/songs/')).toMatchObject({ id: songId, source_asset: assetId, title: '甜蜜蜜' })
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
    expect(screen.getByLabelText('歌曲名称')).toHaveValue('甜蜜蜜')
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
})
