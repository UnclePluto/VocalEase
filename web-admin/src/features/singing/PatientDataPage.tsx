import { useQuery } from '@tanstack/react-query'
import { EditOutlined, UserOutlined } from '@ant-design/icons'
import { Alert, Button, Empty, Input, Table, Tag } from 'antd'
import { useMemo, useState } from 'react'
import { useNavigate, useParams, useLocation } from 'react-router-dom'

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
  const location = useLocation()
  const backTo = typeof location.state?.backTo === 'string' && /^\/(patient-data|patients)(\?|$)/.test(location.state.backTo) ? location.state.backTo : '/patients' 
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
  const treatmentStatus = patient.data?.treatment_plan?.status
  const statusLabel = treatmentStatus === 'active' ? '治疗中' : treatmentStatus === 'pending' ? '待开始' : treatmentStatus === 'completed' ? '已完成' : treatmentStatus === 'cancelled' ? '已取消' : '无计划'
  const totalDuration = metric ? `${Math.round(metric.total_duration_seconds / 60)} 分钟` : '—'

  return (
    <section className="management-page patient-data-page" aria-labelledby="patient-data-title">
      <div className="management-heading">
        <Button onClick={() => navigate(backTo)}>返回患者列表</Button>
        <div><h1 id="patient-data-title">患者数据</h1><p>患者资料与历史演唱记录</p></div>
      </div>
      <div className="patient-profile-card">
        {patient.isPending ? <span role="status">正在加载授权详情</span> : null}
        {patient.isError ? <Alert className="patient-data-error" type="error" showIcon title={errorMessage(patient.error, '无法加载授权详情')} description={errorDescription(patient.error)} action={<Button aria-label="重试加载资料" onClick={() => void patient.refetch()}>重试</Button>} /> : null}
        {patient.data ? <>
          <div className="patient-profile-header">
            <div className="patient-profile-avatar"><UserOutlined /></div>
            <div className="patient-profile-name"><strong>{patient.data.name}</strong><span><span>{patient.data.medical_record_no}</span> · <span>{patient.data.phone}</span> · <span>{patient.data.primary_doctor_name}</span></span></div>
            <Tag color={treatmentStatus === 'active' ? 'processing' : 'default'}>{statusLabel}</Tag>
            <Button icon={<EditOutlined />} disabled={!patient.data} onClick={() => setEditing(true)}>编辑信息</Button>
          </div>
          <div className="patient-profile-metrics">
            <div><span>年龄</span><strong>{patient.data.enrollment_age} 岁</strong></div>
            <div><span>性别</span><strong>{patient.data.gender === 'female' ? '女' : '男'}</strong></div>
            <div><span>治疗开始时间</span><strong>{patient.data.treatment_plan?.start_date ?? '—'}</strong></div>
            <div><span>治疗周期</span><strong>{patient.data.treatment_plan ? `${patient.data.treatment_plan.cycle_weeks} 周` : '—'}</strong></div>
            <div><span>治疗次数</span><strong>{metric ? `${metric.completed_count} 次` : '—'}</strong></div>
            <div><span>总演唱时长</span><strong>{totalDuration}</strong></div>
          </div>
        </> : null}
        {metrics.isPending ? <span role="status">正在加载演唱汇总</span> : null}
        {metrics.isError ? <Alert className="patient-data-error" type="error" showIcon title={errorMessage(metrics.error, '无法加载演唱汇总')} description={errorDescription(metrics.error)} action={<Button aria-label="重试加载汇总" onClick={() => void metrics.refetch()}>重试</Button>} /> : null}
        {!metrics.isPending && !metrics.isError ? <div className="visually-hidden" aria-label="权威演唱汇总">
            <span>统计口径：{metrics.data?.metric_version ?? '—'}</span>
            <span>已完成演唱：{metric?.completed_count ?? 0} 次</span>
            <span>累计时长：{totalDuration}</span>
            <span>平均得分：{metric?.average_score ?? '—'}</span>
            <span>治疗进度：{metric?.treatment_progress ? `${Number(metric.treatment_progress).toFixed(2)}%` : '—'}</span>
            <span>得分趋势：{metric?.score_trend.has_enough_data ? `${metric.score_trend.direction === 'up' ? '上升' : metric.score_trend.direction === 'down' ? '下降' : '持平'} ${metric.score_trend.difference ?? ''}` : '数据不足'}</span>
            <span>嗳气改善率：{metric?.burp_improvement ? `${(Number(metric.burp_improvement) * 100).toFixed(2)}%` : '—'}</span>
            {metric?.is_mock ? <span>模拟统计</span> : null}
        </div> : null}
      </div>
      <div className="patient-history-card">
        <div className="patient-history-header">
          <h2>历史唱歌记录</h2>
          <div className="patient-date-filter">
            <Input type="date" aria-label="演唱日期筛选" value={dates[0] ?? ''} onChange={(event) => { setPage(1); setDates([event.target.value || undefined, dates[1]]) }} />
            <span aria-hidden="true">至</span>
            <Input type="date" aria-label="演唱日期筛选" value={dates[1] ?? ''} onChange={(event) => { setPage(1); setDates([dates[0], event.target.value || undefined]) }} />
          </div>
        </div>
        <div className="visually-hidden" aria-label="历史筛选摘要"><span>筛选结果：{history.data?.count ?? 0} 条</span><span>本页记录：{pageCount} 条</span></div>
        {history.isError ? <Alert className="patient-data-error" type="error" showIcon title={errorMessage(history.error, '无法加载演唱历史')} description={errorDescription(history.error)} action={<Button aria-label="重试加载历史" onClick={() => void history.refetch()}>重试</Button>} /> : null}
        {!history.isError && !history.isPending && history.data?.results.length === 0 ? <Empty description="暂无演唱历史" /> : null}
        <div className="patient-history-table">
          <Table
            rowKey="id"
            loading={history.isPending}
            dataSource={history.data?.results}
            pagination={{ current: page, pageSize: 10, total: history.data?.count, onChange: setPage, showSizeChanger: false }}
            columns={[
              { title: '序号', width: 90, render: (_value, _row, index) => (page - 1) * 10 + index + 1 },
              { title: '演唱日期', dataIndex: 'completed_at', width: 180, render: (value) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' },
              { title: '歌曲名称', width: 220, render: (_value, row) => row.song.title ?? '—' },
              { title: '得分', dataIndex: 'score', width: 150 },
              { title: '演唱时长', dataIndex: 'duration_seconds', width: 120, render: (value) => typeof value === 'number' ? `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}` : '—' },
              { title: '嗳气次数', dataIndex: 'burp_count', width: 110 },
              { title: '操作', width: 180, render: (_value, row) => <Button type="link" onClick={() => navigate(`/singing/${row.id}`, { state: { backTo: location.pathname + location.search, patientDataBackTo: backTo } })}>查看明细</Button> },
            ]}
          />
        </div>
      </div>
      {editing && patient.data ? <PatientEditor patientId={patientId} patient={patient.data} onClose={() => setEditing(false)} onSaved={async () => { await patient.refetch() }} /> : null}
    </section>
  )
}
