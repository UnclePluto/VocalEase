import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Dropdown, Input, Select, Space, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams, type SetURLSearchParams } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { analyticsKeys, createExport, getDashboard, getPatientMetrics, safeDownloadFilename, triggerBlobDownload } from './api'
import { ExportStatus } from './ExportStatus'
import type { AnalyticsFilters, AnalyticsQuery, ExportFormat, PatientMetric } from './types'

const DEFAULT_FILTERS: AnalyticsFilters = { name: '', medical_record_no: '', treatment_status: '', primary_doctor: '', created_from: '', created_to: '' }
const DEFAULT_PAGE_SIZE = 20

function parseQuery(params: URLSearchParams): AnalyticsQuery {
  const page = Number(params.get('page'))
  const pageSize = Number(params.get('page_size'))
  return {
    ...DEFAULT_FILTERS,
    name: params.get('name')?.trim() ?? '', medical_record_no: params.get('medical_record_no')?.trim() ?? '',
    treatment_status: (params.get('treatment_status') ?? '') as AnalyticsFilters['treatment_status'],
    primary_doctor: params.get('primary_doctor')?.trim() ?? '', created_from: params.get('created_from') ?? '', created_to: params.get('created_to') ?? '',
    page: Number.isInteger(page) && page > 0 ? page : 1,
    page_size: [10, 20, 50, 100].includes(pageSize) ? pageSize : DEFAULT_PAGE_SIZE,
  }
}

function urlState(query: AnalyticsQuery, exportJob?: string) {
  const next = new URLSearchParams()
  next.set('page', String(query.page)); next.set('page_size', String(query.page_size))
  ;(['name', 'medical_record_no', 'treatment_status', 'primary_doctor', 'created_from', 'created_to'] as const).forEach((key) => { if (query[key]) next.set(key, query[key]) })
  if (exportJob) next.set('export_job', exportJob)
  return next
}

function errorDescription(error: unknown) { return error instanceof ApiError && error.requestId ? `请求编号：${error.requestId}` : undefined }
function errorMessage(error: unknown, fallback: string) { return error instanceof Error ? error.message : fallback }
function formatDuration(seconds: number) { return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒` }
function trend(row: PatientMetric) {
  if (!row.score_trend.has_enough_data) return '数据不足'
  const label = row.score_trend.direction === 'up' ? '上升' : row.score_trend.direction === 'down' ? '下降' : '持平'
  return `${label}${row.score_trend.difference ? ` ${row.score_trend.difference}` : ''}`
}
function improvement(value: string | null) { return value === null ? '暂无趋势' : `${(Number(value) * 100).toFixed(2)}%` }
function idempotencyKey() { return globalThis.crypto?.randomUUID?.() ?? `export-${Date.now()}-${Math.random().toString(36).slice(2)}` }
function treatmentLabel(value: PatientMetric['treatment_status']) {
  if (!value) return '—'
  return ({ active: '进行中', pending: '待开始', completed: '已完成', cancelled: '已取消', none: '无计划' })[value]
}

function DashboardCards() {
  const dashboard = useQuery({ queryKey: analyticsKeys.dashboard(), queryFn: ({ signal }) => getDashboard(signal), retry: false })
  if (dashboard.isError) return <Alert className="analytics-dashboard-error" type="error" showIcon title={errorMessage(dashboard.error, '无法加载统计汇总')} description={errorDescription(dashboard.error)} action={<Button aria-label="重试统计汇总" onClick={() => void dashboard.refetch()}>重试</Button>} />
  const data = dashboard.data
  const items = [
    ['在治患者', data?.active_patient_count, false], ['累计完成演唱', data?.completed_session_count, false],
    ['平均得分', data?.average_score, true], ['平均嗳气次数', data?.average_burp_count, true],
  ] as const
  return <>
    <div className="analytics-cards" aria-label="统计汇总">
      {items.map(([label, value, nullable]) => <Card key={label} size="small" className="analytics-card" loading={dashboard.isPending}>
        <span className="analytics-card-label">{label}</span><strong>{nullable && value === null ? '暂无数据' : (value ?? 0)}</strong>
      </Card>)}
    </div>
    {!dashboard.isPending && data ? <div className="analytics-meta">统计口径：{data.metric_version}{data.is_mock ? <Tag color="gold">模拟统计</Tag> : null}</div> : null}
  </>
}

export function AnalyticsPage() {
  const [params, setParams] = useSearchParams()
  return <AnalyticsPageContent key={params.toString()} params={params} setParams={setParams} />
}

function AnalyticsPageContent({ params, setParams }: { params: URLSearchParams; setParams: SetURLSearchParams }) {
  const query = useMemo(() => parseQuery(params), [params])
  const exportJob = params.get('export_job') ?? ''
  const [draft, setDraft] = useState<AnalyticsFilters>(() => ({ ...query }))
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [exportError, setExportError] = useState<unknown>(null)
  const [exporting, setExporting] = useState(false)
  const requestKeyRef = useRef<{ signature: string; key: string } | null>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const metrics = useQuery({ queryKey: analyticsKeys.patients(query), queryFn: ({ signal }) => getPatientMetrics(query, signal), retry: false })

  useEffect(() => () => controllerRef.current?.abort(), [])

  const setQuery = useCallback((changes: Partial<AnalyticsQuery>, resetPage = false) => {
    const next = { ...query, ...changes, page: resetPage ? 1 : (changes.page ?? query.page) }
    setSelectedIds([])
    setParams(urlState(next, exportJob), { replace: true })
  }, [exportJob, query, setParams])
  const applyFilters = () => setQuery({ ...draft }, true)
  const resetFilters = () => { setDraft(DEFAULT_FILTERS); setSelectedIds([]); setParams(urlState({ ...DEFAULT_FILTERS, page: 1, page_size: query.page_size }, exportJob), { replace: true }) }
  const clearJob = () => setParams(urlState(query), { replace: true })

  const startExport = useCallback(async (format: ExportFormat) => {
    if (exporting || controllerRef.current) return
    const filters: AnalyticsFilters = { name: query.name, medical_record_no: query.medical_record_no, treatment_status: query.treatment_status, primary_doctor: query.primary_doctor, created_from: query.created_from, created_to: query.created_to }
    const signature = JSON.stringify({ format, filters, selectedIds: [...selectedIds].sort() })
    if (!requestKeyRef.current || requestKeyRef.current.signature !== signature) requestKeyRef.current = { signature, key: idempotencyKey() }
    const controller = new AbortController(); controllerRef.current = controller; setExporting(true); setExportError(null)
    try {
      const result = await createExport({ format, filters, selected_ids: selectedIds, idempotency_key: requestKeyRef.current.key }, controller.signal)
      if (controller.signal.aborted) return
      if (result.mode === 'sync') {
        const blob = await result.response.blob()
        triggerBlobDownload(blob, safeDownloadFilename(result.response.headers.get('content-disposition'), `vocaease-patient-metrics.${format}`))
        requestKeyRef.current = null
      } else setParams(urlState(query, result.job.id), { replace: true })
    } catch (error) {
      if (!controller.signal.aborted) {
        setExportError(error)
        if (error instanceof ApiError && error.status !== undefined && error.status >= 400 && error.status < 500) requestKeyRef.current = null
      }
    } finally { if (controllerRef.current === controller) controllerRef.current = null; setExporting(false) }
  }, [exporting, query, selectedIds, setParams])

  const columns = useMemo<TableColumnsType<PatientMetric>>(() => [
    { title: '病历号', dataIndex: 'medical_record_no', width: 120 }, { title: '姓名', dataIndex: 'name', width: 100 },
    { title: '治疗状态', dataIndex: 'treatment_status', width: 100, render: (value) => treatmentLabel(value as PatientMetric['treatment_status']) },
    { title: '治疗进度', dataIndex: 'treatment_progress', width: 120, render: (value) => value === null ? '暂无数据' : `${value}%` },
    { title: '完成次数', dataIndex: 'completed_count', width: 100, render: (value) => `${value} 次` }, { title: '累计时长', dataIndex: 'total_duration_seconds', width: 130, render: formatDuration },
    { title: '平均得分', dataIndex: 'average_score', width: 100, render: (value) => value ?? '暂无数据' }, { title: '得分趋势', width: 120, render: (_value, row) => trend(row) },
    { title: '嗳气改善率', dataIndex: 'burp_improvement', width: 120, render: improvement },
  ], [])
  const selection = { selectedRowKeys: selectedIds, onChange: (keys: React.Key[]) => setSelectedIds(keys.map(String)), getCheckboxProps: (row: PatientMetric) => ({ 'aria-label': `选择 ${row.medical_record_no}` }) }

  return <section className="management-page analytics-page" aria-labelledby="analytics-title">
    <div className="management-heading"><div><h1 id="analytics-title">数据管理</h1><p>查看治疗指标并按当前筛选安全导出数据</p></div></div>
    <DashboardCards />
    {exportJob ? <ExportStatus jobId={exportJob} onClear={clearJob} /> : null}
    <div className="management-surface">
      <div className="analytics-toolbar">
        <Input aria-label="患者姓名" value={draft.name} placeholder="患者姓名" onChange={(event) => setDraft((value) => ({ ...value, name: event.target.value }))} onPressEnter={applyFilters} />
        <Input aria-label="病历号" value={draft.medical_record_no} placeholder="病历号" onChange={(event) => setDraft((value) => ({ ...value, medical_record_no: event.target.value }))} onPressEnter={applyFilters} />
        <Select aria-label="治疗状态" value={draft.treatment_status || undefined} placeholder="全部治疗状态" allowClear options={[{ value: 'active', label: '进行中' }, { value: 'pending', label: '待开始' }, { value: 'completed', label: '已完成' }, { value: 'cancelled', label: '已取消' }, { value: 'none', label: '无计划' }]} onChange={(value) => setDraft((current) => ({ ...current, treatment_status: value ?? '' }))} />
        <Input aria-label="主治医生 ID" value={draft.primary_doctor} placeholder="主治医生 ID" onChange={(event) => setDraft((value) => ({ ...value, primary_doctor: event.target.value }))} />
        <Input type="date" aria-label="创建开始日期" value={draft.created_from} onChange={(event) => setDraft((value) => ({ ...value, created_from: event.target.value }))} />
        <Input type="date" aria-label="创建结束日期" value={draft.created_to} onChange={(event) => setDraft((value) => ({ ...value, created_to: event.target.value }))} />
        <Space><Button type="primary" aria-label="查询" onClick={applyFilters}>查询</Button><Button onClick={resetFilters}>重置</Button></Space>
      </div>
      <div className="analytics-export-bar">
        <span>{selectedIds.length ? `已选择 ${selectedIds.length} 名当前页患者，将只导出与筛选条件的交集。` : '未选择患者：将导出当前筛选条件下的全部患者。'}</span>
        <Dropdown disabled={exporting} menu={{ items: [{ key: 'csv', label: 'CSV', onClick: () => void startExport('csv') }, { key: 'xlsx', label: 'Excel', onClick: () => void startExport('xlsx') }] }} trigger={['click']}>
          <Button type="primary" loading={exporting} aria-label={`导出报告 (${selectedIds.length})`}>导出报告 ({selectedIds.length})</Button>
        </Dropdown>
      </div>
      {exportError ? <Alert className="analytics-export-error" type="error" showIcon title={errorMessage(exportError, '创建导出失败')} description={errorDescription(exportError)} action={<Button aria-label="关闭导出错误" onClick={() => setExportError(null)}>关闭</Button>} /> : null}
      {metrics.isError ? <Alert type="error" showIcon title={errorMessage(metrics.error, '无法加载患者指标')} description={errorDescription(metrics.error)} action={<Button aria-label="重试患者指标" onClick={() => void metrics.refetch()}>重试</Button>} /> : null}
      <div className="data-table-scroll" tabIndex={0} aria-label="患者指标表格，可横向滚动"><Table<PatientMetric> rowKey="id" columns={columns} dataSource={metrics.data?.results} rowSelection={selection} loading={metrics.isPending} pagination={metrics.data ? { current: metrics.data.page, pageSize: metrics.data.page_size, total: metrics.data.count, showSizeChanger: true, pageSizeOptions: [10, 20, 50, 100], onChange: (page, pageSize) => setQuery({ page, page_size: pageSize }, pageSize !== query.page_size) } : false} scroll={{ x: 1110 }} locale={{ emptyText: '暂无患者指标' }} /></div>
      {metrics.data ? <div className="analytics-list-meta">患者列表口径：{metrics.data.metric_version}</div> : null}
    </div>
  </section>
}
