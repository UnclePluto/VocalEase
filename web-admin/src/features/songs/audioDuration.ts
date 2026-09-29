import { useEffect, useState } from 'react'

export type DurationState = { file?: File; seconds?: number; loading: boolean; error?: string }
const empty: DurationState = { loading: false }

export function readAudioDuration(file: File, signal?: AbortSignal): Promise<number> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(new DOMException('已取消', 'AbortError')); return }
    const audio = new Audio()
    const url = URL.createObjectURL(file)
    let settled = false
    const cleanup = () => {
      clearTimeout(timer)
      audio.removeEventListener('loadedmetadata', check)
      audio.removeEventListener('durationchange', check)
      audio.removeEventListener('error', fail)
      signal?.removeEventListener('abort', abort)
      audio.removeAttribute('src')
      URL.revokeObjectURL(url)
    }
    const finish = (seconds?: number, error?: Error) => {
      if (settled) return
      settled = true
      cleanup()
      if (error) reject(error)
      else resolve(seconds!)
    }
    const check = () => {
      if (!Number.isFinite(audio.duration) || audio.duration <= 0) return
      const seconds = Math.ceil(audio.duration)
      if (seconds > 86400) finish(undefined, new Error('音频时长不能超过 24 小时'))
      else finish(seconds)
    }
    const fail = () => finish(undefined, new Error('无法读取音频时长，请检查文件是否可播放'))
    const abort = () => finish(undefined, new DOMException('已取消', 'AbortError'))
    const timer = setTimeout(fail, 15000)
    audio.addEventListener('loadedmetadata', check)
    audio.addEventListener('durationchange', check)
    audio.addEventListener('error', fail)
    signal?.addEventListener('abort', abort, { once: true })
    audio.preload = 'metadata'
    audio.src = url
    try { audio.load() } catch { fail() }
  })
}

export function useAudioDuration(file?: File | null): DurationState {
  const [state, setState] = useState<DurationState>(empty)
  useEffect(() => {
    if (!file) return
    const controller = new AbortController()
    void readAudioDuration(file, controller.signal).then(
      (seconds) => setState({ file, seconds, loading: false }),
      (error: unknown) => { if (!controller.signal.aborted) setState({ file, loading: false, error: error instanceof Error ? error.message : '无法读取音频时长' }) },
    )
    return () => controller.abort()
  }, [file])
  return state.file === file ? state : { file: file ?? undefined, loading: Boolean(file) }
}
