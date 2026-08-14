import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Modal, Space } from 'antd'

import { ApiError } from '../../api/errors'
import { requestPreview } from './api'
import type { Song, SongArtifacts } from './types'

const labels = { source: '原唱' }

export function AudioPlayer({ song, artifacts, onClose }: { song: Song; artifacts: SongArtifacts; onClose: () => void }) {
  const [track, setTrack] = useState<'source' | null>(null)
  const [url, setUrl] = useState('')
  const [error, setError] = useState<ApiError | null>(null)
  const refreshed = useRef(false)
  const controller = useRef<AbortController | null>(null)
  const audio = useRef<HTMLAudioElement | null>(null)

  const release = () => { controller.current?.abort(); controller.current = null; if (audio.current) { audio.current.pause(); audio.current.removeAttribute('src'); audio.current.load() } setUrl('') }
  useEffect(() => release, [])
  const load = async (retry = false) => {
    if (!retry) refreshed.current = false
    release(); setError(null); setTrack('source')
    const nextController = new AbortController(); controller.current = nextController
    try { const preview = await requestPreview(song.id, nextController.signal); if (!nextController.signal.aborted && controller.current === nextController) setUrl(preview.url) }
    catch (caught) { if (!nextController.signal.aborted) setError(caught instanceof ApiError ? caught : new ApiError('preview_failed', '获取试听地址失败')) }
    if (retry) refreshed.current = true
  }
  const close = () => { release(); onClose() }
  const onAudioError = () => {
    // 原生 audio 不会暴露跨域 401/403。仅在首次 error 时重新签发一次短期 URL，避免循环请求或下载探测整文件。
    if (!track || refreshed.current) { setError(new ApiError('preview_playback_failed', '试听地址失效或媒体暂不可播放，请稍后重试')); return }
    refreshed.current = true
    void load(true)
  }
  return <Modal title={`试听：${song.title}`} open onCancel={close} footer={<Button onClick={close}>关闭</Button>} destroyOnHidden>
    <Space wrap><Button onClick={() => void load()} aria-label="原唱试听" disabled={!artifacts.source}>原唱试听</Button><Button aria-label="人声（不可用）" disabled>人声（不可用）</Button><Button aria-label="伴奏（不可用）" disabled>伴奏（不可用）</Button></Space>
    <p className="song-preview-hint">私有试听地址仅短期有效；播放失败时会安全刷新一次，不会预下载音频。</p>
    {url && track ? <audio ref={audio} aria-label={`正在试听${labels[track]}`} controls autoPlay src={url} onError={onAudioError} /> : null}
    {error ? <Alert type="error" showIcon title={error.message} description={error.requestId ? `请求编号：${error.requestId}` : undefined} /> : null}
  </Modal>
}
