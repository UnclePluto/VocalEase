import { useEffect, useMemo, useRef, useState } from 'react'
import { DeleteOutlined, EditOutlined, LockOutlined, PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, Input, Modal, Select, Space, message } from 'antd'
import type { TableColumnsType } from 'antd'
import type { InputRef } from 'antd'
import { useSearchParams } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { ConfirmDelete } from '../../components/ConfirmDelete'
import { DataTable } from '../../components/DataTable'
import { StatusTag } from '../../components/StatusTag'
import {
  createDoctor,
  deleteDoctor,
  doctorKeys,
  listDoctors,
  resetDoctorPassword,
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
  const [deleteTarget, setDeleteTarget] = useState<Doctor | null>(null)
  const [resetTarget, setResetTarget] = useState<Doctor | null>(null)
  const queryClient = useQueryClient()
  const [messageApi, messageContext] = message.useMessage()

  useEffect(() => {
    const canonical = uiParams(query)
    if (params.toString() !== canonical.toString()) setParams(canonical, { replace: true })
  }, [params, query, setParams])

  const listQuery = useQuery({
    queryKey: doctorKeys.list(query),
    queryFn: ({ signal }) => listDoctors(query, signal),
  })

  const saveMutation = useMutation({
    mutationFn: ({ values, doctor }: { values: DoctorWrite; doctor?: Doctor | null }) => (
      doctor ? updateDoctor(doctor.id, values) : createDoctor(values)
    ),
    onSuccess: async (_saved, variables) => {
      await queryClient.invalidateQueries({ queryKey: doctorKeys.lists() })
      setFormDoctor(undefined)
      if (variables.doctor) messageApi.success('医生信息已更新')
      else messageApi.success('医生创建成功，初始密码为 888888，首次登录需修改')
    },
  })
  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteDoctor(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: doctorKeys.lists() })
      setDeleteTarget(null)
      messageApi.success('医生已停用并隐藏，历史记录已保留')
    },
  })
  const resetMutation = useMutation({
    mutationFn: (userId: string) => resetDoctorPassword(userId),
    onSuccess: () => {
      setResetTarget(null)
      messageApi.success('密码已重置为 888888，首次登录必须修改')
    },
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
      title: '操作', key: 'actions', width: 270, fixed: 'right',
      render: (_value, row) => (
        <Space size={4}>
          <Button type="link" size="small" icon={<EditOutlined />} aria-label={`编辑${row.name}`} onClick={() => setFormDoctor(row)}>编辑</Button>
          <Button type="link" size="small" icon={<LockOutlined />} aria-label={`重置${row.name}密码`} onClick={() => { resetMutation.reset(); setResetTarget(row) }}>重置密码</Button>
          <Button danger type="link" size="small" icon={<DeleteOutlined />} aria-label={`删除${row.name}`} onClick={() => { deleteMutation.reset(); setDeleteTarget(row) }}>删除</Button>
        </Space>
      ),
    },
  ], [deleteMutation, resetMutation])

  const resetError = resetMutation.error instanceof ApiError ? resetMutation.error : null
  return (
    <section className="management-page" aria-labelledby="doctor-page-title">
      {messageContext}
      <div className="management-heading">
        <div><h1 id="doctor-page-title">医生管理</h1><p>维护医生账号与基础资料</p></div>
        <Button aria-label="新增医生" type="primary" icon={<PlusOutlined />} onClick={() => setFormDoctor(null)}>新增医生</Button>
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
        key={formDoctor?.id ?? (formDoctor === null ? 'new' : 'closed')}
        doctor={formDoctor}
        open={formDoctor !== undefined}
        onCancel={() => { if (!saveMutation.isPending) setFormDoctor(undefined) }}
        onSubmit={async (values) => { await saveMutation.mutateAsync({ values, doctor: formDoctor }) }}
      />
      <ConfirmDelete
        title={`删除医生“${deleteTarget?.name ?? ''}”`}
        content="删除后该医生账号将停用并隐藏，历史保留；若仍有在治患者，系统会拒绝删除。"
        open={deleteTarget !== null}
        loading={deleteMutation.isPending}
        error={deleteMutation.error}
        onCancel={() => { if (!deleteMutation.isPending) setDeleteTarget(null) }}
        onConfirm={() => { if (deleteTarget && !deleteMutation.isPending) deleteMutation.mutate(deleteTarget.id) }}
      />
      <Modal
        title={`重置“${resetTarget?.name ?? ''}”的密码`}
        open={resetTarget !== null}
        okText="确认重置"
        cancelText="取消"
        onCancel={() => { if (!resetMutation.isPending) setResetTarget(null) }}
        onOk={() => { if (resetTarget && !resetMutation.isPending) resetMutation.mutate(resetTarget.user_id) }}
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
