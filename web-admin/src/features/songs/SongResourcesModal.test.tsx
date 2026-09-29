import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { expect, it, vi } from 'vitest'

import { server } from '../../test/server'
import { SongResourcesModal } from './SongResourcesModal'
import type { Song } from './types'

const song: Song = { id: '10000000-0000-0000-0000-000000000010', title: '歌', artist: '歌手', genre: '流行', language: '中文', duration_seconds: 60, source_asset: '20000000-0000-0000-0000-000000000010', vocal_asset: null, accompaniment_asset: null, lyrics_asset: null, ingestion_mode: 'manual', analysis_status: 'pending', publication_status: 'published', uploaded_at: '2026-09-29T00:00:00Z' }
const assetId = '20000000-0000-0000-0000-000000000011'
const envelope = (data: unknown) => ({ code: 'ok', message: '', request_id: 'test', data })

it('补传选填资源时提交旧资产条件和新资产标识', async () => {
  server.use(
    http.post('/api/v1/admin/songs/upload-grants/', () => HttpResponse.json(envelope({ song_id: song.id, asset_id: assetId, upload_url: `/api/v1/media/local-upload/${assetId}/?signature=token`, upload_token: '', fields: {} }), { status: 201 })),
    http.put(`/api/v1/media/local-upload/${assetId}/`, () => new HttpResponse(null, { status: 204 })),
    http.post(`/api/v1/admin/media/${assetId}/complete/`, () => HttpResponse.json(envelope({ id: assetId, status: 'ready' }))),
    http.patch(`/api/v1/admin/songs/${song.id}/resources/`, () => HttpResponse.json(envelope({ ...song, vocal_asset: assetId }))),
  )
  const done = vi.fn()
  render(<SongResourcesModal song={song} open onCancel={vi.fn()} onDone={done} />)
  fireEvent.change(screen.getByLabelText('纯人声文件'), { target: { files: [new File(['vocal'], 'vocal.mp3', { type: 'audio/mpeg' })] } })
  fireEvent.click(screen.getByRole('button', { name: '保存资源' }))
  await waitFor(() => expect(done).toHaveBeenCalled())
  expect(server.lastJson(`/api/v1/admin/songs/${song.id}/resources/`)).toEqual({ updates: { vocal_asset: assetId }, expected: { vocal_asset: null } })
})

it('关闭资源弹窗后迟到的保存响应不再触发完成回调', async () => {
  let release: () => void = () => undefined
  const gate = new Promise<void>((resolve) => { release = resolve })
  server.use(
    http.post('/api/v1/admin/songs/upload-grants/', () => HttpResponse.json(envelope({ song_id: song.id, asset_id: assetId, upload_url: `/api/v1/media/local-upload/${assetId}/?signature=token`, upload_token: '', fields: {} }), { status: 201 })),
    http.put(`/api/v1/media/local-upload/${assetId}/`, () => new HttpResponse(null, { status: 204 })),
    http.post(`/api/v1/admin/media/${assetId}/complete/`, () => HttpResponse.json(envelope({ id: assetId, status: 'ready' }))),
    http.patch(`/api/v1/admin/songs/${song.id}/resources/`, async () => { await gate; return HttpResponse.json(envelope({ ...song, vocal_asset: assetId })) }),
  )
  const done = vi.fn()
  const view = render(<SongResourcesModal song={song} open onCancel={vi.fn()} onDone={done} />)
  fireEvent.change(screen.getByLabelText('纯人声文件'), { target: { files: [new File(['vocal'], 'vocal.mp3', { type: 'audio/mpeg' })] } })
  fireEvent.click(screen.getByRole('button', { name: '保存资源' }))
  await waitFor(() => expect(server.calls(`/api/v1/admin/songs/${song.id}/resources/`)).toHaveLength(1))
  fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
  view.rerender(<SongResourcesModal song={song} open={false} onCancel={vi.fn()} onDone={done} />)
  await act(async () => { release(); await new Promise((resolve) => setTimeout(resolve, 0)) })
  expect(done).not.toHaveBeenCalled()
})
