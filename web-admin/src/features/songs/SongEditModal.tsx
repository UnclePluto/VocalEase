import { useRef, useState } from 'react'
import { Alert, Button, Form, Input, InputNumber, Modal } from 'antd'

import { ApiError } from '../../api/errors'
import type { Song } from './types'

type Values = Pick<Song, 'title' | 'artist' | 'genre' | 'language' | 'duration_seconds'>
export function SongEditModal({ song, open, onCancel, onSubmit }: { song: Song | null; open: boolean; onCancel: () => void; onSubmit: (values: Values) => Promise<void> }) {
  const [form] = Form.useForm<Values>(); const [error, setError] = useState<ApiError | null>(null); const [busy, setBusy] = useState(false); const submitting = useRef(false)
  const submit = async (values: Values) => { if (submitting.current) return; submitting.current = true; setBusy(true); setError(null); try { await onSubmit(values); form.resetFields() } catch (caught) { const apiError = caught instanceof ApiError ? caught : new ApiError('save_failed', '保存失败，请重试'); setError(apiError); if (apiError.fieldErrors) form.setFields(Object.entries(apiError.fieldErrors).map(([name, messages]) => ({ name: name as keyof Values, errors: [Array.isArray(messages) ? messages.join('；') : messages] }))) } finally { submitting.current = false; setBusy(false) } }
  return <Modal title={`编辑歌曲${song ? `：${song.title}` : ''}`} open={open} footer={null} onCancel={onCancel} destroyOnHidden mask={{ closable: !busy }} keyboard={!busy}>
    {song ? <Form form={form} initialValues={song} layout="vertical" requiredMark={false} onFinish={(values) => void submit(values)}><div className="form-grid"><Form.Item name="title" label="歌曲名称" rules={[{ required: true, message: '请输入歌曲名称' }]}><Input maxLength={200} /></Form.Item><Form.Item name="artist" label="歌手" rules={[{ required: true, message: '请输入歌手' }]}><Input maxLength={200} /></Form.Item><Form.Item name="genre" label="曲风" rules={[{ required: true, message: '请输入曲风' }]}><Input maxLength={64} /></Form.Item><Form.Item name="language" label="语言" rules={[{ required: true, message: '请输入语言' }]}><Input maxLength={64} /></Form.Item><Form.Item name="duration_seconds" label="时长（秒）"><InputNumber min={1} max={86400} style={{ width: '100%' }} /></Form.Item></div>{error ? <Alert type="error" showIcon title={error.message} description={error.requestId ? `请求编号：${error.requestId}` : undefined} /> : null}<div className="modal-actions"><Button onClick={onCancel} disabled={busy}>取消</Button><Button type="primary" htmlType="submit" loading={busy} disabled={busy}>保存</Button></div></Form> : null}
  </Modal>
}
