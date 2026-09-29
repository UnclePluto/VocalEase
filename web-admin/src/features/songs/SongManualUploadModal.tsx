import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Form, Input, Modal } from 'antd'

import { createSongReliably } from './api'
import { useAudioDuration } from './audioDuration'
import { AudioDurationStatus } from './AudioDurationStatus'
import { describeUploadError } from './manualUpload'
import { SongResourceFields } from './SongResourceFields'
import { useSongResourceUpload } from './useSongResourceUpload'
import type { SongWrite } from './types'

type Values = Pick<SongWrite, 'title' | 'artist' | 'genre' | 'language'>

export function SongManualUploadModal({ open, onCancel, onDone }: { open: boolean; onCancel: () => void; onDone: (id: string) => void }) {
  const [form] = Form.useForm<Values>()
  const upload = useSongResourceUpload()
  const sourceFile = upload.slots.song_source?.file
  const duration = useAudioDuration(sourceFile)
  const resetUpload = upload.reset
  const [saving, setSaving] = useState(false)
  const submitting = useRef(false)
  const generation = useRef(0)
  const wasOpen = useRef(open)
  useEffect(() => { if (wasOpen.current && !open) { generation.current += 1; resetUpload(); form.resetFields(); submitting.current = false; setSaving(false) } wasOpen.current = open }, [open, form, resetUpload])
  const close = () => { generation.current += 1; upload.reset(); form.resetFields(); submitting.current = false; setSaving(false); onCancel() }
  const submit = async (values: Values) => {
    if (submitting.current || !sourceFile || duration.file !== sourceFile || !duration.seconds) return
    submitting.current = true; setSaving(true)
    const run = ++generation.current
    try {
      const { songId, assets } = await upload.uploadSelected()
      if (run !== generation.current) return
      const created = await createSongReliably({ ...values, duration_seconds: duration.seconds, id: songId, source_asset: assets.song_source!, ingestion_mode: 'manual', ...(assets.song_vocal ? { vocal_asset: assets.song_vocal } : {}), ...(assets.song_accompaniment ? { accompaniment_asset: assets.song_accompaniment } : {}), ...(assets.lyrics ? { lyrics_asset: assets.lyrics } : {}) })
      if (run !== generation.current) return
      upload.reset(); form.resetFields(); onDone(created.id)
    } catch (caught) { if (run === generation.current) upload.setError(describeUploadError(caught)) }
    finally { if (run === generation.current) { submitting.current = false; setSaving(false) } }
  }
  const busy = upload.busy || saving
  return <Modal title="人工上传歌曲" open={open} width={680} footer={null} onCancel={close} destroyOnHidden mask={{ closable: !busy }} keyboard={!busy}>
    <Form form={form} layout="vertical" initialValues={{ genre: '流行', language: '中文' }} onFinish={(values) => void submit(values)}>
      <SongResourceFields slots={upload.slots} chooseFile={upload.chooseFile} disabled={busy} />
      <AudioDurationStatus state={duration} />
      <div className="form-grid"><Form.Item name="title" label="歌曲名称" rules={[{ required: true, message: '请输入歌曲名称' }]}><Input maxLength={200} /></Form.Item><Form.Item name="artist" label="歌手" rules={[{ required: true, message: '请输入歌手' }]}><Input maxLength={200} /></Form.Item><Form.Item name="genre" label="曲风" rules={[{ required: true, message: '请输入曲风' }]}><Input maxLength={64} /></Form.Item><Form.Item name="language" label="语言" rules={[{ required: true, message: '请输入语言' }]}><Input maxLength={64} /></Form.Item></div>
      {upload.error ? <Alert type="error" showIcon title={upload.error} /> : null}
      <div className="modal-actions"><Button onClick={close}>取消</Button><Button type="primary" htmlType="submit" aria-label="保存人工歌曲" disabled={!duration.seconds || busy} loading={busy}>保存人工歌曲</Button></div>
    </Form>
  </Modal>
}
