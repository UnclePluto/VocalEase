import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { expect, it, vi } from 'vitest'

import { server } from '../../test/server'
import { SongManualUploadModal } from './SongManualUploadModal'

vi.mock('./audioDuration', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./audioDuration')>()),
  useAudioDuration: (file?: File | null) => ({ file: file ?? undefined, seconds: file ? 97 : undefined, loading: false }),
}))

const songId = '10000000-0000-0000-0000-000000000010'
const sourceId = '20000000-0000-0000-0000-000000000010'
const vocalId = '20000000-0000-0000-0000-000000000011'
const envelope = (data: unknown) => ({ code: 'ok', message: '', request_id: 'manual-test', data })

it('要求原曲并按音轨类型上传资源后创建人工歌曲', async () => {
  let next = 0
  server.use(
    http.post('/api/v1/admin/songs/upload-grants/', async ({ request }) => {
      const payload = await request.json() as { media_type: string }
      const assetId = ++next === 1 ? sourceId : vocalId
      return HttpResponse.json(envelope({ song_id: songId, asset_id: assetId, upload_url: `/api/v1/media/local-upload/${assetId}/?signature=token`, upload_token: '', fields: {}, media_type: payload.media_type }), { status: 201 })
    }),
    http.put('/api/v1/media/local-upload/:assetId/', () => new HttpResponse(null, { status: 204 })),
    http.post('/api/v1/admin/media/:assetId/complete/', ({ params }) => HttpResponse.json(envelope({ id: params.assetId, status: 'ready' }))),
    http.post('/api/v1/admin/songs/', () => HttpResponse.json(envelope({ id: songId }), { status: 201 })),
  )
  const done = vi.fn()
  render(<SongManualUploadModal open onCancel={vi.fn()} onDone={done} />)
  expect(screen.getByRole('button', { name: '保存人工歌曲' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText(/原曲文件/), { target: { files: [new File(['source'], 'source.mp3', { type: 'audio/mpeg' })] } })
  fireEvent.change(screen.getByLabelText(/纯人声文件/), { target: { files: [new File(['vocal'], 'vocal.mp3', { type: 'audio/mpeg' })] } })
  fireEvent.change(screen.getByLabelText('歌曲名称'), { target: { value: '歌' } })
  fireEvent.change(screen.getByLabelText('歌手'), { target: { value: '歌手' } })
  fireEvent.click(screen.getByRole('button', { name: '保存人工歌曲' }))
  await waitFor(() => expect(done).toHaveBeenCalledWith(songId))
  expect(server.lastJson('/api/v1/admin/songs/')).toMatchObject({ ingestion_mode: 'manual', source_asset: sourceId, vocal_asset: vocalId, duration_seconds: 97 })
  expect(server.calls('/api/v1/admin/songs/upload-grants/')).toHaveLength(2)
})

it('关闭弹窗后迟到的创建响应不再触发完成回调', async () => {
  let release: () => void = () => undefined
  const gate = new Promise<void>((resolve) => { release = resolve })
  server.use(
    http.post('/api/v1/admin/songs/upload-grants/', () => HttpResponse.json(envelope({ song_id: songId, asset_id: sourceId, upload_url: `/api/v1/media/local-upload/${sourceId}/?signature=token`, upload_token: '', fields: {} }), { status: 201 })),
    http.put(`/api/v1/media/local-upload/${sourceId}/`, () => new HttpResponse(null, { status: 204 })),
    http.post(`/api/v1/admin/media/${sourceId}/complete/`, () => HttpResponse.json(envelope({ id: sourceId, status: 'ready' }))),
    http.post('/api/v1/admin/songs/', async () => { await gate; return HttpResponse.json(envelope({ id: songId }), { status: 201 }) }),
  )
  const done = vi.fn()
  const cancel = vi.fn()
  const view = render(<SongManualUploadModal open onCancel={cancel} onDone={done} />)
  fireEvent.change(screen.getByLabelText(/原曲文件/), { target: { files: [new File(['source'], 'source.mp3', { type: 'audio/mpeg' })] } })
  fireEvent.change(screen.getByLabelText('歌曲名称'), { target: { value: '歌' } })
  fireEvent.change(screen.getByLabelText('歌手'), { target: { value: '歌手' } })
  fireEvent.click(screen.getByRole('button', { name: '保存人工歌曲' }))
  await waitFor(() => expect(server.calls('/api/v1/admin/songs/')).toHaveLength(1))
  fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
  view.rerender(<SongManualUploadModal open={false} onCancel={cancel} onDone={done} />)
  await act(async () => { release(); await new Promise((resolve) => setTimeout(resolve, 0)) })
  expect(done).not.toHaveBeenCalled()
})
