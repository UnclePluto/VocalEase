from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, File, Header, HTTPException, UploadFile, status
from pydantic import BaseModel
from redis.exceptions import RedisError
from sqlalchemy import func, select

from vocaease_api.database import (
    AccountRole,
    BackingTrackVersion,
    MediaFile,
    Song,
)
from vocaease_api.identity import CurrentAccount, DatabaseSession, require_role
from vocaease_api.media import LocalMediaStorage, probe_audio, read_upload, sha256, sha256_file
from vocaease_api.separation_models import SeparationJob
from vocaease_api.separation_queue import SeparationQueue, SeparationTask
from vocaease_api.settings import Settings

router = APIRouter(prefix="/api/v1")

SAFE_FAILURE_MESSAGES = {
    "QUEUE_UNAVAILABLE": "任务队列暂时不可用",
    "SOURCE_NOT_FOUND": "原版音乐文件不可用",
    "MODEL_UNAVAILABLE": "分离模型暂时不可用",
    "SEPARATION_FAILED": "音轨分离未能完成",
    "OUTPUT_MISSING": "分离结果不完整",
    "OUTPUT_INVALID": "分离结果格式异常",
}
FailureCode = Literal[
    "SOURCE_NOT_FOUND",
    "MODEL_UNAVAILABLE",
    "SEPARATION_FAILED",
    "OUTPUT_MISSING",
    "OUTPUT_INVALID",
]


class SeparationResponse(BaseModel):
    id: UUID
    song_id: UUID
    status: str
    attempts: int
    model_name: str
    failure_code: str | None
    failure_message: str | None
    source_url: str
    vocals_url: str | None
    no_vocals_url: str | None
    approved_backing_track_id: UUID | None


class WorkerStartedRequest(BaseModel):
    attempt: int


class WorkerOutput(BaseModel):
    storage_key: str
    content_type: str
    size_bytes: int
    sha256: str


class WorkerCompletedRequest(BaseModel):
    attempt: int
    vocals: WorkerOutput
    no_vocals: WorkerOutput


class WorkerFailedRequest(BaseModel):
    attempt: int
    failure_code: FailureCode


def settings() -> Settings:
    return Settings()


def storage() -> LocalMediaStorage:
    return LocalMediaStorage(settings().media_directory)


def present(job: SeparationJob) -> SeparationResponse:
    return SeparationResponse(
        id=job.id,
        song_id=job.song_id,
        status=job.status,
        attempts=job.attempts,
        model_name=job.model_name,
        failure_code=job.failure_code,
        failure_message=SAFE_FAILURE_MESSAGES.get(job.failure_code or ""),
        source_url=f"/api/v1/media/{job.source_media_id}",
        vocals_url=f"/api/v1/media/{job.vocals_media_id}" if job.vocals_media_id else None,
        no_vocals_url=(
            f"/api/v1/media/{job.no_vocals_media_id}" if job.no_vocals_media_id else None
        ),
        approved_backing_track_id=job.approved_backing_track_id,
    )


def enqueue(job: SeparationJob, source: MediaFile) -> None:
    SeparationQueue(settings().separation_redis_url).enqueue(
        SeparationTask(
            job_id=job.id,
            attempt=job.attempts,
            source_storage_key=source.storage_key,
            model_name=job.model_name,
        )
    )


def require_internal_token(token: str | None) -> None:
    if token != settings().worker_internal_token:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "内部调用认证失败")


def require_current_attempt(
    job: SeparationJob | None, attempt: int, expected: str
) -> SeparationJob:
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "分离任务不存在")
    if job.attempts != attempt or job.status != expected:
        raise HTTPException(status.HTTP_409_CONFLICT, "任务状态已经变化")
    return job


@router.post(
    "/admin/songs/{song_id}/separations",
    response_model=SeparationResponse,
    status_code=status.HTTP_201_CREATED,
)
def create_separation(
    song_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
    file: Annotated[UploadFile, File()],
) -> SeparationResponse:
    require_role(account, AccountRole.ADMIN)
    if session.get(Song, song_id) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "歌曲不存在")
    content = read_upload(file, settings())
    suffix = Path(file.filename or "original.bin").suffix or ".bin"
    source_key, source_path = storage().write("original-music", suffix, content)
    try:
        probe_audio(source_path)
    except HTTPException:
        source_path.unlink(missing_ok=True)
        raise
    source = MediaFile(
        storage_key=source_key,
        content_type=file.content_type or "application/octet-stream",
        size_bytes=len(content),
        sha256=sha256(content),
        purpose="original_music",
    )
    session.add(source)
    session.flush()
    job = SeparationJob(
        song_id=song_id,
        source_media_id=source.id,
        status="queued",
        attempts=1,
        model_name=settings().separation_model_name,
        vocals_media_id=None,
        no_vocals_media_id=None,
        approved_backing_track_id=None,
        failure_code=None,
    )
    session.add(job)
    session.commit()
    try:
        enqueue(job, source)
    except RedisError:
        job.status = "failed"
        job.failure_code = "QUEUE_UNAVAILABLE"
        job.updated_at = datetime.now(UTC)
        session.commit()
    return present(job)


@router.get("/admin/separations", response_model=list[SeparationResponse])
def list_separations(
    account: CurrentAccount, session: DatabaseSession, song_id: UUID | None = None
) -> list[SeparationResponse]:
    require_role(account, AccountRole.ADMIN)
    query = select(SeparationJob).order_by(SeparationJob.created_at.desc())
    if song_id is not None:
        query = query.where(SeparationJob.song_id == song_id)
    return [present(job) for job in session.scalars(query).all()]


@router.get("/admin/separations/{job_id}", response_model=SeparationResponse)
def get_separation(
    job_id: UUID, account: CurrentAccount, session: DatabaseSession
) -> SeparationResponse:
    require_role(account, AccountRole.ADMIN)
    job = session.get(SeparationJob, job_id)
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "分离任务不存在")
    return present(job)


@router.post("/admin/separations/{job_id}/retry", response_model=SeparationResponse)
def retry_separation(
    job_id: UUID, account: CurrentAccount, session: DatabaseSession
) -> SeparationResponse:
    require_role(account, AccountRole.ADMIN)
    job = session.get(SeparationJob, job_id)
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "分离任务不存在")
    if job.status != "failed":
        raise HTTPException(status.HTTP_409_CONFLICT, "只有失败任务可以重试")
    source = session.get(MediaFile, job.source_media_id)
    if source is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "原版音乐记录不可用")
    job.attempts += 1
    job.status = "queued"
    job.failure_code = None
    job.vocals_media_id = None
    job.no_vocals_media_id = None
    job.updated_at = datetime.now(UTC)
    session.commit()
    try:
        enqueue(job, source)
    except RedisError:
        job.status = "failed"
        job.failure_code = "QUEUE_UNAVAILABLE"
        session.commit()
    return present(job)


@router.post("/admin/separations/{job_id}/accept", response_model=SeparationResponse)
def accept_separation(
    job_id: UUID, account: CurrentAccount, session: DatabaseSession
) -> SeparationResponse:
    require_role(account, AccountRole.ADMIN)
    job = session.get(SeparationJob, job_id)
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "分离任务不存在")
    if job.status != "succeeded" or job.no_vocals_media_id is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "只有成功的候选结果可以接受")
    no_vocals = session.get(MediaFile, job.no_vocals_media_id)
    if no_vocals is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "无人声候选结果不可用")
    metadata = probe_audio(storage().path(no_vocals.storage_key))
    version = (
        session.scalar(
            select(func.max(BackingTrackVersion.version)).where(
                BackingTrackVersion.song_id == job.song_id
            )
        )
        or 0
    ) + 1
    track = BackingTrackVersion(
        song_id=job.song_id,
        version=version,
        source_media_id=job.source_media_id,
        normalized_media_id=no_vocals.id,
        duration_ms=metadata.duration_ms,
        sample_rate=metadata.sample_rate,
        channels=metadata.channels,
        review_status="approved",
        source_kind="ai_separated",
    )
    session.add(track)
    session.flush()
    job.approved_backing_track_id = track.id
    job.status = "accepted"
    job.updated_at = datetime.now(UTC)
    session.commit()
    return present(job)


@router.post("/admin/separations/{job_id}/reject", response_model=SeparationResponse)
def reject_separation(
    job_id: UUID, account: CurrentAccount, session: DatabaseSession
) -> SeparationResponse:
    require_role(account, AccountRole.ADMIN)
    job = session.get(SeparationJob, job_id)
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "分离任务不存在")
    if job.status != "succeeded":
        raise HTTPException(status.HTTP_409_CONFLICT, "只有成功的候选结果可以拒绝")
    job.status = "rejected"
    job.updated_at = datetime.now(UTC)
    session.commit()
    return present(job)


@router.post(
    "/internal/separations/{job_id}/started",
    status_code=status.HTTP_204_NO_CONTENT,
)
def worker_started(
    job_id: UUID,
    payload: WorkerStartedRequest,
    session: DatabaseSession,
    worker_token: Annotated[str | None, Header(alias="X-VocaEase-Worker-Token")] = None,
) -> None:
    require_internal_token(worker_token)
    job = session.get(SeparationJob, job_id)
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "分离任务不存在")
    if job.attempts != payload.attempt or job.status not in {"queued", "running"}:
        raise HTTPException(status.HTTP_409_CONFLICT, "任务状态已经变化")
    if job.status == "running":
        return
    job.status = "running"
    job.updated_at = datetime.now(UTC)
    session.commit()


def validate_worker_output(job: SeparationJob, attempt: int, output: WorkerOutput) -> Path:
    expected_prefix = f"separations/{job.id}/attempt-{attempt}/"
    if not output.storage_key.startswith(expected_prefix):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "输出存储键不合法")
    path = storage().path(output.storage_key)
    if not path.is_file():
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "输出文件不存在")
    if path.stat().st_size != output.size_bytes or sha256_file(path) != output.sha256:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "输出文件校验失败")
    metadata = probe_audio(path)
    if metadata.sample_rate != 48000 or metadata.channels != 2:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "输出音频规格不符合要求")
    return path


@router.post(
    "/internal/separations/{job_id}/completed",
    status_code=status.HTTP_204_NO_CONTENT,
)
def worker_completed(
    job_id: UUID,
    payload: WorkerCompletedRequest,
    session: DatabaseSession,
    worker_token: Annotated[str | None, Header(alias="X-VocaEase-Worker-Token")] = None,
) -> None:
    require_internal_token(worker_token)
    job = session.get(SeparationJob, job_id)
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "分离任务不存在")
    if job.attempts != payload.attempt:
        raise HTTPException(status.HTTP_409_CONFLICT, "任务状态已经变化")
    if job.status == "succeeded":
        vocals = session.get(MediaFile, job.vocals_media_id)
        no_vocals = session.get(MediaFile, job.no_vocals_media_id)
        if (
            vocals is not None
            and no_vocals is not None
            and vocals.storage_key == payload.vocals.storage_key
            and vocals.sha256 == payload.vocals.sha256
            and no_vocals.storage_key == payload.no_vocals.storage_key
            and no_vocals.sha256 == payload.no_vocals.sha256
        ):
            return
        raise HTTPException(status.HTTP_409_CONFLICT, "任务终态与回调结果不一致")
    job = require_current_attempt(job, payload.attempt, "running")
    if payload.vocals.storage_key == payload.no_vocals.storage_key:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "两轨输出不能使用同一文件")
    validate_worker_output(job, payload.attempt, payload.vocals)
    validate_worker_output(job, payload.attempt, payload.no_vocals)
    vocals = MediaFile(
        storage_key=payload.vocals.storage_key,
        content_type=payload.vocals.content_type,
        size_bytes=payload.vocals.size_bytes,
        sha256=payload.vocals.sha256,
        purpose="separated_vocals",
    )
    no_vocals = MediaFile(
        storage_key=payload.no_vocals.storage_key,
        content_type=payload.no_vocals.content_type,
        size_bytes=payload.no_vocals.size_bytes,
        sha256=payload.no_vocals.sha256,
        purpose="separated_no_vocals",
    )
    session.add_all([vocals, no_vocals])
    session.flush()
    job.vocals_media_id = vocals.id
    job.no_vocals_media_id = no_vocals.id
    job.status = "succeeded"
    job.failure_code = None
    job.updated_at = datetime.now(UTC)
    session.commit()


@router.post(
    "/internal/separations/{job_id}/failed",
    status_code=status.HTTP_204_NO_CONTENT,
)
def worker_failed(
    job_id: UUID,
    payload: WorkerFailedRequest,
    session: DatabaseSession,
    worker_token: Annotated[str | None, Header(alias="X-VocaEase-Worker-Token")] = None,
) -> None:
    require_internal_token(worker_token)
    job = session.get(SeparationJob, job_id)
    if (
        job is not None
        and job.attempts == payload.attempt
        and job.status == "failed"
        and job.failure_code == payload.failure_code
    ):
        return
    job = require_current_attempt(job, payload.attempt, "running")
    job.status = "failed"
    job.failure_code = payload.failure_code
    job.updated_at = datetime.now(UTC)
    session.commit()
