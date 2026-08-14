import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CheckCircleOutlined, DeleteOutlined, EditOutlined, LockOutlined, MoreOutlined, PlusOutlined, StopOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, Dropdown, Input, Modal, Select, Space, message } from 'antd'
import type { TableColumnsType } from 'antd'
import type { InputRef } from 'antd'
import { useSearchParams } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { useAuthStore } from '../../auth/store'
import { ConfirmDelete } from '../../components/ConfirmDelete'
import { DataTable } from '../../components/DataTable'
import { StatusTag } from '../../components/StatusTag'
import { useCompactActions } from '../../hooks/useCompactActions'
import {
  createDoctor,
  deleteDoctor,
  doctorKeys,
  doctorOptionKeys,
  getDoctor,
  listDoctors,
  resetDoctorPassword,
  setDoctorActive,
  updateDoctor,
} from './api'
import { DoctorFormModal } from './DoctorFormModal'
import type { Doctor, DoctorListQuery, DoctorStatus, DoctorWrite } from './types'

const PAGE_SIZES = new Set([10, 20, 50, 100])
const STATUSES = new Set<DoctorStatus>(['active', 'inactive'])

function positiveInteger(value: string | null, fallback: number) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}

function readQuery(params: URLSearchParams): DoctorListQuery {
  const rawStatus = params.get('status') as DoctorStatus | null
  const rawPageSize = positiveInteger(params.get('page_size'), 20)
  return {
    page: positiveInteger(params.get('page'), 1),
    page_size: PAGE_SIZES.has(rawPageSize) ? rawPageSize : 20,
    search: params.get('search')?.trim() || undefined,
    status: rawStatus && STATUSES.has(rawStatus) ? rawStatus : undefined,
    department: params.get('department')?.trim() || undefined,
  }
}

function uiParams(query: DoctorListQuery) {
  const params = new URLSearchParams()
  params.set('page', String(query.page))
  params.set('page_size', String(query.page_size))
  if (query.search) params.set('search', query.search)
  if (query.status) params.set('status', query.status)
  if (query.department) params.set('department', query.department)
  return params
}

export function DoctorListPage() {
  const [params, setParams] = useSearchParams()
  const query = useMemo(() => readQuery(params), [params])
  const searchRef = useRef<InputRef>(null)
  const departmentRef = useRef<InputRef>(null)
  const [formDoctor, setFormDoctor] = useState<Doctor | null | undefined>(undefined)
  const [detailTarget, setDetailTarget] = useState<Doctor | null>(null)
  const detailSubmissionRef = useRef(false)
  const [deleteTarget, setDeleteTarget] = useState<Doctor | null>(null)
  const [resetTarget, setResetTarget] = useState<Doctor | null>(null)
  const [statusTarget, setStatusTarget] = useState<Doctor | null>(null)
  const deleteSubmissionRef = useRef(false)
  const resetSubmissionRef = useRef(false)
  const statusSubmissionRef = useRef(false)
  const queryClient = useQueryClient()
  const [messageApi, messageContext] = message.useMessage()
  const currentUserRole = useAuthStore((state) => state.user?.role)
  const currentLoginId = useAuthStore((state) => state.user?.login_id)
  const compactActions = useCompactActions()

  useEffect(() => {
    const canonical = uiParams(query)
    if (params.toString() !== canonical.toString()) setParams(canonical, { replace: true })
  }, [params, query, setParams])

  const listQuery = useQuery({
    queryKey: doctorKeys.list(query),
    queryFn: ({ signal }) => listDoctors(query, signal),
  })

  const detailMutation = useMutation({
    mutationFn: (id: string) => getDoctor(id),
    onSuccess: (doctor) => {
      setFormDoctor(doctor)
      setDetailTarget(null)
    },
    onError: () => { detailSubmissionRef.current = false },
  })

  const openEdit = useCallback((doctor: Doctor) => {
    if (detailSubmissionRef.current) return
    detailSubmissionRef.current = true
    detailMutation.reset()
    setFormDoctor(undefined)
    setDetailTarget(doctor)
    detailMutation.mutate(doctor.id)
  }, [detailMutation])

  const expireDoctorOptions = () => {
    queryClient.removeQueries({ queryKey: doctorOptionKeys.all })
  }

  const saveMutation = useMutation({
    mutationFn: ({ values, doctor }: { values: DoctorWrite; doctor?: Doctor | null }) => (
      doctor ? updateDoctor(doctor.id, values) : createDoctor(values)
    ),
    onSuccess: async (_saved, variables) => {
      await queryClient.invalidateQueries({ queryKey: doctorKeys.list(query), exact: true })
      expireDoctorOptions()
      detailSubmissionRef.current = false
      setFormDoctor(undefined)
      if (variables.doctor) messageApi.success('医生信息已更新')
      else messageApi.success('医生创建成功，初始密码为 888888，首次登录需修改')
    },
  })
  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteDoctor(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: doctorKeys.list(query), exact: true })
      expireDoctorOptions()
      setDeleteTarget(null)
      messageApi.success('医生已停用并隐藏，历史记录已保留')
    },
    onSettled: () => { deleteSubmissionRef.current = false },
  })
  const resetMutation = useMutation({
    mutationFn: (userId: string) => resetDoctorPassword(userId),
    onSuccess: () => {
      setResetTarget(null)
      messageApi.success('密码已重置为 888888，首次登录必须修改')
    },
    onSettled: () => { resetSubmissionRef.current = false },
  })
  const statusMutation = useMutation({
    mutationFn: (target: Doctor) => setDoctorActive(target.id, target.status === 'inactive'),
    onSuccess: async (updated) => {
      await queryClient.invalidateQueries({ queryKey: doctorKeys.list(query), exact: true })
      expireDoctorOptions()
      setStatusTarget(null)
      messageApi.success(updated.status === 'active' ? '医生已启用' : '医生已停用')
    },
    onSettled: () => { statusSubmissionRef.current = false },
  })

  const replaceQuery = (changes: Partial<DoctorListQuery>, resetPage = false) => {
    setParams(uiParams({ ...query, ...changes, page: resetPage ? 1 : (changes.page ?? query.page) }))
  }

  const columns = useMemo<TableColumnsType<Doctor>>(() => [
    { title: '工号', dataIndex: 'employee_no', width: 110 },
    { title: '姓名', dataIndex: 'name', width: 120 },
    { title: '性别', dataIndex: 'gender', width: 80, render: (value: Doctor['gender']) => value === 'male' ? '男' : '女' },
    { title: '手机号', dataIndex: 'phone', width: 145 },
    { title: '科室', dataIndex: 'department', width: 140 },
    { title: '职称', dataIndex: 'title', width: 150 },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: DoctorStatus) => <StatusTag status={value} /> },
    {
      title: '操作', key: 'actions', width: compactActions ? 72 : 350, fixed: 'right',
      render: (_value, row) => {
        const isSelf = currentUserRole === 'doctor' && currentLoginId === row.employee_no
        const openEditRow = () => openEdit(row)
        const openStatus = () => { statusSubmissionRef.current = false; statusMutation.reset(); setStatusTarget(row) }
        const openReset = () => { resetSubmissionRef.current = false; resetMutation.reset(); setResetTarget(row) }
        const openDelete = () => { deleteSubmissionRef.current = false; deleteMutation.reset(); setDeleteTarget(row) }
        if (compactActions) {
          const items = [
            { key: 'edit', label: '编辑', onClick: openEditRow },
            ...(!isSelf ? [{ key: 'status', label: row.status === 'active' ? '停用' : '启用', onClick: openStatus }] : []),
            ...(!isSelf ? [{ key: 'reset', label: '重置密码', onClick: openReset }] : []),
            { key: 'delete', label: '删除', danger: true, onClick: openDelete },
          ]
          return (
            <Dropdown menu={{ items }} trigger={['click']}>
              <Button type="text" size="small" icon={<MoreOutlined />} aria-label={`更多${row.name}操作`}>更多</Button>
            </Dropdown>
          )
        }
        return (
          <Space size={4}>
            <Button type="link" size="small" icon={<EditOutlined />} aria-label={`编辑${row.name}`} onClick={openEditRow}>编辑</Button>
            {!isSelf ? <Button type="link" size="small" icon={row.status === 'active' ? <StopOutlined /> : <CheckCircleOutlined />} aria-label={`${row.status === 'active' ? '停用' : '启用'}${row.name}`} onClick={openStatus}>{row.status === 'active' ? '停用' : '启用'}</Button> : null}
            {!isSelf ? <Button type="link" size="small" icon={<LockOutlined />} aria-label={`重置${row.name}密码`} onClick={openReset}>重置密码</Button> : null}
            <Button danger type="link" size="small" icon={<DeleteOutlined />} aria-label={`删除${row.name}`} onClick={openDelete}>删除</Button>
          </Space>
        )
      },
    },
  ], [compactActions, currentLoginId, currentUserRole, deleteMutation, openEdit, resetMutation, statusMutation])

  const resetError = resetMutation.error instanceof ApiError ? resetMutation.error : null
  return (
    <section className="management-page" aria-labelledby="doctor-page-title">
      {messageContext}
      <div className="management-heading">
        <div><h1 id="doctor-page-title">医生管理</h1><p>维护医生账号与基础资料</p></div>
        <Button aria-label="新增医生" type="primary" icon={<PlusOutlined />} onClick={() => { detailSubmissionRef.current = false; setFormDoctor(null) }}>新增医生</Button>
      </div>
      <div className="management-surface">
        <div className="management-toolbar">
          <Space.Compact className="management-search">
            <Input
              aria-label="搜索医生"
              defaultValue={query.search ?? ''}
              key={`doctor-search-${query.search ?? ''}`}
              placeholder="搜索姓名或工号"
              ref={searchRef}
              allowClear
              onPressEnter={(event) => replaceQuery({ search: event.currentTarget.value.trim() || undefined }, true)}
            />
            <Button aria-label="搜索" type="primary" onClick={() => replaceQuery({ search: searchRef.current?.input?.value.trim() || undefined }, true)}>搜索</Button>
          </Space.Compact>
          <Input
            aria-label="科室筛选"
            defaultValue={query.department ?? ''}
            key={`doctor-department-${query.department ?? ''}`}
            placeholder="科室"
            ref={departmentRef}
            allowClear
            onPressEnter={(event) => replaceQuery({ department: event.currentTarget.value.trim() || undefined }, true)}
            onBlur={() => {
              const next = departmentRef.current?.input?.value.trim() || undefined
              if (next !== query.department) replaceQuery({ department: next }, true)
            }}
          />
          <Select
            id="doctor-status-filter"
            aria-label="医生状态"
            placeholder="全部状态"
            value={query.status}
            allowClear
            options={[{ value: 'active', label: '启用' }, { value: 'inactive', label: '停用' }]}
            onChange={(value) => replaceQuery({ status: value }, true)}
          />
          <Button onClick={() => setParams(uiParams({ page: 1, page_size: query.page_size }))}>重置</Button>
        </div>
        <DataTable<Doctor>
          ariaLabel="正在加载医生列表"
          columns={columns}
          data={listQuery.data}
          emptyText="暂无医生"
          error={listQuery.error}
          loading={listQuery.isPending}
          onPageChange={(page, pageSize) => replaceQuery({ page, page_size: pageSize }, pageSize !== query.page_size)}
          onRetry={() => void listQuery.refetch()}
          rowKey="id"
          scrollX={1120}
        />
      </div>
      <DoctorFormModal
        key={detailTarget?.id ?? formDoctor?.id ?? (formDoctor === null ? 'new' : 'closed')}
        detailError={detailMutation.error instanceof ApiError ? detailMutation.error : null}
        detailLoading={detailMutation.isPending}
        detailTarget={detailTarget}
        doctor={formDoctor}
        open={formDoctor !== undefined || detailTarget !== null}
        onCancel={() => {
          if (saveMutation.isPending || detailMutation.isPending) return
          detailSubmissionRef.current = false
          setDetailTarget(null)
          setFormDoctor(undefined)
        }}
        onRetryDetail={() => {
          if (!detailTarget || detailSubmissionRef.current) return
          detailSubmissionRef.current = true
          detailMutation.mutate(detailTarget.id)
        }}
        onSubmit={async (values) => { await saveMutation.mutateAsync({ values, doctor: formDoctor }) }}
      />
      <ConfirmDelete
        title={`删除医生“${deleteTarget?.name ?? ''}”`}
        content="删除后该医生账号将停用并隐藏，历史保留；若仍有在治患者，系统会拒绝删除。"
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
        title={`${statusTarget?.status === 'active' ? '停用' : '启用'}医生“${statusTarget?.name ?? ''}”`}
        open={statusTarget !== null}
        okText={`确认${statusTarget?.status === 'active' ? '停用' : '启用'}`}
        cancelText="取消"
        onCancel={() => { if (!statusMutation.isPending) setStatusTarget(null) }}
        onOk={() => {
          if (!statusTarget || statusSubmissionRef.current) return
          statusSubmissionRef.current = true
          statusMutation.mutate(statusTarget)
        }}
        okButtonProps={{
          danger: statusTarget?.status === 'active',
          loading: statusMutation.isPending,
          disabled: statusMutation.isPending,
          'aria-label': `确认${statusTarget?.status === 'active' ? '停用' : '启用'}`,
        }}
        cancelButtonProps={{ disabled: statusMutation.isPending, 'aria-label': '取消' }}
        mask={{ closable: !statusMutation.isPending }}
      >
        <p>{statusTarget?.status === 'active' ? '停用后该医生现有登录会话将失效；若仍有在治患者，系统会拒绝停用。' : '启用后该医生可重新登录，旧会话不会恢复。'}</p>
        {statusMutation.error instanceof ApiError ? (
          <p className="inline-error">
            {statusMutation.error.message}
            {statusMutation.error.requestId ? `（请求编号：${statusMutation.error.requestId}）` : ''}
          </p>
        ) : null}
      </Modal>
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
        <p>密码将恢复为 888888，该医生首次登录必须修改密码。</p>
        {resetError ? <p className="inline-error">{resetError.message}{resetError.requestId ? `（请求编号：${resetError.requestId}）` : ''}</p> : null}
      </Modal>
    </section>
  )
}
