import { useCallback, useRef, useState } from 'react'

import { ApiError } from '../../api/errors'
import { confirmUpload, requestSongResourceGrant, uploadWithGrant } from './api'
import { mediaKinds, validateManualFile } from './manualUpload'
import type { MediaKind } from './manualUpload'

type Slot = { file?: File; assetId?: string; progress?: number }
type Slots = Partial<Record<MediaKind, Slot>>

export function useSongResourceUpload(initialSongId?: string) {
  const [slots, setSlots] = useState<Slots>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const slotsRef = useRef<Slots>({})
  const songId = useRef(initialSongId)
  const controller = useRef<AbortController | null>(null)
  const running = useRef(false)
  const generation = useRef(0)

  const publishSlots = (next: Slots) => { slotsRef.current = next; setSlots(next) }
  const reset = useCallback(() => {
    controller.current?.abort(); controller.current = null; generation.current += 1; running.current = false
    slotsRef.current = {}; setSlots({}); songId.current = initialSongId; setBusy(false); setError('')
  }, [initialSongId])
  const chooseFile = (kind: MediaKind, candidate: File | null) => {
    if (running.current) return
    try {
      const file = candidate ? validateManualFile(kind, candidate) : undefined
      const next = { ...slotsRef.current, [kind]: file ? { file } : undefined }
      if (kind === 'song_source') {
        songId.current = initialSongId
        for (const other of mediaKinds.slice(1)) if (next[other]) next[other] = { file: next[other]?.file }
      }
      publishSlots(next); setError('')
    } catch (caught) {
      publishSlots({ ...slotsRef.current, [kind]: undefined })
      setError(caught instanceof Error ? caught.message : '文件格式错误')
    }
  }
  const uploadSelected = async () => {
    if (running.current) throw new Error('上传正在进行')
    running.current = true; setBusy(true); setError('')
    const run = ++generation.current
    const nextController = new AbortController(); controller.current = nextController
    try {
      for (const kind of mediaKinds) {
        const slot = slotsRef.current[kind]
        if (!slot?.file || slot.assetId) continue
        const grant = await requestSongResourceGrant(slot.file, kind, songId.current, nextController.signal)
        if (run !== generation.current || nextController.signal.aborted) throw new ApiError('request_aborted', '上传已取消')
        songId.current = grant.song_id
        await uploadWithGrant(grant, slot.file, { signal: nextController.signal, onProgress: (progress) => { if (run === generation.current) publishSlots({ ...slotsRef.current, [kind]: { ...slotsRef.current[kind], progress } }) } })
        if (grant.upload_url.startsWith('/api/')) await confirmUpload(grant.asset_id, nextController.signal)
        if (run !== generation.current || nextController.signal.aborted) throw new ApiError('request_aborted', '上传已取消')
        publishSlots({ ...slotsRef.current, [kind]: { ...slotsRef.current[kind], assetId: grant.asset_id, progress: 100 } })
      }
      if (!songId.current) throw new Error('请选择原曲文件')
      return { songId: songId.current, assets: Object.fromEntries(mediaKinds.flatMap((kind) => slotsRef.current[kind]?.assetId ? [[kind, slotsRef.current[kind]!.assetId!]] : [])) as Partial<Record<MediaKind, string>> }
    } catch (caught) {
      if (run === generation.current && !(caught instanceof ApiError && caught.code === 'request_aborted')) setError(caught instanceof Error ? caught.message : '上传失败，请重试')
      throw caught
    } finally {
      if (run === generation.current) { running.current = false; setBusy(false); controller.current = null }
    }
  }
  return { slots, busy, error, setError, chooseFile, uploadSelected, reset }
}
