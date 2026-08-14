import { apiRequest } from '../../api/client'
import type { PaginatedPatients, PatientDetail, PatientListQuery, PatientWrite } from './types'

export const patientKeys = {
  all: ['patients'] as const,
  lists: () => [...patientKeys.all, 'list'] as const,
  list: (query: PatientListQuery) => [...patientKeys.lists(), query] as const,
  details: () => [...patientKeys.all, 'detail'] as const,
  detail: (id: string) => [...patientKeys.details(), id] as const,
}

function patientSearch(query: PatientListQuery) {
  const params = new URLSearchParams()
  params.set('page', String(query.page))
  params.set('page_size', String(query.page_size))
  if (query.search) params.set('keyword', query.search)
  if (query.status) params.set('status', query.status)
  if (query.doctor) params.set('primary_doctor', query.doctor)
  return params.toString()
}

export function listPatients(query: PatientListQuery, signal?: AbortSignal) {
  return apiRequest<PaginatedPatients>(`/v1/admin/patients/?${patientSearch(query)}`, { signal })
}

export function getPatient(id: string, signal?: AbortSignal) {
  return apiRequest<PatientDetail>(`/v1/admin/patients/${id}/`, { signal })
}

export function createPatient(values: PatientWrite) {
  return apiRequest<PatientDetail>('/v1/admin/patients/', { method: 'POST', body: JSON.stringify(values) })
}

export function updatePatient(id: string, values: PatientWrite) {
  return apiRequest<PatientDetail>(`/v1/admin/patients/${id}/`, { method: 'PATCH', body: JSON.stringify(values) })
}

export function deletePatient(id: string) {
  return apiRequest<void>(`/v1/admin/patients/${id}/`, { method: 'DELETE' })
}

export function resetPatientPassword(userId: string) {
  return apiRequest<Record<string, never>>(`/v1/admin/users/${userId}/reset-password/`, { method: 'POST' })
}
