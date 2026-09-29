import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Modal, Tag } from 'antd'

import { updateSongResourcesReliably } from './api'
import { SongResourceFields } from './SongResourceFields'
import { useSongResourceUpload } from './useSongResourceUpload'
import type { Song, SongResourceField } from './types'

const fields: Array<{ kind: 'song_vocal' | 'song_accompaniment' | 'lyrics'; key: SongResourceField; title: string }> = [
  { kind: 'song_vocal', key: 'vocal_asset', title: '纯人声' },
  { kind: 'song_accompaniment', key: 'accompaniment_asset', title: '纯伴奏' },
  { kind: 'lyrics', key: 'lyrics_asset', title: 'LRC 歌词' },
]

export function SongResourcesModal({ song, open, onCancel, onDone }: { song: Song; open: boolean; onCancel: () => void; onDone: () => void }) {
  const upload = useSongResourceUpload(song.id)
  const resetUpload = upload.reset
  const [saving, setSaving] = useState(false)
  const submitting = useRef(false)
  const wasOpen = useRef(open)
  useEffect(() => { if (wasOpen.current && !open) resetUpload(); wasOpen.current = open }, [open, resetUpload])
  const close = () => { upload.reset(); onCancel() }
  const submit = async () => {
    if (submitting.current) return
    submitting.current = true; setSaving(true)
    try {
      const { assets } = await upload.uploadSelected()
      const updates: Partial<Record<SongResourceField, string>> = {}
      const expected: Partial<Record<SongResourceField, string | null>> = {}
      for (const field of fields) if (upload.slots[field.kind]?.file && assets[field.kind]) {
        updates[field.key] = assets[field.kind]
        expected[field.key] = song[field.key] ?? null
      }
      await updateSongResourcesReliably(song.id, { updates, expected })
      upload.reset(); onDone()
    } catch (caught) { upload.setError(caught instanceof Error ? caught.message : '保存资源失败，请重试') }
    finally { submitting.current = false; setSaving(false) }
  }
  const busy = saving || upload.busy
  return <Modal title={`管理资源：${song.title}`} open={open} width={640} footer={null} onCancel={close} destroyOnHidden mask={{ closable: !busy }} keyboard={!busy}>
    <p>当前资源：{fields.map((field) => <Tag key={field.key} color={song[field.key] ? 'success' : 'default'}>{field.title}{song[field.key] ? '已上传' : '未上传'}</Tag>)}</p>
    <SongResourceFields slots={upload.slots} chooseFile={upload.chooseFile} disabled={busy} includeSource={false} />
    {upload.error ? <Alert type="error" showIcon title={upload.error} /> : null}
    <div className="modal-actions"><Button onClick={close}>取消</Button><Button type="primary" aria-label="保存资源" disabled={busy || !fields.some((field) => upload.slots[field.kind]?.file)} loading={busy} onClick={() => void submit()}>保存资源</Button></div>
  </Modal>
}
