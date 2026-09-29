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
  const versionRef = useRef(0)
  const [request, setRequest] = useState({ keyword: '', page: 1, version: 0 })
  const requestRef = useRef(request)
  const [doctors, setDoctors] = useState<Doctor[]>([])
  const [isDebouncing, setIsDebouncing] = useState(false)
  const refetchRef = useRef<() => void>(() => undefined)

  const changeSearch = useCallback((value: string) => {
    const version = versionRef.current + 1
    versionRef.current = version
    setSearch(value)
    setDoctors([])
    setIsDebouncing(true)
    requestRef.current = { keyword: value.trim(), page: 0, version }
  }, [])

  useEffect(() => {
    const keyword = search.trim()
    const version = versionRef.current
    if (version === 0) return undefined
    const timer = window.setTimeout(() => {
      if (version !== versionRef.current) return
      const next = { keyword, page: 1, version }
      requestRef.current = next
      if (keyword === request.keyword && request.page === 1) refetchRef.current()
      else setRequest(next)
    }, 250)
    return () => window.clearTimeout(timer)
  }, [request.keyword, request.page, search])

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
      if (active.keyword === request.keyword && active.page === request.page && active.version === request.version) {
        setDoctors((current) => request.page === 1
          ? mergeUnique([], response.results)
          : mergeUnique(current, response.results))
        setIsDebouncing(false)
      }
      return response
    },
  })
  const refetch = query.refetch
  useEffect(() => {
    refetchRef.current = () => { void refetch() }
  }, [refetch])

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
    isLoading: isDebouncing || query.isPending || query.isFetching,
    loadMore,
    retry,
    search,
    setSearch: changeSearch,
  }), [changeSearch, doctors, error, hasMore, isDebouncing, loadMore, query.isFetching, query.isPending, retry, search])
}
