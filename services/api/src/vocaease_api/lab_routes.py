from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy import select

from vocaease_api.audit import record_audit
from vocaease_api.database import (
    AccountRole,
    MediaFile,
    Participant,
    Song,
)
from vocaease_api.identity import CurrentAccount, DatabaseSession, require_role
from vocaease_api.mixing_models import PlaybackMixJob
from vocaease_api.singing_models import (
    AudioQualityReportRecord,
    SessionMediaAnalysis,
    SingingSession,
    VoiceUpload,
)
from vocaease_api.singing_routes import storage

router = APIRouter(prefix="/api/v1")


class LabQualityReport(BaseModel):
    source: str
    algorithm_version: str
    status: str
    metrics: dict


class SoundLabResponse(BaseModel):
    singing_session_id: UUID
    participant_research_code: str
    song_title: str
    song_artist: str
    status: str
    stages: dict[str, int]
    accompaniment_start_frame: int | None
    capture_timing: dict[str, int | None]
    device_snapshot: dict
    quality_reports: list[LabQualityReport]
    waveform: list[dict]
    spectrogram_url: str
    raw_voice_url: str
    playback_mix_status: str | None
    playback_mix_experience_file: bool = True


class AdminSingingSessionSummary(BaseModel):
    id: UUID
    participant_research_code: str
    song_title: str
    status: str
    upload_status: str | None
    quality_status: str | None
    playback_mix_status: str | None
    used_headphones: bool
    headphone_risk_confirmed: bool


def require_admin_session(
    singing_session_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
) -> SingingSession:
    if account.role != AccountRole.ADMIN:
        record_audit(
            session,
            actor_account_id=account.id,
            action="singing_session.admin_access_denied",
            object_type="singing_session",
            object_id=singing_session_id,
            detail={"reason": "admin_role_required"},
        )
        session.commit()
        raise HTTPException(403, "需要管理员权限")
    singing_session = session.get(SingingSession, singing_session_id)
    if singing_session is None:
        raise HTTPException(404, "演唱会话不存在")
    return singing_session


@router.get(
    "/admin/singing-sessions/summary",
    response_model=list[AdminSingingSessionSummary],
)
def list_session_summaries(
    account: CurrentAccount,
    session: DatabaseSession,
) -> list[AdminSingingSessionSummary]:
    require_role(account, AccountRole.ADMIN)
    singing_sessions = session.scalars(
        select(SingingSession).order_by(SingingSession.created_at.desc())
    ).all()
    result: list[AdminSingingSessionSummary] = []
    for item in singing_sessions:
        participant = session.get(Participant, item.participant_id)
        song = session.get(Song, item.song_id)
        upload = session.scalar(
            select(VoiceUpload).where(VoiceUpload.singing_session_id == item.id)
        )
        quality = session.scalar(
            select(AudioQualityReportRecord)
            .where(
                AudioQualityReportRecord.singing_session_id == item.id,
                AudioQualityReportRecord.source == "server",
            )
            .order_by(AudioQualityReportRecord.generated_at.desc())
        )
        mix = session.scalar(
            select(PlaybackMixJob).where(PlaybackMixJob.singing_session_id == item.id)
        )
        if participant is None or song is None:
            continue
        result.append(
            AdminSingingSessionSummary(
                id=item.id,
                participant_research_code=participant.research_code,
                song_title=song.title,
                status=item.status,
                upload_status=upload.status if upload else None,
                quality_status=quality.status if quality else None,
                playback_mix_status=mix.status if mix else None,
                used_headphones=item.used_headphones,
                headphone_risk_confirmed=item.headphone_risk_confirmed,
            )
        )
    return result


@router.get(
    "/admin/singing-sessions/{singing_session_id}/lab",
    response_model=SoundLabResponse,
)
def sound_lab(
    singing_session_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
) -> SoundLabResponse:
    item = require_admin_session(singing_session_id, account, session)
    participant = session.get(Participant, item.participant_id)
    song = session.get(Song, item.song_id)
    analysis = session.scalar(
        select(SessionMediaAnalysis).where(SessionMediaAnalysis.singing_session_id == item.id)
    )
    if participant is None or song is None or analysis is None or item.raw_voice_media_id is None:
        raise HTTPException(409, "声音实验室数据尚未生成")
    quality = session.scalars(
        select(AudioQualityReportRecord)
        .where(AudioQualityReportRecord.singing_session_id == item.id)
        .order_by(AudioQualityReportRecord.generated_at)
    ).all()
    mix = session.scalar(select(PlaybackMixJob).where(PlaybackMixJob.singing_session_id == item.id))
    return SoundLabResponse(
        singing_session_id=item.id,
        participant_research_code=participant.research_code,
        song_title=song.title,
        song_artist=song.artist,
        status=item.status,
        stages={
            "pre_start_ms": 0,
            "singing_start_ms": item.pre_duration_ms,
            "singing_end_ms": item.pre_duration_ms + item.song_duration_ms,
            "post_end_ms": (item.pre_duration_ms + item.song_duration_ms + item.post_duration_ms),
        },
        accompaniment_start_frame=item.accompaniment_start_frame,
        capture_timing={
            "audio_start_monotonic_ns": item.audio_start_monotonic_ns,
            "accompaniment_start_monotonic_ns": (item.accompaniment_start_monotonic_ns),
            "recorded_frame_count": item.recorded_frame_count,
        },
        device_snapshot=item.device_snapshot,
        quality_reports=[
            LabQualityReport(
                source=report.source,
                algorithm_version=report.algorithm_version,
                status=report.status,
                metrics=report.metrics,
            )
            for report in quality
        ],
        waveform=analysis.waveform,
        spectrogram_url=f"/api/v1/admin/singing-sessions/{item.id}/spectrogram",
        raw_voice_url=f"/api/v1/admin/singing-sessions/{item.id}/raw-voice",
        playback_mix_status=mix.status if mix else None,
    )


@router.get("/admin/singing-sessions/{singing_session_id}/raw-voice")
def read_raw_voice(
    singing_session_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
    download: Annotated[bool, Query()] = False,
) -> FileResponse:
    item = require_admin_session(singing_session_id, account, session)
    media = session.get(MediaFile, item.raw_voice_media_id)
    if media is None:
        raise HTTPException(404, "原始人声不存在")
    path = storage().path(media.storage_key)
    if not path.is_file():
        raise HTTPException(404, "原始人声文件不存在")
    record_audit(
        session,
        actor_account_id=account.id,
        action="raw_voice.downloaded" if download else "raw_voice.played",
        object_type="singing_session",
        object_id=item.id,
    )
    session.commit()
    return FileResponse(
        path,
        media_type="audio/wav",
        filename=f"raw-voice-{item.id}.wav" if download else None,
    )


@router.get("/admin/singing-sessions/{singing_session_id}/spectrogram")
def read_spectrogram(
    singing_session_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
) -> FileResponse:
    item = require_admin_session(singing_session_id, account, session)
    analysis = session.scalar(
        select(SessionMediaAnalysis).where(
            SessionMediaAnalysis.singing_session_id == item.id
        )
    )
    media = session.get(MediaFile, analysis.spectrogram_media_id) if analysis else None
    if media is None:
        raise HTTPException(404, "频谱图不存在")
    path = storage().path(media.storage_key)
    if not path.is_file():
        raise HTTPException(404, "频谱图文件不存在")
    record_audit(
        session,
        actor_account_id=account.id,
        action="spectrogram.viewed",
        object_type="singing_session",
        object_id=item.id,
    )
    session.commit()
    return FileResponse(path, media_type=media.content_type)
