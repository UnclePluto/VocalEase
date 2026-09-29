import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Modal, Space } from 'antd'

import { ApiError } from '../../api/errors'
import { getSongLyrics, requestPreview } from './api'
import type { Song, SongArtifacts, SongTrack } from './types'

const labels = { source: '原唱', vocal: '人声', accompaniment: '伴奏' }

export function AudioPlayer({ song, artifacts, onClose }: { song: Song; artifacts: SongArtifacts; onClose: () => void }) {
  const [track, setTrack] = useState<SongTrack | null>(null)
  const [url, setUrl] = useState('')
  const [lyrics, setLyrics] = useState<Array<{ time_ms: number; text: string }>>([])
  const [lyricsError, setLyricsError] = useState('')
  const [error, setError] = useState<ApiError | null>(null)
  const refreshed = useRef(false)
  const refreshing = useRef(false)
  const controller = useRef<AbortController | null>(null)
  const audio = useRef<HTMLAudioElement | null>(null)

  const release = () => { controller.current?.abort(); controller.current = null; if (audio.current) { audio.current.pause(); audio.current.removeAttribute('src'); audio.current.load() } setUrl('') }
  useEffect(() => release, [])
  useEffect(() => {
    if (!artifacts.lyrics) return
    const next = new AbortController()
    void getSongLyrics(song.id, next.signal).then((result) => { if (!next.signal.aborted) setLyrics(result.lines) }).catch((caught) => { if (!next.signal.aborted) setLyricsError(caught instanceof ApiError ? caught.message : '歌词加载失败') })
    return () => next.abort()
  }, [artifacts.lyrics, song.id])
  const load = async (nextTrack: SongTrack = 'source', retry = false) => {
    if (!retry) { refreshed.current = false; refreshing.current = false }
    release(); setError(null); setTrack(nextTrack)
    const nextController = new AbortController(); controller.current = nextController
    try {
      const preview = await requestPreview(song.id, nextController.signal, nextTrack)
      if (!nextController.signal.aborted && controller.current === nextController) { setError(null); setUrl(preview.url) }
    } catch (caught) {
      if (!nextController.signal.aborted && controller.current === nextController) setError(caught instanceof ApiError ? caught : new ApiError('preview_failed', '获取试听地址失败'))
    } finally {
      if (retry && controller.current === nextController) refreshing.current = false
    }
  }
  const close = () => { release(); onClose() }
  const onAudioError = () => {
    // 原生 audio 不会暴露跨域 401/403。仅在首次 error 时重新签发一次短期 URL，避免循环请求或下载探测整文件。
    if (!track || refreshing.current) return
    if (refreshed.current) { setError(new ApiError('preview_playback_failed', '试听地址失效或媒体暂不可播放，请稍后重试')); return }
    refreshed.current = true
    refreshing.current = true
    void load(track, true)
  }
  return <Modal title={`试听：${song.title}`} open onCancel={close} footer={<Button onClick={close}>关闭</Button>} destroyOnHidden>
    <Space wrap><Button onClick={() => void load('source')} aria-label="原唱试听" disabled={!artifacts.source}>原唱试听</Button><Button onClick={() => void load('vocal')} aria-label={artifacts.vocal ? '人声试听' : '人声（不可用）'} disabled={!artifacts.vocal}>{artifacts.vocal ? '人声试听' : '人声（不可用）'}</Button><Button onClick={() => void load('accompaniment')} aria-label={artifacts.accompaniment ? '伴奏试听' : '伴奏（不可用）'} disabled={!artifacts.accompaniment}>{artifacts.accompaniment ? '伴奏试听' : '伴奏（不可用）'}</Button></Space>
    <p className="song-preview-hint">私有试听地址仅短期有效；播放失败时会安全刷新一次，不会预下载音频。</p>
    {url && track ? <audio ref={audio} aria-label={`正在试听${labels[track]}`} controls autoPlay src={url} onError={onAudioError} /> : null}
    {error ? <Alert type="error" showIcon title={error.message} description={error.requestId ? `请求编号：${error.requestId}` : undefined} /> : null}
    {artifacts.lyrics ? <div className="song-lyrics" aria-label="歌词">{lyricsError ? <Alert type="error" title={lyricsError} /> : null}{lyrics.map((line, index) => <p key={`${line.time_ms}-${index}`}><time>{Math.floor(line.time_ms / 60000)}:{String(Math.floor(line.time_ms / 1000) % 60).padStart(2, '0')}</time> {line.text}</p>)}</div> : null}
  </Modal>
}
