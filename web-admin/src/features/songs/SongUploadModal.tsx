import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Button, Checkbox, Form, Input, InputNumber, Modal, Progress } from 'antd'

import { ApiError } from '../../api/errors'
import { confirmUpload, createSongReliably, requestUploadGrant, uploadWithGrant } from './api'
import type { SongWrite, UploadState } from './types'

type UploadValues = Omit<SongWrite, 'id' | 'source_asset'>
const maxBytes = 50 * 1024 * 1024
const mediaByExtension = {
  mp3: { canonical: 'audio/mpeg', accepted: new Set(['', 'audio/mpeg', 'audio/mp3']) },
  wav: { canonical: 'audio/wav', accepted: new Set(['', 'audio/wav', 'audio/x-wav']) },
  flac: { canonical: 'audio/flac', accepted: new Set(['', 'audio/flac', 'audio/x-flac']) },
} as const
type AcceptedMedia = typeof mediaByExtension[keyof typeof mediaByExtension]

function message(error: unknown) { return error instanceof ApiError ? error.message : '上传失败，请重试' }

export function SongUploadModal({ open, onCancel, onDone }: { open: boolean; onCancel: () => void; onDone: (songId: string) => void }) {
  const [form] = Form.useForm<UploadValues>()
  const [file, setFile] = useState<File | null>(null)
  const [state, setState] = useState<UploadState>({ kind: 'idle' })
  const [retryableCreate, setRetryableCreate] = useState(false)
  const [confirmedUpload, setConfirmedUpload] = useState<{ songId: string; assetId: string } | null>(null)
  const controller = useRef<AbortController | null>(null)
  const submitting = useRef(false)
  const activeRun = useRef(0)
  const songId = useRef<string | null>(null)
  const fileInput = useRef<HTMLInputElement | null>(null)
  const wasOpen = useRef(open)

  useEffect(() => () => controller.current?.abort(), [])
  const clearInternal = useCallback(() => {
    controller.current?.abort(); controller.current = null; activeRun.current += 1; submitting.current = false
    setRetryableCreate(false); setConfirmedUpload(null); setState({ kind: 'idle' }); setFile(null); songId.current = null
    if (fileInput.current) fileInput.current.value = ''
    form.resetFields()
  }, [form])
  useEffect(() => {
    if (wasOpen.current && !open) clearInternal()
    wasOpen.current = open
  }, [clearInternal, open])
  const reset = () => { clearInternal(); onCancel() }
  const chooseFile = (next: File) => {
    setFile(null); setRetryableCreate(false); setConfirmedUpload(null); songId.current = null
    const extension = next.name.split('.').at(-1)?.toLowerCase() as keyof typeof mediaByExtension | undefined
    const media: AcceptedMedia | undefined = extension ? mediaByExtension[extension] : undefined
    if (!media || !(media.accepted as ReadonlySet<string>).has(next.type)) { setState({ kind: 'failed', message: '仅支持 MP3、WAV 或 FLAC 音频' }); return false }
    if (next.size > maxBytes) { setState({ kind: 'failed', message: '音频不能超过 50MB' }); return false }
    setFile(next.type === media.canonical ? next : new File([next], next.name, { type: media.canonical, lastModified: next.lastModified })); setState({ kind: 'idle' }); return true
  }
  const submit = async (values: UploadValues) => {
    if (!file || submitting.current) return
    submitting.current = true
    const run = ++activeRun.current
    controller.current = new AbortController()
    const assertCurrent = () => { if (controller.current?.signal.aborted || run !== activeRun.current) throw new ApiError('request_aborted', '上传已取消') }
    try {
      let completed = confirmedUpload
      if (!completed) {
        setState({ kind: 'uploading', progress: 0 })
        const grant = await requestUploadGrant(file, songId.current ?? undefined, controller.current.signal); assertCurrent()
        songId.current = grant.song_id
        await uploadWithGrant(grant, file, { signal: controller.current.signal, onProgress: (progress) => { if (run === activeRun.current) setState({ kind: 'uploading', progress }) } }); assertCurrent()
        setState({ kind: 'confirming' })
        if (grant.upload_url.startsWith('/api/')) await confirmUpload(grant.asset_id, controller.current.signal)
        assertCurrent(); completed = { songId: grant.song_id, assetId: grant.asset_id }; setConfirmedUpload(completed)
      }
      setRetryableCreate(true); setState({ kind: 'creating-song' })
      const created = await createSongReliably({ ...values, id: completed.songId, source_asset: completed.assetId }, controller.current.signal); assertCurrent()
      setRetryableCreate(false)
      setState({ kind: 'done', songId: created.id }); clearInternal(); onDone(created.id)
    } catch (error) {
      if (run === activeRun.current && !(error instanceof ApiError && error.code === 'request_aborted')) {
        const details = error instanceof ApiError && error.fieldErrors
          ? Object.values(error.fieldErrors).flatMap((detail) => Array.isArray(detail) ? detail : [detail])
          : undefined
        setState({ kind: 'failed', message: message(error), details })
        if (error instanceof ApiError && error.fieldErrors) {
          const writable = new Set<keyof UploadValues>(['title', 'artist', 'genre', 'language', 'duration_seconds', 'auto_analyze'])
          form.setFields(Object.entries(error.fieldErrors).flatMap(([name, detail]) => writable.has(name as keyof UploadValues)
            ? [{ name: name as keyof UploadValues, errors: [Array.isArray(detail) ? detail.join('；') : detail] }]
            : []))
        }
      }
    } finally { if (run === activeRun.current) submitting.current = false }
  }
  const retryCreate = () => void form.validateFields().then(submit).catch(() => undefined)
  const busy = ['uploading', 'confirming', 'creating-song'].includes(state.kind)
  return <Modal title="上传歌曲" open={open} footer={null} onCancel={reset} destroyOnHidden mask={{ closable: !busy }} keyboard={!busy}>
    <Form form={form} layout="vertical" requiredMark={false} initialValues={{ genre: '流行', language: '中文', duration_seconds: 180, auto_analyze: true }} onFinish={(values) => void submit(values)}>
      <Form.Item label="上传歌曲" required><input ref={fileInput} aria-label="上传歌曲" type="file" accept=".mp3,.wav,.flac,audio/mpeg,audio/wav,audio/flac,audio/x-wav,audio/x-flac" disabled={busy} onChange={(event) => { const next = event.currentTarget.files?.[0]; if (next && !chooseFile(next)) event.currentTarget.value = '' }} />{file ? <span>{file.name}</span> : null}</Form.Item>
      <div className="form-grid"><Form.Item name="title" label="歌曲名称" rules={[{ required: true, message: '请输入歌曲名称' }]}><Input maxLength={200} /></Form.Item><Form.Item name="artist" label="歌手" rules={[{ required: true, message: '请输入歌手' }]}><Input maxLength={200} /></Form.Item><Form.Item name="genre" label="曲风" rules={[{ required: true, message: '请输入曲风' }]}><Input maxLength={64} /></Form.Item><Form.Item name="language" label="语言" rules={[{ required: true, message: '请输入语言' }]}><Input maxLength={64} /></Form.Item><Form.Item name="duration_seconds" label="时长（秒）" rules={[{ required: true, message: '请输入时长' }]}><InputNumber min={1} max={86400} style={{ width: '100%' }} /></Form.Item></div>
      <Form.Item name="auto_analyze" valuePropName="checked"><Checkbox>上传后创建模拟分析任务</Checkbox></Form.Item>
      {state.kind === 'uploading' ? <Progress percent={state.progress} /> : null}
      {state.kind === 'confirming' ? <p role="status">正在确认上传</p> : null}{state.kind === 'creating-song' ? <p role="status">正在创建歌曲</p> : null}
      {state.kind === 'failed' ? <Alert type="error" showIcon title={state.message} description={state.details?.join('；')} action={retryableCreate ? <Button size="small" onClick={retryCreate} aria-label="重试创建歌曲">重试创建歌曲</Button> : undefined} /> : null}
      <div className="modal-actions"><Button onClick={reset} aria-label={busy ? '取消上传' : '取消'}>{busy ? '取消上传' : '取消'}</Button><Button type="primary" htmlType="submit" disabled={!file || busy} loading={busy} aria-label="开始上传">开始上传</Button></div>
    </Form>
  </Modal>
}
