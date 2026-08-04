import hashlib
from dataclasses import asdict
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Body, Header, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select

from vocaease_api.audio_quality import QualityThresholds, analyze_pcm_wav
from vocaease_api.audit import record_audit
from vocaease_api.database import (
    AccountRole,
    LyricVersion,
    MediaFile,
    Participant,
    SongPublication,
)
from vocaease_api.identity import CurrentAccount, DatabaseSession, require_role
from vocaease_api.media import LocalMediaStorage
from vocaease_api.media_analysis import generate_spectrogram, waveform_envelope
from vocaease_api.settings import Settings
from vocaease_api.singing_models import (
    AudioQualityReportRecord,
    SessionMediaAnalysis,
    SingingSession,
    VoiceUpload,
    VoiceUploadChunk,
)

router = APIRouter(prefix="/api/v1")
INTERRUPTION_REASONS = Literal[
    "user_cancelled",
    "app_backgrounded",
    "audio_focus_lost",
    "route_changed",
    "process_recovered",
    "capture_error",
]


class DeviceSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid")

    manufacturer: str = Field(max_length=80)
    model: str = Field(max_length=120)
    android_version: str = Field(max_length=40)
    app_version: str = Field(max_length=40)
    input_type: str = Field(max_length=40)
    output_route: str = Field(max_length=40)
    bluetooth_mode: str | None = Field(default=None, max_length=40)
    sample_rate: int
    channels: int
    bit_depth: int


class CreateSingingSessionRequest(BaseModel):
    song_id: UUID
    used_headphones: bool
    headphone_risk_confirmed: bool = False
    device_snapshot: DeviceSnapshot


class CompleteCaptureRequest(BaseModel):
    accompaniment_start_frame: int = Field(ge=0)
    audio_start_monotonic_ns: int = Field(ge=0)
    accompaniment_start_monotonic_ns: int = Field(ge=0)
    recorded_frame_count: int = Field(gt=0)


class InterruptRequest(BaseModel):
    reason: INTERRUPTION_REASONS


class ClientQualityMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid")

    readable: bool
    sample_rate: int
    channels: int
    bit_depth: int
    duration_ms: int
    rms_dbfs: float
    silent_sample_ratio: float
    clipped_sample_ratio: float
    stage_complete: bool
    used_headphones: bool
    route_risk: bool
    file_warnings: list[str] = Field(default_factory=list)
    markers: list[dict] = Field(default_factory=list)


class ClientQualityReportRequest(BaseModel):
    algorithm_version: str = Field(min_length=1, max_length=40)
    status: Literal["ok", "warning"]
    metrics: ClientQualityMetrics


class SingingSessionResponse(BaseModel):
    id: UUID
    status: str
    song_id: UUID
    backing_track_id: UUID
    lyric_version_id: UUID
    used_headphones: bool
    headphone_risk_confirmed: bool
    pre_duration_ms: int
    song_duration_ms: int
    post_duration_ms: int
    accompaniment_start_frame: int | None
    audio_start_monotonic_ns: int | None
    accompaniment_start_monotonic_ns: int | None
    recorded_frame_count: int | None
    interruption_reason: str | None
    device_snapshot: dict
    upload_status: str | None
    raw_voice_url: str | None
    quality_report: dict | None


class CreateUploadRequest(BaseModel):
    expected_chunks: int = Field(ge=1, le=10_000)
    total_bytes: int = Field(gt=0)
    total_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class UploadResponse(BaseModel):
    id: UUID
    singing_session_id: UUID
    status: str
    expected_chunks: int
    received_chunks: list[int]


class CompleteUploadResponse(UploadResponse):
    media_url: str
    quality_report: dict


def storage() -> LocalMediaStorage:
    return LocalMediaStorage(Settings().media_directory)


def quality_thresholds() -> QualityThresholds:
    settings = Settings()
    return QualityThresholds(
        silence_amplitude=settings.quality_silence_amplitude,
        clipping_amplitude=settings.quality_clipping_amplitude,
        silent_ratio_warning=settings.quality_silent_ratio_warning,
        clipping_ratio_warning=settings.quality_clipping_ratio_warning,
        low_volume_dbfs=settings.quality_low_volume_dbfs,
        window_ms=settings.quality_window_ms,
    )


def participant_for(account: CurrentAccount, session: DatabaseSession) -> Participant:
    require_role(account, AccountRole.PARTICIPANT)
    if account.must_change_password:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "必须先修改初始密码")
    participant = session.scalar(select(Participant).where(Participant.account_id == account.id))
    if participant is None or participant.deleted_at is not None:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "参与者档案不存在")
    return participant


def ensure_session_access(
    singing_session: SingingSession | None,
    account: CurrentAccount,
    session: DatabaseSession,
) -> SingingSession:
    if singing_session is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "演唱会话不存在")
    if account.role == AccountRole.ADMIN:
        return singing_session
    participant = participant_for(account, session)
    if singing_session.participant_id != participant.id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "没有访问该演唱会话的权限")
    return singing_session


def latest_quality(
    singing_session_id: UUID, session: DatabaseSession
) -> AudioQualityReportRecord | None:
    return session.scalar(
        select(AudioQualityReportRecord)
        .where(AudioQualityReportRecord.singing_session_id == singing_session_id)
        .order_by(AudioQualityReportRecord.generated_at.desc())
    )


def ensure_media_analysis(
    session: DatabaseSession,
    singing_session: SingingSession,
    raw_voice: MediaFile,
) -> SessionMediaAnalysis:
    existing = session.scalar(
        select(SessionMediaAnalysis).where(
            SessionMediaAnalysis.singing_session_id == singing_session.id
        )
    )
    if existing is not None:
        return existing
    raw_path = storage().path(raw_voice.storage_key)
    spectrogram_key, spectrogram_path = storage().allocate("spectrograms", ".png")
    try:
        generate_spectrogram(raw_path, spectrogram_path)
        spectrogram_content = spectrogram_path.read_bytes()
        spectrogram_media = MediaFile(
            storage_key=spectrogram_key,
            content_type="image/png",
            size_bytes=len(spectrogram_content),
            sha256=hashlib.sha256(spectrogram_content).hexdigest(),
            purpose="spectrogram",
        )
        session.add(spectrogram_media)
        session.flush()
        analysis = SessionMediaAnalysis(
            singing_session_id=singing_session.id,
            waveform=waveform_envelope(raw_path),
            spectrogram_media_id=spectrogram_media.id,
            algorithm_version="waveform-spectrogram-v1",
        )
        session.add(analysis)
        session.flush()
        return analysis
    except Exception:
        spectrogram_path.unlink(missing_ok=True)
        raise


def present_session(
    singing_session: SingingSession, session: DatabaseSession
) -> SingingSessionResponse:
    upload = session.scalar(
        select(VoiceUpload).where(VoiceUpload.singing_session_id == singing_session.id)
    )
    quality = latest_quality(singing_session.id, session)
    return SingingSessionResponse(
        id=singing_session.id,
        status=singing_session.status,
        song_id=singing_session.song_id,
        backing_track_id=singing_session.backing_track_id,
        lyric_version_id=singing_session.lyric_version_id,
        used_headphones=singing_session.used_headphones,
        headphone_risk_confirmed=singing_session.headphone_risk_confirmed,
        pre_duration_ms=singing_session.pre_duration_ms,
        song_duration_ms=singing_session.song_duration_ms,
        post_duration_ms=singing_session.post_duration_ms,
        accompaniment_start_frame=singing_session.accompaniment_start_frame,
        audio_start_monotonic_ns=singing_session.audio_start_monotonic_ns,
        accompaniment_start_monotonic_ns=(singing_session.accompaniment_start_monotonic_ns),
        recorded_frame_count=singing_session.recorded_frame_count,
        interruption_reason=singing_session.interruption_reason,
        device_snapshot=singing_session.device_snapshot,
        upload_status=upload.status if upload else None,
        raw_voice_url=(
            f"/api/v1/media/{singing_session.raw_voice_media_id}"
            if singing_session.raw_voice_media_id
            else None
        ),
        quality_report=quality.metrics if quality else None,
    )


def present_upload(upload: VoiceUpload, session: DatabaseSession) -> UploadResponse:
    received = session.scalars(
        select(VoiceUploadChunk.chunk_number)
        .where(VoiceUploadChunk.upload_id == upload.id)
        .order_by(VoiceUploadChunk.chunk_number)
    ).all()
    return UploadResponse(
        id=upload.id,
        singing_session_id=upload.singing_session_id,
        status=upload.status,
        expected_chunks=upload.expected_chunks,
        received_chunks=list(received),
    )


def audit(
    session: DatabaseSession,
    account: CurrentAccount,
    action: str,
    object_type: str,
    object_id: UUID,
    detail: dict | None = None,
) -> None:
    record_audit(
        session,
        actor_account_id=account.id,
        action=action,
        object_type=object_type,
        object_id=object_id,
        detail=detail,
    )


@router.post(
    "/singing-sessions",
    response_model=SingingSessionResponse,
    status_code=status.HTTP_201_CREATED,
)
def create_singing_session(
    payload: CreateSingingSessionRequest,
    account: CurrentAccount,
    session: DatabaseSession,
) -> SingingSessionResponse:
    participant = participant_for(account, session)
    if not payload.used_headphones and not payload.headphone_risk_confirmed:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "无耳机录制需要二次确认")
    if (
        payload.device_snapshot.sample_rate != 48_000
        or payload.device_snapshot.channels != 1
        or payload.device_snapshot.bit_depth != 16
    ):
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "录音参数必须为 48 kHz、16-bit、单声道",
        )
    publication = session.scalar(
        select(SongPublication).where(
            SongPublication.song_id == payload.song_id,
            SongPublication.active.is_(True),
        )
    )
    if publication is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "歌曲未发布或已撤下")
    lyrics = session.get(LyricVersion, publication.lyric_version_id)
    if lyrics is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "歌曲歌词版本不可用")
    from vocaease_api.database import BackingTrackVersion

    track = session.get(BackingTrackVersion, publication.backing_track_id)
    if track is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "歌曲伴奏版本不可用")
    singing_session = SingingSession(
        participant_id=participant.id,
        song_id=publication.song_id,
        backing_track_id=publication.backing_track_id,
        lyric_version_id=lyrics.id,
        status="recording",
        used_headphones=payload.used_headphones,
        headphone_risk_confirmed=payload.headphone_risk_confirmed,
        device_snapshot=payload.device_snapshot.model_dump(),
        pre_duration_ms=3_000,
        song_duration_ms=track.duration_ms,
        post_duration_ms=3_000,
        accompaniment_start_frame=None,
        audio_start_monotonic_ns=None,
        accompaniment_start_monotonic_ns=None,
        recorded_frame_count=None,
        interruption_reason=None,
        raw_voice_media_id=None,
    )
    session.add(singing_session)
    session.flush()
    audit(
        session,
        account,
        "singing_session.created",
        "singing_session",
        singing_session.id,
        {"used_headphones": payload.used_headphones},
    )
    session.commit()
    return present_session(singing_session, session)


@router.post(
    "/singing-sessions/{singing_session_id}/capture-completed",
    response_model=SingingSessionResponse,
)
def complete_capture(
    singing_session_id: UUID,
    payload: CompleteCaptureRequest,
    account: CurrentAccount,
    session: DatabaseSession,
) -> SingingSessionResponse:
    singing_session = ensure_session_access(
        session.get(SingingSession, singing_session_id), account, session
    )
    if singing_session.status != "recording":
        raise HTTPException(status.HTTP_409_CONFLICT, "演唱会话不在录制状态")
    singing_session.status = "pending_upload"
    singing_session.accompaniment_start_frame = payload.accompaniment_start_frame
    singing_session.audio_start_monotonic_ns = payload.audio_start_monotonic_ns
    singing_session.accompaniment_start_monotonic_ns = payload.accompaniment_start_monotonic_ns
    singing_session.recorded_frame_count = payload.recorded_frame_count
    singing_session.completed_at = datetime.now(UTC)
    session.commit()
    return present_session(singing_session, session)


@router.post(
    "/singing-sessions/{singing_session_id}/interrupt",
    response_model=SingingSessionResponse,
)
def interrupt_session(
    singing_session_id: UUID,
    payload: InterruptRequest,
    account: CurrentAccount,
    session: DatabaseSession,
) -> SingingSessionResponse:
    singing_session = ensure_session_access(
        session.get(SingingSession, singing_session_id), account, session
    )
    if singing_session.status != "recording":
        raise HTTPException(status.HTTP_409_CONFLICT, "演唱会话不能再次中断")
    singing_session.status = "interrupted"
    singing_session.interruption_reason = payload.reason
    singing_session.completed_at = datetime.now(UTC)
    audit(
        session,
        account,
        "singing_session.interrupted",
        "singing_session",
        singing_session.id,
        {"reason": payload.reason},
    )
    session.commit()
    return present_session(singing_session, session)


@router.get("/singing-sessions/{singing_session_id}", response_model=SingingSessionResponse)
def get_singing_session(
    singing_session_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
) -> SingingSessionResponse:
    singing_session = ensure_session_access(
        session.get(SingingSession, singing_session_id), account, session
    )
    return present_session(singing_session, session)


@router.post(
    "/singing-sessions/{singing_session_id}/quality-reports/client",
    status_code=status.HTTP_204_NO_CONTENT,
)
def save_client_quality_report(
    singing_session_id: UUID,
    payload: ClientQualityReportRequest,
    account: CurrentAccount,
    session: DatabaseSession,
) -> None:
    singing_session = ensure_session_access(
        session.get(SingingSession, singing_session_id), account, session
    )
    if singing_session.status == "interrupted":
        raise HTTPException(status.HTTP_409_CONFLICT, "中断会话不保存正式技术质检")
    existing = session.scalar(
        select(AudioQualityReportRecord).where(
            AudioQualityReportRecord.singing_session_id == singing_session.id,
            AudioQualityReportRecord.source == "android",
        )
    )
    metrics = payload.metrics.model_dump()
    if existing is None:
        session.add(
            AudioQualityReportRecord(
                singing_session_id=singing_session.id,
                source="android",
                algorithm_version=payload.algorithm_version,
                status=payload.status,
                metrics=metrics,
            )
        )
    else:
        existing.algorithm_version = payload.algorithm_version
        existing.status = payload.status
        existing.metrics = metrics
        existing.generated_at = datetime.now(UTC)
    session.commit()


@router.get("/admin/singing-sessions", response_model=list[SingingSessionResponse])
def list_singing_sessions(
    account: CurrentAccount, session: DatabaseSession
) -> list[SingingSessionResponse]:
    require_role(account, AccountRole.ADMIN)
    items = session.scalars(select(SingingSession).order_by(SingingSession.created_at.desc())).all()
    return [present_session(item, session) for item in items]


@router.post(
    "/singing-sessions/{singing_session_id}/upload",
    response_model=UploadResponse,
    status_code=status.HTTP_201_CREATED,
)
def create_upload(
    singing_session_id: UUID,
    payload: CreateUploadRequest,
    account: CurrentAccount,
    session: DatabaseSession,
) -> UploadResponse:
    singing_session = ensure_session_access(
        session.get(SingingSession, singing_session_id), account, session
    )
    if singing_session.status not in {"pending_upload", "uploading", "upload_failed"}:
        raise HTTPException(status.HTTP_409_CONFLICT, "演唱会话不能创建上传")
    if payload.total_bytes > Settings().max_audio_upload_bytes:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "原始人声文件过大")
    existing = session.scalar(
        select(VoiceUpload).where(VoiceUpload.singing_session_id == singing_session.id)
    )
    if existing is not None:
        if existing.status == "failed":
            old_chunks = session.scalars(
                select(VoiceUploadChunk).where(VoiceUploadChunk.upload_id == existing.id)
            ).all()
            for chunk in old_chunks:
                storage().path(chunk.storage_key).unlink(missing_ok=True)
                session.delete(chunk)
            existing.expected_chunks = payload.expected_chunks
            existing.total_bytes = payload.total_bytes
            existing.total_sha256 = payload.total_sha256
            existing.status = "uploading"
            singing_session.status = "uploading"
            session.commit()
            return present_upload(existing, session)
        if (
            existing.expected_chunks != payload.expected_chunks
            or existing.total_bytes != payload.total_bytes
            or existing.total_sha256 != payload.total_sha256
        ):
            raise HTTPException(status.HTTP_409_CONFLICT, "上传参数与已有任务不一致")
        return present_upload(existing, session)
    upload = VoiceUpload(
        singing_session_id=singing_session.id,
        status="uploading",
        expected_chunks=payload.expected_chunks,
        total_bytes=payload.total_bytes,
        total_sha256=payload.total_sha256,
    )
    singing_session.status = "uploading"
    session.add(upload)
    session.commit()
    return present_upload(upload, session)


def owned_upload(
    upload_id: UUID, account: CurrentAccount, session: DatabaseSession
) -> tuple[VoiceUpload, SingingSession]:
    upload = session.get(VoiceUpload, upload_id)
    if upload is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "上传任务不存在")
    singing_session = ensure_session_access(
        session.get(SingingSession, upload.singing_session_id), account, session
    )
    return upload, singing_session


@router.get("/voice-uploads/{upload_id}", response_model=UploadResponse)
def get_upload(
    upload_id: UUID, account: CurrentAccount, session: DatabaseSession
) -> UploadResponse:
    upload, _ = owned_upload(upload_id, account, session)
    return present_upload(upload, session)


@router.put(
    "/voice-uploads/{upload_id}/chunks/{chunk_number}",
    response_model=UploadResponse,
)
def upload_chunk(
    upload_id: UUID,
    chunk_number: int,
    account: CurrentAccount,
    session: DatabaseSession,
    content: Annotated[bytes, Body(media_type="application/octet-stream")],
    chunk_sha256: Annotated[str, Header(alias="X-Chunk-SHA256", pattern=r"^[0-9a-f]{64}$")],
) -> UploadResponse:
    upload, _ = owned_upload(upload_id, account, session)
    if upload.status != "uploading":
        raise HTTPException(status.HTTP_409_CONFLICT, "上传任务不接收分片")
    if chunk_number < 0 or chunk_number >= upload.expected_chunks:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "分片编号超出范围")
    if not content or len(content) > 8 * 1024 * 1024:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "分片大小不符合要求")
    actual_sha256 = hashlib.sha256(content).hexdigest()
    if actual_sha256 != chunk_sha256:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "分片校验失败")
    existing = session.get(VoiceUploadChunk, {"upload_id": upload.id, "chunk_number": chunk_number})
    if existing is not None:
        if existing.sha256 != chunk_sha256 or existing.size_bytes != len(content):
            raise HTTPException(status.HTTP_409_CONFLICT, "重复分片内容不一致")
        return present_upload(upload, session)
    key, _ = storage().write("voice-upload-chunks", ".part", content)
    session.add(
        VoiceUploadChunk(
            upload_id=upload.id,
            chunk_number=chunk_number,
            size_bytes=len(content),
            sha256=chunk_sha256,
            storage_key=key,
        )
    )
    session.commit()
    return present_upload(upload, session)


@router.post(
    "/voice-uploads/{upload_id}/complete",
    response_model=CompleteUploadResponse,
)
def complete_upload(
    upload_id: UUID, account: CurrentAccount, session: DatabaseSession
) -> CompleteUploadResponse:
    upload, singing_session = owned_upload(upload_id, account, session)
    if upload.status == "verified" and singing_session.raw_voice_media_id is not None:
        quality = latest_quality(singing_session.id, session)
        if quality is None:
            raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, "技术质检记录不存在")
        raw_voice = session.get(MediaFile, singing_session.raw_voice_media_id)
        if raw_voice is None:
            raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, "原始人声记录不存在")
        ensure_media_analysis(session, singing_session, raw_voice)
        session.commit()
        from vocaease_api.mixing_routes import ensure_mix_job

        ensure_mix_job(session, singing_session)
        return CompleteUploadResponse(
            **present_upload(upload, session).model_dump(),
            media_url=f"/api/v1/media/{singing_session.raw_voice_media_id}",
            quality_report=quality.metrics,
        )
    chunks = session.scalars(
        select(VoiceUploadChunk)
        .where(VoiceUploadChunk.upload_id == upload.id)
        .order_by(VoiceUploadChunk.chunk_number)
    ).all()
    if [chunk.chunk_number for chunk in chunks] != list(range(upload.expected_chunks)):
        raise HTTPException(status.HTTP_409_CONFLICT, "仍有分片未上传")
    target_key, target_path = storage().allocate("raw-voices", ".wav")
    digest = hashlib.sha256()
    size = 0
    try:
        with target_path.open("wb") as target:
            for chunk in chunks:
                chunk_path = storage().path(chunk.storage_key)
                with chunk_path.open("rb") as source:
                    while block := source.read(1024 * 1024):
                        target.write(block)
                        digest.update(block)
                        size += len(block)
        if size != upload.total_bytes or digest.hexdigest() != upload.total_sha256:
            upload.status = "failed"
            singing_session.status = "upload_failed"
            session.commit()
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "完整文件校验失败")
        content = target_path.read_bytes()
        report = analyze_pcm_wav(content, quality_thresholds())
        media = MediaFile(
            storage_key=target_key,
            content_type="audio/wav",
            size_bytes=size,
            sha256=digest.hexdigest(),
            purpose="raw_voice",
        )
        session.add(media)
        session.flush()
        quality_metrics = asdict(report)
        expected_duration_ms = (
            singing_session.pre_duration_ms
            + singing_session.song_duration_ms
            + singing_session.post_duration_ms
        )
        quality_metrics["expected_duration_ms"] = expected_duration_ms
        quality_metrics["stage_complete"] = abs(report.duration_ms - expected_duration_ms) <= 250
        if not quality_metrics["stage_complete"]:
            quality_metrics["file_warnings"].append("录音时长与三阶段预期不一致")
            quality_metrics["status"] = "warning"
        session.add(
            AudioQualityReportRecord(
                singing_session_id=singing_session.id,
                source="server",
                algorithm_version=report.algorithm_version,
                status=quality_metrics["status"],
                metrics=quality_metrics,
            )
        )
        upload.status = "verified"
        upload.verified_at = datetime.now(UTC)
        singing_session.status = "submitted"
        singing_session.raw_voice_media_id = media.id
        ensure_media_analysis(session, singing_session, media)
        audit(
            session,
            account,
            "voice_upload.verified",
            "singing_session",
            singing_session.id,
            {"quality_status": report.status},
        )
        session.commit()
    except Exception:
        target_path.unlink(missing_ok=True)
        raise
    from vocaease_api.mixing_routes import ensure_mix_job

    ensure_mix_job(session, singing_session)
    for chunk in chunks:
        Path(storage().path(chunk.storage_key)).unlink(missing_ok=True)
        session.delete(chunk)
    session.commit()
    return CompleteUploadResponse(
        **present_upload(upload, session).model_dump(),
        media_url=f"/api/v1/media/{media.id}",
        quality_report=quality_metrics,
    )
