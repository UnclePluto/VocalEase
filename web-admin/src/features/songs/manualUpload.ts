import { ApiError } from '../../api/errors'

export type MediaKind = 'song_source' | 'song_vocal' | 'song_accompaniment' | 'lyrics'
export const mediaKinds: MediaKind[] = ['song_source', 'song_vocal', 'song_accompaniment', 'lyrics']
export const labels: Record<MediaKind, string> = { song_source: '原曲文件', song_vocal: '纯人声文件', song_accompaniment: '纯伴奏文件', lyrics: 'LRC 歌词文件' }

export function describeUploadError(caught: unknown): string {
  if (caught instanceof ApiError) {
    const details = Object.values(caught.fieldErrors ?? {}).flatMap((value) => Array.isArray(value) ? value : [value])
    return details.length ? `${caught.message}：${details.join('；')}` : caught.message
  }
  return caught instanceof Error ? caught.message : '上传失败，请重试'
}

const audioMime: Record<string, string> = { mp3: 'audio/mpeg', wav: 'audio/wav', flac: 'audio/flac' }
export function validateManualFile(kind: MediaKind, file: File): File {
  const extension = file.name.split('.').at(-1)?.toLowerCase() ?? ''
  if (kind === 'lyrics') {
    if (extension !== 'lrc' || !['', 'text/plain', 'application/octet-stream'].includes(file.type)) throw new Error('仅支持 LRC 歌词文件')
    if (!file.size || file.size > 1024 * 1024) throw new Error('歌词不能为空且不能超过 1MB')
    return new File([file], file.name, { type: 'text/plain' })
  }
  const mime = audioMime[extension]
  if (!mime || !['', mime, ...(extension === 'wav' ? ['audio/x-wav'] : extension === 'flac' ? ['audio/x-flac'] : ['audio/mp3'])].includes(file.type)) throw new Error('仅支持 MP3、WAV 或 FLAC 音频')
  if (!file.size || file.size > 50 * 1024 * 1024) throw new Error('音频不能为空且不能超过 50MB')
  return new File([file], file.name, { type: mime })
}
