import { useQuery } from '@tanstack/react-query'
import { ArrowLeftOutlined, PlayCircleOutlined } from '@ant-design/icons'
import { Alert, Button, Spin } from 'antd'
/* eslint-disable react-refresh/only-export-components */
import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { useAuthStore } from '../../auth/store'
import { getPrivateMediaUrl, getSessionAccompanimentUrl, getSingingSession } from './api'
import { MetricPanel, nearestMetricSample } from './components/MetricPanel'
import { WaveformPlayer, type PlayerMedia } from './components/WaveformPlayer'
import type { SingingSession } from './types'

export { nearestMetricSample }

function audioResult(session: SingingSession) { return session.analysis_results.find((item) => item.task_type === 'singing_audio_metrics') }
function descriptionFor(error: unknown) { return error instanceof ApiError && error.requestId ? `请求编号：${error.requestId}` : undefined }
function messageFor(error: unknown, fallback: string) { return error instanceof Error ? error.message : fallback }

function usePrivateMedia(session: SingingSession, requested: boolean) {
  const authEpoch = useAuthStore((state) => state.sessionEpoch)
  const audioBinding = session.media.find((item) => item.media_type === 'singing_audio')
  const videoBinding = session.media.find((item) => item.media_type === 'singing_video')
  const audio = useQuery({
    queryKey: ['singing-private-url', authEpoch, session.id, 'audio', audioBinding?.asset_id ?? 'none'],
    enabled: requested && Boolean(audioBinding),
    retry: false,
    staleTime: 0,
    gcTime: 0,
    queryFn: ({ signal }) => getPrivateMediaUrl(audioBinding!.asset_id, signal),
  })
  const video = useQuery({
    queryKey: ['singing-private-url', authEpoch, session.id, 'video', videoBinding?.asset_id ?? 'none'],
    enabled: requested && Boolean(videoBinding),
    retry: false,
    staleTime: 0,
    gcTime: 0,
    queryFn: ({ signal }) => getPrivateMediaUrl(videoBinding!.asset_id, signal),
  })
  const accompaniment=useQuery({
    queryKey:['singing-private-url',authEpoch,session.id,'accompaniment',session.playback?.accompaniment_asset_id],
    enabled:requested&&Boolean(session.playback?.combined_available),retry:false,staleTime:0,gcTime:0,
    queryFn:async({signal})=>{const grant=await getSessionAccompanimentUrl(session.id,signal);if(grant.asset_id!==session.playback?.accompaniment_asset_id)throw new Error('会话伴奏资产不匹配');return grant},
  })
  return { audio, audioBinding, video, videoBinding, accompaniment }
}

export function SingingDetailContent({ session, onBack }: { session: SingingSession; onBack?:()=>void }) {
  const [seconds, setSeconds] = useState(0)
  const [mediaRequested, setMediaRequested] = useState(false)
  const sessionFence = useRef(session.id)
  useEffect(() => { sessionFence.current = session.id }, [session.id])
  const source = audioResult(session)
  const events = useMemo(()=>source?.payload?.burp_events ?? [],[source])
  const urls = usePrivateMedia(session, mediaRequested)
  const hasMediaBindings = Boolean(urls.audioBinding || urls.videoBinding)
  const currentMedia: PlayerMedia = {
    patientAudio: urls.audioBinding && urls.audio.data ? { assetId: urls.audioBinding.asset_id, url: urls.audio.data.url } : undefined,
    video: urls.videoBinding && urls.video.data ? { assetId: urls.videoBinding.asset_id, url: urls.video.data.url } : undefined,
    videoExpected: Boolean(urls.videoBinding),
    accompaniment: urls.accompaniment.data ? {assetId:urls.accompaniment.data.asset_id,url:urls.accompaniment.data.url}:undefined,
    metadata: session.playback?.metadata,

  }
  const refreshMedia = async (assetId: string) => {
    const expectedSession = sessionFence.current
    const epoch=useAuthStore.getState().sessionEpoch
    const next = assetId===session.playback?.accompaniment_asset_id ? await getSessionAccompanimentUrl(expectedSession) : await getPrivateMediaUrl(assetId)
    if(epoch!==useAuthStore.getState().sessionEpoch)throw new ApiError('stale_media_response','已忽略过期账户授权')
    if('asset_id' in next && next.asset_id!==assetId)throw new ApiError('media_binding_mismatch','会话伴奏资产不匹配')
    if (expectedSession !== sessionFence.current) throw new ApiError('stale_media_response', '已忽略过期媒体授权响应')
    return next.url
  }
  const analysisFailed = session.analysis_results.find((result) => result.status === 'failed')
  return <section className="management-page singing-detail-page" aria-labelledby="singing-detail-title">
    <div className="management-heading"><div><h1 id="singing-detail-title">演唱明细</h1><p>模拟分析结果，不用于临床诊断或现场监测</p></div></div>
    <div className="singing-summary-card">
      <Button type="text" className="singing-back" icon={<ArrowLeftOutlined />} aria-label="返回" onClick={onBack} />
      <div className="singing-summary-copy">
        <h2>{session.song.title ?? '—'} — {session.patient.name ?? '—'} ({session.patient.medical_record_no ?? '—'})</h2>
        <p>演唱时间：{session.completed_at ?? session.created_at ?? '—'} · 演唱时长 {session.duration_seconds === null ? '—' : `${session.duration_seconds} 秒`} · 嗳气 {session.burp_count ?? '—'} 次</p>
        <span className="visually-hidden">嗳气次数</span>
        <small>模拟分析结果 · 不作为临床依据</small>
      </div>
      <div className="singing-score"><strong>{session.score ?? '—'}</strong><span>综合得分</span></div>
    </div>
    <div className="singing-detail-content">
      {analysisFailed ? <Alert type="warning" showIcon message="模拟分析任务失败，以下指标可能没有结果。" description={analysisFailed.error_summary || analysisFailed.error_code || '请稍后重试分析任务。'} /> : null}
      {hasMediaBindings && !mediaRequested ? <div className="media-preview-layout">
        <div className="audio-workspace">
          <div className="spectrum-card"><h2>声音波形</h2><div className="media-prepare"><Button type="primary" shape="circle" icon={<PlayCircleOutlined />} aria-label="准备回放" onClick={() => setMediaRequested(true)} /><span>点击准备演唱回放</span></div></div>
          
          <MetricPanel seconds={seconds} result={source} />
        </div>
        <aside className="video-card"><h2>演唱录像</h2><div className="video-placeholder"><PlayCircleOutlined /><p>点击准备后加载录像</p></div></aside>
      </div> : null}
      {mediaRequested && urls.audioBinding && urls.audio.isPending ? <Spin aria-label="正在获取媒体授权" /> : null}
      {mediaRequested && urls.audio.isError ? <Alert className="media-playback-error" type="error" showIcon title={messageFor(urls.audio.error, '媒体授权失败，请重试')} description={descriptionFor(urls.audio.error)} action={<Button aria-label="重试媒体授权" onClick={() => void urls.audio.refetch()}>重试</Button>} /> : null}
      {mediaRequested && urls.accompaniment.isError ? <Alert type="warning" title={messageFor(urls.accompaniment.error,'伴奏授权失败，患者人声仍可播放')} action={<Button onClick={()=>void urls.accompaniment.refetch()}>重试伴奏授权</Button>}/> : null}
      {mediaRequested && urls.videoBinding && urls.video.isPending ? <Spin aria-label="正在获取录像授权" /> : null}
      {mediaRequested && urls.video.isError ? <Alert className="media-playback-error" type="warning" showIcon title={messageFor(urls.video.error, '录像授权失败，音频仍可播放')} description={descriptionFor(urls.video.error)} action={<Button aria-label="重试录像授权" onClick={() => void urls.video.refetch()}>重试</Button>} /> : null}
      {(!hasMediaBindings || (mediaRequested && !urls.audio.isError && (!urls.audioBinding || !urls.audio.isPending))) ? <WaveformPlayer key={`${session.id}:${currentMedia.patientAudio?.assetId ?? 'none'}`} media={currentMedia} events={events} analysisResult={source} onRefreshMedia={refreshMedia} onTime={setSeconds}><MetricPanel seconds={seconds} result={source} /></WaveformPlayer> : null}
    </div>
  </section>
}

export function SingingDetailPage() {
  const { sessionId = '' } = useParams()
  const navigate=useNavigate(),location=useLocation()
  const backTo=typeof location.state?.backTo==='string' && /^\/patients\/[^/?]+\/data(\?|$)/.test(location.state.backTo)? location.state.backTo:'/patient-data'
  const query = useQuery({ queryKey: ['singing', 'detail', sessionId], queryFn: ({ signal }) => getSingingSession(sessionId, signal), enabled: Boolean(sessionId), retry: false })
  if (query.isPending) return <Spin aria-label="正在加载演唱明细" />
  if (query.isError || !query.data) return <Alert type="error" showIcon title={messageFor(query.error, '无法加载演唱明细')} description={descriptionFor(query.error)} action={<Button aria-label="重试加载演唱明细" onClick={() => void query.refetch()}>重试</Button>} />
  return <SingingDetailContent key={query.data.id} session={query.data} onBack={()=>navigate(backTo,{state:{backTo:location.state?.patientDataBackTo}})} />
}
