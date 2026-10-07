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


def current_reference_pitch(song, *, ready_only=False):
    rows = SongReferencePitch.objects.filter(song=song).exclude(status='stale')
    if ready_only:
        rows = rows.filter(status='ready')
    vocal_fingerprint = asset_fingerprint(song.vocal_asset)
    for row in rows:
        expected = song.source_receipt_fingerprint if row.document.get('origin', {}).get('type') == 'annotation' else vocal_fingerprint
        if row.input_fingerprint == expected:
            return row
    return None


def reference_pitch_status(song):
    pitch = current_reference_pitch(song)
    return {'status': pitch.status if pitch else 'missing', 'version': str(pitch.id) if pitch else None,
            'note_count': len(pitch.document.get('notes', [])) if pitch else 0}


def read_reference_pitch(*, song_id: UUID, version: UUID | None = None) -> dict:
    if version:
        pitch = get_object_or_404(SongReferencePitch, song_id=song_id, pk=version)
    else:
        pitch = current_reference_pitch(get_object_or_404(Song, pk=song_id), ready_only=True)
    if pitch is None:
        return {'status': 'missing', 'version': None, 'schema_version': 1, 'notes': []}
    return {'status': pitch.status, 'version': str(pitch.id), **pitch.document}


def queue_reference_pitch(song, *, force=False):
    """调用者已在歌曲事务内核验真实人声音轨；同一输入只保持一个运行任务。"""
    from .tasks import generate_reference_pitch_task
    fingerprint = asset_fingerprint(song.vocal_asset)
    rows = SongReferencePitch.objects.filter(song=song, input_asset=song.vocal_asset, input_fingerprint=fingerprint)
    active = rows.filter(status__in=['pending', 'processing']).first()
    if active:
        return active
    latest = rows.exclude(status='stale').first()
    if latest and latest.status == 'ready' and not force:
        return latest
    pitch = SongReferencePitch.objects.create(song=song, input_asset=song.vocal_asset, input_fingerprint=fingerprint)
    transaction.on_commit(lambda: generate_reference_pitch_task.delay(song_id=str(song.id), expected_fingerprint=fingerprint, version=str(pitch.id)), robust=True)
    return pitch


def request_reference_pitch(*, actor, song_id, expected_fingerprint, force=False):
    from .resources import validate_song_resource
    if not actor.is_active or actor.deleted_at or actor.must_change_password or actor.role not in (Role.SYSTEM_ADMIN, Role.DOCTOR):
        raise PermissionDenied()
    with transaction.atomic():
        song = get_object_or_404(Song.objects.select_for_update(), pk=song_id, deleted_at__isnull=True)
        if not song.vocal_asset_id:
            raise SongStateConflict('缺少真实歌曲人声音轨')
        asset = validate_song_resource(song_id=song.id, asset_id=song.vocal_asset_id, media_type='song_vocal')
        if asset_fingerprint(asset) != expected_fingerprint:
            raise SongStateConflict('人声音轨已变更')
        return queue_reference_pitch(song, force=force)


def generate_reference_pitch(*, song_id: UUID, expected_fingerprint: str, version: UUID) -> None:
    import threading
    from datetime import timedelta
    from uuid import uuid4
    from django.db import close_old_connections
    from django.utils import timezone
    from apps.media.readers import read_verified_asset_bytes
    from .reference_pitch_audio import decode_vocal, extract_pitch_notes, SAMPLE_RATE
    from .resources import validate_song_resource
    token = uuid4()
    with transaction.atomic():
        pitch = get_object_or_404(SongReferencePitch.objects.select_for_update(), pk=version, song_id=song_id)
        if pitch.status in ('ready', 'stale', 'failed') or (pitch.lease_until and pitch.lease_until > timezone.now()) or (pitch.next_attempt_at and pitch.next_attempt_at > timezone.now()):
            return
        song = Song.objects.select_for_update().get(pk=song_id)
        if not song.vocal_asset_id or song.vocal_asset_id != pitch.input_asset_id or asset_fingerprint(song.vocal_asset) != expected_fingerprint or pitch.input_fingerprint != expected_fingerprint:
            pitch.status = 'stale'; pitch.save(update_fields=['status']); return
        if pitch.attempt >= 3:
            pitch.status='failed';pitch.save(update_fields=['status']);return
        pitch.status='processing';pitch.attempt += 1;pitch.lease_token=token;pitch.lease_until=timezone.now()+timedelta(seconds=60);pitch.save()
    stopped = threading.Event()
    def heartbeat():
        close_old_connections()
        try:
            while not stopped.wait(10):
                if not SongReferencePitch.objects.filter(pk=version, lease_token=token, status='processing').update(lease_until=timezone.now()+timedelta(seconds=60)):
                    break
        finally:
            close_old_connections()
    worker = threading.Thread(target=heartbeat, daemon=True); worker.start()
    try:
        asset = validate_song_resource(song_id=song_id, asset_id=pitch.input_asset_id, media_type='song_vocal')
        content = read_verified_asset_bytes(asset=asset, max_bytes=50*1024*1024)
        notes = extract_pitch_notes(decode_vocal(content, duration_ms=song.duration_seconds*1000), sample_rate=SAMPLE_RATE, duration_ms=song.duration_seconds*1000)
        document = validate_pitch_document({'schema_version':1, 'origin':{'type':'vocal_yin','fingerprint':expected_fingerprint}, 'notes':notes}, duration_ms=song.duration_seconds*1000)
        with transaction.atomic():
            current = Song.objects.select_for_update().get(pk=song_id)
            row = SongReferencePitch.objects.select_for_update().get(pk=version)
            if row.lease_token != token or row.status != 'processing':
                return
            if current.vocal_asset_id != pitch.input_asset_id or asset_fingerprint(current.vocal_asset) != expected_fingerprint:
                row.status='stale'
            else:
                row.document=document;row.status='ready'
            row.lease_token=None;row.lease_until=None;row.save()
    except Exception as exc:
        SongReferencePitch.objects.filter(pk=version, lease_token=token).update(status='failed' if pitch.attempt >= 3 else 'pending', lease_token=None, lease_until=None, next_attempt_at=timezone.now()+timedelta(seconds=30*pitch.attempt), error_code=type(exc).__name__[:64])
    finally:
        stopped.set(); worker.join(timeout=2)
