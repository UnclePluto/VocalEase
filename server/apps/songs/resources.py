from uuid import UUID

from django.db import transaction
from django.conf import settings
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import ValidationError

from apps.audit.services import record
from apps.media.models import MediaAsset
from apps.media.services import create_upload_grant

from .models import Song, SongUploadIntent
from .services import SourceAssetInvalid, SourceVerificationTemporary, SongStateConflict, backend_for_asset, preview_source


RESOURCE_TYPES = {"vocal_asset": "song_vocal", "accompaniment_asset": "song_accompaniment", "lyrics_asset": "lyrics"}


def _validated_song_resource(*, song_id: UUID, asset_id: UUID, media_type: str) -> tuple[MediaAsset, list[dict] | None]:
    asset = MediaAsset.objects.filter(pk=asset_id, deleted_at__isnull=True).first()
    if not asset or asset.owner_type != "song" or asset.owner_id != song_id or asset.media_type != media_type or asset.status != "ready":
        raise SourceAssetInvalid("歌曲资源不可用", code="song_resource_invalid")
    if media_type == "lyrics":
        allowed_mimes = {"text/plain", "application/octet-stream"} if asset.backend == "qiniu" else {"text/plain"}
        if asset.mime not in allowed_mimes or asset.size > 1024 * 1024:
            raise SourceAssetInvalid("歌词类型或大小不符合人工上传要求", code="song_resource_invalid")
    try:
        metadata = backend_for_asset(asset).stat(asset.object_key)
    except Exception as exc:
        raise SourceVerificationTemporary("媒体存储暂时不可用") from exc
    if metadata.object_key != asset.object_key or metadata.size != asset.size or metadata.mime != asset.mime:
        raise SourceAssetInvalid("歌曲资源验证失败", code="song_resource_invalid")
    if asset.backend == "local" and (not asset.sha256 or asset.sha256 != metadata.sha256 or asset.manifest_generation != metadata.generation):
        raise SourceAssetInvalid("歌曲资源验证失败", code="song_resource_invalid")
    if asset.backend == "qiniu" and (not asset.etag or asset.etag != metadata.etag):
        raise SourceAssetInvalid("歌曲资源验证失败", code="song_resource_invalid")
    if media_type == "lyrics":
        from apps.media.readers import read_verified_asset_bytes
        from .lyrics import parse_lrc
        from apps.media.contracts import StorageValidationError
        try:
            lines = parse_lrc(read_verified_asset_bytes(asset=asset, max_bytes=1024 * 1024))
        except Exception as exc:
            if isinstance(exc, (ValidationError, SourceAssetInvalid, SourceVerificationTemporary)):
                raise
            if isinstance(exc, StorageValidationError) and "回执不一致" in str(exc):
                raise SourceAssetInvalid("歌词内容与可信回执不一致", code="song_resource_invalid") from exc
            raise SourceVerificationTemporary("歌词存储暂时不可用") from exc
        return asset, lines
    return asset, None


def validate_singing_accompaniment(*, song: Song) -> MediaAsset:
    if not song.accompaniment_asset_id:
        raise SourceAssetInvalid("歌曲缺少可用伴奏", code="song_resource_invalid")
    return validate_song_resource(
        song_id=song.id, asset_id=song.accompaniment_asset_id,
        media_type="song_accompaniment",
    )


def validate_song_resource(*, song_id: UUID, asset_id: UUID, media_type: str) -> MediaAsset:
    return _validated_song_resource(song_id=song_id, asset_id=asset_id, media_type=media_type)[0]


def issue_optional_song_upload_grant(*, actor, request_id: str, media_type: str, mime: str, size: int, song_id: UUID | None = None):
    if song_id is None:
        raise ValidationError({"song_id": "上传选填资源前须先取得歌曲标识"})
    song = Song.objects.filter(pk=song_id, deleted_at__isnull=True).first()
    if song is None and not SongUploadIntent.objects.filter(song_id=song_id).exists():
        raise ValidationError({"song_id": "歌曲或上传意图不存在"})
    if song is not None and song.ingestion_mode != "manual":
        raise ValidationError({"song_id": "仅人工歌曲可补传资源"})
    asset, grant = create_upload_grant(owner_type="song", owner_id=song_id, media_type=media_type, mime=mime, size=size)
    record(actor=actor, action="song.resource_upload_grant", target=asset, changes={"song_id": str(song_id), "media_type": media_type}, request_id=request_id)
    return song_id, asset, grant


def update_song_resources(*, actor, request_id: str, song: Song, updates: dict, expected: dict) -> Song:
    if not updates or set(updates) - RESOURCE_TYPES.keys() or set(expected) != set(updates):
        raise ValidationError({"resources": "资源字段或预期旧值无效"})
    with transaction.atomic():
        locked = Song.objects.select_for_update().get(pk=song.pk, deleted_at__isnull=True)
        if locked.ingestion_mode != Song.IngestionMode.MANUAL:
            raise SongStateConflict("仅人工歌曲支持维护资源")
        for field, new_id in updates.items():
            if new_id is None or str(getattr(locked, f"{field}_id") or "") != str(expected[field] or ""):
                raise SongStateConflict("歌曲资源已被其他管理员修改", code="song_resource_conflict")
            setattr(locked, field, validate_song_resource(song_id=locked.id, asset_id=new_id, media_type=RESOURCE_TYPES[field]))
        locked.save(update_fields=[*updates, "updated_at"])
        if "vocal_asset" in updates:
            from .models import SongReferencePitch
            SongReferencePitch.objects.filter(song=locked, status__in=['pending','processing']).update(status='stale')
            from .reference_pitch_services import queue_reference_pitch
            queue_reference_pitch(locked)
        record(actor=actor, action="song.resources_update", target=locked, changes={field: {"from": str(expected[field]) if expected[field] else None, "to": str(value)} for field, value in updates.items()}, request_id=request_id)
        return locked


def preview_song_resource(*, actor, request_id: str, song: Song, track: str):
    if track == "source":
        return preview_source(actor=actor, request_id=request_id, song=song)
    field = {"vocal": "vocal_asset", "accompaniment": "accompaniment_asset"}.get(track)
    if field is None:
        raise ValidationError({"track": "不支持的音轨"})
    asset_id = getattr(song, f"{field}_id")
    if not asset_id:
        from django.http import Http404
        raise Http404
    asset = validate_song_resource(song_id=song.id, asset_id=asset_id, media_type=RESOURCE_TYPES[field])
    backend = backend_for_asset(asset)
    from apps.media.backends.local import LocalStorageBackend
    private = backend.create_private_url(asset.object_key, ttl_seconds=settings.MEDIA_PRIVATE_URL_TTL_SECONDS, **({"asset_id": asset.id, "expected_generation": asset.manifest_generation} if isinstance(backend, LocalStorageBackend) else {}))
    record(actor=actor, action="song.preview_authorized", target=song, changes={"asset_id": str(asset.id), "media_type": asset.media_type}, request_id=request_id)
    return private


def read_song_lyrics(*, song: Song) -> list[dict]:
    if not song.lyrics_asset_id:
        from django.http import Http404
        raise Http404
    _, lines = _validated_song_resource(song_id=song.id, asset_id=song.lyrics_asset_id, media_type="lyrics")
    return lines or []
