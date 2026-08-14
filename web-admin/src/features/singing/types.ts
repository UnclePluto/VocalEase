export type MediaBinding = { asset_id: string; media_type: 'singing_audio' | 'singing_video'; status: string; mime: string; size: number }
export type TimeSeries = Record<string, { sample_interval_ms: number; values: number[] }>
export type AnalysisResult = { task_type: string; status: string; is_mock: boolean | null; payload: { burp_events?: number[] } | null; time_series: TimeSeries; error_code?: string; error_summary?: string }
export type SingingSession = {
  id: string; status: string; score: number | null; burp_count: number | null; duration_seconds: number | null; is_mock: boolean; analysis_generation?: number
  patient: { name?: string; medical_record_no?: string }; song: { title?: string; artist?: string }
  media: MediaBinding[]; analysis_results: AnalysisResult[]; created_at?: string; completed_at?: string
}
export type SingingPage = { count: number; page: number; page_size: number; results: SingingSession[] }
export type PrivateMediaUrl = { url: string; expires_at: string }
