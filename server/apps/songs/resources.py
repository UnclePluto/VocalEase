from uuid import UUID

from django.db import transaction
from rest_framework.exceptions import ValidationError

from apps.audit.services import record
from apps.media.models import MediaAsset
from apps.media.services import create_upload_grant

from .models import Song, SongUploadIntent
from .services import SourceAssetInvalid, SourceVerificationTemporary, backend_for_asset, validate_source_asset


RESOURCE_TYPES = {"vocal_asset": "song_vocal", "accompaniment_asset": "song_accompaniment", "lyrics_asset": "lyrics"}


def validate_song_resource(*, song_id: UUID, asset_id: UUID, media_type: str) -> MediaAsset:
    asset = MediaAsset.objects.filter(pk=asset_id, deleted_at__isnull=True).first()
    if not asset or asset.owner_type != "song" or asset.owner_id != song_id or asset.media_type != media_type or asset.status != "ready":
        raise SourceAssetInvalid("歌曲资源不可用", code="song_resource_invalid")
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
    return asset


def issue_optional_song_upload_grant(*, actor, request_id: str, song_id: UUID | None, media_type: str, mime: str, size: int):
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
