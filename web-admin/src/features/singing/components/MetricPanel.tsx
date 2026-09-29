/* eslint-disable react-refresh/only-export-components */
import type { AnalysisResult } from '../types'

export function nearestMetricSample(values: number[], intervalMs: number, seconds: number) {
  if (!values.length || intervalMs <= 0) return undefined
  return values[Math.min(values.length - 1, Math.max(0, Math.round(seconds * 1000 / intervalMs)))]
}
export function MetricPanel({ seconds, result }: { seconds: number; result?: AnalysisResult }) {
  const read = (name: string) => {
    const series = result?.time_series[name]
    const value = series ? nearestMetricSample(series.values, series.sample_interval_ms, seconds) : undefined
    return value ?? '无结果'
  }
  return <section className="metric-panel" aria-label="模拟指标"><h2>模拟指标</h2><p>当前 {seconds.toFixed(1)} 秒</p><p>音量：{read('volume')}</p><p>音高：{read('pitch_hz')}</p><p>信噪比：{read('snr_db')}</p><p>嗳气事件：{result?.payload?.burp_events?.filter((item) => item <= seconds).length ?? 0}</p></section>
}
