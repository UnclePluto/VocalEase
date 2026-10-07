import type { ReactNode } from 'react'
import { Tooltip } from 'antd'

import { referencePitchLabels } from './referencePitchLabels'
import type { ReferencePitchStatus, SongArtifacts } from './types'

const icons: Record<keyof SongArtifacts | 'pitch', ReactNode> = {
  source: <path fill="currentColor" stroke="none" d="M10 3a1 1 0 0 1 1.5-.86l8 4.5a1 1 0 0 1 .5.86V11l-8-4.5v10.25c0 2.1-2.04 3.75-4.5 3.75S3 19.26 3 17.5s2.04-3.25 4.5-3.25c.91 0 1.77.23 2.5.6V3Z" />,
  vocal: <><path d="M7 21v-4.1A8 8 0 1 1 18 7.5l2 4.5h-3v4h-4v5H7Z" /><path d="M21 5a7 7 0 0 1 0 10" /></>,
  accompaniment: <><path d="M10 6 16 3v18l-6-4H5v-5" /><path d="M20 9a5 5 0 0 1 0 6M8 3v5" /><circle cx="5.5" cy="8.5" r="2.5" /></>,
  lyrics: <><rect x="3" y="3" width="18" height="18" rx="1.5" /><text x="12" y="16.5" textAnchor="middle" fill="currentColor" stroke="none" fontSize="13" fontWeight="600">词</text></>,
  pitch: <><path d="M3 19V5m0 14h18M6 14h4V9h4v4h4V6h3" /><circle cx="21" cy="6" r="1.2" fill="currentColor" stroke="none" /></>,
}

export function SongArtifactIcons({ artifacts, referencePitch }: { artifacts: SongArtifacts; referencePitch?: ReferencePitchStatus }) {
  const items = [
    ...(['source', 'vocal', 'accompaniment', 'lyrics'] as const).map((kind) => ({
      kind, name: { source: '原曲', vocal: '人声', accompaniment: '伴奏', lyrics: '歌词' }[kind],
      ready: Boolean(artifacts[kind]), status: artifacts[kind] ? '已完成' : '未完成',
    })),
    { kind: 'pitch' as const, name: '原唱音高', ready: referencePitch?.status === 'ready', status: referencePitchLabels[referencePitch?.status ?? 'missing'] },
  ]
  return <div className="song-artifact-icons" role="group" aria-label="歌曲资源完成状态">
    {items.map(({ kind, name, ready, status }) => <Tooltip key={kind} title={`${name}：${status}`} trigger={['hover', 'focus']}>
      <span className={`song-artifact-icon${ready ? ' is-ready' : ''}`} role="img" aria-label={`${name}：${status}`} tabIndex={0}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{icons[kind]}</svg>
      </span>
    </Tooltip>)}
  </div>
}
