export interface Participant {
  id: string;
  account_id: string;
  name: string;
  phone: string;
  research_code: string;
  active: boolean;
  must_change_password: boolean;
}

export interface AuditEvent {
  id: string;
  actor_account_id: string | null;
  action: string;
  object_type: string;
  object_id: string | null;
  detail: Record<string, unknown>;
  created_at: string;
}

export interface AuditFilters {
  actor_account_id?: string;
  action?: string;
  object_type?: string;
  created_from?: string;
  created_to?: string;
}

export interface SingingSessionSummary {
  id: string;
  participant_research_code: string;
  song_title: string;
  status: string;
  upload_status: string | null;
  quality_status: string | null;
  playback_mix_status: string | null;
  used_headphones: boolean;
  headphone_risk_confirmed: boolean;
}

export interface WaveformPoint {
  start_ms: number;
  min: number;
  max: number;
  rms: number;
}

export interface LabQualityReport {
  source: string;
  algorithm_version: string;
  status: string;
  metrics: Record<string, unknown>;
}

export interface SoundLabData {
  singing_session_id: string;
  participant_research_code: string;
  song_title: string;
  song_artist: string;
  status: string;
  stages: {
    pre_start_ms: number;
    singing_start_ms: number;
    singing_end_ms: number;
    post_end_ms: number;
  };
  accompaniment_start_frame: number | null;
  device_snapshot: Record<string, unknown>;
  quality_reports: LabQualityReport[];
  waveform: WaveformPoint[];
  spectrogram_url: string;
  raw_voice_url: string;
  playback_mix_status: string | null;
  playback_mix_experience_file: boolean;
}

export interface PlaybackMix {
  id: string;
  singing_session_id: string;
  status: string;
  attempts: number;
  algorithm_version: string;
  accompaniment_start_frame: number;
  failure_code: string | null;
  failure_message: string | null;
  experience_file: boolean;
  media_ready: boolean;
}

export interface PlaybackMixAccess {
  url: string;
  expires_in_seconds: number;
  experience_file: boolean;
}

export interface LyricLine {
  time_ms: number;
  text: string;
}

export interface AdminLyricVersion {
  id: string;
  backing_track_id: string;
  version: number;
  lrc: string;
  lines: LyricLine[];
}

export interface AdminBackingTrack {
  id: string;
  version: number;
  duration_ms: number;
  sample_rate: number;
  channels: number;
  source_sha256: string;
  audio_url: string;
  review_status: "processing" | "approved" | "rejected";
  lyrics: AdminLyricVersion[];
}

export interface AdminSong {
  id: string;
  title: string;
  artist: string;
  cover_url: string | null;
  published: boolean;
  published_backing_track_id: string | null;
  published_lyric_version_id: string | null;
  backing_tracks: AdminBackingTrack[];
}

export type SeparationStatus =
  | "queued"
  | "running"
  | "succeeded"
  | "failed"
  | "accepted"
  | "rejected";

export interface SeparationJob {
  id: string;
  song_id: string;
  status: SeparationStatus;
  attempts: number;
  model_name: string;
  failure_code: string | null;
  failure_message: string | null;
  source_url: string;
  vocals_url: string | null;
  no_vocals_url: string | null;
  approved_backing_track_id: string | null;
}

type CreatedSong = Pick<AdminSong, "id" | "title" | "artist" | "cover_url">;
type UploadedTrack = Omit<AdminBackingTrack, "review_status" | "lyrics"> & {
  review_status?: AdminBackingTrack["review_status"];
};

async function request<T>(path: string, options: RequestInit = {}, token?: string): Promise<T> {
  const isMultipart = options.body instanceof FormData;
  const response = await fetch(path, {
    ...options,
    headers: {
      ...(!isMultipart ? { "Content-Type": "application/json" } : {}),
      ...options.headers,
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    }
  });
  if (!response.ok) {
    const payload = (await response.json().catch(() => ({}))) as { detail?: string };
    throw new Error(payload.detail ?? "请求失败");
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export async function loginAdmin(username: string, password: string): Promise<string> {
  const result = await request<{ access_token: string }>("/api/v1/auth/admin/login", {
    method: "POST",
    body: JSON.stringify({ username, password })
  });
  return result.access_token;
}

export function listParticipants(token: string, query?: string): Promise<Participant[]> {
  const path = query?.trim()
    ? `/api/v1/admin/participants?q=${encodeURIComponent(query.trim())}`
    : "/api/v1/admin/participants";
  return request(path, {}, token);
}

export function createParticipant(
  token: string,
  participant: Pick<Participant, "name" | "phone" | "research_code">
): Promise<Participant> {
  return request(
    "/api/v1/admin/participants",
    { method: "POST", body: JSON.stringify(participant) },
    token
  );
}

export function setParticipantActive(
  token: string,
  participantId: string,
  active: boolean
): Promise<Participant> {
  return request(
    `/api/v1/admin/participants/${participantId}`,
    { method: "PATCH", body: JSON.stringify({ active }) },
    token
  );
}

export function updateParticipant(
  token: string,
  participantId: string,
  changes: Partial<Pick<Participant, "name" | "phone" | "research_code" | "active">>
): Promise<Participant> {
  return request(
    `/api/v1/admin/participants/${participantId}`,
    { method: "PATCH", body: JSON.stringify(changes) },
    token
  );
}

export function resetParticipantPassword(token: string, participantId: string): Promise<void> {
  return request(
    `/api/v1/admin/participants/${participantId}/reset-password`,
    { method: "POST" },
    token
  );
}

export function listAdminSongs(token: string): Promise<AdminSong[]> {
  return request("/api/v1/admin/songs", {}, token);
}

export async function createSong(
  token: string,
  song: { title: string; artist: string }
): Promise<AdminSong> {
  const created = await request<CreatedSong>(
    "/api/v1/admin/songs",
    { method: "POST", body: JSON.stringify(song) },
    token
  );
  return {
    ...created,
    published: false,
    published_backing_track_id: null,
    published_lyric_version_id: null,
    backing_tracks: []
  };
}

function mediaForm(file: File): FormData {
  const body = new FormData();
  body.append("file", file);
  return body;
}

export function uploadSongCover(
  token: string,
  songId: string,
  file: File
): Promise<CreatedSong> {
  return request(
    `/api/v1/admin/songs/${songId}/cover`,
    { method: "POST", body: mediaForm(file) },
    token
  );
}

export async function uploadBackingTrack(
  token: string,
  songId: string,
  file: File
): Promise<AdminBackingTrack> {
  const track = await request<UploadedTrack>(
    `/api/v1/admin/songs/${songId}/backing-tracks`,
    { method: "POST", body: mediaForm(file) },
    token
  );
  return {
    ...track,
    review_status: track.review_status ?? "approved",
    lyrics: []
  };
}

export function saveLyrics(
  token: string,
  trackId: string,
  lrc: string
): Promise<AdminLyricVersion> {
  return request<Omit<AdminLyricVersion, "lrc">>(
    `/api/v1/admin/backing-tracks/${trackId}/lyrics`,
    { method: "PUT", body: JSON.stringify({ lrc }) },
    token
  ).then((lyrics) => ({ ...lyrics, lrc }));
}

export function publishSong(
  token: string,
  songId: string,
  backingTrackId: string,
  lyricVersionId: string
): Promise<void> {
  return request(
    `/api/v1/admin/songs/${songId}/publish`,
    {
      method: "POST",
      body: JSON.stringify({
        backing_track_id: backingTrackId,
        lyric_version_id: lyricVersionId
      })
    },
    token
  );
}

export function unpublishSong(token: string, songId: string): Promise<void> {
  return request(`/api/v1/admin/songs/${songId}/unpublish`, { method: "POST" }, token);
}

export function listSeparations(token: string, songId: string): Promise<SeparationJob[]> {
  return request(`/api/v1/admin/separations?song_id=${encodeURIComponent(songId)}`, {}, token);
}

export function createSeparation(
  token: string,
  songId: string,
  file: File
): Promise<SeparationJob> {
  return request(
    `/api/v1/admin/songs/${songId}/separations`,
    { method: "POST", body: mediaForm(file) },
    token
  );
}

export function getSeparation(token: string, jobId: string): Promise<SeparationJob> {
  return request(`/api/v1/admin/separations/${jobId}`, {}, token);
}

export function retrySeparation(token: string, jobId: string): Promise<SeparationJob> {
  return request(`/api/v1/admin/separations/${jobId}/retry`, { method: "POST" }, token);
}

export function acceptSeparation(token: string, jobId: string): Promise<SeparationJob> {
  return request(`/api/v1/admin/separations/${jobId}/accept`, { method: "POST" }, token);
}

export function rejectSeparation(token: string, jobId: string): Promise<SeparationJob> {
  return request(`/api/v1/admin/separations/${jobId}/reject`, { method: "POST" }, token);
}

export function listAuditEvents(token: string, filters: AuditFilters = {}): Promise<AuditEvent[]> {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value?.trim()) query.set(key, value.trim());
  }
  const suffix = query.size > 0 ? `?${query.toString()}` : "";
  return request(`/api/v1/admin/audit-events${suffix}`, {}, token);
}

export function listSingingSessionSummaries(
  token: string
): Promise<SingingSessionSummary[]> {
  return request("/api/v1/admin/singing-sessions/summary", {}, token);
}

export function getSoundLab(token: string, singingSessionId: string): Promise<SoundLabData> {
  return request(
    `/api/v1/admin/singing-sessions/${singingSessionId}/lab`,
    {},
    token
  );
}

export function getPlaybackMix(token: string, singingSessionId: string): Promise<PlaybackMix> {
  return request(`/api/v1/singing-sessions/${singingSessionId}/playback-mix`, {}, token);
}

export function createPlaybackMixAccess(
  token: string,
  singingSessionId: string
): Promise<PlaybackMixAccess> {
  return request(
    `/api/v1/singing-sessions/${singingSessionId}/playback-mix/access`,
    { method: "POST" },
    token
  );
}

export function deleteSingingSession(token: string, singingSessionId: string): Promise<void> {
  return request(
    `/api/v1/admin/singing-sessions/${singingSessionId}`,
    { method: "DELETE" },
    token
  );
}

export function deleteParticipant(
  token: string,
  participantId: string,
  deleteSingingData: boolean
): Promise<void> {
  return request(
    `/api/v1/admin/participants/${participantId}`,
    {
      method: "DELETE",
      body: JSON.stringify({ delete_singing_data: deleteSingingData })
    },
    token
  );
}

export async function loadProtectedMedia(token: string, path: string): Promise<Blob> {
  const response = await fetch(path, {
    headers: { Authorization: `Bearer ${token}` }
  });
  if (!response.ok) throw new Error("媒体加载失败");
  return response.blob();
}
