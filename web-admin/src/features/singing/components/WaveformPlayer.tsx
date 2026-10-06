import { PauseOutlined, PlayCircleOutlined } from '@ant-design/icons'
import { Alert, Button, Space } from 'antd'
import WaveSurfer from 'wavesurfer.js'
import HoverPlugin from 'wavesurfer.js/dist/plugins/hover.esm.js'
import RegionsPlugin from 'wavesurfer.js/dist/plugins/regions.esm.js'
import TimelinePlugin from 'wavesurfer.js/dist/plugins/timeline.esm.js'
import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'

import { ApiError } from '../../../api/errors'
import { createPlaybackClock, type PlaybackClock } from '../PlaybackClock'
import type { AnalysisResult, PlaybackMetadata } from '../types'
import { LiveVoiceBoard } from './LiveVoiceBoard'
import type { PatientAudioFrame } from './PatientAudioAnalyser'
import { createVisualizerAdapter, type VisualizerAdapter } from './VisualizerAdapter'

export type Track = { assetId: string; url: string }
export type PlayerMedia = { patientAudio?: Track; mixed?: Track; video?: Track; videoExpected?: boolean; accompaniment?: Track; metadata?: PlaybackMetadata | null }
export type WaveHandle = {
  addRegion(region: { start: number; end: number; color?: string }): unknown
  destroy(): void
  seek?(seconds: number): void
  onError?(listener: (error?: unknown) => void): () => void
}
type MediaFailure = { asset: Track; message: string; requestId?: string }
type TrackKey = 'combined' | 'patient'

function failureOf(asset: Track, error: unknown): MediaFailure {
  const apiError = error instanceof ApiError ? error : null
  const source = error instanceof Error ? error : null
  return { asset, message: apiError?.message ?? source?.message ?? '媒体播放或授权失败，请重试', requestId: apiError?.requestId ?? (source as (Error & { requestId?: string }) | null)?.requestId }
}

export function WaveformPlayer({ media, events, waveFactory, visualizerFactory = createVisualizerAdapter, onRefreshMedia, onTime, children, analysisResult }: {
  media: PlayerMedia; events: number[]; waveFactory?: (container: HTMLElement) => WaveHandle; visualizerFactory?: () => VisualizerAdapter; onRefreshMedia?: (assetId: string) => Promise<string>; onTime?: (seconds: number) => void; children?: ReactNode; analysisResult?:AnalysisResult
}) {
  const container = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const polar = useRef<HTMLCanvasElement>(null)
  const [audioFrame,setAudioFrame]=useState<PatientAudioFrame|null>(null)
  const [seconds,setSeconds]=useState(0)
  const visualizer = useRef<VisualizerAdapter | null>(null)
  const audio = useRef<HTMLAudioElement>(null)
  const video = useRef<HTMLVideoElement>(null)
  const clock = useRef<PlaybackClock | null>(null)
  const handle = useRef<WaveHandle | null>(null)
  const backing = useRef<HTMLAudioElement>(null)
  const modeRef = useRef<TrackKey>('patient')
  const resumeAt = useRef(0)
  const activeAssetIds = useRef(new Set<string>())
  const refreshed = useRef(new Set<string>())
  const refreshGenerations = useRef(new Map<string, number>())
  const [playing, setPlaying] = useState(false)
  const [chosenTrack, setActiveTrack] = useState<TrackKey|null>(null)
  const activeTrack:TrackKey = chosenTrack ?? (media.accompaniment && media.metadata ? 'combined' : 'patient')
  const [videoFailed, setVideoFailed] = useState(false)
  const [overrides, setOverrides] = useState<Record<string, string>>({})
  const [failure, setFailure] = useState<MediaFailure | null>(null)
  const mixed = media.patientAudio ?? media.mixed
  const mixedAssetId = mixed?.assetId
  const videoAssetId = media.video?.assetId
  const accompanimentAssetId = media.accompaniment?.assetId
  const selectedTrack = mixed
  const selectedAssetId = selectedTrack?.assetId
  const videoTrack = videoFailed ? undefined : media.video
  const selectedUrl = selectedTrack ? overrides[selectedTrack.assetId] ?? selectedTrack.url : ''
  const videoUrl = videoTrack ? overrides[videoTrack.assetId] ?? videoTrack.url : ''

  useEffect(() => {
    activeAssetIds.current = new Set([mixedAssetId, videoAssetId, accompanimentAssetId].filter((assetId): assetId is string => Boolean(assetId)))
    return () => { activeAssetIds.current = new Set() }
  }, [accompanimentAssetId, mixedAssetId, videoAssetId])

  useEffect(() => {
    if (!container.current || !selectedAssetId || !audio.current) return
    const patientElement=audio.current
    const resetVisuals=()=>visualizer.current?.reset?.()
    patientElement.addEventListener('seeked',resetVisuals)
    if (resumeAt.current > 0) audio.current.currentTime = resumeAt.current
    const factory = waveFactory ?? ((element: HTMLElement) => {
      const regions = RegionsPlugin.create()
      const wave = WaveSurfer.create({ container: element, media: audio.current ?? undefined, height: 96, waveColor: '#a9c7ff', progressColor: '#3478f6', plugins: [regions, TimelinePlugin.create(), HoverPlugin.create()] })
      return {
        addRegion: (region) => regions.addRegion(region),
        destroy: () => wave.destroy(),
        seek: (seconds) => wave.setTime(seconds),
        onError: (listener) => wave.on('error', listener),
      }
    })
    const created = factory(container.current)
    handle.current = created
    events.forEach((seconds) => created.addRegion({ start: seconds, end: seconds + 0.15, color: 'rgba(255,77,79,.55)' }))
    const unsubscribeWaveError = created.onError?.(() => audio.current?.dispatchEvent(new Event('error')))
    clock.current = createPlaybackClock(audio.current, video.current, backing.current, media.metadata)
    clock.current.setMode(modeRef.current)
    const unsubscribe = clock.current.subscribe((seconds)=>{setSeconds(seconds);onTime?.(seconds)})
    return () => {
      patientElement.removeEventListener('seeked',resetVisuals)
      resumeAt.current = patientElement.currentTime
      unsubscribeWaveError?.()
      unsubscribe(); visualizer.current?.destroy(); visualizer.current = null
      clock.current?.destroy(); clock.current = null
      created.destroy(); handle.current = null
    }
  }, [events, onTime, selectedAssetId, selectedUrl, videoTrack?.assetId, videoUrl, waveFactory, media.metadata, media.accompaniment?.url, accompanimentAssetId])

  useEffect(() => {
    modeRef.current = activeTrack
    clock.current?.setMode(activeTrack)
  }, [activeTrack])

  if (!mixed) return <p className="inline-error">缺少可播放的真实演唱录音。</p>

  const refresh = async (asset: Track, force = false): Promise<boolean> => {
    if (!onRefreshMedia) return false
    if (!force && refreshed.current.has(asset.assetId)) {
      setFailure({ asset, message: '授权自动刷新次数已用尽，请手动重试。' })
      return false
    }
    refreshed.current.add(asset.assetId)
    const generation = (refreshGenerations.current.get(asset.assetId) ?? 0) + 1
    refreshGenerations.current.set(asset.assetId, generation)
    setFailure(null)
    try {
      const url = await onRefreshMedia(asset.assetId)
      if (!activeAssetIds.current.has(asset.assetId)) return false
      if (refreshGenerations.current.get(asset.assetId) !== generation) return true
      setOverrides((current) => ({ ...current, [asset.assetId]: url }))
      if (asset.assetId === media.video?.assetId) setVideoFailed(false)
      return true
    } catch (error) {
      if (!activeAssetIds.current.has(asset.assetId)) return false
      if (refreshGenerations.current.get(asset.assetId) !== generation) return true
      setFailure(failureOf(asset, error))
      return false
    }
  }

  const play = async () => {
    setFailure(null)
    try {
      await clock.current?.play()
      if (audio.current && canvas.current) {
        visualizer.current ??= visualizerFactory()
        try { await visualizer.current.start(audio.current, canvas.current, polar.current,setAudioFrame) } catch { setAudioFrame({timeDomain:new Float32Array(0),frequency:new Uint8Array(0),rmsDbfs:null,pitchHz:null,status:'unavailable'}) }
      }
      setPlaying(true)
    } catch (error) {
      setPlaying(false)
      setFailure({ asset: selectedTrack ?? mixed, message: error instanceof DOMException && error.name === 'NotAllowedError' ? '浏览器阻止自动播放，请再次点击播放按钮。' : failureOf(selectedTrack ?? mixed, error).message, requestId: failureOf(selectedTrack ?? mixed, error).requestId })
    }
  }

  const selectTrack = (track: TrackKey) => {
    if (track==='combined' && (!media.accompaniment || !media.metadata)) return
    modeRef.current=track
    clock.current?.setMode(track)
    setActiveTrack(track)
  }

  const videoMessage = media.video && videoFailed ? '录像加载失败，已降级为音频回放。' : media.videoExpected ? '录像授权尚未可用，音频回放不受影响。' : '未提供录像，音频回放不受影响。'
  return <section className="waveform-player" aria-label="演唱回放">
    <div className="audio-workspace">
      <div className="spectrum-card">
        <h2>声音波形</h2>
        <audio key={`${selectedTrack?.assetId}:${selectedUrl}`} ref={audio} crossOrigin="anonymous" src={selectedUrl} onPause={() => { setPlaying(false); visualizer.current?.stop() }} onEnded={() => { setPlaying(false); visualizer.current?.stop() }} onError={() => selectedTrack && void refresh(selectedTrack)} />
        {media.accompaniment ? <audio ref={backing} crossOrigin="anonymous" src={overrides[media.accompaniment.assetId] ?? media.accompaniment.url} onError={() => { selectTrack('patient'); void refresh(media.accompaniment!) }} /> : null}
        {failure ? <Alert className="media-playback-error" type="error" showIcon title={failure.message} description={failure.requestId ? `请求编号：${failure.requestId}` : undefined} action={<Button aria-label="重试媒体授权" onClick={() => void refresh(failure.asset, true)}>重试</Button>} /> : null}
        <Space>
          <Button type="primary" shape="circle" aria-label={playing ? '暂停' : '播放'} icon={playing ? <PauseOutlined /> : <PlayCircleOutlined />} onClick={() => {
            if (playing) { clock.current?.pause(); visualizer.current?.stop(); setPlaying(false) } else void play()
          }} />
          {events.map((seconds) => <Button key={seconds} onClick={() => { clock.current?.seek(seconds); handle.current?.seek?.(seconds); visualizer.current?.reset?.() }} aria-label={`跳转至 ${seconds} 秒`}>{seconds}s 嗳气</Button>)}
        </Space>
        <p aria-label="录音播放时间">{seconds.toFixed(1)} 秒</p>
        <div role="tablist" aria-label="音轨选择">
          <button role="tab" aria-selected={activeTrack === 'combined'} disabled={!media.accompaniment || !media.metadata} onClick={() => selectTrack('combined')}>人声 + 伴奏</button>
          <button role="tab" aria-selected={activeTrack === 'patient'} onClick={() => selectTrack('patient')}>人声</button>
        </div>
        {(!media.accompaniment || !media.metadata) ? <p>缺少可信伴奏同步数据，当前可播放患者人声。</p> : null}
        <div className="waveform-scroll"><div ref={container} className="waveform" /></div>
      </div>
      <LiveVoiceBoard frame={audioFrame} result={analysisResult} seconds={seconds} playing={playing} waveRef={canvas} polarRef={polar}/>
      {children}
    </div>
    <aside className="video-card">
      <h2>演唱录像</h2>
      {videoTrack ? <video ref={video} src={videoUrl} onError={() => { void refresh(videoTrack).then((renewed) => { if (!renewed) setVideoFailed(true) }) }} muted playsInline /> : <div className="video-placeholder"><PlayCircleOutlined /><p>{videoMessage}</p></div>}
    </aside>
  </section>
}
