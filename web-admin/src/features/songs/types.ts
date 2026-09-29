export type SongAnalysisStatus = 'pending' | 'processing' | 'succeeded' | 'failed' | 'retrying'
export type SongPublicationStatus = 'draft' | 'published'
export type SongResourceField = 'vocal_asset' | 'accompaniment_asset' | 'lyrics_asset'
export type SongTrack = 'source' | 'vocal' | 'accompaniment'

export type Song = {
  id: string
  title: string
  artist: string
  genre: string
  language: string
  duration_seconds: number
  source_asset: string | null
  vocal_asset?: string | null
  accompaniment_asset?: string | null
  lyrics_asset?: string | null
  ingestion_mode?: 'existing' | 'manual'
  artifacts?: SongArtifacts
  analysis_status: SongAnalysisStatus
  publication_status: SongPublicationStatus
  uploaded_at: string
}

export type SongWrite = Pick<Song, 'id' | 'title' | 'artist' | 'genre' | 'language' | 'duration_seconds'> & {
  source_asset: string
  auto_analyze?: boolean
  ingestion_mode?: 'existing' | 'manual'
  vocal_asset?: string
  accompaniment_asset?: string
  lyrics_asset?: string
}

export type SongListQuery = {
  page: number
  page_size: number
  search?: string
  genre?: string
  language?: string
  analysis_status?: SongAnalysisStatus
  publication_status?: SongPublicationStatus
}

export type PaginatedSongs = { count: number; page: number; page_size: number; results: Song[] }

export type UploadGrant = {
  song_id: string
  asset_id: string
  object_key: string
  upload_url: string
  upload_token: string
  fields: Record<string, string>
  expires_at?: string
}

export type AnalysisTask = {
  id: string
  task_type: 'vocal_separation' | 'accompaniment_generation' | 'lyrics_recognition'
  protocol_version: string
  executor: string
  status: SongAnalysisStatus | 'superseded'
  attempt: number
  result: { is_mock?: boolean; artifacts?: Array<{ kind?: string; asset_id?: string }> }
  error_code: string
  error_summary: string
  created_at: string
  completed_at: string | null
}

export type SongArtifacts = { source?: boolean; vocal?: boolean; accompaniment?: boolean; lyrics?: boolean }

export type UploadState =
  | { kind: 'idle' }
  | { kind: 'uploading'; progress: number }
  | { kind: 'confirming' }
  | { kind: 'creating-song' }
  | { kind: 'done'; songId: string }
  | { kind: 'failed'; message: string; details?: string[] }
