import { apiRequest } from '../../api/client'
import type { PrivateMediaUrl, SingingPage, SingingSession } from './types'

export function getSingingHistory(patientId: string, query: { page: number; page_size: number; created_from?: string; created_to?: string }, signal?: AbortSignal) {
  const params = new URLSearchParams({ patient_id: patientId, page: String(query.page), page_size: String(query.page_size) })
  if (query.created_from) params.set('created_from', query.created_from)
  if (query.created_to) params.set('created_to', query.created_to)
  return apiRequest<SingingPage>(`/v1/admin/singing-sessions/?${params}`, { signal })
}
export function getSingingSession(id: string, signal?: AbortSignal) { return apiRequest<SingingSession>(`/v1/admin/singing-sessions/${id}/`, { signal }) }
export function getPrivateMediaUrl(assetId: string, signal?: AbortSignal) { return apiRequest<PrivateMediaUrl>(`/v1/admin/media/${assetId}/private-url/`, { method: 'POST', signal }) }
