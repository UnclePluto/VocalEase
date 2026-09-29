import { afterEach, beforeEach, expect, it, vi } from 'vitest'

import { readAudioDuration } from './audioDuration'

let reportedDuration = 96.1
let emitMetadata = true
const revokeObjectURL = vi.fn()

class FakeAudio extends EventTarget {
  duration = reportedDuration
  preload = ''
  src = ''
  load() { if (emitMetadata) queueMicrotask(() => this.dispatchEvent(new Event('loadedmetadata'))) }
  removeAttribute() { this.src = '' }
}

beforeEach(() => {
  reportedDuration = 96.1
  emitMetadata = true
  revokeObjectURL.mockClear()
  vi.stubGlobal('Audio', FakeAudio)
  vi.stubGlobal('URL', { createObjectURL: () => 'blob:test-audio', revokeObjectURL })
})
afterEach(() => vi.unstubAllGlobals())

it('直接读取原曲元数据并向上取整为整数秒', async () => {
  const seconds = await readAudioDuration(new File(['audio'], 'song.mp3', { type: 'audio/mpeg' }))
  expect(seconds).toBe(97)
  expect(revokeObjectURL).toHaveBeenCalledWith('blob:test-audio')
})

it('原曲更换时取消旧分析并释放对象地址', async () => {
  emitMetadata = false
  const controller = new AbortController()
  const pending = readAudioDuration(new File(['audio'], 'old.mp3'), controller.signal)
  controller.abort()
  await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  expect(revokeObjectURL).toHaveBeenCalledWith('blob:test-audio')
})

it('拒绝超过歌曲时长上限的文件', async () => {
  reportedDuration = 86400.5
  await expect(readAudioDuration(new File(['audio'], 'long.wav'))).rejects.toThrow('音频时长不能超过 24 小时')
})
