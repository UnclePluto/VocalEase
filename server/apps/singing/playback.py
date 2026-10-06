import json
from django.conf import settings
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import PermissionDenied, ValidationError
from apps.accounts.models import Role
from apps.media.backends.local import LocalStorageBackend
from apps.media.services import backend_for_asset
from apps.songs.reference_pitch_services import asset_fingerprint, read_reference_pitch
from apps.songs.resources import validate_song_resource
from apps.songs.services import SongStateConflict
from .models import SingingSession


def snapshot_playback(song):
    from apps.songs.alignment import current_alignment
    reference = read_reference_pitch(song_id=song.id)
    return dict(playback_alignment=current_alignment(song), playback_source_asset=song.source_asset, playback_accompaniment_asset=song.accompaniment_asset, playback_source_fingerprint=asset_fingerprint(song.source_asset), playback_accompaniment_fingerprint=asset_fingerprint(song.accompaniment_asset), reference_version_id=reference['version'] if reference['status']=='ready' else None)


def validate_playback_metadata(value: object, *, session: SingingSession) -> dict | None:
    if value is None:
        return None
    def invalid():
        raise ValidationError({'playback_metadata':'播放时间锚点或绑定资源无效'})
    try:
        if len(json.dumps(value, allow_nan=False, ensure_ascii=False).encode()) > 1024*1024:
            invalid()
    except (TypeError, ValueError):
        invalid()
    keys={'schema_version','sample_rate','source_asset_id','accompaniment_asset_id','reference_version','anchors','mode_changes'}
    if not isinstance(value,dict) or set(value)!=keys or type(value['schema_version']) is not int or value['schema_version']!=1 or type(value['sample_rate']) is not int or value['sample_rate'] not in (44100,48000):
        invalid()
    for name, bound in [('source_asset_id',session.playback_source_asset_id),('accompaniment_asset_id',session.playback_accompaniment_asset_id),('reference_version',session.reference_version_id)]:
        if value[name] != (str(bound) if bound else None):
            invalid()
    anchors=value['anchors'];changes=value['mode_changes']
    if not isinstance(anchors,list) or not 1 <= len(anchors) <= 10000 or not isinstance(changes,list) or len(changes)>1000:
        invalid()
    prior=None;limit=session.song_snapshot.get('duration_seconds',0)*1000
    for anchor in anchors:
        if not isinstance(anchor,dict) or set(anchor)!= {'recording_ms','song_ms','track','playing','segment'}:
            invalid()
        if any(type(anchor[k]) is not int or anchor[k]<0 for k in ('recording_ms','song_ms','segment')) or type(anchor['playing']) is not bool or anchor['track'] not in ('source','accompaniment') or anchor['song_ms']>limit or anchor['recording_ms']>24*60*60*1000:
            invalid()
        if prior and (anchor['recording_ms']<=prior['recording_ms'] or anchor['segment']<prior['segment'] or (anchor['segment']==prior['segment'] and anchor['song_ms']<prior['song_ms'])):
            invalid()
        prior=anchor
    for change in changes:
        if not isinstance(change,dict) or set(change)!= {'recording_ms','track'} or type(change['recording_ms']) is not int or not 0<=change['recording_ms']<=anchors[-1]['recording_ms'] or change['track'] not in ('source','accompaniment'):
            invalid()
    if any(changes[i]['recording_ms'] < changes[i-1]['recording_ms'] for i in range(1,len(changes))):
        invalid()
    return value


def bound_alignment(session):
    value=session.playback_alignment
    if not isinstance(value,dict) or type(value.get('offset_ms')) is not int:
        return None
    if any(value.get(key)!=expected for key,expected in [('source_asset_id',str(session.playback_source_asset_id)),('accompaniment_asset_id',str(session.playback_accompaniment_asset_id)),('source_fingerprint',session.playback_source_fingerprint),('accompaniment_fingerprint',session.playback_accompaniment_fingerprint)]):
        return None
    return value


def playback_description(session):
    alignment=bound_alignment(session)
    return {'alignment_verified':bool(alignment),'accompaniment_offset_ms':alignment['offset_ms'] if alignment else None,'source_asset_id':str(session.playback_source_asset_id) if session.playback_source_asset_id else None,'accompaniment_asset_id':str(session.playback_accompaniment_asset_id) if session.playback_accompaniment_asset_id else None,'reference_version':str(session.reference_version_id) if session.reference_version_id else None,'combined_available':bool(alignment and session.playback_accompaniment_asset_id and session.playback_metadata and session.playback_metadata.get('anchors')),'metadata':session.playback_metadata}


def authorize_session_song(*, actor, session_id, track, request_id):
    if track not in ('source','accompaniment'):
        raise ValidationError({'track':'请选择原唱或伴奏'})
    session=get_object_or_404(SingingSession.objects.select_related('patient','playback_source_asset','playback_accompaniment_asset'),pk=session_id)
    if not actor.is_active or actor.deleted_at or actor.must_change_password or actor.role not in (Role.PATIENT,Role.DOCTOR,Role.SYSTEM_ADMIN):
        raise PermissionDenied()
    if actor.role == Role.PATIENT and session.patient.user_id != actor.id:
        raise PermissionDenied()
    if track=='accompaniment' and bound_alignment(session) is None:
        raise SongStateConflict('会话伴奏起点尚未核验')
    asset=getattr(session, f'playback_{track}_asset')
    if not asset:
        raise SongStateConflict('此会话缺少可信歌曲音轨绑定')
    if asset_fingerprint(asset) != getattr(session,f'playback_{track}_fingerprint'):
        raise SongStateConflict('会话音轨回执已变更')
    validate_song_resource(song_id=session.song_id,asset_id=asset.id,media_type='song_source' if track=='source' else 'song_accompaniment')
    backend=backend_for_asset(asset)
    grant=backend.create_private_url(asset.object_key,ttl_seconds=settings.MEDIA_PRIVATE_URL_TTL_SECONDS,**({'asset_id':asset.id,'expected_generation':asset.manifest_generation} if isinstance(backend,LocalStorageBackend) else {}))
    from apps.audit.services import record
    record(actor=actor,action='singing.playback_authorized',target=session,changes={'asset_id':str(asset.id),'track':track},request_id=request_id)
    return {'asset_id':str(asset.id),'url':grant.url,'expires_at':grant.expires_at.isoformat()}
