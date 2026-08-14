import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { WaveformPlayer } from './WaveformPlayer'

describe('WaveformPlayer', () => {
  it('禁用未产出的分轨，为每个嗳气事件建立区域，且不伪造分轨', () => {
    const addRegion = vi.fn()
    render(<WaveformPlayer media={{ mixed: { assetId: 'audio', url: '/audio.mp3' } }} events={[12.4, 88.2]} waveFactory={() => ({ addRegion, destroy: vi.fn() })} />)
    expect(screen.getByRole('tab', { name: '仅人声' })).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('tab', { name: '仅伴奏' })).toHaveAttribute('aria-disabled', 'true')
    expect(addRegion).toHaveBeenCalledTimes(2)
    expect(screen.getByText(/缺少真实分轨产物/)).toBeInTheDocument()
    expect(screen.queryByText('仅人声正在播放')).not.toBeInTheDocument()
  })

  it('点击事件标记会跳转，卸载释放波形、时钟、可视化和媒体监听', async () => {
    const seek = vi.fn(); const destroy = vi.fn(); const visualizer = { start: vi.fn(), stop: vi.fn(), destroy: vi.fn() }
    const view = render(<WaveformPlayer media={{ mixed: { assetId: 'audio', url: '/audio.mp3' } }} events={[12]} waveFactory={() => ({ addRegion: vi.fn(), destroy, seek })} visualizerFactory={() => visualizer} />)
    fireEvent.click(screen.getByRole('button', { name: '跳转至 12 秒' }))
    expect(seek).toHaveBeenCalledWith(12)
    const audio = view.container.querySelector('audio')!
    Object.defineProperty(audio, 'play', { value: vi.fn().mockResolvedValue(undefined) })
    fireEvent.click(screen.getByRole('button', { name: '播放' }))
    await waitFor(() => expect(visualizer.start).toHaveBeenCalledOnce())
    view.unmount()
    expect(destroy).toHaveBeenCalledOnce()
    expect(visualizer.destroy).toHaveBeenCalledOnce()
  })

  it('播放时钟真实驱动指标回调，漂移视频只跟随音频', async () => {
    const onTime = vi.fn()
    const { container } = render(<WaveformPlayer media={{ mixed: { assetId: 'audio', url: '/audio.mp3' }, video: { assetId: 'video', url: '/video.mp4' } }} events={[]} onTime={onTime} waveFactory={() => ({ addRegion: vi.fn(), destroy: vi.fn() })} />)
    const audio = container.querySelector('audio')!; const video = container.querySelector('video')!
    Object.defineProperty(audio, 'currentTime', { value: 4, writable: true }); Object.defineProperty(video, 'currentTime', { value: 1, writable: true })
    fireEvent.timeUpdate(audio)
    expect(onTime).toHaveBeenLastCalledWith(4)
    expect(video.currentTime).toBe(4)
  })

  it('每个资产仅受控刷新一次，401、403 与媒体错误均保留稳定错误和重试入口', async () => {
    const refresh = vi.fn().mockRejectedValue(Object.assign(new Error('授权失效'), { requestId: 'media-403' }))
    const { container } = render(<WaveformPlayer media={{ mixed: { assetId: 'audio', url: '/audio.mp3' } }} events={[]} onRefreshMedia={refresh} waveFactory={() => ({ addRegion: vi.fn(), destroy: vi.fn() })} />)
    const audio = container.querySelector('audio')!
    fireEvent.error(audio); fireEvent.error(audio)
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('授权失效')).toBeInTheDocument()
    expect(screen.getByText('请求编号：media-403')).toBeInTheDocument()
    await screen.findByRole('button', { name: '重试媒体授权' })
  })

  it('旧会话刷新完成后不会覆盖新的媒体 URL', async () => {
    let resolveOld!: (url: string) => void
    const oldRefresh = vi.fn(() => new Promise<string>((resolve) => { resolveOld = resolve }))
    const waveFactory = () => ({ addRegion: vi.fn(), destroy: vi.fn() })
    const view = render(<WaveformPlayer media={{ mixed: { assetId: 'old', url: '/old.mp3' } }} events={[]} onRefreshMedia={oldRefresh} waveFactory={waveFactory} />)
    fireEvent.error(view.container.querySelector('audio')!)
    view.rerender(<WaveformPlayer media={{ mixed: { assetId: 'new', url: '/new.mp3' } }} events={[]} onRefreshMedia={vi.fn().mockResolvedValue('/newer.mp3')} waveFactory={waveFactory} />)
    resolveOld('/stale.mp3')
    await Promise.resolve()
    expect(view.container.querySelector('audio')!.getAttribute('src')).toBe('/new.mp3')
  })

  it('播放被浏览器拒绝时保持暂停并提示用户点击', async () => {
    const { container } = render(<WaveformPlayer media={{ mixed: { assetId: 'audio', url: '/audio.mp3' } }} events={[]} waveFactory={() => ({ addRegion: vi.fn(), destroy: vi.fn() })} />)
    const audio = container.querySelector('audio')!
    Object.defineProperty(audio, 'play', { value: vi.fn().mockRejectedValue(new DOMException('blocked', 'NotAllowedError')) })
    fireEvent.click(screen.getByRole('button', { name: '播放' }))
    expect(await screen.findByText(/浏览器阻止自动播放/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '播放' })).toBeInTheDocument()
  })

  it('录像加载失败且刷新失败时降级为音频，不影响回放主时钟', async () => {
    const refresh = vi.fn().mockRejectedValue(new Error('录像授权失败'))
    const { container } = render(<WaveformPlayer media={{ mixed: { assetId: 'audio', url: '/audio.mp3' }, video: { assetId: 'video', url: '/video.mp4' } }} events={[]} onRefreshMedia={refresh} waveFactory={() => ({ addRegion: vi.fn(), destroy: vi.fn() })} />)
    fireEvent.error(container.querySelector('video')!)
    expect(await screen.findByText('录像加载失败，已降级为音频回放。')).toBeInTheDocument()
    expect(container.querySelector('audio')).toBeInTheDocument()
  })
})
