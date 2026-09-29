import { UploadOutlined } from '@ant-design/icons'
import { Button, Progress, Typography, Upload } from 'antd'

import { labels, mediaKinds } from './manualUpload'
import type { MediaKind } from './manualUpload'

type Slot = { file?: File; assetId?: string; progress?: number }
export function SongResourceFields({ slots, chooseFile, disabled, includeSource = true }: {
  slots: Partial<Record<MediaKind, Slot>>
  chooseFile: (kind: MediaKind, file: File | null) => void
  disabled: boolean
  includeSource?: boolean
}) {
  return <div className="song-resource-fields">{mediaKinds.filter((kind) => includeSource || kind !== 'song_source').map((kind) => {
    const file = slots[kind]?.file
    const progress = slots[kind]?.progress
    return <div key={kind} className="song-resource-field">
      <label htmlFor={`song-resource-${kind}`}>{labels[kind]} <Typography.Text type="secondary">{kind === 'song_source' ? '必填' : '选填'}</Typography.Text></label>
      <div className="song-resource-picker">
        <Upload id={`song-resource-${kind}`} accept={kind === 'lyrics' ? '.lrc,text/plain' : '.mp3,.wav,.flac,audio/mpeg,audio/wav,audio/flac'} disabled={disabled} showUploadList={false} fileList={[]} beforeUpload={(selected) => { chooseFile(kind, selected); return Upload.LIST_IGNORE }}>
          <Button icon={<UploadOutlined />} disabled={disabled}>{file ? '更换文件' : '选择文件'}</Button>
        </Upload>
        <Typography.Text type={file ? undefined : 'secondary'} ellipsis title={file?.name} className="song-resource-filename">{file?.name ?? '未选择文件'}</Typography.Text>
      </div>
      {progress !== undefined && progress < 100 ? <Progress percent={progress} size="small" /> : null}
    </div>
  })}</div>
}
