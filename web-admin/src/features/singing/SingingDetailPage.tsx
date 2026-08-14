import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Descriptions, Spin } from 'antd'
/* eslint-disable react-refresh/only-export-components */
import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { getPrivateMediaUrl, getSingingSession } from './api'
import { MetricPanel, nearestMetricSample } from './components/MetricPanel'
import { WaveformPlayer, type PlayerMedia } from './components/WaveformPlayer'
import type { SingingSession } from './types'

export { nearestMetricSample }

function audioResult(session: SingingSession) { return session.analysis_results.find((item) => item.task_type === 'singing_audio_metrics') }
function descriptionFor(error: unknown) { return error instanceof ApiError && error.requestId ? `请求编号：${error.requestId}` : undefined }
function messageFor(error: unknown, fallback: string) { return error instanceof Error ? error.message : fallback }

function usePrivateMedia(session: SingingSession) {
  const sessionFence = useRef(session.id)
  useEffect(() => { sessionFence.current = session.id }, [session.id])
  return useQuery({
    queryKey: ['singing-media', session.id, session.analysis_generation ?? 0],
    retry: false,
    queryFn: async ({ signal }) => {
      const bindings = session.media.filter((item) => item.media_type === 'singing_audio' || item.media_type === 'singing_video')
      const resolved = await Promise.all(bindings.map(async (item) => [item, await getPrivateMediaUrl(item.asset_id, signal)] as const))
      if (sessionFence.current !== session.id) throw new ApiError('stale_media_response', '已忽略过期媒体授权响应')
      const mixed = resolved.find(([item]) => item.media_type === 'singing_audio')
      const video = resolved.find(([item]) => item.media_type === 'singing_video')
      return {
        mixed: mixed ? { assetId: mixed[0].asset_id, url: mixed[1].url } : undefined,
        video: video ? { assetId: video[0].asset_id, url: video[1].url } : undefined,
      }
    },
  })
}

export function SingingDetailContent({ session }: { session: SingingSession }) {
  const [seconds, setSeconds] = useState(0)
  const sessionFence = useRef(session.id)
  useEffect(() => { sessionFence.current = session.id }, [session.id])
  const source = audioResult(session)
  const events = source?.payload?.burp_events ?? []
  const urls = usePrivateMedia(session)
  const currentMedia: PlayerMedia = urls.data ? { mixed: urls.data.mixed, video: urls.data.video } : {}
  const refreshMedia = async (assetId: string) => {
    const expectedSession = sessionFence.current
    const next = await getPrivateMediaUrl(assetId)
    if (expectedSession !== sessionFence.current) throw new ApiError('stale_media_response', '已忽略过期媒体授权响应')
    return next.url
  }
  const analysisFailed = session.analysis_results.find((result) => result.status === 'failed')
  return <section className="management-page singing-detail-page" aria-labelledby="singing-detail-title">
    <div className="management-heading"><div><h1 id="singing-detail-title">演唱明细</h1><p>模拟分析结果，不用于临床诊断或现场监测</p></div></div>
    <div className="management-surface">
      <Descriptions bordered size="small" items={[
        { key: 'patient', label: '患者', children: session.patient.name ?? '—' },
        { key: 'song', label: '歌曲', children: `${session.song.title ?? '—'} · ${session.song.artist ?? ''}` },
        { key: 'time', label: '演唱时间', children: session.completed_at ?? session.created_at ?? '—' },
        { key: 'duration', label: '时长', children: session.duration_seconds === null ? '—' : `${session.duration_seconds} 秒` },
        { key: 'score', label: '得分', children: session.score ?? '—' },
        { key: 'burp', label: '嗳气次数', children: session.burp_count ?? '—' },
      ]} />
      {analysisFailed ? <Alert type="warning" showIcon message="模拟分析任务失败，以下指标可能没有结果。" description={analysisFailed.error_summary || analysisFailed.error_code || '请稍后重试分析任务。'} /> : null}
      {urls.isPending ? <Spin aria-label="正在获取媒体授权" /> : null}
      {urls.isError ? <Alert className="media-playback-error" type="error" showIcon title={messageFor(urls.error, '媒体授权失败，请重试')} description={descriptionFor(urls.error)} action={<Button aria-label="重试媒体授权" onClick={() => void urls.refetch()}>重试</Button>} /> : null}
      {!urls.isPending && !urls.isError ? <WaveformPlayer key={`${session.id}:${currentMedia.mixed?.assetId ?? 'none'}`} media={currentMedia} events={events} onRefreshMedia={refreshMedia} onTime={setSeconds} /> : null}
      <MetricPanel seconds={seconds} result={source} />
    </div>
  </section>
}

export function SingingDetailPage() {
  const { sessionId = '' } = useParams()
  const query = useQuery({ queryKey: ['singing', 'detail', sessionId], queryFn: ({ signal }) => getSingingSession(sessionId, signal), enabled: Boolean(sessionId), retry: false })
  if (query.isPending) return <Spin aria-label="正在加载演唱明细" />
  if (query.isError || !query.data) return <Alert type="error" showIcon title={messageFor(query.error, '无法加载演唱明细')} description={descriptionFor(query.error)} action={<Button aria-label="重试加载演唱明细" onClick={() => void query.refetch()}>重试</Button>} />
  return <SingingDetailContent key={query.data.id} session={query.data} />
}
