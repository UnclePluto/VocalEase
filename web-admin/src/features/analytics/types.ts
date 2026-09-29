export type AnalyticsFilters = {
  name: string
  medical_record_no: string
  treatment_status: '' | 'active' | 'pending' | 'completed' | 'cancelled' | 'none'
  primary_doctor: string
  created_from: string
  created_to: string
}

export type AnalyticsQuery = AnalyticsFilters & { page: number; page_size: number }

export type DashboardMetrics = {
  metric_version: string
  active_patient_count: number
  completed_session_count: number
  average_score: string | null
  average_burp_count: string | null
  is_mock: boolean
}

export type PatientMetric = {
  id: string
  medical_record_no: string
  name: string
  treatment_status: AnalyticsFilters['treatment_status']
  primary_doctor: string
  primary_doctor_id: string | null
  treatment_progress: string | null
  completed_count: number
  total_duration_seconds: number
  average_score: string | null
  score_trend: { difference: string | null; direction: 'up' | 'down' | 'flat'; has_enough_data: boolean }
  burp_improvement: string | null
  is_mock: boolean
}

export type PatientMetricsPage = { metric_version: string; count: number; page: number; page_size: number; results: PatientMetric[] }
export type ExportFormat = 'csv' | 'xlsx'
export type ExportJobStatus = 'pending' | 'processing' | 'ready' | 'failed' | 'expired'
export type ExportJob = {
  id: string; metric_version: string; format: ExportFormat; status: ExportJobStatus; count: number
  failure_reason: string; expires_at: string; result_asset_id: string | null
}
export type ExportPrivateUrl = { metric_version: string; url: string; expires_at: string }
