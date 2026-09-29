import { apiRequest } from '../../api/client'
import type { Doctor, DoctorListQuery, DoctorOption, DoctorWrite, PaginatedDoctors } from './types'

export const doctorKeys = {
  all: ['doctors'] as const,
  lists: () => [...doctorKeys.all, 'list'] as const,
  list: (query: DoctorListQuery) => [...doctorKeys.lists(), query] as const,
  details: () => [...doctorKeys.all, 'detail'] as const,
  detail: (id: string) => [...doctorKeys.details(), id] as const,
}

export const doctorOptionKeys = {
  all: ['doctor-options'] as const,
  page: (search: string, page: number) => [...doctorOptionKeys.all, { search, page }] as const,
}

export const doctorOptionLookupKeys = {
  all: ['doctor-option-lookups'] as const,
  detail: (id: string) => [...doctorOptionLookupKeys.all, id] as const,
}

function doctorSearch(query: DoctorListQuery) {
  const params = new URLSearchParams()
  params.set('page', String(query.page))
  params.set('page_size', String(query.page_size))
  if (query.search) params.set('keyword', query.search)
  if (query.status) params.set('status', query.status)
  if (query.department) params.set('department', query.department)
  return params.toString()
}

export function listDoctors(query: DoctorListQuery, signal?: AbortSignal) {
  return apiRequest<PaginatedDoctors>(`/v1/admin/doctors/?${doctorSearch(query)}`, { signal })
}

export function getDoctor(id: string, signal?: AbortSignal) {
  return apiRequest<Doctor>(`/v1/admin/doctors/${id}/`, { signal })
}

export function getDoctorOption(id: string, signal?: AbortSignal) {
  return apiRequest<DoctorOption>(`/v1/admin/doctors/${id}/option/`, { signal })
}

export function createDoctor(values: DoctorWrite) {
  return apiRequest<Doctor>('/v1/admin/doctors/', { method: 'POST', body: JSON.stringify(values) })
}

export function updateDoctor(id: string, values: DoctorWrite) {
  return apiRequest<Doctor>(`/v1/admin/doctors/${id}/`, { method: 'PATCH', body: JSON.stringify(values) })
}

export function deleteDoctor(id: string) {
  return apiRequest<void>(`/v1/admin/doctors/${id}/`, { method: 'DELETE' })
}

export function resetDoctorPassword(userId: string) {
  return apiRequest<Record<string, never>>(`/v1/admin/users/${userId}/reset-password/`, { method: 'POST' })
}

export function setDoctorActive(id: string, isActive: boolean) {
  const action = isActive ? 'activate' : 'deactivate'
  return apiRequest<Doctor>(`/v1/admin/doctors/${id}/${action}/`, { method: 'POST' })
}
