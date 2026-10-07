import { ApiError } from '../../api/errors'
import { apiRequest } from '../../api/client'
import { useAuthStore } from '../../auth/store'
import type { AnalysisTask, PaginatedSongs, Song, SongListQuery, SongResourceField, SongTrack, SongWrite, UploadGrant } from './types'

export const songKeys = {
  all: ['songs'] as const,
  lists: () => [...songKeys.all, 'list'] as const,
  list: (query: SongListQuery) => [...songKeys.lists(), query] as const,
  analysis: (id: string) => [...songKeys.all, 'analysis', id] as const,
}

function songSearch(query: SongListQuery) {
  const params = new URLSearchParams({ page: String(query.page), page_size: String(query.page_size), sort: '-created_at' })
  if (query.search) params.set('keyword', query.search)
  if (query.genre) params.set('genre', query.genre)
  if (query.language) params.set('language', query.language)
  if (query.analysis_status) params.set('analysis_status', query.analysis_status)
  if (query.publication_status) params.set('publication_status', query.publication_status)
  return params.toString()
}

export function listSongs(query: SongListQuery, signal?: AbortSignal) {
  return apiRequest<PaginatedSongs>(`/v1/admin/songs/?${songSearch(query)}`, { signal })
}
export function getSong(id: string, signal?: AbortSignal) { return apiRequest<Song>(`/v1/admin/songs/${id}/`, { signal }) }
export function createSong(values: SongWrite, signal?: AbortSignal) { return apiRequest<Song>('/v1/admin/songs/', { method: 'POST', body: JSON.stringify(values), signal }) }
export async function createSongReliably(values: SongWrite, signal?: AbortSignal) {
  try {
    return await createSong(values, signal)
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 409 || error.code !== 'song_id_exists') throw error
    const existing = await getSong(values.id, signal)
    const matchesSubmittedSong = existing.id === values.id
      && existing.source_asset === values.source_asset
      && existing.title === values.title
      && existing.artist === values.artist
      && existing.genre === values.genre
      && existing.language === values.language
      && existing.duration_seconds === values.duration_seconds
      && (existing.ingestion_mode ?? 'existing') === (values.ingestion_mode ?? 'existing')
      && (existing.vocal_asset ?? null) === (values.vocal_asset ?? null)
      && (existing.accompaniment_asset ?? null) === (values.accompaniment_asset ?? null)
      && (existing.lyrics_asset ?? null) === (values.lyrics_asset ?? null)
    if (!matchesSubmittedSong) {
      throw new ApiError('song_reconciliation_conflict', '已有歌曲与本次提交内容不一致', error.requestId, undefined, 409)
    }
    return existing
  }
}
export function updateSong(id: string, values: Partial<SongWrite>) { return apiRequest<Song>(`/v1/admin/songs/${id}/`, { method: 'PATCH', body: JSON.stringify(values) }) }
export function deleteSong(id: string) { return apiRequest<void>(`/v1/admin/songs/${id}/`, { method: 'DELETE' }) }
export function publishSong(id: string, publish: boolean) { return apiRequest<Song>(`/v1/admin/songs/${id}/${publish ? 'publish' : 'unpublish'}/`, { method: 'POST' }) }
export function reanalyzeSong(id: string, idempotencyKey: string, taskType: AnalysisTask['task_type'] = 'vocal_separation') {
  return apiRequest<{ task_id: string; status: string; is_mock: true }>(`/v1/admin/songs/${id}/reanalyze/`, { method: 'POST', body: JSON.stringify({ task_type: taskType, idempotency_key: idempotencyKey }) })
}
export function getSongAnalysis(id: string, signal?: AbortSignal) { return apiRequest<{ results: AnalysisTask[] }>(`/v1/admin/songs/${id}/analysis/`, { signal }) }
export function requestUploadGrant(file: File, songId?: string, signal?: AbortSignal) {
  return apiRequest<UploadGrant>('/v1/admin/songs/upload-grants/', { method: 'POST', body: JSON.stringify({ mime: file.type, size: file.size, ...(songId ? { song_id: songId } : {}) }), signal })
}
export function requestSongResourceGrant(file: File, mediaType: 'song_source' | 'song_vocal' | 'song_accompaniment' | 'lyrics', songId?: string, signal?: AbortSignal) {
  return apiRequest<UploadGrant>('/v1/admin/songs/upload-grants/', { method: 'POST', body: JSON.stringify({ media_type: mediaType, mime: file.type, size: file.size, ...(songId ? { song_id: songId } : {}) }), signal })
}
export type SongResourceChange = { updates: Partial<Record<SongResourceField, string>>; expected: Partial<Record<SongResourceField, string | null>> }
export function updateSongResources(id: string, change: SongResourceChange, signal?: AbortSignal) {
  return apiRequest<Song>(`/v1/admin/songs/${id}/resources/`, { method: 'PATCH', body: JSON.stringify(change), signal })
}
export async function updateSongResourcesReliably(id: string, change: SongResourceChange, signal?: AbortSignal) {
  try { return await updateSongResources(id, change, signal) }
  catch (error) {
    if (error instanceof ApiError && error.status !== undefined && error.status !== 409 && error.status < 500) throw error
    try {
      const current = await getSong(id, signal)
      if (Object.entries(change.updates).every(([field, assetId]) => current[field as SongResourceField] === assetId)) return current
    } catch { /* 保留原始保存错误，避免对账请求失败掩盖冲突原因。 */ }
    throw error
  }
}
export function getSongLyrics(id: string, signal?: AbortSignal) { return apiRequest<{ lines: Array<{ time_ms: number; text: string }> }>(`/v1/admin/songs/${id}/lyrics/`, { signal }) }
export function confirmUpload(assetId: string, signal?: AbortSignal) { return apiRequest(`/v1/admin/media/${assetId}/complete/`, { method: 'POST', body: JSON.stringify({}), signal }) }
export function requestPreview(id: string, signal?: AbortSignal, track: SongTrack = 'source') { return apiRequest<{ url: string; expires_at: string }>(`/v1/admin/songs/${id}/preview/`, { method: 'POST', body: JSON.stringify({ track }), signal }) }
/** callbackUrl 开启时，七牛将业务服务的回调响应原样返回给浏览器。 */
export function validateQiniuCallback(body: string, expectedAssetId: string) {
  try {
    const value = JSON.parse(body) as { code?: string; data?: { asset_id?: string; status?: string } }
    if (value.code !== 'ok' || value.data?.asset_id !== expectedAssetId || value.data.status !== 'ready') throw new Error('invalid')
  } catch {
    throw new ApiError('qiniu_callback_invalid', '七牛上传完成回执无效')
  }
}

/** 上传只抵达凭证指定的存储端；绝不把文件内容重新发给 Django API。 */
export function uploadWithGrant(grant: UploadGrant, file: File, options: { signal: AbortSignal; onProgress: (progress: number) => void }) {
  return new Promise<void>((resolve, reject) => {
    const initialEpoch = useAuthStore.getState().sessionEpoch
    const request = new XMLHttpRequest()
    const abort = () => request.abort()
    options.signal.addEventListener('abort', abort, { once: true })
    request.upload.onprogress = (event) => { if (event.lengthComputable) options.onProgress(Math.round(event.loaded / event.total * 100)) }
    const assertEpoch = () => { if (useAuthStore.getState().sessionEpoch !== initialEpoch) { request.abort(); throw new ApiError('session_changed', '登录状态已变更') } }
    request.onerror = () => { try { assertEpoch(); reject(new ApiError('upload_failed', '直传失败，请重试')) } catch (error) { reject(error) } }
    request.onabort = () => reject(new ApiError('request_aborted', '上传已取消'))
    request.onload = () => {
      options.signal.removeEventListener('abort', abort)
      try { assertEpoch() } catch (error) { reject(error); return }
      if (request.status >= 200 && request.status < 300) {
        try { if (!isLocal) validateQiniuCallback(request.responseText, grant.asset_id); resolve() } catch (error) { reject(error) }
      }
      else reject(new ApiError('upload_failed', `直传失败（HTTP ${request.status}）`, request.getResponseHeader('x-request-id') ?? '', undefined, request.status))
    }
    const isLocal = grant.upload_url.startsWith('/api/')
    request.open(isLocal ? 'PUT' : 'POST', grant.upload_url)
    request.withCredentials = isLocal
    if (isLocal) {
      const access = useAuthStore.getState().accessToken
      if (access) request.setRequestHeader('Authorization', `Bearer ${access}`)
      request.setRequestHeader('Content-Type', file.type)
      request.send(file)
    } else {
      const form = new FormData()
      Object.entries(grant.fields).forEach(([key, value]) => form.set(key, value))
      if (grant.upload_token && !grant.fields.token) form.set('token', grant.upload_token)
      form.set('file', file)
      request.send(form)
    }
  })
}

export function generateSongReferencePitch(id: string, fingerprint: string, force = false) {
  return apiRequest<{ version: string; status: string }>(`/v1/admin/songs/${id}/reference-pitch/generate/`, {
    method: 'POST', body: JSON.stringify({ expected_fingerprint: fingerprint, force }),
  })
}
