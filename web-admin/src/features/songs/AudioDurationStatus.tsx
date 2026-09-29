import { Alert, Typography } from 'antd'

import type { DurationState } from './audioDuration'

export function AudioDurationStatus({ state }: { state: DurationState }) {
  if (state.error) return <div className="song-duration-status"><Alert type="error" showIcon title={state.error} /></div>
  if (!state.file) return null
  if (state.loading) return <div className="song-duration-status"><Typography.Text type="secondary" role="status">正在读取原曲时长…</Typography.Text></div>
  const seconds = state.seconds ?? 0
  return <div className="song-duration-status"><Typography.Text type="secondary" role="status">已识别原曲时长：{Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')}</Typography.Text></div>
}
