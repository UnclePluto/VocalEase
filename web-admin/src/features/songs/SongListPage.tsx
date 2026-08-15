import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { DeleteOutlined, EditOutlined, MoreOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Dropdown, Input, message, Select, Space, Tag } from 'antd'
import type { InputRef } from 'antd'
import type { TableColumnsType } from 'antd'
import { useSearchParams } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { ConfirmDelete } from '../../components/ConfirmDelete'
import { DataTable } from '../../components/DataTable'
import { useCompactActions } from '../../hooks/useCompactActions'
import { deleteSong, getSongAnalysis, listSongs, publishSong, reanalyzeSong, songKeys, updateSong } from './api'
import { AudioPlayer } from './AudioPlayer'
import { SongEditModal } from './SongEditModal'
import { SongUploadModal } from './SongUploadModal'
import type { Song, SongArtifacts, SongListQuery } from './types'

const pageSizes = new Set([10, 20, 50, 100])
const analysisLabels: Record<string, { label: string; color: string }> = { pending: { label: '待分析', color: 'default' }, processing: { label: '分析中', color: 'processing' }, retrying: { label: '重试中', color: 'warning' }, succeeded: { label: '分析完成', color: 'success' }, failed: { label: '分析失败', color: 'error' } }
function int(value: string | null, fallback: number) { const parsed = Number(value); return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback }
function readQuery(params: URLSearchParams): SongListQuery { const size = int(params.get('page_size'), 20); const analysis = params.get('analysis_status'); const publication = params.get('publication_status'); return { page: int(params.get('page'), 1), page_size: pageSizes.has(size) ? size : 20, search: params.get('search')?.trim() || undefined, analysis_status: ['pending', 'processing', 'succeeded', 'failed', 'retrying'].includes(analysis ?? '') ? analysis as SongListQuery['analysis_status'] : undefined, publication_status: ['draft', 'published'].includes(publication ?? '') ? publication as SongListQuery['publication_status'] : undefined } }
function queryParams(query: SongListQuery) { const params = new URLSearchParams({ page: String(query.page), page_size: String(query.page_size) }); if (query.search) params.set('search', query.search); if (query.analysis_status) params.set('analysis_status', query.analysis_status); if (query.publication_status) params.set('publication_status', query.publication_status); return params }
function seconds(value: number) { return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}` }
function AnalysisStatus({ song, onPreview }: { song: Song; onPreview: (song: Song, artifacts: SongArtifacts) => void }) {
  const active = ['pending', 'processing', 'retrying'].includes(song.analysis_status)
  const shouldLoadDetails = active || song.analysis_status === 'failed'
  const client = useQueryClient()
  const reportedTerminal = useRef('')
  const analysis = useQuery({
    queryKey: songKeys.analysis(song.id),
    queryFn: ({ signal }) => getSongAnalysis(song.id, signal),
    enabled: shouldLoadDetails,
    refetchInterval: (query) => {
      const stillActive = active && (query.state.data?.results ?? []).some((task) => ['pending', 'processing', 'retrying'].includes(task.status))
      if (!stillActive) return false
      const completedPolls = Math.max(0, query.state.dataUpdateCount - 1)
      return Math.min(2_000 * (2 ** Math.min(completedPolls, 3)), 16_000)
    },
    refetchIntervalInBackground: false,
  })
  const tasks = analysis.data?.results ?? []
  const latest = tasks[0]
  const latestStatus = latest?.status ?? song.analysis_status
  useEffect(() => {
    const terminalKey = latest && !['pending', 'processing', 'retrying'].includes(latest.status) ? `${latest.id}:${latest.status}` : ''
    if (active && terminalKey && reportedTerminal.current !== terminalKey) { reportedTerminal.current = terminalKey; void client.invalidateQueries({ queryKey: songKeys.lists() }) }
  }, [active, client, latest])
  const status = analysisLabels[latestStatus] ?? { label: latestStatus, color: 'default' }
  const queryError = analysis.error instanceof ApiError ? analysis.error : null
  return <div className="song-analysis-cell"><Space size={4} wrap><Tag color={status.color}>{status.label}</Tag>{latestStatus === 'succeeded' || tasks.some((task) => task.result?.is_mock) ? <Tag color="gold">模拟分析 / 非临床</Tag> : null}<Button type="link" size="small" aria-label={`试听${song.title}`} icon={<PlayCircleOutlined />} onClick={() => onPreview(song, { source: Boolean(song.source_asset) })}>试听</Button></Space>
    {queryError ? <Alert type="error" showIcon title={queryError.message} description={queryError.requestId ? `请求编号：${queryError.requestId}` : undefined} action={<Button size="small" aria-label={`重试${song.title}分析状态`} onClick={() => void analysis.refetch()}>重试</Button>} /> : null}
    {latest?.status === 'failed' ? <Alert type="error" showIcon title={latest.error_summary || '分析失败'} description={latest.error_code ? `错误代码：${latest.error_code}` : undefined} /> : null}
  </div>
}

export function SongListPage() {
  const [params, setParams] = useSearchParams(); const query = useMemo(() => readQuery(params), [params]); const searchRef = useRef<InputRef>(null)
  const [uploadOpen, setUploadOpen] = useState(false); const [editSong, setEditSong] = useState<Song | null>(null); const [deleteSongTarget, setDeleteSongTarget] = useState<Song | null>(null); const [preview, setPreview] = useState<{ song: Song; artifacts: SongArtifacts } | null>(null); const [actionError, setActionError] = useState<ApiError | null>(null); const deleteGuard = useRef(false); const actionGuard = useRef(new Set<string>()); const reanalysisKeys = useRef(new Map<string, string>()); const compact = useCompactActions(); const client = useQueryClient(); const [messageApi, messageContext] = message.useMessage()
  useEffect(() => { const canonical = queryParams(query); if (params.toString() !== canonical.toString()) setParams(canonical, { replace: true }) }, [params, query, setParams])
  const list = useQuery({ queryKey: songKeys.list(query), queryFn: ({ signal }) => listSongs(query, signal) })
  const invalidate = useCallback(async () => { await client.invalidateQueries({ queryKey: songKeys.lists() }) }, [client])
  const edit = useMutation({ mutationFn: ({ id, values }: { id: string; values: Parameters<typeof updateSong>[1] }) => updateSong(id, values), onSuccess: async () => { await invalidate(); setEditSong(null); messageApi.success('歌曲信息已更新') } })
  const remove = useMutation({ mutationFn: deleteSong, onSuccess: async () => { await invalidate(); setDeleteSongTarget(null); messageApi.success('歌曲已下架并隐藏，历史演唱记录已保留') }, onSettled: () => { deleteGuard.current = false } })
  const publication = useMutation({ mutationFn: ({ id, publish }: { id: string; publish: boolean }) => publishSong(id, publish), onSuccess: async (song) => { await invalidate(); messageApi.success(song.publication_status === 'published' ? '歌曲已发布' : '歌曲已下架') }, onError: (error) => setActionError(error instanceof ApiError ? error : new ApiError('publication_failed', '更新发布状态失败')), onSettled: (_data, _error, variables) => actionGuard.current.delete(`publish:${variables.id}`) })
  const reanalyze = useMutation({ mutationFn: ({ id, key }: { id: string; key: string }) => reanalyzeSong(id, key), onSuccess: async (_data, variables) => { reanalysisKeys.current.delete(variables.id); await invalidate(); messageApi.success('已创建模拟分析任务（非临床）') }, onError: (error, variables) => { const apiError = error instanceof ApiError ? error : new ApiError('reanalyze_failed', '创建分析任务失败'); if (apiError.status && apiError.status >= 400 && apiError.status < 500) reanalysisKeys.current.delete(variables.id); setActionError(apiError) }, onSettled: (_data, _error, variables) => actionGuard.current.delete(`reanalyze:${variables.id}`) })
  const runPublish = useCallback((row: Song) => { const key = `publish:${row.id}`; if (actionGuard.current.has(key)) return; actionGuard.current.add(key); setActionError(null); publication.mutate({ id: row.id, publish: row.publication_status !== 'published' }) }, [publication])
  const runReanalyze = useCallback((row: Song) => { const guardKey = `reanalyze:${row.id}`; if (actionGuard.current.has(guardKey)) return; actionGuard.current.add(guardKey); setActionError(null); const key = reanalysisKeys.current.get(row.id) ?? crypto.randomUUID(); reanalysisKeys.current.set(row.id, key); reanalyze.mutate({ id: row.id, key }) }, [reanalyze])
  const replace = (changes: Partial<SongListQuery>, reset = false) => setParams(queryParams({ ...query, ...changes, page: reset ? 1 : (changes.page ?? query.page) }))
  const columns = useMemo<TableColumnsType<Song>>(() => [
    { title: '歌曲', key: 'song', width: 220, render: (_value, row) => <><strong>{row.title}</strong><br /><span className="muted">{row.artist}</span></> },
    { title: '曲风 / 语言', key: 'meta', width: 150, render: (_value, row) => `${row.genre} / ${row.language}` }, { title: '时长', dataIndex: 'duration_seconds', width: 90, render: seconds },
    { title: '发布', dataIndex: 'publication_status', width: 100, render: (status: Song['publication_status']) => <Tag color={status === 'published' ? 'success' : 'default'}>{status === 'published' ? '已发布' : '草稿'}</Tag> },
    { title: '分析 / 试听', key: 'analysis', width: 250, render: (_value, row) => <AnalysisStatus song={row} onPreview={(song, artifacts) => setPreview({ song, artifacts })} /> },
    { title: '上传时间', dataIndex: 'uploaded_at', width: 175, render: (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false }) },
    { title: '操作', key: 'actions', width: compact ? 76 : 320, fixed: 'right', render: (_value, row) => { const publishingRow = publication.isPending && publication.variables?.id === row.id; const reanalyzingRow = reanalyze.isPending && reanalyze.variables?.id === row.id; const actions = [{ key: 'edit', label: '编辑', onClick: () => setEditSong(row) }, { key: 'publish', label: row.publication_status === 'published' ? '下架' : '发布', disabled: publishingRow, onClick: () => runPublish(row) }, { key: 'reanalyze', label: '重新分析', disabled: reanalyzingRow, onClick: () => runReanalyze(row) }, { key: 'delete', label: '删除', danger: true, onClick: () => { deleteGuard.current = false; remove.reset(); setDeleteSongTarget(row) } }]; return compact ? <Dropdown menu={{ items: actions }} trigger={['click']}><Button type="text" size="small" icon={<MoreOutlined />} aria-label={`更多${row.title}操作`}>更多</Button></Dropdown> : <Space size={2}><Button type="link" size="small" icon={<EditOutlined />} onClick={() => setEditSong(row)} aria-label={`编辑${row.title}`}>编辑</Button><Button type="link" size="small" loading={publishingRow} disabled={publishingRow} onClick={() => runPublish(row)}>{row.publication_status === 'published' ? '下架' : '发布'}</Button><Button type="link" size="small" icon={<ReloadOutlined />} loading={reanalyzingRow} disabled={reanalyzingRow} onClick={() => runReanalyze(row)}>重新分析</Button><Button type="link" danger size="small" icon={<DeleteOutlined />} onClick={() => { deleteGuard.current = false; remove.reset(); setDeleteSongTarget(row) }}>删除</Button></Space> } },
  ], [compact, publication.isPending, publication.variables, reanalyze.isPending, reanalyze.variables, remove, runPublish, runReanalyze])
  const listError = list.error instanceof ApiError ? list.error : null
  return <section className="management-page" aria-labelledby="song-page-title">{messageContext}<div className="management-heading"><div><h1 id="song-page-title">曲库管理</h1><p>上传、发布和试听治疗歌曲；模拟分析结果仅用于流程演示。</p></div><Button type="primary" icon={<PlusOutlined />} aria-label="上传歌曲" onClick={() => setUploadOpen(true)}>上传歌曲</Button></div><div className="management-surface"><div className="management-toolbar songs-toolbar"><Space.Compact className="management-search"><Input aria-label="搜索歌曲" defaultValue={query.search} key={`song-search-${query.search ?? ''}`} ref={searchRef} placeholder="搜索歌名或歌手" onPressEnter={(event) => replace({ search: event.currentTarget.value.trim() || undefined }, true)} /><Button type="primary" aria-label="搜索" onClick={() => replace({ search: searchRef.current?.input?.value.trim() || undefined }, true)}>搜索</Button></Space.Compact><Select aria-label="发布状态" value={query.publication_status} allowClear placeholder="全部发布状态" options={[{ value: 'published', label: '已发布' }, { value: 'draft', label: '草稿' }]} onChange={(value) => replace({ publication_status: value }, true)} /><Select aria-label="分析状态" value={query.analysis_status} allowClear placeholder="全部分析状态" options={Object.entries(analysisLabels).map(([value, item]) => ({ value, label: item.label }))} onChange={(value) => replace({ analysis_status: value }, true)} /><Button onClick={() => setParams(queryParams({ page: 1, page_size: query.page_size }))}>重置</Button></div>{actionError ? <Alert className="song-list-error" type="error" showIcon title={actionError.message} description={actionError.requestId ? `请求编号：${actionError.requestId}` : undefined} /> : null}{listError ? <Alert className="song-list-error" type="error" showIcon title={listError.message} description={listError.requestId ? `请求编号：${listError.requestId}` : undefined} /> : null}<DataTable<Song> ariaLabel="正在加载歌曲列表" columns={columns} data={list.data} emptyText="暂无歌曲，请上传首首治疗歌曲" error={list.error} loading={list.isPending} onPageChange={(page, pageSize) => replace({ page, page_size: pageSize }, pageSize !== query.page_size)} onRetry={() => void list.refetch()} rowKey="id" scrollX={1310} /></div>
    <SongUploadModal open={uploadOpen} onCancel={() => setUploadOpen(false)} onDone={() => { setUploadOpen(false); void invalidate(); messageApi.success('歌曲上传成功') }} />
    <SongEditModal song={editSong} open={editSong !== null} onCancel={() => setEditSong(null)} onSubmit={async (values) => { if (editSong) await edit.mutateAsync({ id: editSong.id, values }) }} />
    <ConfirmDelete title={`删除歌曲“${deleteSongTarget?.title ?? ''}”`} content="删除后歌曲会下架并从曲库隐藏，历史演唱记录和审计历史将保留。" open={deleteSongTarget !== null} loading={remove.isPending} error={remove.error} onCancel={() => { if (!remove.isPending) setDeleteSongTarget(null) }} onConfirm={() => { if (!deleteSongTarget || deleteGuard.current) return; deleteGuard.current = true; remove.mutate(deleteSongTarget.id) }} />
    {preview ? <AudioPlayer song={preview.song} artifacts={preview.artifacts} onClose={() => setPreview(null)} /> : null}
  </section>
}
