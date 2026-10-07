import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '../../auth/store'
import { renderApp } from '../../test/renderApp'
import { server } from '../../test/server'
import { nearestMetricSample, SingingDetailContent } from './SingingDetailPage'

vi.mock('wavesurfer.js', () => ({ default: { create: vi.fn(() => ({ destroy: vi.fn(), on: vi.fn(() => vi.fn()), setTime: vi.fn() })) } }))
vi.mock('wavesurfer.js/dist/plugins/hover.esm.js', () => ({ default: { create: vi.fn(() => ({})) } }))
vi.mock('wavesurfer.js/dist/plugins/regions.esm.js', () => ({ default: { create: vi.fn(() => ({ addRegion: vi.fn() })) } }))
vi.mock('wavesurfer.js/dist/plugins/timeline.esm.js', () => ({ default: { create: vi.fn(() => ({})) } }))

const session = { id: 'session-1', status: 'completed', score: 92, burp_count: 1, duration_seconds: 10, is_mock: true, patient: { name: '小明', medical_record_no: 'MR-1' }, song: { title: '示例歌曲', artist: '演示' }, media: [], analysis_results: [{ task_type: 'singing_audio_metrics', status: 'completed', is_mock: true, payload: { burp_events: [3] }, time_series: { volume: { sample_interval_ms: 1000, values: [1, 2, 3] } } }] }
const envelope = (data: unknown) => ({ code: 'ok', message: '', data, request_id: 'detail-ok' })

describe('SingingDetailPage', () => {
  it('展示明确的模拟指标，并以最近的主时钟采样值读取空序列安全降级', async () => {
    expect(nearestMetricSample([1, 2, 3], 1000, 2.4)).toBe(3)
    expect(nearestMetricSample([1, 2, 3], 1000, 1.6)).toBe(3)
    expect(nearestMetricSample([], 1000, 2.4)).toBeUndefined()
    render(<QueryClientProvider client={new QueryClient()}><SingingDetailContent session={session} /></QueryClientProvider>)
    expect(screen.getByText('模拟分析结果，不用于临床诊断或现场监测')).toBeInTheDocument()
    expect(screen.getByText('嗳气次数')).toBeInTheDocument()
    expect(await screen.findByText(/缺少可播放的真实演唱录音/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '准备回放' })).not.toBeInTheDocument()
  })

  it('详情请求错误稳定展示 request_id，并可重试', async () => {
    useAuthStore.setState({ accessToken: 'valid', user: { login_id: 'A', role: 'doctor', must_change_password: false }, status: 'authenticated' })
    let attempts = 0
    server.use(http.get('/api/v1/admin/singing-sessions/:id/', () => {
      attempts += 1
      return attempts === 1 ? HttpResponse.json({ code: 'detail_failed', message: '明细暂不可用', data: {}, request_id: 'detail-request' }, { status: 503 }) : HttpResponse.json(envelope(session))
    }))
    renderApp('/singing/session-1')
    expect(await screen.findByText('明细暂不可用')).toBeInTheDocument()
    expect(screen.getByText('请求编号：detail-request')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: '重试加载演唱明细' }))
    expect(await screen.findByRole('heading', { name: '演唱明细' })).toBeInTheDocument()
  })

  it('私有 URL 授权失败显示稳定 request_id 与重试入口', async () => {
    useAuthStore.setState({ accessToken: 'valid', user: { login_id: 'A', role: 'doctor', must_change_password: false }, status: 'authenticated' })
    server.use(
      http.get('/api/v1/admin/singing-sessions/:id/', () => HttpResponse.json(envelope({ ...session, media: [{ asset_id: 'asset-1', media_type: 'singing_audio', status: 'ready', mime: 'audio/mpeg', size: 1 }] }))),
      http.post('/api/v1/admin/media/:id/private-url/', () => HttpResponse.json({ code: 'media_private_url_invalid', message: '授权已过期', data: {}, request_id: 'media-request' }, { status: 403 })),
    )
    renderApp('/singing/session-1')
    expect(await screen.findByText('授权已过期')).toBeInTheDocument()
    expect(screen.getByText('授权已过期').closest('.ant-message')).not.toBeNull()
    expect(document.querySelector('.singing-detail-content .ant-alert')).toBeNull()
    expect(screen.getByText('请求编号：media-request')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重试媒体授权' })).toBeInTheDocument()
  })

  it('进入详情自动加载每个资产且视频失败不拖垮音频', async () => {
    useAuthStore.setState({ accessToken: 'valid', user: { login_id: 'A', role: 'doctor', must_change_password: false }, status: 'authenticated' })
    let audioRequests = 0; let videoRequests = 0
    server.use(
      http.get('/api/v1/admin/singing-sessions/:id/', () => HttpResponse.json(envelope({ ...session, media: [
        { asset_id: 'audio-1', media_type: 'singing_audio', status: 'ready', mime: 'audio/mpeg', size: 1 },
        { asset_id: 'video-1', media_type: 'singing_video', status: 'ready', mime: 'video/mp4', size: 1 },
      ] }))),
      http.post('/api/v1/admin/media/audio-1/private-url/', () => { audioRequests += 1; return HttpResponse.json(envelope({ url: '/audio.mp3', expires_at: '2026-08-15T10:00:00Z' })) }),
      http.post('/api/v1/admin/media/video-1/private-url/', () => { videoRequests += 1; return HttpResponse.json({ code: 'video_denied', message: '录像授权失败', data: {}, request_id: 'video-request' }, { status: 403 }) }),
    )
    renderApp('/singing/session-1')
    expect(await screen.findByLabelText('演唱回放')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '准备回放' })).not.toBeInTheDocument()
    expect(document.querySelector('audio')).toHaveAttribute('src', '/audio.mp3')
    expect(document.querySelector('audio')?.paused).toBe(true)
    expect(audioRequests).toBe(1); expect(videoRequests).toBe(1)
    expect(await screen.findByText('录像授权失败')).toBeInTheDocument()
    expect(screen.getByText('请求编号：video-request')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重试录像授权' })).toBeInTheDocument()
  })

  it('私有 URL 以认证 epoch 隔离且离开页面即回收，不复用旧用户授权', async () => {
    useAuthStore.setState({ accessToken: 'valid', user: { login_id: 'A', role: 'doctor', must_change_password: false }, status: 'authenticated' })
    let calls = 0
    server.use(http.post('/api/v1/admin/media/audio-1/private-url/', () => {
      calls += 1
      return HttpResponse.json(envelope({ url: `/audio-${calls}.mp3`, expires_at: '2026-08-15T10:00:00Z' }))
    }))
    const withAudio = { ...session, media: [{ asset_id: 'audio-1', media_type: 'singing_audio' as const, status: 'ready', mime: 'audio/mpeg', size: 1 }] }
    const client = new QueryClient()
    const first = render(<QueryClientProvider client={client}><SingingDetailContent session={withAudio} /></QueryClientProvider>)
    await waitFor(() => expect(first.container.querySelector('audio')).toHaveAttribute('src', '/audio-1.mp3'))
    act(() => useAuthStore.setState({ sessionEpoch: useAuthStore.getState().sessionEpoch + 1 }))
    await waitFor(() => expect(first.container.querySelector('audio')).toHaveAttribute('src', '/audio-2.mp3'))
    first.unmount()
    render(<QueryClientProvider client={client}><SingingDetailContent session={withAudio} /></QueryClientProvider>)
    expect(calls).toBe(2)
    await waitFor(() => expect(calls).toBe(3))
  })
})
