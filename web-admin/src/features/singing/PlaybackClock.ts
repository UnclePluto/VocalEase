export interface PlaybackClock {
  play(): Promise<void>
  pause(): void
  seek(seconds: number): void
  currentTime(): number
  subscribe(listener: (seconds: number) => void): () => void
  destroy(): void
}

const VIDEO_DRIFT_SECONDS = 0.25

export function createPlaybackClock(audio: HTMLMediaElement, video?: HTMLMediaElement | null): PlaybackClock {
  const listeners = new Set<(seconds: number) => void>()
  const sync = () => {
    const seconds = audio.currentTime || 0
    if (video && Math.abs(video.currentTime - seconds) > VIDEO_DRIFT_SECONDS) video.currentTime = seconds
    listeners.forEach((listener) => listener(seconds))
  }
  audio.addEventListener('timeupdate', sync)
  audio.addEventListener('seeked', sync)
  return {
    async play() {
      await audio.play()
      if (video) {
        video.currentTime = audio.currentTime || 0
        try { await video.play() } catch { /* 音频持续作为主时钟，视频可独立降级。 */ }
      }
    },
    pause() { audio.pause(); video?.pause() },
    seek(seconds) { audio.currentTime = seconds; if (video) video.currentTime = seconds; sync() },
    currentTime: () => audio.currentTime || 0,
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener) },
    destroy() { audio.removeEventListener('timeupdate', sync); audio.removeEventListener('seeked', sync); listeners.clear(); video?.pause() },
  }
}
