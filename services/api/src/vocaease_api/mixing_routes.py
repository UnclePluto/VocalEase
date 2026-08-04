import base64
import hashlib
import hmac
import json
import time
from datetime import UTC, datetime
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Header, HTTPException, Query, status
from fastapi.responses import FileResponse
from pydantic import BaseModel
from redis.exceptions import RedisError
from sqlalchemy import select

from vocaease_api.database import (
    Account,
    BackingTrackVersion,
    MediaFile,
)
from vocaease_api.identity import CurrentAccount, DatabaseSession
from vocaease_api.media import probe_audio, sha256
from vocaease_api.mixing_models import PlaybackMixJob
from vocaease_api.mixing_queue import PlaybackMixQueue, PlaybackMixTask
from vocaease_api.settings import Settings
from vocaease_api.singing_models import AuditEvent, SingingSession
from vocaease_api.singing_routes import ensure_session_access, storage

router = APIRouter(prefix="/api/v1")

SAFE_FAILURE_MESSAGES = {
    "QUEUE_UNAVAILABLE": "混音任务队列暂时不可用",
    "SOURCE_NOT_FOUND": "原始人声文件不可用",
    "BACKING_NOT_FOUND": "伴奏文件不可用",
    "MIX_FAILED": "回放混音未能完成",
    "OUTPUT_INVALID": "回放混音格式异常",
}
FailureCode = Literal[
    "SOURCE_NOT_FOUND",
    "BACKING_NOT_FOUND",
    "MIX_FAILED",
    "OUTPUT_INVALID",
]


class PlaybackMixResponse(BaseModel):
    id: UUID
    singing_session_id: UUID
    status: str
    attempts: int
    algorithm_version: str
    accompaniment_start_frame: int
    failure_code: str | None
    failure_message: str | None
    experience_file: bool = True
    media_ready: bool


class PlaybackAccessResponse(BaseModel):
    url: str
    expires_in_seconds: int
    experience_file: bool = True


class WorkerStartedRequest(BaseModel):
    attempt: int


class WorkerOutput(BaseModel):
    storage_key: str
    content_type: str
    size_bytes: int
    sha256: str


class WorkerCompletedRequest(BaseModel):
    attempt: int
    output: WorkerOutput


class WorkerFailedRequest(BaseModel):
    attempt: int
    failure_code: FailureCode


def settings() -> Settings:
    return Settings()


def present(job: PlaybackMixJob) -> PlaybackMixResponse:
    return PlaybackMixResponse(
        id=job.id,
        singing_session_id=job.singing_session_id,
        status=job.status,
        attempts=job.attempts,
        algorithm_version=job.algorithm_version,
        accompaniment_start_frame=job.accompaniment_start_frame,
        failure_code=job.failure_code,
        failure_message=SAFE_FAILURE_MESSAGES.get(job.failure_code or ""),
        media_ready=job.status == "succeeded" and job.output_media_id is not None,
    )


def enqueue_mix(job: PlaybackMixJob, raw_voice: MediaFile, backing: MediaFile) -> None:
    PlaybackMixQueue(settings().separation_redis_url).enqueue(
        PlaybackMixTask(
            job_id=job.id,
            attempt=job.attempts,
            raw_voice_storage_key=raw_voice.storage_key,
            backing_storage_key=backing.storage_key,
            accompaniment_start_frame=job.accompaniment_start_frame,
            algorithm_version=job.algorithm_version,
        )
    )


def ensure_mix_job(
    session: DatabaseSession,
    singing_session: SingingSession,
) -> PlaybackMixJob:
    existing = session.scalar(
        select(PlaybackMixJob).where(PlaybackMixJob.singing_session_id == singing_session.id)
    )
    if existing is not None:
        return existing
    if (
        singing_session.raw_voice_media_id is None
        or singing_session.accompaniment_start_frame is None
    ):
        raise HTTPException(status.HTTP_409_CONFLICT, "演唱会话缺少混音来源")
    raw_voice = session.get(MediaFile, singing_session.raw_voice_media_id)
    track = session.get(BackingTrackVersion, singing_session.backing_track_id)
    backing = session.get(MediaFile, track.normalized_media_id) if track else None
    if raw_voice is None or track is None or backing is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "混音来源文件不可用")
    job = PlaybackMixJob(
        singing_session_id=singing_session.id,
        raw_voice_media_id=raw_voice.id,
        backing_track_id=track.id,
        accompaniment_start_frame=singing_session.accompaniment_start_frame,
        algorithm_version=settings().playback_mix_algorithm_version,
        status="queued",
        attempts=1,
        output_media_id=None,
        failure_code=None,
    )
    session.add(job)
    session.commit()
    try:
        enqueue_mix(job, raw_voice, backing)
    except RedisError:
        job.status = "failed"
        job.failure_code = "QUEUE_UNAVAILABLE"
        session.commit()
    return job


def require_internal_token(token: str | None) -> None:
    if token != settings().worker_internal_token:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "内部调用认证失败")


def require_current_attempt(
    job: PlaybackMixJob | None, attempt: int, expected_status: str
) -> PlaybackMixJob:
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "混音任务不存在")
    if job.attempts != attempt or job.status != expected_status:
        raise HTTPException(status.HTTP_409_CONFLICT, "混音任务状态已经变化")
    return job


@router.get(
    "/singing-sessions/{singing_session_id}/playback-mix",
    response_model=PlaybackMixResponse,
)
def get_playback_mix(
    singing_session_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
) -> PlaybackMixResponse:
    singing_session = ensure_session_access(
        session.get(SingingSession, singing_session_id), account, session
    )
    job = session.scalar(
        select(PlaybackMixJob).where(PlaybackMixJob.singing_session_id == singing_session.id)
    )
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "回放混音任务尚未创建")
    return present(job)


@router.post(
    "/singing-sessions/{singing_session_id}/playback-mix/retry",
    response_model=PlaybackMixResponse,
)
def retry_playback_mix(
    singing_session_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
) -> PlaybackMixResponse:
    singing_session = ensure_session_access(
        session.get(SingingSession, singing_session_id), account, session
    )
    job = session.scalar(
        select(PlaybackMixJob).where(PlaybackMixJob.singing_session_id == singing_session.id)
    )
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "回放混音任务尚未创建")
    if job.status != "failed":
        raise HTTPException(status.HTTP_409_CONFLICT, "只有失败的混音任务可以重试")
    raw_voice = session.get(MediaFile, job.raw_voice_media_id)
    track = session.get(BackingTrackVersion, job.backing_track_id)
    backing = session.get(MediaFile, track.normalized_media_id) if track else None
    if raw_voice is None or track is None or backing is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "混音来源文件不可用")
    job.status = "queued"
    job.attempts += 1
    job.failure_code = None
    job.output_media_id = None
    job.updated_at = datetime.now(UTC)
    session.commit()
    try:
        enqueue_mix(job, raw_voice, backing)
    except RedisError:
        job.status = "failed"
        job.failure_code = "QUEUE_UNAVAILABLE"
        session.commit()
    return present(job)


def encode_access_token(job: PlaybackMixJob, account_id: UUID) -> str:
    expires_at = int(time.time()) + settings().playback_media_grant_seconds
    payload = json.dumps(
        {"job_id": str(job.id), "account_id": str(account_id), "expires_at": expires_at},
        separators=(",", ":"),
    ).encode()
    signature = hmac.new(
        settings().playback_media_signing_secret.encode(),
        payload,
        hashlib.sha256,
    ).digest()
    return base64.urlsafe_b64encode(payload + signature).decode().rstrip("=")


def decode_access_token(token: str, expected_job_id: UUID) -> tuple[UUID, int]:
    try:
        padded = token + "=" * (-len(token) % 4)
        decoded = base64.urlsafe_b64decode(padded)
        payload, supplied_signature = decoded[:-32], decoded[-32:]
        expected_signature = hmac.new(
            settings().playback_media_signing_secret.encode(),
            payload,
            hashlib.sha256,
        ).digest()
        if not hmac.compare_digest(supplied_signature, expected_signature):
            raise ValueError
        claims = json.loads(payload)
        if UUID(claims["job_id"]) != expected_job_id:
            raise ValueError
        account_id = UUID(claims["account_id"])
        expires_at = int(claims["expires_at"])
        if expires_at < int(time.time()):
            raise ValueError
        return account_id, expires_at
    except (ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "回放授权无效或已过期") from error


@router.post(
    "/singing-sessions/{singing_session_id}/playback-mix/access",
    response_model=PlaybackAccessResponse,
)
def create_playback_access(
    singing_session_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
) -> PlaybackAccessResponse:
    singing_session = ensure_session_access(
        session.get(SingingSession, singing_session_id), account, session
    )
    job = session.scalar(
        select(PlaybackMixJob).where(PlaybackMixJob.singing_session_id == singing_session.id)
    )
    if job is None or job.status != "succeeded" or job.output_media_id is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "回放混音仍在处理中")
    token = encode_access_token(job, account.id)
    return PlaybackAccessResponse(
        url=f"/api/v1/playback-mixes/{job.id}/media?token={token}",
        expires_in_seconds=settings().playback_media_grant_seconds,
    )


@router.get("/playback-mixes/{job_id}/media")
def read_playback_mix(
    job_id: UUID,
    session: DatabaseSession,
    token: Annotated[str, Query(min_length=20)],
) -> FileResponse:
    account_id, expires_at = decode_access_token(token, job_id)
    account = session.get(Account, account_id)
    if account is None or not account.active:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "回放授权对应账号已失效")
    job = session.get(PlaybackMixJob, job_id)
    if job is None or job.status != "succeeded" or job.output_media_id is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "回放混音不存在")
    media = session.get(MediaFile, job.output_media_id)
    if media is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "回放混音文件不存在")
    singing_session = ensure_session_access(
        session.get(SingingSession, job.singing_session_id),
        account,
        session,
    )
    path = storage().path(media.storage_key)
    if not path.is_file():
        raise HTTPException(status.HTTP_404_NOT_FOUND, "回放混音文件不存在")
    session.add(
        AuditEvent(
            actor_account_id=account_id,
            action="playback_mix.played",
            object_type="singing_session",
            object_id=singing_session.id,
            detail={"grant_expires_at": expires_at},
        )
    )
    session.commit()
    return FileResponse(path, media_type=media.content_type)


@router.post(
    "/internal/playback-mixes/{job_id}/started",
    status_code=status.HTTP_204_NO_CONTENT,
)
def worker_started(
    job_id: UUID,
    payload: WorkerStartedRequest,
    session: DatabaseSession,
    worker_token: Annotated[str | None, Header(alias="X-VocaEase-Worker-Token")] = None,
) -> None:
    require_internal_token(worker_token)
    job = require_current_attempt(session.get(PlaybackMixJob, job_id), payload.attempt, "queued")
    job.status = "running"
    job.updated_at = datetime.now(UTC)
    session.commit()


@router.post(
    "/internal/playback-mixes/{job_id}/completed",
    status_code=status.HTTP_204_NO_CONTENT,
)
def worker_completed(
    job_id: UUID,
    payload: WorkerCompletedRequest,
    session: DatabaseSession,
    worker_token: Annotated[str | None, Header(alias="X-VocaEase-Worker-Token")] = None,
) -> None:
    require_internal_token(worker_token)
    job = require_current_attempt(session.get(PlaybackMixJob, job_id), payload.attempt, "running")
    expected_key = f"playback-mixes/{job.id}/mix.m4a"
    if payload.output.storage_key != expected_key:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "混音输出存储键不合法")
    path = storage().path(payload.output.storage_key)
    if not path.is_file():
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "混音输出文件不存在")
    content = path.read_bytes()
    if len(content) != payload.output.size_bytes or sha256(content) != payload.output.sha256:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "混音输出文件校验失败")
    metadata = probe_audio(path)
    if metadata.sample_rate != 48000 or metadata.channels != 2:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "混音输出规格不符合要求")
    raw_voice = session.get(MediaFile, job.raw_voice_media_id)
    track = session.get(BackingTrackVersion, job.backing_track_id)
    backing = session.get(MediaFile, track.normalized_media_id) if track else None
    if raw_voice is None or backing is None:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "混音来源记录不完整")
    raw_path = storage().path(raw_voice.storage_key)
    backing_path = storage().path(backing.storage_key)
    if (
        not raw_path.is_file()
        or sha256(raw_path.read_bytes()) != raw_voice.sha256
        or not backing_path.is_file()
    ):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "混音来源文件校验失败")
    raw_metadata = probe_audio(raw_path)
    backing_metadata = probe_audio(backing_path)
    offset_ms = round(job.accompaniment_start_frame * 1000 / 48000)
    expected_duration_ms = max(
        raw_metadata.duration_ms,
        offset_ms + backing_metadata.duration_ms,
    )
    if abs(metadata.duration_ms - expected_duration_ms) > 500:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "混音输出时长不符合要求")
    output = MediaFile(
        storage_key=payload.output.storage_key,
        content_type="audio/mp4",
        size_bytes=payload.output.size_bytes,
        sha256=payload.output.sha256,
        purpose="playback_mix",
    )
    session.add(output)
    session.flush()
    job.output_media_id = output.id
    job.status = "succeeded"
    job.failure_code = None
    job.updated_at = datetime.now(UTC)
    session.commit()


@router.post(
    "/internal/playback-mixes/{job_id}/failed",
    status_code=status.HTTP_204_NO_CONTENT,
)
def worker_failed(
    job_id: UUID,
    payload: WorkerFailedRequest,
    session: DatabaseSession,
    worker_token: Annotated[str | None, Header(alias="X-VocaEase-Worker-Token")] = None,
) -> None:
    require_internal_token(worker_token)
    job = require_current_attempt(session.get(PlaybackMixJob, job_id), payload.attempt, "running")
    job.status = "failed"
    job.failure_code = payload.failure_code
    job.updated_at = datetime.now(UTC)
    session.commit()
