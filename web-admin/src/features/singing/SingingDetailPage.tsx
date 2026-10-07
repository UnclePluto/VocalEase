import { useQuery } from '@tanstack/react-query'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { Button, Spin } from 'antd'
/* eslint-disable react-refresh/only-export-components */
import { useEffect, useMemo, useRef } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { useAuthStore } from '../../auth/store'
import { getPrivateMediaUrl, getSessionAccompanimentUrl, getSingingSession } from './api'
import { nearestMetricSample } from './components/MetricPanel'
import { WaveformPlayer, type PlayerMedia } from './components/WaveformPlayer'
import { DetailToast, DetailToastProvider } from './components/DetailToast'
import type { SingingSession } from './types'

export { nearestMetricSample }

function audioResult(session: SingingSession) { return session.analysis_results.find((item) => item.task_type === 'singing_audio_metrics') }
function requestIdFor(error: unknown) { return error instanceof ApiError ? error.requestId : undefined }
function messageFor(error: unknown, fallback: string) { return error instanceof Error ? error.message : fallback }

function usePrivateMedia(session: SingingSession) {
  const authEpoch = useAuthStore((state) => state.sessionEpoch)
  const audioBinding = session.media.find((item) => item.media_type === 'singing_audio')
  const videoBinding = session.media.find((item) => item.media_type === 'singing_video')
  const audio = useQuery({
    queryKey: ['singing-private-url', authEpoch, session.id, 'audio', audioBinding?.asset_id ?? 'none'],
    enabled: Boolean(audioBinding),
    retry: false,
    staleTime: 0,
    gcTime: 0,
    queryFn: ({ signal }) => getPrivateMediaUrl(audioBinding!.asset_id, signal),
  })
  const video = useQuery({
    queryKey: ['singing-private-url', authEpoch, session.id, 'video', videoBinding?.asset_id ?? 'none'],
    enabled: Boolean(videoBinding),
    retry: false,
    staleTime: 0,
    gcTime: 0,
    queryFn: ({ signal }) => getPrivateMediaUrl(videoBinding!.asset_id, signal),
  })
  const accompanimentId=session.playback?.combined_available ? session.playback.accompaniment_asset_id : session.playback?.accompaniment_preview_asset_id
  const previewId=session.playback?.combined_available ? undefined : accompanimentId ?? undefined
  const accompaniment=useQuery({
    queryKey:['singing-private-url',authEpoch,session.id,'accompaniment',accompanimentId,previewId],
    enabled:Boolean(accompanimentId && (session.playback?.combined_available || session.playback?.accompaniment_preview_available)),retry:false,staleTime:0,gcTime:0,
    queryFn:async({signal})=>{const grant=await getSessionAccompanimentUrl(session.id,signal,previewId);if(grant.asset_id!==accompanimentId)throw new Error('会话伴奏资产不匹配');return grant},
  })
  return { audio, audioBinding, video, videoBinding, accompaniment, accompanimentId, previewId }
}

export function SingingDetailContent({ session, onBack }: { session: SingingSession; onBack?:()=>void }) {
  const sessionFence = useRef(session.id)
  useEffect(() => { sessionFence.current = session.id }, [session.id])
  const source = audioResult(session)
  const events = useMemo(()=>source?.payload?.burp_events ?? [],[source])
  const urls = usePrivateMedia(session)
  const currentMedia: PlayerMedia = {
    patientAudio: urls.audioBinding && urls.audio.data ? { assetId: urls.audioBinding.asset_id, url: urls.audio.data.url } : undefined,
    video: urls.videoBinding && urls.video.data ? { assetId: urls.videoBinding.asset_id, url: urls.video.data.url } : undefined,
    videoExpected: Boolean(urls.videoBinding),
    accompaniment: urls.accompaniment.data ? {assetId:urls.accompaniment.data.asset_id,url:urls.accompaniment.data.url}:undefined,
    metadata: session.playback?.combined_available ? session.playback.metadata : null,
    manualAccompaniment: Boolean(urls.previewId),
    accompanimentOffsetMillis:session.playback?.accompaniment_offset_ms ?? 0,

  }
  const refreshMedia = async (assetId: string) => {
    const expectedSession = sessionFence.current
    const epoch=useAuthStore.getState().sessionEpoch
    const next = assetId===urls.accompanimentId ? await getSessionAccompanimentUrl(expectedSession,undefined,urls.previewId) : await getPrivateMediaUrl(assetId)
    if(epoch!==useAuthStore.getState().sessionEpoch)throw new ApiError('stale_media_response','已忽略过期账户授权')
    if('asset_id' in next && next.asset_id!==assetId)throw new ApiError('media_binding_mismatch','会话伴奏资产不匹配')
    if (expectedSession !== sessionFence.current) throw new ApiError('stale_media_response', '已忽略过期媒体授权响应')
    return next.url
  }
  const analysisFailed = session.analysis_results.find((result) => result.status === 'failed')
  return <DetailToastProvider><section className="management-page singing-detail-page" aria-labelledby="singing-detail-title">
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
      <DetailToast type="warning" text={analysisFailed ? `模拟分析任务失败，以下指标可能没有结果。${analysisFailed.error_summary || analysisFailed.error_code || ''}` : undefined} />
      <DetailToast text={urls.audio.isError ? messageFor(urls.audio.error, '媒体授权失败，请重试') : undefined} requestId={requestIdFor(urls.audio.error)} retryLabel="重试媒体授权" onRetry={() => void urls.audio.refetch()} />
      <DetailToast type="warning" text={urls.accompaniment.isError ? messageFor(urls.accompaniment.error, '伴奏授权失败，患者人声仍可播放') : undefined} requestId={requestIdFor(urls.accompaniment.error)} retryLabel="重试伴奏授权" onRetry={() => void urls.accompaniment.refetch()} />
      <DetailToast type="warning" text={urls.video.isError ? messageFor(urls.video.error, '录像授权失败，音频仍可播放') : undefined} requestId={requestIdFor(urls.video.error)} retryLabel="重试录像授权" onRetry={() => void urls.video.refetch()} />
      {(!urls.audio.isError && (!urls.audioBinding || !urls.audio.isPending)) ? <WaveformPlayer key={`${session.id}:${currentMedia.patientAudio?.assetId ?? 'none'}`} media={currentMedia} events={events} analysisResult={source} onRefreshMedia={refreshMedia} /> : null}
    </div>
  </section></DetailToastProvider>
}

export function SingingDetailPage() {
  const { sessionId = '' } = useParams()
  const navigate=useNavigate(),location=useLocation()
  const backTo=typeof location.state?.backTo==='string' && /^\/patients\/[^/?]+\/data(\?|$)/.test(location.state.backTo)? location.state.backTo:'/patient-data'
  const query = useQuery({ queryKey: ['singing', 'detail', sessionId], queryFn: ({ signal }) => getSingingSession(sessionId, signal), enabled: Boolean(sessionId), retry: false })
  if (query.isPending) return <Spin aria-label="正在加载演唱明细" />
  if (query.isError || !query.data) return <DetailToast text={messageFor(query.error, '无法加载演唱明细')} requestId={requestIdFor(query.error)} retryLabel="重试加载演唱明细" onRetry={() => void query.refetch()} />
  return <SingingDetailContent key={query.data.id} session={query.data} onBack={()=>navigate(backTo,{state:{backTo:location.state?.patientDataBackTo}})} />
}
