"""人工核验同一音乐标记的起点偏移，绑定到两份不可变媒体回执。"""
from copy import deepcopy
from django.db import transaction
from django.shortcuts import get_object_or_404
from django.utils import timezone
from rest_framework.exceptions import PermissionDenied, ValidationError
from apps.accounts.models import Role
from apps.audit.services import record
from .models import Song
from .reference_pitch_services import asset_fingerprint
from .services import SongStateConflict, validate_source_asset
from .resources import validate_song_resource


def current_alignment(song):
    value=song.playback_alignment
    if not isinstance(value,dict) or type(value.get('offset_ms')) is not int:
        return None
    if (value.get('source_asset_id')!=str(song.source_asset_id) or value.get('accompaniment_asset_id')!=str(song.accompaniment_asset_id)
        or value.get('source_fingerprint')!=asset_fingerprint(song.source_asset) or value.get('accompaniment_fingerprint')!=asset_fingerprint(song.accompaniment_asset)):
        return None
    return deepcopy(value)


@transaction.atomic
def verify_track_alignment(*,actor,song_id,source_marker_ms,accompaniment_marker_ms,evidence,expected_source_fingerprint,expected_accompaniment_fingerprint):
    if not actor.is_active or actor.deleted_at or actor.must_change_password or actor.role not in (Role.SYSTEM_ADMIN,Role.DOCTOR):
        raise PermissionDenied()
    song=get_object_or_404(Song.objects.select_for_update(),pk=song_id,deleted_at__isnull=True)
    if any(type(v) is not int or not 0<=v<=song.duration_seconds*1000 for v in (source_marker_ms,accompaniment_marker_ms)) or not isinstance(evidence,str) or not 5<=len(evidence.strip())<=1000:
        raise ValidationError({'alignment':'须提供有效的同一音乐标记时刻与核验出处'})
    if accompaniment_marker_ms<source_marker_ms:
        raise ValidationError({'alignment':'伴奏缺少开头区间，请先补齐静音后重新核验'})
    if not song.source_asset_id or not song.accompaniment_asset_id:
        raise SongStateConflict('缺少原唱或伴奏资源')
    validate_source_asset(song=song,asset=song.source_asset)
    validate_song_resource(song_id=song.id,asset_id=song.accompaniment_asset_id,media_type='song_accompaniment')
    source=asset_fingerprint(song.source_asset);backing=asset_fingerprint(song.accompaniment_asset)
    if source!=expected_source_fingerprint or backing!=expected_accompaniment_fingerprint:
        raise SongStateConflict('核验期间曲轨回执已变更')
    value={'offset_ms':accompaniment_marker_ms-source_marker_ms,'source_asset_id':str(song.source_asset_id),'accompaniment_asset_id':str(song.accompaniment_asset_id),'source_fingerprint':source,'accompaniment_fingerprint':backing,'source_marker_ms':source_marker_ms,'accompaniment_marker_ms':accompaniment_marker_ms,'evidence':evidence.strip(),'verified_by':str(actor.id),'verified_at':timezone.now().isoformat()}
    song.playback_alignment=value;song.save(update_fields=['playback_alignment','updated_at'])
    record(actor=actor,action='song.alignment_verified',target=song,changes=value,request_id='')
    return deepcopy(value)
