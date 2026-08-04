from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID, uuid4

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import delete, select

from vocaease_api.audit import record_audit
from vocaease_api.database import (
    Account,
    AccountRole,
    MediaFile,
    Participant,
)
from vocaease_api.identity import (
    CurrentAccount,
    DatabaseSession,
    require_role,
    revoke_account_sessions,
)
from vocaease_api.mixing_models import PlaybackMixJob
from vocaease_api.singing_models import (
    AudioQualityReportRecord,
    SessionMediaAnalysis,
    SingingSession,
    VoiceUpload,
    VoiceUploadChunk,
)
from vocaease_api.singing_routes import storage

router = APIRouter(prefix="/api/v1")


class DeleteParticipantRequest(BaseModel):
    delete_singing_data: bool


def purge_singing_session(
    session: DatabaseSession,
    singing_session: SingingSession,
) -> list[Path]:
    paths: list[Path] = []
    media_ids: set[UUID] = set()
    if singing_session.raw_voice_media_id:
        media_ids.add(singing_session.raw_voice_media_id)

    analysis = session.scalar(
        select(SessionMediaAnalysis).where(
            SessionMediaAnalysis.singing_session_id == singing_session.id
        )
    )
    if analysis is not None:
        media_ids.add(analysis.spectrogram_media_id)

    mix = session.scalar(
        select(PlaybackMixJob).where(PlaybackMixJob.singing_session_id == singing_session.id)
    )
    if mix is not None and mix.output_media_id is not None:
        media_ids.add(mix.output_media_id)

    uploads = session.scalars(
        select(VoiceUpload).where(VoiceUpload.singing_session_id == singing_session.id)
    ).all()
    for upload in uploads:
        chunks = session.scalars(
            select(VoiceUploadChunk).where(VoiceUploadChunk.upload_id == upload.id)
        ).all()
        paths.extend(storage().path(chunk.storage_key) for chunk in chunks)
        session.execute(delete(VoiceUploadChunk).where(VoiceUploadChunk.upload_id == upload.id))

    for media_id in media_ids:
        media = session.get(MediaFile, media_id)
        if media is not None:
            paths.append(storage().path(media.storage_key))

    session.execute(delete(VoiceUpload).where(VoiceUpload.singing_session_id == singing_session.id))
    session.execute(
        delete(AudioQualityReportRecord).where(
            AudioQualityReportRecord.singing_session_id == singing_session.id
        )
    )
    session.execute(
        delete(SessionMediaAnalysis).where(
            SessionMediaAnalysis.singing_session_id == singing_session.id
        )
    )
    session.execute(
        delete(PlaybackMixJob).where(PlaybackMixJob.singing_session_id == singing_session.id)
    )
    session.execute(delete(SingingSession).where(SingingSession.id == singing_session.id))
    if media_ids:
        session.execute(delete(MediaFile).where(MediaFile.id.in_(media_ids)))
    return paths


def stage_paths_for_deletion(paths: list[Path]) -> list[tuple[Path, Path]]:
    trash = storage().root / ".trash"
    trash.mkdir(parents=True, exist_ok=True)
    moved: list[tuple[Path, Path]] = []
    try:
        for original in set(paths):
            if not original.is_file():
                continue
            staged = trash / f"{uuid4().hex}.deleted"
            original.replace(staged)
            moved.append((original, staged))
        return moved
    except OSError:
        restore_staged_paths(moved)
        raise


def restore_staged_paths(moved: list[tuple[Path, Path]]) -> None:
    for original, staged in reversed(moved):
        if staged.is_file():
            original.parent.mkdir(parents=True, exist_ok=True)
            staged.replace(original)


def finalize_staged_paths(moved: list[tuple[Path, Path]]) -> None:
    for _, staged in moved:
        staged.unlink(missing_ok=True)


@router.delete(
    "/admin/singing-sessions/{singing_session_id}",
    status_code=status.HTTP_204_NO_CONTENT,
)
def delete_singing_session(
    singing_session_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
) -> None:
    require_role(account, AccountRole.ADMIN)
    singing_session = session.get(SingingSession, singing_session_id)
    if singing_session is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "演唱会话不存在")
    paths = purge_singing_session(session, singing_session)
    moved = stage_paths_for_deletion(paths)
    try:
        record_audit(
            session,
            actor_account_id=account.id,
            action="singing_session.deleted",
            object_type="singing_session",
            object_id=singing_session_id,
        )
        session.commit()
    except Exception:
        session.rollback()
        restore_staged_paths(moved)
        raise
    finalize_staged_paths(moved)


@router.delete(
    "/admin/participants/{participant_id}",
    status_code=status.HTTP_204_NO_CONTENT,
)
def delete_participant(
    participant_id: UUID,
    payload: DeleteParticipantRequest,
    account: CurrentAccount,
    session: DatabaseSession,
) -> None:
    require_role(account, AccountRole.ADMIN)
    participant = session.get(Participant, participant_id)
    if participant is None or participant.deleted_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "参与者不存在")
    paths: list[Path] = []
    singing_sessions = session.scalars(
        select(SingingSession).where(SingingSession.participant_id == participant.id)
    ).all()
    if payload.delete_singing_data:
        for singing_session in singing_sessions:
            paths.extend(purge_singing_session(session, singing_session))

    account_record = session.get(Account, participant.account_id)
    if account_record is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "参与者账号不存在")
    revoke_account_sessions(session, account_record.id)
    account_record.active = False
    account_record.phone = None
    participant.name = "已删除测试参与者"
    participant.research_code = f"DELETED-{participant.id.hex}"
    participant.deleted_at = datetime.now(UTC)
    moved = stage_paths_for_deletion(paths)
    try:
        record_audit(
            session,
            actor_account_id=account.id,
            action="participant.deleted",
            object_type="participant",
            object_id=participant_id,
            detail={"singing_data_deleted": payload.delete_singing_data},
        )
        session.commit()
    except Exception:
        session.rollback()
        restore_staged_paths(moved)
        raise
    finalize_staged_paths(moved)
