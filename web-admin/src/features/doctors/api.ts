import { apiRequest } from '../../api/client'
import type { Doctor, DoctorListQuery, DoctorWrite, PaginatedDoctors } from './types'

export const doctorKeys = {
  all: ['doctors'] as const,
  lists: () => [...doctorKeys.all, 'list'] as const,
  list: (query: DoctorListQuery) => [...doctorKeys.lists(), query] as const,
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
