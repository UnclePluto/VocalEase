import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'

import { ApiError } from '../../api/errors'
import { doctorOptionKeys, listDoctors } from './api'
import type { Doctor } from './types'

const PAGE_SIZE = 20

function mergeUnique(current: Doctor[], incoming: Doctor[]) {
  const doctors = new Map(current.map((doctor) => [doctor.id, doctor]))
  incoming.forEach((doctor) => doctors.set(doctor.id, doctor))
  return [...doctors.values()]
}

export type RemoteDoctorOptions = {
  doctors: Doctor[]
  error: ApiError | null
  hasMore: boolean
  isLoading: boolean
  loadMore: () => void
  retry: () => void
  search: string
  setSearch: (value: string) => void
}

export function useRemoteDoctorOptions(): RemoteDoctorOptions {
  const [search, setSearch] = useState('')
  const [request, setRequest] = useState({ keyword: '', page: 1 })
  const requestRef = useRef(request)
  const [doctors, setDoctors] = useState<Doctor[]>([])

  useEffect(() => {
    const keyword = search.trim()
    const timer = window.setTimeout(() => {
      if (keyword === request.keyword) return
      setDoctors([])
      const next = { keyword, page: 1 }
      requestRef.current = next
      setRequest(next)
    }, 250)
    return () => window.clearTimeout(timer)
  }, [request.keyword, search])

  const query = useQuery({
    queryKey: doctorOptionKeys.page(request.keyword, request.page),
    queryFn: async ({ signal }) => {
      const response = await listDoctors({
        page: request.page,
        page_size: PAGE_SIZE,
        search: request.keyword || undefined,
        status: 'active',
      }, signal)
      const active = requestRef.current
      if (active.keyword === request.keyword && active.page === request.page) {
        setDoctors((current) => request.page === 1
          ? mergeUnique([], response.results)
          : mergeUnique(current, response.results))
      }
      return response
    },
  })

  const hasMore = Boolean(query.data && query.data.page * query.data.page_size < query.data.count)
  const error = useMemo(() => query.error instanceof ApiError
    ? query.error
    : query.error ? new ApiError('doctor_options_failed', '医生选项加载失败') : null, [query.error])
  const loadMore = useCallback(() => {
    if (!hasMore || query.isFetching) return
    const next = { ...requestRef.current, page: requestRef.current.page + 1 }
    requestRef.current = next
    setRequest(next)
  }, [hasMore, query.isFetching])
  const retry = useCallback(() => { void query.refetch() }, [query])

  return useMemo(() => ({
    doctors,
    error,
    hasMore,
    isLoading: query.isPending || query.isFetching,
    loadMore,
    retry,
    search,
    setSearch,
  }), [doctors, error, hasMore, loadMore, query.isFetching, query.isPending, retry, search])
}
