import { Progress } from 'antd'

import { labels, mediaKinds } from './manualUpload'
import type { MediaKind } from './manualUpload'

type Slot = { file?: File; assetId?: string; progress?: number }
export function SongResourceFields({ slots, chooseFile, disabled, includeSource = true }: {
  slots: Partial<Record<MediaKind, Slot>>
  chooseFile: (kind: MediaKind, file: File | null) => void
  disabled: boolean
  includeSource?: boolean
}) {
  return <>{mediaKinds.filter((kind) => includeSource || kind !== 'song_source').map((kind) => <div key={kind} className="song-resource-field"><label>{labels[kind]}{kind === 'song_source' ? '（必填）' : '（选填）'}<input aria-label={labels[kind]} type="file" accept={kind === 'lyrics' ? '.lrc,text/plain' : '.mp3,.wav,.flac,audio/mpeg,audio/wav,audio/flac'} disabled={disabled} onChange={(event) => chooseFile(kind, event.currentTarget.files?.[0] ?? null)} /></label>{slots[kind]?.file ? <span>{slots[kind]?.file?.name}</span> : null}{slots[kind]?.progress && slots[kind]?.progress !== 100 ? <Progress percent={slots[kind]?.progress} /> : null}</div>)}</>
}
