import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Descriptions, Empty, Input, Table } from 'antd'
import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { useRemoteDoctorOptions } from '../doctors/useRemoteDoctorOptions'
import { PatientFormModal } from '../patients/PatientFormModal'
import { getPatient, updatePatient } from '../patients/api'
import type { PatientDetail, PatientWrite } from '../patients/types'
import { getPatientMetrics, getSingingHistory } from './api'

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
  const metrics = useQuery({
    queryKey: ['analytics', 'patient', patient.data?.medical_record_no ?? ''],
    queryFn: ({ signal }) => getPatientMetrics(patient.data?.medical_record_no ?? '', signal),
    enabled: Boolean(patient.data?.medical_record_no),
    retry: false,
  })
  const metric = metrics.data?.results[0]
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
        <div className="singing-summary" aria-label="权威演唱汇总">
          <strong>权威演唱汇总</strong>
          {metrics.isPending ? <span role="status">正在加载演唱汇总</span> : null}
          {metrics.isError ? <Alert className="patient-data-error" type="error" showIcon title={errorMessage(metrics.error, '无法加载演唱汇总')} description={errorDescription(metrics.error)} action={<Button aria-label="重试加载汇总" onClick={() => void metrics.refetch()}>重试</Button>} /> : null}
          {!metrics.isPending && !metrics.isError ? <>
            <span>统计口径：{metrics.data?.metric_version ?? '—'}</span>
            <span>已完成演唱：{metric?.completed_count ?? 0} 次</span>
            <span>累计时长：{metric ? `${Math.round(metric.total_duration_seconds / 60)} 分钟` : '—'}</span>
            <span>平均得分：{metric?.average_score ?? '—'}</span>
            <span>治疗进度：{metric?.treatment_progress ? `${Number(metric.treatment_progress).toFixed(2)}%` : '—'}</span>
            <span>得分趋势：{metric?.score_trend.has_enough_data ? `${metric.score_trend.direction === 'up' ? '上升' : metric.score_trend.direction === 'down' ? '下降' : '持平'} ${metric.score_trend.difference ?? ''}` : '数据不足'}</span>
            <span>嗳气改善率：{metric?.burp_improvement ? `${(Number(metric.burp_improvement) * 100).toFixed(2)}%` : '—'}</span>
            {metric?.is_mock ? <span>模拟统计</span> : null}
          </> : null}
        </div>
        <div className="singing-summary" aria-label="历史筛选摘要">
          <span>筛选结果：{history.data?.count ?? 0} 条</span>
          <span>本页记录：{pageCount} 条</span>
        </div>
        <div className="patient-date-filter">
          <Input type="date" aria-label="演唱日期筛选" value={dates[0] ?? ''} onChange={(event) => { setPage(1); setDates([event.target.value || undefined, dates[1]]) }} />
          <span aria-hidden="true">至</span>
          <Input type="date" aria-label="演唱日期筛选" value={dates[1] ?? ''} onChange={(event) => { setPage(1); setDates([dates[0], event.target.value || undefined]) }} />
        </div>
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
