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
export type PlayerMedia = { patientAudio?: Track; mixed?: Track; video?: Track; videoExpected?: boolean; accompaniment?: Track; metadata?: PlaybackMetadata | null; accompanimentOffsetMillis?:number; manualAccompaniment?:boolean }
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
  const resumeAt = useRef({assetId:'',seconds:0,wanted:false})
  const wanted = useRef(false)
  const activeAssetIds = useRef(new Set<string>())
  const refreshed = useRef(new Set<string>())
  const refreshGenerations = useRef(new Map<string, number>())
  const canCombine=Boolean(media.accompaniment && (media.metadata || media.manualAccompaniment))
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
  const backingUrl = media.accompaniment ? overrides[media.accompaniment.assetId] ?? media.accompaniment.url : ''

  useEffect(() => {
    activeAssetIds.current = new Set([mixedAssetId, videoAssetId, accompanimentAssetId].filter((assetId): assetId is string => Boolean(assetId)))
    return () => { activeAssetIds.current = new Set() }
  }, [accompanimentAssetId, mixedAssetId, videoAssetId])

  // 只有患者资产/授权 URL 更换才重建主时钟；跟随音轨变化不能销毁患者采样链。
  useEffect(() => {
    const patient=audio.current
    if(!patient || !selectedAssetId) return
    const created=createPlaybackClock(patient)
    clock.current=created
    const resume=resumeAt.current
    const resetVisuals=()=>visualizer.current?.reset?.()
    patient.addEventListener('seeked',resetVisuals)
    const restore=async()=>{
      if(clock.current!==created || resume.assetId!==selectedAssetId) return
      patient.currentTime=resume.seconds
      if(!resume.wanted) return
      try {
        await created.play()
        if(clock.current!==created) return
        setPlaying(true)
        if(canvas.current){
          visualizer.current ??= visualizerFactory()
          try { await visualizer.current.start(patient,canvas.current,polar.current,setAudioFrame) }
          catch { setAudioFrame({timeDomain:new Float32Array(0),frequency:new Uint8Array(0),rmsDbfs:null,pitchHz:null,status:'unavailable'}) }
        }
      } catch { if(clock.current===created) setPlaying(false) }
    }
    patient.addEventListener('loadedmetadata',restore)
    if(patient.readyState>=1) void restore()
    return()=>{
      resumeAt.current={assetId:selectedAssetId,seconds:patient.currentTime,wanted:wanted.current}
      patient.removeEventListener('loadedmetadata',restore);patient.removeEventListener('seeked',resetVisuals)
      visualizer.current?.destroy();visualizer.current=null
      created.destroy();if(clock.current===created) clock.current=null
    }
    // 工厂只在用户播放/新授权创建采样器时使用，不属于主音频身份。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  },[selectedAssetId,selectedUrl])

  useEffect(()=>clock.current?.subscribe(value=>{setSeconds(value);onTime?.(value)}),[onTime,selectedAssetId,selectedUrl])

  useEffect(()=>{
    clock.current?.setFollowers(video.current,backing.current,media.metadata,media.manualAccompaniment ? 0 : media.accompanimentOffsetMillis,media.manualAccompaniment ? 0 : undefined)
    clock.current?.setMode(modeRef.current)
  },[selectedAssetId,selectedUrl,videoTrack?.assetId,videoUrl,backingUrl,media.metadata,media.accompanimentOffsetMillis,media.manualAccompaniment])

  useEffect(() => {
    if (!container.current || !selectedAssetId || !audio.current) return
    const patient=audio.current
    const factory = waveFactory ?? ((element: HTMLElement) => {
      const regions = RegionsPlugin.create()
      const wave = WaveSurfer.create({ container: element, media: patient, height: 96, waveColor: '#a9c7ff', progressColor: '#3478f6', plugins: [regions, TimelinePlugin.create(), HoverPlugin.create()] })
      return {addRegion: (region) => regions.addRegion(region),destroy: () => wave.destroy(),seek: (seconds) => wave.setTime(seconds),onError: (listener) => wave.on('error', listener)}
    })
    const created = factory(container.current)
    handle.current=created
    events.forEach(seconds=>created.addRegion({start:seconds,end:seconds+.15,color:'rgba(255,77,79,.55)'}))
    const unsubscribe=created.onError?.(()=>patient.dispatchEvent(new Event('error')))
    return()=>{unsubscribe?.();created.destroy();if(handle.current===created) handle.current=null}
  },[events,selectedAssetId,selectedUrl,waveFactory])

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
      wanted.current=true
      await clock.current?.play()
      if (audio.current && canvas.current) {
        visualizer.current ??= visualizerFactory()
        try { await visualizer.current.start(audio.current, canvas.current, polar.current,setAudioFrame) } catch { setAudioFrame({timeDomain:new Float32Array(0),frequency:new Uint8Array(0),rmsDbfs:null,pitchHz:null,status:'unavailable'}) }
      }
      setPlaying(true)
    } catch (error) {
      wanted.current=false
      setPlaying(false)
      setFailure({ asset: selectedTrack ?? mixed, message: error instanceof DOMException && error.name === 'NotAllowedError' ? '浏览器阻止自动播放，请再次点击播放按钮。' : failureOf(selectedTrack ?? mixed, error).message, requestId: failureOf(selectedTrack ?? mixed, error).requestId })
    }
  }

  const selectTrack = (track: TrackKey) => {
    if (track==='combined' && !canCombine) return
    modeRef.current=track
    clock.current?.setMode(track)
    setActiveTrack(track)
  }

  const videoMessage = media.video && videoFailed ? '录像加载失败，已降级为音频回放。' : media.videoExpected ? '录像授权尚未可用，音频回放不受影响。' : '未提供录像，音频回放不受影响。'
  return <section className="waveform-player" aria-label="演唱回放">
    <div className="audio-workspace">
      <div className="spectrum-card">
        <h2>声音波形</h2>
        <audio key={`${selectedTrack?.assetId}:${selectedUrl}`} ref={audio} crossOrigin="anonymous" src={selectedUrl} onPause={(event) => { if(audio.current===event.currentTarget){wanted.current=false;setPlaying(false); visualizer.current?.stop()} }} onEnded={() => { wanted.current=false;setPlaying(false); visualizer.current?.stop() }} onError={() => selectedTrack && void refresh(selectedTrack)} />
        {media.accompaniment ? <audio ref={backing} crossOrigin="anonymous" src={backingUrl} onError={() => { selectTrack('patient'); void refresh(media.accompaniment!) }} /> : null}
        {failure ? <Alert className="media-playback-error" type="error" showIcon title={failure.message} description={failure.requestId ? `请求编号：${failure.requestId}` : undefined} action={<Button aria-label="重试媒体授权" onClick={() => void refresh(failure.asset, true)}>重试</Button>} /> : null}
        <Space>
          <Button type="primary" shape="circle" aria-label={playing ? '暂停' : '播放'} icon={playing ? <PauseOutlined /> : <PlayCircleOutlined />} onClick={() => {
            if (playing) { wanted.current=false;clock.current?.pause(); visualizer.current?.stop(); setPlaying(false) } else void play()
          }} />
        </Space>
        <p aria-label="录音播放时间">{seconds.toFixed(1)} 秒</p>
        <div role="tablist" aria-label="音轨选择">
          <button role="tab" aria-selected={activeTrack === 'combined'} disabled={!canCombine} onClick={() => selectTrack('combined')}>人声 + 伴奏</button>
          <button role="tab" aria-selected={activeTrack === 'patient'} onClick={() => selectTrack('patient')}>人声</button>
        </div>
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
