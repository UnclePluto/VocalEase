from pathlib import Path
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, File, HTTPException, UploadFile, status
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from sqlalchemy import func, select, update

from vocaease_api.audit import record_audit
from vocaease_api.database import (
    AccountRole,
    BackingTrackVersion,
    LyricVersion,
    MediaFile,
    Participant,
    Song,
    SongPublication,
)
from vocaease_api.identity import CurrentAccount, DatabaseSession, require_role
from vocaease_api.media import (
    LocalMediaStorage,
    normalize_backing_track,
    parse_lrc,
    probe_audio,
    read_upload,
    sha256,
    validate_image,
)
from vocaease_api.settings import Settings

router = APIRouter(prefix="/api/v1")


class SongRequest(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    artist: str = Field(min_length=1, max_length=200)


class SongResponse(BaseModel):
    id: UUID
    title: str
    artist: str
    cover_url: str | None


class LyricSummary(BaseModel):
    id: UUID
    backing_track_id: UUID
    version: int
    lrc: str
    lines: list[dict[str, int | str]]


class TrackResponse(BaseModel):
    id: UUID
    version: int
    duration_ms: int
    sample_rate: int
    channels: int
    source_sha256: str
    review_status: str
    source_kind: str
    audio_url: str
    lyrics: list[LyricSummary]


class AdminSongResponse(SongResponse):
    published: bool
    published_backing_track_id: UUID | None
    published_lyric_version_id: UUID | None
    backing_tracks: list[TrackResponse]


class LyricRequest(BaseModel):
    lrc: str


class LyricResponse(BaseModel):
    id: UUID
    backing_track_id: UUID
    version: int
    lines: list[dict[str, int | str]]


class PublishRequest(BaseModel):
    backing_track_id: UUID
    lyric_version_id: UUID


class CatalogSongResponse(BaseModel):
    id: UUID
    title: str
    artist: str
    cover_url: str | None
    duration_ms: int
    backing_track_id: UUID
    backing_track_url: str
    lyric_version_id: UUID
    lines: list[dict[str, int | str]]


def storage() -> LocalMediaStorage:
    return LocalMediaStorage(Settings().media_directory)


def media_record(content: bytes, key: str, content_type: str, purpose: str) -> MediaFile:
    return MediaFile(
        storage_key=key,
        content_type=content_type,
        size_bytes=len(content),
        sha256=sha256(content),
        purpose=purpose,
    )


@router.post("/admin/songs", response_model=SongResponse, status_code=status.HTTP_201_CREATED)
def create_song(
    payload: SongRequest, account: CurrentAccount, session: DatabaseSession
) -> SongResponse:
    require_role(account, AccountRole.ADMIN)
    song = Song(title=payload.title, artist=payload.artist, cover_media_id=None)
    session.add(song)
    session.flush()
    record_audit(
        session,
        actor_account_id=account.id,
        action="song.created",
        object_type="song",
        object_id=song.id,
    )
    session.commit()
    return SongResponse(id=song.id, title=song.title, artist=song.artist, cover_url=None)


@router.post("/admin/songs/{song_id}/cover", response_model=SongResponse)
def upload_cover(
    song_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
    file: Annotated[UploadFile, File()],
) -> SongResponse:
    require_role(account, AccountRole.ADMIN)
    song = session.get(Song, song_id)
    if song is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "歌曲不存在")
    content = read_upload(file, Settings())
    if file.content_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "封面格式不支持")
    validate_image(content, file.content_type)
    suffix = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}[file.content_type]
    key, _ = storage().write("covers", suffix, content)
    media = media_record(content, key, file.content_type, "song_cover")
    session.add(media)
    session.flush()
    song.cover_media_id = media.id
    record_audit(
        session,
        actor_account_id=account.id,
        action="song.cover_uploaded",
        object_type="song",
        object_id=song.id,
    )
    session.commit()
    return SongResponse(
        id=song.id,
        title=song.title,
        artist=song.artist,
        cover_url=f"/api/v1/media/{media.id}",
    )


@router.post("/admin/songs/{song_id}/backing-tracks", response_model=TrackResponse)
def upload_backing_track(
    song_id: UUID,
    account: CurrentAccount,
    session: DatabaseSession,
    file: Annotated[UploadFile, File()],
) -> TrackResponse:
    require_role(account, AccountRole.ADMIN)
    if session.get(Song, song_id) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "歌曲不存在")
    content = read_upload(file, Settings())
    store = storage()
    source_key, source_path = store.write(
        "song-sources", Path(file.filename or "audio").suffix or ".bin", content
    )
    normalized_key, normalized_path = store.allocate("backing-tracks", ".m4a")
    try:
        probe_audio(source_path)
        metadata = normalize_backing_track(source_path, normalized_path)
    except HTTPException:
        source_path.unlink(missing_ok=True)
        normalized_path.unlink(missing_ok=True)
        raise
    normalized_content = normalized_path.read_bytes()
    source_media = media_record(
        content, source_key, file.content_type or "application/octet-stream", "song_source"
    )
    normalized_media = media_record(
        normalized_content, normalized_key, "audio/mp4", "backing_track"
    )
    session.add_all([source_media, normalized_media])
    session.flush()
    version = (
        session.scalar(
            select(func.max(BackingTrackVersion.version)).where(
                BackingTrackVersion.song_id == song_id
            )
        )
        or 0
    ) + 1
    track = BackingTrackVersion(
        song_id=song_id,
        version=version,
        source_media_id=source_media.id,
        normalized_media_id=normalized_media.id,
        duration_ms=metadata.duration_ms,
        sample_rate=metadata.sample_rate,
        channels=metadata.channels,
        review_status="approved",
        source_kind="uploaded_backing",
    )
    session.add(track)
    session.flush()
    record_audit(
        session,
        actor_account_id=account.id,
        action="backing_track.uploaded",
        object_type="song",
        object_id=song_id,
        detail={"backing_track_id": str(track.id), "version": track.version},
    )
    session.commit()
    return track_response(track, session)


def track_response(track: BackingTrackVersion, session: DatabaseSession) -> TrackResponse:
    source = session.get(MediaFile, track.source_media_id)
    if source is None:
        raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, "伴奏原始文件记录不存在")
    lyrics = session.scalars(
        select(LyricVersion)
        .where(LyricVersion.backing_track_id == track.id)
        .order_by(LyricVersion.version.desc())
    ).all()
    return TrackResponse(
        id=track.id,
        version=track.version,
        duration_ms=track.duration_ms,
        sample_rate=track.sample_rate,
        channels=track.channels,
        source_sha256=source.sha256,
        review_status=track.review_status,
        source_kind=track.source_kind,
        audio_url=f"/api/v1/media/{track.normalized_media_id}",
        lyrics=[
            LyricSummary(
                id=item.id,
                backing_track_id=item.backing_track_id,
                version=item.version,
                lrc=item.lrc_text,
                lines=parse_lrc(item.lrc_text),
            )
            for item in lyrics
        ],
    )


@router.get("/admin/songs", response_model=list[AdminSongResponse])
def list_admin_songs(account: CurrentAccount, session: DatabaseSession) -> list[AdminSongResponse]:
    require_role(account, AccountRole.ADMIN)
    songs = session.scalars(select(Song).order_by(Song.created_at.desc())).all()
    active_publications = {
        publication.song_id: publication
        for publication in session.scalars(
            select(SongPublication).where(SongPublication.active.is_(True))
        ).all()
    }
    result: list[AdminSongResponse] = []
    for song in songs:
        tracks = session.scalars(
            select(BackingTrackVersion)
            .where(BackingTrackVersion.song_id == song.id)
            .order_by(BackingTrackVersion.version)
        ).all()
        publication = active_publications.get(song.id)
        result.append(
            AdminSongResponse(
                id=song.id,
                title=song.title,
                artist=song.artist,
                cover_url=(f"/api/v1/media/{song.cover_media_id}" if song.cover_media_id else None),
                published=publication is not None,
                published_backing_track_id=(
                    publication.backing_track_id if publication is not None else None
                ),
                published_lyric_version_id=(
                    publication.lyric_version_id if publication is not None else None
                ),
                backing_tracks=[track_response(track, session) for track in tracks],
            )
        )
    return result


@router.put("/admin/backing-tracks/{track_id}/lyrics", response_model=LyricResponse)
def create_lyrics(
    track_id: UUID, payload: LyricRequest, account: CurrentAccount, session: DatabaseSession
) -> LyricResponse:
    require_role(account, AccountRole.ADMIN)
    if session.get(BackingTrackVersion, track_id) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "伴奏版本不存在")
    lines = parse_lrc(payload.lrc)
    version = (
        session.scalar(
            select(func.max(LyricVersion.version)).where(LyricVersion.backing_track_id == track_id)
        )
        or 0
    ) + 1
    lyrics = LyricVersion(backing_track_id=track_id, version=version, lrc_text=payload.lrc)
    session.add(lyrics)
    session.flush()
    record_audit(
        session,
        actor_account_id=account.id,
        action="lyrics.created",
        object_type="backing_track",
        object_id=track_id,
        detail={"lyric_version_id": str(lyrics.id), "version": lyrics.version},
    )
    session.commit()
    return LyricResponse(id=lyrics.id, backing_track_id=track_id, version=version, lines=lines)


@router.post("/admin/songs/{song_id}/publish", status_code=status.HTTP_204_NO_CONTENT)
def publish_song(
    song_id: UUID,
    payload: PublishRequest,
    account: CurrentAccount,
    session: DatabaseSession,
) -> None:
    require_role(account, AccountRole.ADMIN)
    track = session.get(BackingTrackVersion, payload.backing_track_id)
    lyrics = session.get(LyricVersion, payload.lyric_version_id)
    if (
        track is None
        or lyrics is None
        or track.song_id != song_id
        or lyrics.backing_track_id != track.id
        or track.review_status != "approved"
    ):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "伴奏与歌词版本不能发布")
    session.execute(
        update(SongPublication)
        .where(SongPublication.song_id == song_id, SongPublication.active.is_(True))
        .values(active=False)
    )
    session.add(
        SongPublication(
            song_id=song_id,
            backing_track_id=track.id,
            lyric_version_id=lyrics.id,
            active=True,
        )
    )
    record_audit(
        session,
        actor_account_id=account.id,
        action="song.published",
        object_type="song",
        object_id=song_id,
        detail={
            "backing_track_id": str(track.id),
            "lyric_version_id": str(lyrics.id),
        },
    )
    session.commit()


@router.post("/admin/songs/{song_id}/unpublish", status_code=status.HTTP_204_NO_CONTENT)
def unpublish_song(song_id: UUID, account: CurrentAccount, session: DatabaseSession) -> None:
    require_role(account, AccountRole.ADMIN)
    session.execute(
        update(SongPublication)
        .where(SongPublication.song_id == song_id, SongPublication.active.is_(True))
        .values(active=False)
    )
    record_audit(
        session,
        actor_account_id=account.id,
        action="song.unpublished",
        object_type="song",
        object_id=song_id,
    )
    session.commit()


def catalog_song(publication: SongPublication, session: DatabaseSession) -> CatalogSongResponse:
    song = session.get(Song, publication.song_id)
    track = session.get(BackingTrackVersion, publication.backing_track_id)
    lyrics = session.get(LyricVersion, publication.lyric_version_id)
    if song is None or track is None or lyrics is None:
        raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, "曲库版本数据不完整")
    return CatalogSongResponse(
        id=song.id,
        title=song.title,
        artist=song.artist,
        cover_url=f"/api/v1/media/{song.cover_media_id}" if song.cover_media_id else None,
        duration_ms=track.duration_ms,
        backing_track_id=track.id,
        backing_track_url=f"/api/v1/media/{track.normalized_media_id}",
        lyric_version_id=lyrics.id,
        lines=parse_lrc(lyrics.lrc_text),
    )


def require_catalog_access(account: CurrentAccount) -> None:
    require_role(account, AccountRole.PARTICIPANT)
    if account.must_change_password:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "必须先修改初始密码")


@router.get("/catalog/songs", response_model=list[CatalogSongResponse])
def list_catalog(account: CurrentAccount, session: DatabaseSession) -> list[CatalogSongResponse]:
    require_catalog_access(account)
    publications = session.scalars(
        select(SongPublication)
        .where(SongPublication.active.is_(True))
        .order_by(SongPublication.published_at.desc())
    ).all()
    return [catalog_song(publication, session) for publication in publications]


@router.get("/catalog/songs/{song_id}", response_model=CatalogSongResponse)
def song_detail(
    song_id: UUID, account: CurrentAccount, session: DatabaseSession
) -> CatalogSongResponse:
    require_catalog_access(account)
    publication = session.scalar(
        select(SongPublication).where(
            SongPublication.song_id == song_id, SongPublication.active.is_(True)
        )
    )
    if publication is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "歌曲未发布")
    return catalog_song(publication, session)


@router.get("/media/{media_id}")
def read_media(media_id: UUID, account: CurrentAccount, session: DatabaseSession) -> FileResponse:
    if account.role == AccountRole.PARTICIPANT and account.must_change_password:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "必须先修改初始密码")
    media = session.get(MediaFile, media_id)
    if media is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "媒体不存在")
    allowed: object | None = None
    if account.role == AccountRole.PARTICIPANT:
        participant = session.scalar(
            select(Participant).where(Participant.account_id == account.id)
        )
        if participant is None:
            record_audit(
                session,
                actor_account_id=account.id,
                action="media.access_denied",
                object_type="media",
                object_id=media.id,
                detail={"reason": "participant_missing", "purpose": media.purpose},
            )
            session.commit()
            raise HTTPException(status.HTTP_403_FORBIDDEN, "参与者档案不存在")
        if media.purpose == "song_cover":
            allowed = session.scalar(
                select(SongPublication.id)
                .join(Song, Song.id == SongPublication.song_id)
                .where(
                    SongPublication.active.is_(True),
                    Song.cover_media_id == media.id,
                )
            )
        elif media.purpose == "backing_track":
            allowed = session.scalar(
                select(SongPublication.id)
                .join(
                    BackingTrackVersion,
                    BackingTrackVersion.id == SongPublication.backing_track_id,
                )
                .where(
                    SongPublication.active.is_(True),
                    BackingTrackVersion.normalized_media_id == media.id,
                )
            )
        else:
            allowed = None
    elif media.purpose in {
        "song_cover",
        "song_source",
        "original_music",
        "backing_track",
        "separated_vocals",
        "separated_no_vocals",
    }:
        allowed = media.id
    if allowed is None:
        record_audit(
            session,
            actor_account_id=account.id,
            action="media.access_denied",
            object_type="media",
            object_id=media.id,
            detail={"reason": "purpose_requires_audited_endpoint", "purpose": media.purpose},
        )
        session.commit()
        raise HTTPException(status.HTTP_403_FORBIDDEN, "没有访问该媒体的权限")
    path = storage().path(media.storage_key)
    if not path.is_file():
        raise HTTPException(status.HTTP_404_NOT_FOUND, "媒体文件不存在")
    return FileResponse(path, media_type=media.content_type)
