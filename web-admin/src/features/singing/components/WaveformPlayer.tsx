import { PauseOutlined, PlayCircleOutlined } from '@ant-design/icons'
import { Alert, Button, Space } from 'antd'
import WaveSurfer from 'wavesurfer.js'
import HoverPlugin from 'wavesurfer.js/dist/plugins/hover.esm.js'
import RegionsPlugin from 'wavesurfer.js/dist/plugins/regions.esm.js'
import TimelinePlugin from 'wavesurfer.js/dist/plugins/timeline.esm.js'
import { useEffect, useRef, useState } from 'react'

import { ApiError } from '../../../api/errors'
import { createPlaybackClock, type PlaybackClock } from '../PlaybackClock'
import { createVisualizerAdapter, type VisualizerAdapter } from './VisualizerAdapter'

export type Track = { assetId: string; url: string }
export type PlayerMedia = { mixed?: Track; video?: Track; videoExpected?: boolean; vocal?: Track; accompaniment?: Track }
export type WaveHandle = {
  addRegion(region: { start: number; end: number; color?: string }): unknown
  destroy(): void
  seek?(seconds: number): void
  onError?(listener: (error?: unknown) => void): () => void
}
type MediaFailure = { asset: Track; message: string; requestId?: string }
type TrackKey = 'mixed' | 'vocal' | 'accompaniment'

function failureOf(asset: Track, error: unknown): MediaFailure {
  const apiError = error instanceof ApiError ? error : null
  const source = error instanceof Error ? error : null
  return { asset, message: apiError?.message ?? source?.message ?? '媒体播放或授权失败，请重试', requestId: apiError?.requestId ?? (source as (Error & { requestId?: string }) | null)?.requestId }
}

export function WaveformPlayer({ media, events, waveFactory, visualizerFactory = createVisualizerAdapter, onRefreshMedia, onTime }: {
  media: PlayerMedia; events: number[]; waveFactory?: (container: HTMLElement) => WaveHandle; visualizerFactory?: () => VisualizerAdapter; onRefreshMedia?: (assetId: string) => Promise<string>; onTime?: (seconds: number) => void
}) {
  const container = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const visualizer = useRef<VisualizerAdapter | null>(null)
  const audio = useRef<HTMLAudioElement>(null)
  const video = useRef<HTMLVideoElement>(null)
  const clock = useRef<PlaybackClock | null>(null)
  const handle = useRef<WaveHandle | null>(null)
  const resumeAt = useRef(0)
  const activeAssetIds = useRef(new Set<string>())
  const refreshed = useRef(new Set<string>())
  const [playing, setPlaying] = useState(false)
  const [activeTrack, setActiveTrack] = useState<TrackKey>('mixed')
  const [videoFailed, setVideoFailed] = useState(false)
  const [overrides, setOverrides] = useState<Record<string, string>>({})
  const [failure, setFailure] = useState<MediaFailure | null>(null)
  const mixed = media.mixed
  const mixedAssetId = media.mixed?.assetId
  const videoAssetId = media.video?.assetId
  const vocalAssetId = media.vocal?.assetId
  const accompanimentAssetId = media.accompaniment?.assetId
  const selectedTrack = media[activeTrack] ?? mixed
  const selectedAssetId = selectedTrack?.assetId
  const videoTrack = videoFailed ? undefined : media.video
  const selectedUrl = selectedTrack ? overrides[selectedTrack.assetId] ?? selectedTrack.url : ''
  const videoUrl = videoTrack ? overrides[videoTrack.assetId] ?? videoTrack.url : ''

  useEffect(() => {
    activeAssetIds.current = new Set([mixedAssetId, videoAssetId, vocalAssetId, accompanimentAssetId].filter((assetId): assetId is string => Boolean(assetId)))
    return () => { activeAssetIds.current = new Set() }
  }, [accompanimentAssetId, mixedAssetId, videoAssetId, vocalAssetId])

  useEffect(() => {
    if (!container.current || !selectedAssetId || !audio.current) return
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
    clock.current = createPlaybackClock(audio.current, video.current)
    const unsubscribe = clock.current.subscribe(onTime ?? (() => undefined))
    return () => {
      unsubscribeWaveError?.()
      unsubscribe(); visualizer.current?.destroy(); visualizer.current = null
      clock.current?.destroy(); clock.current = null
      created.destroy(); handle.current = null
    }
  }, [events, onTime, selectedAssetId, selectedUrl, videoTrack?.assetId, videoUrl, waveFactory])

  if (!mixed) return <p className="inline-error">缺少可播放的真实演唱录音。</p>

  const refresh = async (asset: Track, force = false): Promise<boolean> => {
    if (!onRefreshMedia) return false
    if (!force && refreshed.current.has(asset.assetId)) {
      setFailure({ asset, message: '授权自动刷新次数已用尽，请手动重试。' })
      return false
    }
    refreshed.current.add(asset.assetId)
    setFailure(null)
    try {
      const url = await onRefreshMedia(asset.assetId)
      if (!activeAssetIds.current.has(asset.assetId)) return false
      setOverrides((current) => ({ ...current, [asset.assetId]: url }))
      if (asset.assetId === media.video?.assetId) setVideoFailed(false)
      return true
    } catch (error) {
      if (activeAssetIds.current.has(asset.assetId)) setFailure(failureOf(asset, error))
      return false
    }
  }

  const play = async () => {
    setFailure(null)
    try {
      await clock.current?.play()
      if (audio.current && canvas.current) {
        visualizer.current ??= visualizerFactory()
        await visualizer.current.start(audio.current, canvas.current)
      }
      setPlaying(true)
    } catch (error) {
      setPlaying(false)
      setFailure({ asset: selectedTrack ?? mixed, message: error instanceof DOMException && error.name === 'NotAllowedError' ? '浏览器阻止自动播放，请再次点击播放按钮。' : failureOf(selectedTrack ?? mixed, error).message, requestId: failureOf(selectedTrack ?? mixed, error).requestId })
    }
  }

  const selectTrack = (track: TrackKey) => {
    if (!media[track] || track === activeTrack) return
    resumeAt.current = audio.current?.currentTime ?? 0
    clock.current?.pause()
    visualizer.current?.stop()
    setPlaying(false)
    setActiveTrack(track)
  }

  return <section className="waveform-player" aria-label="演唱回放">
    <audio key={`${selectedTrack?.assetId}:${selectedUrl}`} ref={audio} src={selectedUrl} onError={() => selectedTrack && void refresh(selectedTrack)} />
    {videoTrack ? <video ref={video} src={videoUrl} onError={() => { void refresh(videoTrack).then((renewed) => { if (!renewed) setVideoFailed(true) }) }} controls /> : <p>{media.video && videoFailed ? '录像加载失败，已降级为音频回放。' : media.videoExpected ? '录像授权尚未可用，音频回放不受影响。' : '未提供录像，音频回放不受影响。'}</p>}
    {failure ? <Alert className="media-playback-error" type="error" showIcon title={failure.message} description={failure.requestId ? `请求编号：${failure.requestId}` : undefined} action={<Button aria-label="重试媒体授权" onClick={() => void refresh(failure.asset, true)}>重试</Button>} /> : null}
    <canvas ref={canvas} width="520" height="80" aria-label="播放可视化" />
    <div role="tablist" aria-label="音轨选择">
      <button role="tab" aria-selected={activeTrack === 'mixed'} onClick={() => selectTrack('mixed')}>人声 + 伴奏</button>
      <button role="tab" aria-selected={activeTrack === 'vocal'} aria-disabled={!media.vocal} disabled={!media.vocal} onClick={() => selectTrack('vocal')}>仅人声</button>
      <button role="tab" aria-selected={activeTrack === 'accompaniment'} aria-disabled={!media.accompaniment} disabled={!media.accompaniment} onClick={() => selectTrack('accompaniment')}>仅伴奏</button>
    </div>
    {(!media.vocal || !media.accompaniment) ? <p>缺少真实分轨产物；当前仅可播放真实演唱混合录音。</p> : null}
    <div className="waveform-scroll"><div ref={container} className="waveform" /></div>
    <Space>
      <Button aria-label={playing ? '暂停' : '播放'} icon={playing ? <PauseOutlined /> : <PlayCircleOutlined />} onClick={() => {
        if (playing) { clock.current?.pause(); visualizer.current?.stop(); setPlaying(false) } else void play()
      }}>{playing ? '暂停' : '播放'}</Button>
      {events.map((seconds) => <Button key={seconds} onClick={() => { clock.current?.seek(seconds); handle.current?.seek?.(seconds) }} aria-label={`跳转至 ${seconds} 秒`}>{seconds}s 嗳气</Button>)}
    </Space>
  </section>
}
