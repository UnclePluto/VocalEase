export interface VisualizerAdapter { start(media: HTMLMediaElement, canvas: HTMLCanvasElement): Promise<void>; stop(): void; destroy(): void }

type WavizInstance = { simpleBars?: () => Promise<void> | void; getFrequencyData?: () => Uint8Array | null; stop?: () => void; cleanup?: () => void }
type WavizConstructor = new (canvas: HTMLCanvasElement, source: HTMLMediaElement, context?: AudioContext) => WavizInstance
export type VisualizerDependencies = {
  loadWaviz?: () => Promise<{ Waviz: WavizConstructor }>
  createContext?: () => AudioContext
  requestFrame?: (callback: FrameRequestCallback) => number
  cancelFrame?: (id: number) => void
}

/** Waviz 1.0.0 is loaded and constructed only after an explicit playback action. */
export function createVisualizerAdapter(dependencies: VisualizerDependencies = {}): VisualizerAdapter {
  const loadWaviz = dependencies.loadWaviz ?? (() => import('waviz') as Promise<{ Waviz: WavizConstructor }>)
  const requestFrame = dependencies.requestFrame ?? requestAnimationFrame
  const cancelFrame = dependencies.cancelFrame ?? cancelAnimationFrame
  let waviz: WavizInstance | null = null
  let context: AudioContext | null = null
  let source: MediaElementAudioSourceNode | null = null
  let analyser: AnalyserNode | null = null
  let raf = 0
  let destroyed = false

  const stopFallback = () => {
    if (raf) cancelFrame(raf)
    raf = 0
  }
  const drawFallback = (canvas: HTMLCanvasElement, sampleProvider?: () => Uint8Array | null) => {
    const drawing = canvas.getContext('2d')
    const analyserSamples = analyser ? new Uint8Array(analyser.frequencyBinCount) : null
    if (!drawing) return
    const frame = () => {
      if (destroyed) return
      if (analyserSamples && analyser) analyser.getByteFrequencyData(analyserSamples)
      let samples: Uint8Array | null = analyserSamples
      if (sampleProvider) {
        try { samples = sampleProvider() }
        catch { samples = null }
      }
      drawing.clearRect(0, 0, canvas.width, canvas.height)
      drawing.fillStyle = '#3478f6'
      const stride = Math.max(1, Math.floor((samples?.length ?? 32) / 32))
      for (let index = 0; index < 32; index += 1) {
        const level = samples ? samples[index * stride] / 255 : (index % 5) / 5
        const height = Math.max(2, level * canvas.height)
        drawing.fillRect(index * (canvas.width / 32), canvas.height - height, Math.max(2, canvas.width / 48), height)
      }
      raf = requestFrame(frame)
    }
    frame()
  }
  const startWebAudioFallback = async (media: HTMLMediaElement, canvas: HTMLCanvasElement) => {
    context = dependencies.createContext?.() ?? new AudioContext()
    source = context.createMediaElementSource(media)
    analyser = context.createAnalyser()
    source.connect(analyser); analyser.connect(context.destination)
    await context.resume?.()
    drawFallback(canvas)
  }

  return {
    async start(media, canvas) {
      if (destroyed || waviz || raf) return
      let Waviz: WavizConstructor
      try {
        Waviz = (await loadWaviz()).Waviz
      } catch {
        if (!destroyed) await startWebAudioFallback(media, canvas)
        return
      }
      if (destroyed) return
      try { waviz = new Waviz(canvas, media) }
      catch {
        if (!destroyed) await startWebAudioFallback(media, canvas)
        return
      }
      if (waviz.getFrequencyData?.() === null) {
        waviz.cleanup?.()
        waviz = null
        if (!destroyed) await startWebAudioFallback(media, canvas)
        return
      }
      try { await waviz.simpleBars?.() }
      catch {
        if (destroyed) return
        waviz.stop?.()
        drawFallback(canvas, () => waviz?.getFrequencyData?.() ?? null)
      }
    },
    stop() { waviz?.stop?.(); stopFallback() },
    destroy() {
      destroyed = true
      this.stop()
      waviz?.cleanup?.(); waviz = null
      source?.disconnect(); analyser?.disconnect(); source = null; analyser = null
      if (context && context.state !== 'closed') void context.close()
      context = null
    },
  }
}
