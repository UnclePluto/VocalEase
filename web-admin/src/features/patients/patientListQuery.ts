import type { PatientListQuery, TreatmentStatus } from './types'

const PAGE_SIZES = new Set([10, 20, 50, 100])
const STATUSES = new Set<TreatmentStatus>(['pending', 'active', 'completed', 'cancelled'])
const UUID_PATTERN = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i

function positiveInteger(value: string | null, fallback: number) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}

export function readQuery(params: URLSearchParams): PatientListQuery {
  const rawStatus = params.get('status') as TreatmentStatus | null
  const rawPageSize = positiveInteger(params.get('page_size'), 20)
  const rawDoctor = params.get('doctor')
  return {
    page: positiveInteger(params.get('page'), 1),
    page_size: PAGE_SIZES.has(rawPageSize) ? rawPageSize : 20,
    search: params.get('search')?.trim() || undefined,
    status: rawStatus && STATUSES.has(rawStatus) ? rawStatus : undefined,
    doctor: rawDoctor && UUID_PATTERN.test(rawDoctor) ? rawDoctor : undefined,
  }
}

export function uiParams(query: PatientListQuery) {
  const params = new URLSearchParams()
  params.set('page', String(query.page))
  params.set('page_size', String(query.page_size))
  if (query.search) params.set('search', query.search)
  if (query.status) params.set('status', query.status)
  if (query.doctor) params.set('doctor', query.doctor)
  return params
}

