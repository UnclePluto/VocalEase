import { describe, expect, it } from 'vitest'

import { validateQiniuCallback } from './api'

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
