import { describe, expect, it, vi } from 'vitest'

import { createVisualizerAdapter } from './VisualizerAdapter'

describe('VisualizerAdapter', () => {
  it('延迟到 start 才加载 Waviz 1.0.0，停止和销毁只释放已创建实例', async () => {
    const simpleBars = vi.fn(); const stop = vi.fn(); const cleanup = vi.fn()
    const loadWaviz = vi.fn().mockResolvedValue({ Waviz: class { simpleBars = simpleBars; stop = stop; cleanup = cleanup } })
    const adapter = createVisualizerAdapter({ loadWaviz })
    expect(loadWaviz).not.toHaveBeenCalled()
    await adapter.start(document.createElement('audio'), document.createElement('canvas'))
    expect(loadWaviz).toHaveBeenCalledOnce(); expect(simpleBars).toHaveBeenCalledOnce()
    adapter.stop(); adapter.destroy()
    expect(stop).toHaveBeenCalledTimes(2); expect(cleanup).toHaveBeenCalledOnce()
  })

  it('Waviz 不兼容时采用可停止的 Web Audio Canvas fallback，并关闭所有资源', async () => {
    const requestFrame = vi.fn().mockReturnValue(9); const cancelFrame = vi.fn()
    const disconnectSource = vi.fn(); const disconnectAnalyser = vi.fn(); const close = vi.fn().mockResolvedValue(undefined)
    const analyser = { frequencyBinCount: 32, getByteFrequencyData: vi.fn(), connect: vi.fn(), disconnect: disconnectAnalyser }
    const context = { state: 'running', destination: {}, createMediaElementSource: vi.fn(() => ({ connect: vi.fn(), disconnect: disconnectSource })), createAnalyser: vi.fn(() => analyser), resume: vi.fn().mockResolvedValue(undefined), close }
    const adapter = createVisualizerAdapter({ loadWaviz: vi.fn().mockRejectedValue(new Error('esm mismatch')), createContext: () => context as unknown as AudioContext, requestFrame, cancelFrame })
    const canvas = document.createElement('canvas'); vi.spyOn(canvas, 'getContext').mockReturnValue({ clearRect: vi.fn(), fillRect: vi.fn(), fillStyle: '' } as unknown as CanvasRenderingContext2D)
    await adapter.start(document.createElement('audio'), canvas)
    expect(context.createMediaElementSource).toHaveBeenCalledOnce(); expect(requestFrame).toHaveBeenCalledOnce()
    adapter.destroy()
    expect(cancelFrame).toHaveBeenCalledWith(9); expect(disconnectSource).toHaveBeenCalledOnce(); expect(disconnectAnalyser).toHaveBeenCalledOnce(); expect(close).toHaveBeenCalledOnce()
  })
})
