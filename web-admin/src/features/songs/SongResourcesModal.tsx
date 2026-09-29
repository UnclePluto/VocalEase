import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Modal, Tag } from 'antd'

import { ApiError } from '../../api/errors'
import { getSong, updateSongResourcesReliably } from './api'
import { describeUploadError } from './manualUpload'
import { SongResourceFields } from './SongResourceFields'
import { useSongResourceUpload } from './useSongResourceUpload'
import type { Song, SongResourceField } from './types'

const fields: Array<{ kind: 'song_vocal' | 'song_accompaniment' | 'lyrics'; key: SongResourceField; title: string }> = [
  { kind: 'song_vocal', key: 'vocal_asset', title: '纯人声' },
  { kind: 'song_accompaniment', key: 'accompaniment_asset', title: '纯伴奏' },
  { kind: 'lyrics', key: 'lyrics_asset', title: 'LRC 歌词' },
]

export function SongResourcesModal({ song, open, onCancel, onDone, onRefresh }: { song: Song; open: boolean; onCancel: () => void; onDone: () => void; onRefresh?: () => void }) {
  const upload = useSongResourceUpload(song.id)
  const [currentSong, setCurrentSong] = useState(song)
  const resetUpload = upload.reset
  const [saving, setSaving] = useState(false)
  const submitting = useRef(false)
  const generation = useRef(0)
  const wasOpen = useRef(open)
  useEffect(() => { if (wasOpen.current && !open) { generation.current += 1; resetUpload(); submitting.current = false; setSaving(false) } wasOpen.current = open }, [open, resetUpload])
  const close = () => { generation.current += 1; upload.reset(); submitting.current = false; setSaving(false); onCancel() }
  const submit = async () => {
    if (submitting.current) return
    submitting.current = true; setSaving(true)
    const run = ++generation.current
    try {
      const { assets } = await upload.uploadSelected()
      if (run !== generation.current) return
      const updates: Partial<Record<SongResourceField, string>> = {}
      const expected: Partial<Record<SongResourceField, string | null>> = {}
      for (const field of fields) if (upload.slots[field.kind]?.file && assets[field.kind]) {
        updates[field.key] = assets[field.kind]
        expected[field.key] = currentSong[field.key] ?? null
      }
      await updateSongResourcesReliably(song.id, { updates, expected })
      if (run !== generation.current) return
      upload.reset(); onDone()
    } catch (caught) {
      if (run !== generation.current) return
      if (caught instanceof ApiError && caught.status === 409) {
        try {
          const latest = await getSong(song.id)
          if (run !== generation.current) return
          setCurrentSong(latest)
          onRefresh?.()
          upload.setError(`资源已刷新，请核对最新状态后重试：${describeUploadError(caught)}`)
        } catch { upload.setError(describeUploadError(caught)) }
      } else upload.setError(describeUploadError(caught))
    }
    finally { if (run === generation.current) { submitting.current = false; setSaving(false) } }
  }
  const busy = saving || upload.busy
  return <Modal title={`管理资源：${song.title}`} open={open} width={640} footer={null} onCancel={close} destroyOnHidden mask={{ closable: !busy }} keyboard={!busy}>
    <p>当前资源：{fields.map((field) => <Tag key={field.key} color={currentSong[field.key] ? 'success' : 'default'}>{field.title}{currentSong[field.key] ? '已上传' : '未上传'}</Tag>)}</p>
    <SongResourceFields slots={upload.slots} chooseFile={upload.chooseFile} disabled={busy} includeSource={false} />
    {upload.error ? <Alert type="error" showIcon title={upload.error} /> : null}
    <div className="modal-actions"><Button onClick={close}>取消</Button><Button type="primary" aria-label="保存资源" disabled={busy || !fields.some((field) => upload.slots[field.kind]?.file)} loading={busy} onClick={() => void submit()}>保存资源</Button></div>
  </Modal>
}
