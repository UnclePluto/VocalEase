import { Alert, Button, Input, Select, Spin } from 'antd'
import { useQuery } from '@tanstack/react-query'

import { ApiError } from '../../api/errors'
import { doctorOptionLookupKeys, getDoctorOption } from './api'
import type { RemoteDoctorOptions } from './useRemoteDoctorOptions'
import type { Doctor } from './types'

type Props = {
  ariaLabel: string
  lookupEnabled?: boolean
  mode: 'filter' | 'form'
  onChange?: (value: string | undefined) => void
  selectedDoctor?: Pick<Doctor, 'id' | 'name' | 'employee_no'> | null
  source: RemoteDoctorOptions
  value?: string
}

export function RemoteDoctorSelect({ ariaLabel, lookupEnabled = true, mode, onChange, selectedDoctor, source, value }: Props) {
  const change = onChange ?? (() => undefined)
  const needsLookup = Boolean(
    lookupEnabled
    && !source.isLoading
    && value
    && !selectedDoctor
    && !source.doctors.some((doctor) => doctor.id === value),
  )
  const lookup = useQuery({
    queryKey: doctorOptionLookupKeys.detail(value ?? ''),
    queryFn: ({ signal }) => getDoctorOption(value ?? '', signal),
    enabled: needsLookup,
  })
  const lookedUp = needsLookup ? lookup.data : null
  const selected = selectedDoctor ?? lookedUp
  const doctors = selected
    ? [selected, ...source.doctors.filter((doctor) => doctor.id !== selected.id)]
    : source.doctors
  const lookupError = lookup.error instanceof ApiError ? lookup.error : null
  const error = source.error ?? lookupError

  const feedback = (
    <>
      {error ? (
        <Alert
          className="doctor-options-error"
          type="error"
          showIcon
          title={error.message}
          description={error.requestId ? `请求编号：${error.requestId}` : undefined}
          action={<Button aria-label="重试医生选项" size="small" onClick={source.error ? source.retry : () => void lookup.refetch()}>重试</Button>}
        />
      ) : null}
      {source.hasMore ? <Button aria-label="加载更多医生" type="link" loading={source.isLoading} onClick={source.loadMore}>加载更多</Button> : null}
      {!source.isLoading && !error && doctors.length === 0 ? <span role="status">无匹配医生</span> : null}
      {source.isLoading ? <span role="status"><Spin size="small" /> 正在加载医生</span> : null}
    </>
  )

  if (mode === 'form') {
    return (
      <div className="doctor-picker">
        <Input
          aria-label="搜索主治医生选项"
          allowClear
          placeholder="输入姓名或工号搜索"
          value={source.search}
          onChange={(event) => source.setSearch(event.target.value)}
        />
        <select
          aria-label={ariaLabel}
          className="native-select"
          disabled={source.isLoading && doctors.length === 0}
          value={value ?? ''}
          onChange={(event) => change(event.target.value || undefined)}
        >
          <option value="">请选择</option>
          {doctors.map((doctor) => (
            <option key={doctor.id} value={doctor.id}>{doctor.name}{doctor.employee_no ? ` · ${doctor.employee_no}` : ''}</option>
          ))}
        </select>
        {feedback}
      </div>
    )
  }

  return (
    <div className="doctor-filter-picker">
      <Select
        id="patient-doctor-filter"
        aria-label={ariaLabel}
        placeholder="全部主治医生"
        value={value}
        allowClear
        loading={source.isLoading || lookup.isPending}
        showSearch
        filterOption={false}
        searchValue={source.search}
        onSearch={source.setSearch}
        options={doctors.map((doctor) => ({ value: doctor.id, label: `${doctor.name}${doctor.employee_no ? ` · ${doctor.employee_no}` : ''}` }))}
        onChange={change}
      />
      {feedback}
    </div>
  )
}
