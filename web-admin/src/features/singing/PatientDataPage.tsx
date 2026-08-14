import { useQuery } from '@tanstack/react-query'
import { Alert, Button, DatePicker, Descriptions, Empty, Table } from 'antd'
import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { useRemoteDoctorOptions } from '../doctors/useRemoteDoctorOptions'
import { PatientFormModal } from '../patients/PatientFormModal'
import { getPatient, updatePatient } from '../patients/api'
import type { PatientDetail, PatientWrite } from '../patients/types'
import { getSingingHistory } from './api'

function errorDescription(error: unknown) {
  const apiError = error instanceof ApiError ? error : null
  return apiError?.requestId ? `请求编号：${apiError.requestId}` : undefined
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

function PatientEditor({ patientId, patient, onClose, onSaved }: { patientId: string; patient: PatientDetail; onClose: () => void; onSaved: () => Promise<void> }) {
  const doctorSource = useRemoteDoctorOptions()
  const savePatient = async (values: PatientWrite) => {
    await updatePatient(patientId, values)
    await onSaved()
    onClose()
  }
  return <PatientFormModal open patient={patient} doctorSource={doctorSource} onCancel={onClose} onSubmit={savePatient} />
}

export function PatientDataPage() {
  const { patientId = '' } = useParams()
  const navigate = useNavigate()
  const [page, setPage] = useState(1)
  const [dates, setDates] = useState<[string?, string?]>([])
  const [editing, setEditing] = useState(false)
  const patient = useQuery({
    queryKey: ['patients', 'detail', patientId],
    queryFn: ({ signal }) => getPatient(patientId, signal),
    enabled: Boolean(patientId),
    retry: false,
  })
  const history = useQuery({
    queryKey: ['singing', 'history', patientId, page, dates[0] ?? '', dates[1] ?? ''],
    queryFn: ({ signal }) => getSingingHistory(patientId, { page, page_size: 10, created_from: dates[0], created_to: dates[1] }, signal),
    enabled: Boolean(patientId),
    retry: false,
  })
  const pageCount = useMemo(() => history.data?.results.length ?? 0, [history.data])

  return (
    <section className="management-page patient-data-page" aria-labelledby="patient-data-title">
      <div className="management-heading">
        <div><h1 id="patient-data-title">患者数据</h1><p>授权详情、治疗计划与演唱历史</p></div>
        <Button disabled={!patient.data} onClick={() => setEditing(true)}>编辑资料</Button>
      </div>
      <div className="management-surface">
        {patient.isPending ? <span role="status">正在加载授权详情</span> : null}
        {patient.isError ? <Alert className="patient-data-error" type="error" showIcon title={errorMessage(patient.error, '无法加载授权详情')} description={errorDescription(patient.error)} action={<Button aria-label="重试加载资料" onClick={() => void patient.refetch()}>重试</Button>} /> : null}
        <Descriptions title="授权详情" bordered size="small" items={[
          { key: 'name', label: '姓名', children: patient.data?.name ?? '—' },
          { key: 'record', label: '病历号', children: patient.data?.medical_record_no ?? '—' },
          { key: 'phone', label: '手机号', children: patient.data?.phone ?? '—' },
          { key: 'doctor', label: '主治医生', children: patient.data?.primary_doctor_name ?? '—' },
        ]} />
        <Descriptions title="当前治疗计划" size="small" items={[
          { key: 'status', label: '状态', children: patient.data?.treatment_plan?.status ?? '无活动计划' },
          { key: 'target', label: '目标演唱次数', children: patient.data?.treatment_plan?.target_session_count ?? '—' },
        ]} />
        <div className="singing-summary" aria-label="演唱汇总">
          <span>筛选结果：{history.data?.count ?? 0} 条</span>
          <span>本页记录：{pageCount} 条</span>
          <span>演唱汇总以数据管理页返回的服务端统计口径为准。</span>
        </div>
        <DatePicker.RangePicker aria-label="演唱日期筛选" onChange={(value) => {
          setPage(1)
          setDates([value?.[0]?.format('YYYY-MM-DD'), value?.[1]?.format('YYYY-MM-DD')])
        }} />
        {history.isError ? <Alert className="patient-data-error" type="error" showIcon title={errorMessage(history.error, '无法加载演唱历史')} description={errorDescription(history.error)} action={<Button aria-label="重试加载历史" onClick={() => void history.refetch()}>重试</Button>} /> : null}
        {!history.isError && !history.isPending && history.data?.results.length === 0 ? <Empty description="暂无演唱历史" /> : null}
        <div className="patient-history-table">
          <Table
            rowKey="id"
            loading={history.isPending}
            dataSource={history.data?.results}
            pagination={{ current: page, pageSize: 10, total: history.data?.count, onChange: setPage, showSizeChanger: false }}
            columns={[
              { title: '歌曲', render: (_value, row) => row.song.title ?? '—' },
              { title: '完成时间', dataIndex: 'completed_at' },
              { title: '得分', dataIndex: 'score' },
              { title: '嗳气次数', dataIndex: 'burp_count' },
              { title: '操作', render: (_value, row) => <Button type="link" onClick={() => navigate(`/singing/${row.id}`)}>查看明细</Button> },
            ]}
          />
        </div>
      </div>
      {editing && patient.data ? <PatientEditor patientId={patientId} patient={patient.data} onClose={() => setEditing(false)} onSaved={async () => { await patient.refetch() }} /> : null}
    </section>
  )
}
