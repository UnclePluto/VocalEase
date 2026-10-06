import { useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Modal, Spin, Tag } from 'antd'

import { ApiError } from '../../api/errors'
import { generateSongReferencePitch, getSong, songKeys } from './api'
import type { Song } from './types'
import { referencePitchLabels } from './referencePitchLabels'

export function SongReferencePitchModal({ song, onClose }: { song: Song; onClose: () => void }) {
  const client = useQueryClient()
  const submitted = useRef(false)
  const key = ['songs', 'reference-pitch', song.id]
  const detail = useQuery({
    queryKey: key, queryFn: ({ signal }) => getSong(song.id, signal),
    refetchInterval: (query) => ['pending', 'processing'].includes(query.state.data?.reference_pitch?.status ?? '') ? 2000 : false,
  })
  const current = detail.data
  const pitch = current?.reference_pitch
  const processing = pitch?.status === 'pending' || pitch?.status === 'processing'
  const generation = useMutation({
    mutationFn: () => generateSongReferencePitch(song.id, current?.vocal_fingerprint ?? '', pitch?.status === 'ready'),
    onSuccess: async () => { await client.invalidateQueries({ queryKey: key }); await client.invalidateQueries({ queryKey: songKeys.lists() }) },
    onSettled: () => { submitted.current = false },
  })
  useEffect(() => {
    if (pitch?.status === 'ready' || pitch?.status === 'failed') void client.invalidateQueries({ queryKey: songKeys.lists() })
  }, [client, pitch?.status, pitch?.version])
  const error = generation.error ?? detail.error
  const message = error instanceof ApiError ? error.message : error ? '获取原唱音高状态失败，请重试' : null
  return <Modal title={`原唱音高：${song.title}`} open onCancel={onClose} footer={null} destroyOnHidden>
    <p>从歌曲纯人声生成演唱参考音符，保存后供手机演唱时使用。</p>
    {detail.isPending ? <Spin /> : <Tag color={pitch?.status === 'ready' ? 'success' : processing ? 'processing' : 'default'}>{referencePitchLabels[pitch?.status ?? 'missing']}</Tag>}
    {pitch?.status === 'ready' ? <p>已保存 {pitch.note_count} 个音符片段</p> : null}
    {current && !current.vocal_asset ? <Alert type="info" title="请先在管理资源中上传歌曲纯人声" /> : null}
    {message ? <Alert type="error" showIcon title={message} description={error instanceof ApiError && error.requestId ? `请求编号：${error.requestId}` : undefined} /> : null}
    <div className="modal-actions">
      <Button onClick={onClose}>关闭</Button>
      {detail.isError ? <Button onClick={() => void detail.refetch()}>重试加载</Button> : null}
      <Button type="primary" loading={generation.isPending} disabled={detail.isPending || detail.isError || !current?.vocal_asset || !current.vocal_fingerprint || processing} onClick={() => {
        if (submitted.current) return
        submitted.current = true; generation.mutate()
      }}>{pitch?.status === 'ready' ? '重新生成' : processing ? '生成中' : '生成音高'}</Button>
    </div>
  </Modal>
}
