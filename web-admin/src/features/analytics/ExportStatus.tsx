import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Spin } from 'antd'
import { useEffect, useRef, useState } from 'react'

import { ApiError } from '../../api/errors'
import { useAuthStore } from '../../auth/store'
import { analyticsKeys, downloadExportFile, getExportJob, getExportPrivateUrl, triggerBlobDownload } from './api'
import type { ExportJob } from './types'

const POLL_INTERVAL_MS = 1_500

class PrivateUrlRefreshFailure extends Error {
  constructor(readonly original: unknown) { super('private_url_refresh_failed') }
}

function errorText(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}
function errorDescription(error: unknown) {
  return error instanceof ApiError && error.requestId ? `请求编号：${error.requestId}` : undefined
}

export function ExportStatus({ jobId, onClear }: { jobId: string; onClear: () => void }) {
  const epoch = useAuthStore((state) => state.sessionEpoch)
  const [downloadError, setDownloadError] = useState<unknown>(null)
  const [downloading, setDownloading] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const job = useQuery({
    queryKey: analyticsKeys.export(jobId, epoch), queryFn: ({ signal }) => getExportJob(jobId, signal), retry: false,
    refetchInterval: (query) => query.state.data?.status === 'pending' || query.state.data?.status === 'processing' ? POLL_INTERVAL_MS : false,
    gcTime: 0,
  })
  const privateUrl = useQuery({
    queryKey: analyticsKeys.privateUrl(jobId, epoch), queryFn: ({ signal }) => getExportPrivateUrl(jobId, signal),
    enabled: job.data?.status === 'ready', retry: false, staleTime: 0, gcTime: 0,
  })
  useEffect(() => () => abortRef.current?.abort(), [])

  async function refreshJobAfterPrivateFailure(error: unknown) {
    await job.refetch()
    throw new PrivateUrlRefreshFailure(error)
  }

  async function freshPrivateUrl() {
    const refreshed = await privateUrl.refetch()
    if (refreshed.error) await refreshJobAfterPrivateFailure(refreshed.error)
    if (!refreshed.data) throw new ApiError('private_url_missing', '无法获取下载地址')
    return refreshed.data
  }

  async function download(refreshBeforeDownload = false) {
    if ((!privateUrl.data && !refreshBeforeDownload) || abortRef.current) return
    const controller = new AbortController(); abortRef.current = controller; setDownloadError(null); setDownloading(true)
    try {
      let address = refreshBeforeDownload ? await freshPrivateUrl() : privateUrl.data
      if (!address) return
      try {
        const file = await downloadExportFile(address.url, job.data?.format ?? 'csv', controller.signal)
        triggerBlobDownload(file.blob, file.filename)
      } catch (error) {
        const needsResign = error instanceof ApiError && (error.status === 401 || error.status === 403 || error.status === 410)
        if (!needsResign) throw error
        address = await freshPrivateUrl()
        const file = await downloadExportFile(address.url, job.data?.format ?? 'csv', controller.signal)
        triggerBlobDownload(file.blob, file.filename)
      }
    } catch (error) {
      if (!controller.signal.aborted && !(error instanceof PrivateUrlRefreshFailure)) setDownloadError(error)
    } finally { if (abortRef.current === controller) abortRef.current = null; setDownloading(false) }
  }

  const value: ExportJob | undefined = job.data
  if (job.isPending) return <div className="export-status" role="status"><Spin size="small" />正在恢复导出任务</div>
  if (job.isError) return <Alert className="export-status" type="error" showIcon title={errorText(job.error, '无法加载导出任务')} description={errorDescription(job.error)} action={<Button aria-label="重试导出状态" onClick={() => void job.refetch()}>重试</Button>} />
  if (!value) return null
  if (value.status === 'pending' || value.status === 'processing') return <Alert className="export-status" type="info" showIcon title="导出任务处理中" description={`已固定 ${value.count} 条数据，完成后可下载。`} action={<Button aria-label="取消导出状态跟踪" onClick={onClear}>关闭</Button>} />
  if (value.status === 'failed' || value.status === 'expired') return <Alert className="export-status" type="error" showIcon title={value.status === 'expired' ? '导出文件已过期' : '导出任务失败'} description={value.failure_reason || '该任务不可下载，请重新创建导出。'} action={<Button aria-label="关闭导出状态" onClick={onClear}>关闭</Button>} />
  return <div className="export-status">
    <Alert type="success" showIcon title="导出文件已准备好" description={`共 ${value.count} 条数据；下载地址不会被保存。`} />
    {privateUrl.isPending ? <span role="status">正在获取下载地址</span> : null}
    {privateUrl.isError ? <Alert type="error" showIcon title={errorText(privateUrl.error, '无法获取下载地址')} description={errorDescription(privateUrl.error)} action={<Button aria-label="重试获取下载地址" onClick={() => void privateUrl.refetch()}>重试</Button>} /> : null}
    {downloadError ? <Alert type="error" showIcon title={errorText(downloadError, '文件下载失败')} description={errorDescription(downloadError)} action={<Button aria-label="重试下载文件" onClick={() => void download(true)}>重试</Button>} /> : null}
    {privateUrl.data ? <Button type="primary" aria-label="下载文件" onClick={() => void download()} loading={downloading}>下载文件</Button> : null}
  </div>
}
