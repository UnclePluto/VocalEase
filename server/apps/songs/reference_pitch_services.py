import hashlib
import json
from uuid import UUID
from django.db import transaction
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import PermissionDenied
from apps.accounts.models import Role
from .models import Song, SongReferencePitch
from .reference_pitch import validate_pitch_document
from .services import SongStateConflict


def asset_fingerprint(asset):
    if asset is None:
        return ''
    return hashlib.sha256(json.dumps([str(asset.id), asset.backend, asset.object_key, asset.size, asset.mime, asset.sha256, asset.etag, asset.manifest_generation], separators=(',', ':')).encode()).hexdigest()


@transaction.atomic
def import_reference_pitch(*, actor, song_id: UUID, document: dict, expected_fingerprint: str) -> SongReferencePitch:
    if not actor.is_active or actor.deleted_at or actor.must_change_password or actor.role not in (Role.SYSTEM_ADMIN, Role.DOCTOR):
        raise PermissionDenied()
    song = get_object_or_404(Song.objects.select_for_update(), pk=song_id, deleted_at__isnull=True)
    if song.source_receipt_fingerprint != expected_fingerprint:
        raise SongStateConflict('歌曲输入已变更')
    data = validate_pitch_document(document, duration_ms=song.duration_seconds * 1000)
    if data['origin']['type'] != 'annotation':
        from rest_framework.exceptions import ValidationError
        raise ValidationError({'origin': '导入须注明可信标注出处；自动生成请使用生成接口'})
    return SongReferencePitch.objects.create(song=song, status='ready', input_asset=song.source_asset, input_fingerprint=expected_fingerprint, document=data)


def read_reference_pitch(*, song_id: UUID, version: UUID | None = None) -> dict:
    query = SongReferencePitch.objects.filter(song_id=song_id)
    pitch = get_object_or_404(query, pk=version) if version else query.filter(status='ready').first()
    if pitch is None:
        return {'status': 'missing', 'version': None, 'schema_version': 1, 'notes': []}
    return {'status': pitch.status, 'version': str(pitch.id), **pitch.document}
