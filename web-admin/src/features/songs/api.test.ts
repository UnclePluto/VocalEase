import { describe, expect, it } from 'vitest'

import { createSongReliably, validateQiniuCallback } from './api'
import { http, HttpResponse } from 'msw'
import { server } from '../../test/server'

describe('七牛上传回执', () => {
  const assetId = '20000000-0000-0000-0000-000000000010'
  it('只接受当前资产的 ready 回调 envelope', () => {
    expect(() => validateQiniuCallback(JSON.stringify({ code: 'ok', data: { asset_id: assetId, status: 'ready' } }), assetId)).not.toThrow()
  })
  it.each([
    JSON.stringify({ code: 'ok', data: { asset_id: '20000000-0000-0000-0000-000000000011', status: 'ready' } }),
    JSON.stringify({ code: 'ok', data: { asset_id: assetId, status: 'uploading' } }),
    'not-json',
  ])('拒绝错资产、非 ready 或无效回执', (body) => {
    expect(() => validateQiniuCallback(body, assetId)).toThrow('七牛上传完成回执无效')
  })
})

it('人工歌曲创建冲突时必须比对所有选填资源', async () => {
  const songId = '10000000-0000-0000-0000-000000000010'
  const values = { id: songId, title: '歌', artist: '歌手', genre: '流行', language: '中文', duration_seconds: 60, source_asset: '20000000-0000-0000-0000-000000000010', ingestion_mode: 'manual' as const, vocal_asset: '20000000-0000-0000-0000-000000000011' }
  server.use(
    http.post('/api/v1/admin/songs/', () => HttpResponse.json({ code: 'song_id_exists', message: '标识已存在', data: null, request_id: 'test' }, { status: 409 })),
    http.get(`/api/v1/admin/songs/${songId}/`, () => HttpResponse.json({ code: 'ok', message: '', request_id: 'test', data: { ...values, vocal_asset: null } })),
  )
  await expect(createSongReliably(values)).rejects.toThrow('已有歌曲与本次提交内容不一致')
})
