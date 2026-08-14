import type { Gender } from '../doctors/types'

export type TreatmentStatus = 'pending' | 'active' | 'completed' | 'cancelled'

export type TreatmentPlan = {
  id: string
  start_date: string
  cycle_weeks: number
  target_session_count: number
  status: TreatmentStatus
}

export type Patient = {
  id: string
  user_id: string
  medical_record_no: string
  name: string
  gender: Gender
  enrollment_age: number
  phone: string
  primary_doctor: string
  primary_doctor_name: string
  notes: string
  treatment_plan: TreatmentPlan | null
}

export type PatientWrite = {
  name: string
  gender: Gender
  enrollment_age: number
  phone: string
  primary_doctor: string
  start_date?: string
  cycle_weeks?: number
  notes: string
}

export type PatientListQuery = {
  page: number
  page_size: number
  search?: string
  status?: TreatmentStatus
  doctor?: string
}

export type PaginatedPatients = {
  count: number
  page: number
  page_size: number
  results: Patient[]
}
