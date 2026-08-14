export type Gender = 'male' | 'female'
export type DoctorStatus = 'active' | 'inactive'

export type Doctor = {
  id: string
  user_id: string
  employee_no: string
  name: string
  gender: Gender
  phone: string
  department: string
  title: string
  status: DoctorStatus
}

export type DoctorWrite = Pick<Doctor, 'name' | 'gender' | 'phone' | 'department' | 'title'>

export type DoctorListQuery = {
  page: number
  page_size: number
  search?: string
  status?: DoctorStatus
  department?: string
}

export type PaginatedDoctors = {
  count: number
  page: number
  page_size: number
  results: Doctor[]
}
