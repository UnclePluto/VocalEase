import { describe, expect, it, vi } from 'vitest'

import { createPlaybackClock } from './PlaybackClock'

describe('PlaybackClock', () => {
  it('uses audio as the single clock and corrects a drifting video', async () => {
    const audio = document.createElement('audio')
    const video = document.createElement('video')
    Object.defineProperty(audio, 'currentTime', { value: 12, writable: true })
    Object.defineProperty(video, 'currentTime', { value: 10, writable: true })
    Object.defineProperty(audio, 'play', { value: vi.fn().mockResolvedValue(undefined) })
    Object.defineProperty(video, 'play', { value: vi.fn().mockResolvedValue(undefined) })
    const clock = createPlaybackClock(audio, video)

    await clock.play()
    audio.dispatchEvent(new Event('timeupdate'))

    expect(video.currentTime).toBe(12)
    expect(video.play).toHaveBeenCalledOnce()
    clock.destroy()
  })

  it('does not loop autoplay rejections', async () => {
    const audio = document.createElement('audio')
    Object.defineProperty(audio, 'play', { value: vi.fn().mockRejectedValue(new DOMException('blocked', 'NotAllowedError')) })
    const clock = createPlaybackClock(audio)
    await expect(clock.play()).rejects.toMatchObject({ name: 'NotAllowedError' })
    expect(audio.play).toHaveBeenCalledOnce()
    clock.destroy()
  })
})
