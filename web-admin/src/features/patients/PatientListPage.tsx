import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { BarChartOutlined, DeleteOutlined, EditOutlined, LockOutlined, MoreOutlined, PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, Dropdown, Input, Modal, Select, Space, message } from 'antd'
import type { TableColumnsType } from 'antd'
import type { InputRef } from 'antd'
import { useNavigate, useSearchParams } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { ConfirmDelete } from '../../components/ConfirmDelete'
import { DataTable } from '../../components/DataTable'
import { StatusTag } from '../../components/StatusTag'
import { useCompactActions } from '../../hooks/useCompactActions'
import { RemoteDoctorSelect } from '../doctors/RemoteDoctorSelect'
import { useRemoteDoctorOptions } from '../doctors/useRemoteDoctorOptions'
import {
  createPatient,
  deletePatient,
  getPatient,
  listPatients,
  patientKeys,
  resetPatientPassword,
  updatePatient,
} from './api'
import { PatientFormModal } from './PatientFormModal'
import type { Patient, PatientDetail, PatientListQuery, PatientWrite, TreatmentStatus } from './types'

const PAGE_SIZES = new Set([10, 20, 50, 100])
const STATUSES = new Set<TreatmentStatus>(['pending', 'active', 'completed', 'cancelled'])
const UUID_PATTERN = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i

function positiveInteger(value: string | null, fallback: number) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}

function readQuery(params: URLSearchParams): PatientListQuery {
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

function uiParams(query: PatientListQuery) {
  const params = new URLSearchParams()
  params.set('page', String(query.page))
  params.set('page_size', String(query.page_size))
  if (query.search) params.set('search', query.search)
  if (query.status) params.set('status', query.status)
  if (query.doctor) params.set('doctor', query.doctor)
  return params
}

export function PatientListPage() {
  const [params, setParams] = useSearchParams()
  const query = useMemo(() => readQuery(params), [params])
  const searchRef = useRef<InputRef>(null)
  const navigate = useNavigate()
  const compactActions = useCompactActions()
  const doctorSource = useRemoteDoctorOptions()
  const [formPatient, setFormPatient] = useState<PatientDetail | null | undefined>(undefined)
  const [detailTarget, setDetailTarget] = useState<Patient | null>(null)
  const detailSubmissionRef = useRef(false)
  const [deleteTarget, setDeleteTarget] = useState<Patient | null>(null)
  const [resetTarget, setResetTarget] = useState<Patient | null>(null)
  const deleteSubmissionRef = useRef(false)
  const resetSubmissionRef = useRef(false)
  const queryClient = useQueryClient()
  const [messageApi, messageContext] = message.useMessage()

  useEffect(() => {
    const canonical = uiParams(query)
    if (params.toString() !== canonical.toString()) setParams(canonical, { replace: true })
  }, [params, query, setParams])

  const listQuery = useQuery({
    queryKey: patientKeys.list(query),
    queryFn: ({ signal }) => listPatients(query, signal),
  })
  const selectedDoctor = useMemo(() => {
    if (!query.doctor || doctorSource.doctors.some((doctor) => doctor.id === query.doctor)) return null
    const patientWithDoctor = listQuery.data?.results.find((item) => item.primary_doctor === query.doctor)
    if (!patientWithDoctor) return null
    return {
      id: patientWithDoctor.primary_doctor,
      name: patientWithDoctor.primary_doctor_name,
      employee_no: '',
    }
  }, [doctorSource.doctors, listQuery.data?.results, query.doctor])

  const detailMutation = useMutation({
    mutationFn: (id: string) => getPatient(id),
    onSuccess: (patient) => {
      setFormPatient(patient)
      setDetailTarget(null)
    },
    onError: () => { detailSubmissionRef.current = false },
  })

  const openEdit = useCallback((patient: Patient) => {
    if (detailSubmissionRef.current) return
    detailSubmissionRef.current = true
    detailMutation.reset()
    setFormPatient(undefined)
    setDetailTarget(patient)
    detailMutation.mutate(patient.id)
  }, [detailMutation])

  const saveMutation = useMutation({
    mutationFn: ({ values, patient }: { values: PatientWrite; patient?: PatientDetail | null }) => (
      patient ? updatePatient(patient.id, values) : createPatient(values)
    ),
    onSuccess: async (_saved, variables) => {
      await queryClient.invalidateQueries({ queryKey: patientKeys.list(query), exact: true })
      detailSubmissionRef.current = false
      setFormPatient(undefined)
      if (variables.patient) messageApi.success('患者信息已更新')
      else messageApi.success('患者创建成功，初始密码为 888888，首次登录需修改')
    },
  })
  const deleteMutation = useMutation({
    mutationFn: (id: string) => deletePatient(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: patientKeys.list(query), exact: true })
      setDeleteTarget(null)
      messageApi.success('患者已停用并隐藏，历史数据已保留')
    },
    onSettled: () => { deleteSubmissionRef.current = false },
  })
  const resetMutation = useMutation({
    mutationFn: (userId: string) => resetPatientPassword(userId),
    onSuccess: () => {
      setResetTarget(null)
      messageApi.success('密码已重置为 888888，首次登录必须修改')
    },
    onSettled: () => { resetSubmissionRef.current = false },
  })

  const replaceQuery = (changes: Partial<PatientListQuery>, resetPage = false) => {
    setParams(uiParams({ ...query, ...changes, page: resetPage ? 1 : (changes.page ?? query.page) }))
  }

  const columns = useMemo<TableColumnsType<Patient>>(() => [
    { title: '病历号', dataIndex: 'medical_record_no', width: 120 },
    { title: '姓名', dataIndex: 'name', width: 110 },
    { title: '性别', dataIndex: 'gender', width: 72, render: (value: Patient['gender']) => value === 'male' ? '男' : '女' },
    { title: '入组年龄', dataIndex: 'enrollment_age', width: 100 },
    { title: '手机号', dataIndex: 'phone', width: 145 },
    { title: '主治医生', dataIndex: 'primary_doctor_name', width: 120 },
    {
      title: '治疗状态', key: 'status', width: 110,
      render: (_value, row) => row.treatment_plan ? <StatusTag scope="treatment" status={row.treatment_plan.status} /> : '—',
    },
    {
      title: '治疗周期', key: 'cycle', width: 140,
      render: (_value, row) => row.treatment_plan ? `${row.treatment_plan.cycle_weeks} 周 / ${row.treatment_plan.target_session_count} 次` : '—',
    },
    {
      title: '操作', key: 'actions', width: compactActions ? 72 : 380, fixed: 'right',
      render: (_value, row) => {
        const openData = () => navigate(`/patients/${row.id}/data`)
        const openEditRow = () => openEdit(row)
        const openReset = () => { resetSubmissionRef.current = false; resetMutation.reset(); setResetTarget(row) }
        const openDelete = () => { deleteSubmissionRef.current = false; deleteMutation.reset(); setDeleteTarget(row) }
        if (compactActions) {
          return (
            <Dropdown menu={{ items: [
              { key: 'data', label: '查看患者数据', onClick: openData },
              { key: 'edit', label: '编辑', onClick: openEditRow },
              { key: 'reset', label: '重置密码', onClick: openReset },
              { key: 'delete', label: '删除', danger: true, onClick: openDelete },
            ] }} trigger={['click']}>
              <Button type="text" size="small" icon={<MoreOutlined />} aria-label={`更多${row.name}操作`}>更多</Button>
            </Dropdown>
          )
        }
        return (
          <Space size={4}>
            <Button type="link" size="small" icon={<BarChartOutlined />} aria-label={`查看${row.name}数据`} onClick={openData}>查看数据</Button>
            <Button type="link" size="small" icon={<EditOutlined />} aria-label={`编辑${row.name}`} onClick={openEditRow}>编辑</Button>
            <Button type="link" size="small" icon={<LockOutlined />} aria-label={`重置${row.name}密码`} onClick={openReset}>重置密码</Button>
            <Button danger type="link" size="small" icon={<DeleteOutlined />} aria-label={`删除${row.name}`} onClick={openDelete}>删除</Button>
          </Space>
        )
      },
    },
  ], [compactActions, deleteMutation, navigate, openEdit, resetMutation])

  const resetError = resetMutation.error instanceof ApiError ? resetMutation.error : null
  return (
    <section className="management-page" aria-labelledby="patient-page-title">
      {messageContext}
      <div className="management-heading">
        <div><h1 id="patient-page-title">病人管理</h1><p>维护患者档案与当前治疗计划</p></div>
        <Button aria-label="新增患者" type="primary" icon={<PlusOutlined />} onClick={() => { detailSubmissionRef.current = false; setFormPatient(null) }}>新增患者</Button>
      </div>
      <div className="management-surface">
        <div className="management-toolbar">
          <Space.Compact className="management-search">
            <Input
              aria-label="搜索患者"
              defaultValue={query.search ?? ''}
              key={`patient-search-${query.search ?? ''}`}
              placeholder="搜索姓名或病历号"
              ref={searchRef}
              allowClear
              onPressEnter={(event) => replaceQuery({ search: event.currentTarget.value.trim() || undefined }, true)}
            />
            <Button aria-label="搜索" type="primary" onClick={() => replaceQuery({ search: searchRef.current?.input?.value.trim() || undefined }, true)}>搜索</Button>
          </Space.Compact>
          <Select
            id="patient-treatment-status-filter"
            aria-label="治疗状态"
            placeholder="全部治疗状态"
            value={query.status}
            allowClear
            options={[
              { value: 'pending', label: '待开始' },
              { value: 'active', label: '进行中' },
              { value: 'completed', label: '已完成' },
              { value: 'cancelled', label: '已取消' },
            ]}
            onChange={(value) => replaceQuery({ status: value }, true)}
          />
          <RemoteDoctorSelect
            ariaLabel="主治医生筛选"
            mode="filter"
            lookupEnabled={!listQuery.isPending}
            source={doctorSource}
            selectedDoctor={selectedDoctor}
            value={query.doctor}
            onChange={(value) => replaceQuery({ doctor: value }, true)}
          />
          <Button onClick={() => setParams(uiParams({ page: 1, page_size: query.page_size }))}>重置</Button>
        </div>
        <DataTable<Patient>
          ariaLabel="正在加载患者列表"
          columns={columns}
          data={listQuery.data}
          emptyText="暂无患者"
          error={listQuery.error}
          loading={listQuery.isPending}
          onPageChange={(page, pageSize) => replaceQuery({ page, page_size: pageSize }, pageSize !== query.page_size)}
          onRetry={() => void listQuery.refetch()}
          rowKey="id"
          scrollX={1220}
        />
      </div>
      <PatientFormModal
        key={detailTarget?.id ?? formPatient?.id ?? (formPatient === null ? 'new' : 'closed')}
        detailError={detailMutation.error instanceof ApiError ? detailMutation.error : null}
        detailLoading={detailMutation.isPending}
        detailTarget={detailTarget}
        patient={formPatient}
        doctorSource={doctorSource}
        open={formPatient !== undefined || detailTarget !== null}
        onCancel={() => {
          if (saveMutation.isPending || detailMutation.isPending) return
          detailSubmissionRef.current = false
          setDetailTarget(null)
          setFormPatient(undefined)
        }}
        onRetryDetail={() => {
          if (!detailTarget || detailSubmissionRef.current) return
          detailSubmissionRef.current = true
          detailMutation.mutate(detailTarget.id)
        }}
        onSubmit={async (values) => { await saveMutation.mutateAsync({ values, patient: formPatient }) }}
      />
      <ConfirmDelete
        title={`删除患者“${deleteTarget?.name ?? ''}”`}
        content="删除后患者账号将停用并隐藏，历史治疗和演唱数据将保留。"
        open={deleteTarget !== null}
        loading={deleteMutation.isPending}
        error={deleteMutation.error}
        onCancel={() => { if (!deleteMutation.isPending) setDeleteTarget(null) }}
        onConfirm={() => {
          if (!deleteTarget || deleteSubmissionRef.current) return
          deleteSubmissionRef.current = true
          deleteMutation.mutate(deleteTarget.id)
        }}
      />
      <Modal
        title={`重置“${resetTarget?.name ?? ''}”的密码`}
        open={resetTarget !== null}
        okText="确认重置"
        cancelText="取消"
        onCancel={() => { if (!resetMutation.isPending) setResetTarget(null) }}
        onOk={() => {
          if (!resetTarget || resetSubmissionRef.current) return
          resetSubmissionRef.current = true
          resetMutation.mutate(resetTarget.user_id)
        }}
        okButtonProps={{ loading: resetMutation.isPending, disabled: resetMutation.isPending, 'aria-label': '确认重置' }}
        cancelButtonProps={{ disabled: resetMutation.isPending, 'aria-label': '取消' }}
        mask={{ closable: !resetMutation.isPending }}
      >
        <p>密码将恢复为 888888，该患者首次登录必须修改密码。</p>
        {resetError ? <p className="inline-error">{resetError.message}{resetError.requestId ? `（请求编号：${resetError.requestId}）` : ''}</p> : null}
      </Modal>
    </section>
  )
}
