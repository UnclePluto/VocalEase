export type MediaBinding = { asset_id: string; media_type: 'singing_audio' | 'singing_video'; status: string; mime: string; size: number }
export type TimeSeries = Record<string, { sample_interval_ms: number; values: number[] }>
export type AnalysisResult = { task_type: string; status: string; is_mock: boolean | null; payload: { burp_events?: number[] } | null; time_series: TimeSeries; error_code?: string; error_summary?: string }
export type SingingSession = {
  id: string; status: string; score: number | null; burp_count: number | null; duration_seconds: number | null; is_mock: boolean; analysis_generation?: number
  patient: { name?: string; medical_record_no?: string }; song: { title?: string; artist?: string }
  playback?: { accompaniment_preview_available?:boolean;accompaniment_preview_asset_id?:string|null;alignment_verified?:boolean;accompaniment_offset_ms?:number|null; source_asset_id: string | null; accompaniment_asset_id: string | null; reference_version: string | null; combined_available: boolean; metadata: PlaybackMetadata | null }
  media: MediaBinding[]; analysis_results: AnalysisResult[]; created_at?: string; completed_at?: string
}
export type SingingPage = { count: number; page: number; page_size: number; results: SingingSession[] }
export type PrivateMediaUrl = { url: string; expires_at: string }
export type PatientMetric = {
  medical_record_no: string
  treatment_progress: string | null
  completed_count: number
  total_duration_seconds: number
  average_score: string | null
  score_trend: { difference: string | null; direction: 'up' | 'down' | 'flat'; has_enough_data: boolean }
  burp_improvement: string | null
  is_mock: boolean
}
export type PatientMetricsPage = { metric_version: string; count: number; page: number; page_size: number; results: PatientMetric[] }

export type SongPlaybackMode = 'source' | 'accompaniment'
export type PlaybackAnchor = {recording_ms:number;song_ms:number;track:SongPlaybackMode;playing:boolean;segment:number}
export type ModeChange = {recording_ms:number;track:SongPlaybackMode}
export type PlaybackMetadata = {schema_version:1;sample_rate:number;source_asset_id:string|null;accompaniment_asset_id:string|null;reference_version:string|null;anchors:PlaybackAnchor[];mode_changes:ModeChange[]}
