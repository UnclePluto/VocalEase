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

  it('Waviz 已占用媒体源但健康探针失败时只清理并停用可视化，不二次绑定同一媒体', async () => {
    const context = { state: 'running', destination: {}, createMediaElementSource: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })), createAnalyser: vi.fn(() => ({ frequencyBinCount: 32, getByteFrequencyData: vi.fn(), connect: vi.fn(), disconnect: vi.fn() })), resume: vi.fn(), close: vi.fn() }
    const cleanup = vi.fn(); const requestFrame = vi.fn().mockReturnValue(1)
    const Waviz = vi.fn(class { cleanup = cleanup; getFrequencyData = () => null; simpleBars = vi.fn() })
    const adapter = createVisualizerAdapter({ loadWaviz: vi.fn().mockResolvedValue({ Waviz }), createContext: () => context as unknown as AudioContext, requestFrame })
    const canvas = document.createElement('canvas'); vi.spyOn(canvas, 'getContext').mockReturnValue({ clearRect: vi.fn(), fillRect: vi.fn(), fillStyle: '' } as unknown as CanvasRenderingContext2D)
    const media = document.createElement('audio')
    await expect(adapter.start(media, canvas)).resolves.toBeUndefined()
    await expect(adapter.start(media, canvas)).resolves.toBeUndefined()
    expect(cleanup).toHaveBeenCalledOnce()
    expect(Waviz).toHaveBeenCalledOnce()
    expect(context.createMediaElementSource).not.toHaveBeenCalled()
    expect(requestFrame).not.toHaveBeenCalled()
    adapter.destroy()
    expect(cleanup).toHaveBeenCalledOnce()
  })

  it('Waviz 已连接后预设失败时复用其频谱数据绘制，绝不对同一媒体创建第二个 source', async () => {
    const stop = vi.fn(); const cleanup = vi.fn(); const cancelFrame = vi.fn()
    const samples = new Uint8Array(32).fill(128)
    const analyser = { frequencyBinCount: 32, getByteFrequencyData: vi.fn(), connect: vi.fn(), disconnect: vi.fn() }
    const createContext = vi.fn(() => ({
      state: 'running', destination: {},
      createMediaElementSource: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createAnalyser: vi.fn(() => analyser), resume: vi.fn(), close: vi.fn(),
    }) as unknown as AudioContext)
    const adapter = createVisualizerAdapter({
      loadWaviz: vi.fn().mockResolvedValue({ Waviz: class {
        stop = stop
        cleanup = cleanup
        getFrequencyData = () => samples
        simpleBars = vi.fn().mockRejectedValue(new Error('preset failed'))
      } }),
      createContext,
      requestFrame: vi.fn().mockReturnValue(7),
      cancelFrame,
    })
    const canvas = document.createElement('canvas')
    const fillRect = vi.fn()
    vi.spyOn(canvas, 'getContext').mockReturnValue({ clearRect: vi.fn(), fillRect, fillStyle: '' } as unknown as CanvasRenderingContext2D)

    await adapter.start(document.createElement('audio'), canvas)

    expect(createContext).not.toHaveBeenCalled()
    expect(fillRect).toHaveBeenCalled()
    expect(stop).toHaveBeenCalledOnce()
    adapter.destroy()
    expect(cancelFrame).toHaveBeenCalledWith(7)
    expect(cleanup).toHaveBeenCalledOnce()
  })
})
