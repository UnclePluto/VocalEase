import { apiRawRequest, apiRequest } from '../../api/client'
import { ApiError } from '../../api/errors'
import type { AnalyticsFilters, AnalyticsQuery, DashboardMetrics, ExportFormat, ExportJob, ExportPrivateUrl, PatientMetricsPage } from './types'

export const analyticsKeys = {
  dashboard: () => ['analytics', 'dashboard'] as const,
  patients: (query: AnalyticsQuery) => ['analytics', 'patients', query] as const,
  export: (jobId: string, epoch: number) => ['analytics', 'export', jobId, epoch] as const,
  privateUrl: (jobId: string, epoch: number) => ['analytics', 'private-url', jobId, epoch] as const,
}

function queryString(query: AnalyticsQuery) {
  const params = new URLSearchParams({ page: String(query.page), page_size: String(query.page_size) })
  ;(['name', 'medical_record_no', 'treatment_status', 'primary_doctor', 'created_from', 'created_to'] as const).forEach((key) => {
    if (query[key]) params.set(key, query[key])
  })
  return params.toString()
}

function payloadFilters(filters: AnalyticsFilters) {
  return Object.fromEntries(Object.entries(filters).filter(([, value]) => value !== ''))
}

export function getDashboard(signal?: AbortSignal) { return apiRequest<DashboardMetrics>('/v1/admin/analytics/dashboard/', { signal }) }
export function getPatientMetrics(query: AnalyticsQuery, signal?: AbortSignal) { return apiRequest<PatientMetricsPage>(`/v1/admin/analytics/patients/?${queryString(query)}`, { signal }) }
export function getExportJob(id: string, signal?: AbortSignal) { return apiRequest<ExportJob>(`/v1/admin/analytics/exports/${id}/`, { signal }) }
export function getExportPrivateUrl(id: string, signal?: AbortSignal) { return apiRequest<ExportPrivateUrl>(`/v1/admin/analytics/exports/${id}/private-url/`, { method: 'POST', signal }) }

export type CreateExportInput = { format: ExportFormat; filters: AnalyticsFilters; selected_ids: string[]; idempotency_key: string }
export type ExportResult = { mode: 'sync'; response: Response } | { mode: 'async'; job: ExportJob }

export async function createExport(input: CreateExportInput, signal?: AbortSignal): Promise<ExportResult> {
  const response = await apiRawRequest('/v1/admin/analytics/exports/', {
    method: 'POST', signal,
    body: JSON.stringify({ format: input.format, filters: payloadFilters(input.filters), selected_ids: input.selected_ids, idempotency_key: input.idempotency_key }),
  })
  const contentType = response.headers.get('content-type')?.toLowerCase() ?? ''
  if (!contentType.includes('application/json')) return { mode: 'sync', response }
  try {
    const envelope = await response.json() as { data?: ExportJob }
    if (!envelope.data?.id) throw new Error('invalid')
    return { mode: 'async', job: envelope.data }
  } catch {
    throw new ApiError('invalid_response', '服务响应格式异常', response.headers.get('x-request-id') ?? '', undefined, response.status)
  }
}

function hasControlCharacter(value: string) { return [...value].some((character) => character.charCodeAt(0) <= 31 || character.charCodeAt(0) === 127) }
export function safeDownloadFilename(header: string | null, fallback: string) {
  const encoded = header?.match(/filename\*\s*=\s*(?:UTF-8'')?([^;]+)/i)?.[1]
  const plain = header?.match(/filename\s*=\s*(?:"([^"]*)"|([^;\s]+))/i)
  let candidate = fallback
  try { candidate = encoded ? decodeURIComponent(encoded.trim().replace(/^"|"$/g, '')) : (plain?.[1] ?? plain?.[2] ?? fallback) } catch { candidate = fallback }
  return candidate && !hasControlCharacter(candidate) && !/[\\/]/.test(candidate) ? candidate.slice(0, 160) : fallback
}

export function triggerBlobDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = filename; anchor.style.display = 'none'
  document.body.append(anchor); anchor.click(); anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
  return url
}
