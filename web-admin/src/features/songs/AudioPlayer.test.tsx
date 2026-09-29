import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'

import { AudioPlayer } from './AudioPlayer'
import type { Song } from './types'
import { server } from '../../test/server'

const song: Song = {
  id: '10000000-0000-0000-0000-000000000010', title: '甜蜜蜜', artist: '邓丽君', genre: '流行', language: '中文',
  duration_seconds: 180, source_asset: '20000000-0000-0000-0000-000000000010', analysis_status: 'succeeded', publication_status: 'published', uploaded_at: '2026-08-13T00:00:00Z',
}

function envelope<T>(data: T) { return { code: 'ok', message: '', data, request_id: 'preview-test' } }

describe('AudioPlayer', () => {
  it('没有真实分析产物时禁用伴奏试听', () => {
    render(<AudioPlayer song={song} artifacts={{}} onClose={vi.fn()} />)
    expect(screen.getByRole('button', { name: '伴奏（不可用）' })).toBeDisabled()
  })

  it('原生 audio 报错时仅重新获取一次私有地址', async () => {
    let grants = 0
    server.use(http.post(`/api/v1/admin/songs/${song.id}/preview/`, () => {
      grants += 1
      return HttpResponse.json(envelope({ url: `https://private.example/${grants}.mp3`, expires_at: '2026-08-13T00:10:00Z' }))
    }))
    render(<AudioPlayer song={song} artifacts={{ source: true }} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '原唱试听' }))
    const audio = await screen.findByLabelText('正在试听原唱')
    fireEvent.error(audio)
    await waitFor(() => expect(grants).toBe(2))
    fireEvent.error(audio)
    await waitFor(() => expect(grants).toBe(2))
  })

  it('连续错误只刷新一次，手动重播可开启新一轮刷新', async () => {
    let grants = 0
    server.use(http.post(`/api/v1/admin/songs/${song.id}/preview/`, () => HttpResponse.json(envelope({ url: `https://private.example/${++grants}.mp3`, expires_at: '2026-08-13T00:10:00Z' }))))
    render(<AudioPlayer song={song} artifacts={{ source: true }} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '原唱试听' }))
    const audio = await screen.findByLabelText('正在试听原唱')
    fireEvent.error(audio); fireEvent.error(audio)
    await waitFor(() => expect(grants).toBe(2))
    fireEvent.click(screen.getByRole('button', { name: '原唱试听' }))
    await waitFor(() => expect(grants).toBe(3))
    fireEvent.error(await screen.findByLabelText('正在试听原唱'))
    await waitFor(() => expect(grants).toBe(4))
  })

  it('刷新进行中的并发 error 不残留红错，刷新后真正失败才提示', async () => {
    let attempt = 0
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    server.use(http.post(`/api/v1/admin/songs/${song.id}/preview/`, async () => {
      attempt += 1
      if (attempt === 2) await gate
      return HttpResponse.json(envelope({ url: `https://private.example/${attempt}.mp3`, expires_at: '2026-08-13T00:10:00Z' }))
    }))
    render(<AudioPlayer song={song} artifacts={{ source: true }} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '原唱试听' }))
    const audio = await screen.findByLabelText('正在试听原唱')
    fireEvent.error(audio)
    fireEvent.error(audio)
    await waitFor(() => expect(attempt).toBe(2))
    const showedErrorWhileRefreshing = screen.queryByText('试听地址失效或媒体暂不可播放，请稍后重试') !== null
    release()
    expect(showedErrorWhileRefreshing).toBe(false)
    expect(screen.queryByText('试听地址失效或媒体暂不可播放，请稍后重试')).not.toBeInTheDocument()
    fireEvent.error(await screen.findByLabelText('正在试听原唱'))
    expect(await screen.findByText('试听地址失效或媒体暂不可播放，请稍后重试')).toBeInTheDocument()
  })

  it('关闭时撤销签发请求且旧响应不能恢复音频资源', async () => {
    let markStarted: () => void = () => undefined
    const started = new Promise<void>((resolve) => { markStarted = resolve })
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    server.use(http.post(`/api/v1/admin/songs/${song.id}/preview/`, async () => {
      markStarted(); await gate
      return HttpResponse.json(envelope({ url: 'https://private.example/stale.mp3', expires_at: '2026-08-13T00:10:00Z' }))
    }))
    const onClose = vi.fn()
    render(<AudioPlayer song={song} artifacts={{ source: true }} onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: '原唱试听' }))
    await started
    fireEvent.click(screen.getByRole('button', { name: /关\s*闭/ }))
    release()
    await waitFor(() => expect(onClose).toHaveBeenCalledOnce())
    expect(screen.queryByLabelText('正在试听原唱')).not.toBeInTheDocument()
    expect(screen.queryByText('获取试听地址失败')).not.toBeInTheDocument()
  })
})
