from copy import deepcopy
import pytest
from django.utils import timezone
from rest_framework.test import APIClient
from rest_framework.exceptions import ValidationError
from apps.singing.tests.test_patient_api import doctor, patient, ready_song
from apps.singing.tests.test_submission_idempotency import uploaded_session
from apps.singing.services import create_session


def metadata(session):
    return {'schema_version':1, 'sample_rate':48000, 'source_asset_id':str(session.playback_source_asset_id), 'accompaniment_asset_id':str(session.playback_accompaniment_asset_id), 'reference_version':None, 'anchors':[{'recording_ms':0,'song_ms':0,'track':'accompaniment','playing':True,'segment':0}], 'mode_changes':[]}

@pytest.mark.django_db
def test_session_keeps_original_resources_after_song_edit(patient, doctor, tmp_path, settings):
    song=ready_song(tmp_path, settings)
    doctor.user.must_change_password=False;doctor.user.save(update_fields=["must_change_password"])
    from apps.songs.alignment import verify_track_alignment
    from apps.songs.reference_pitch_services import asset_fingerprint
    verify_track_alignment(actor=doctor.user,song_id=song.id,source_marker_ms=0,accompaniment_marker_ms=0,evidence='合同夹具同起点标记',expected_source_fingerprint=asset_fingerprint(song.source_asset),expected_accompaniment_fingerprint=asset_fingerprint(song.accompaniment_asset))
    session=create_session(patient_id=patient.id, song_id=song.id, idempotency_key='snapshot').session
    assert session.playback_source_asset_id == song.source_asset_id
    original = song.accompaniment_asset_id
    song.accompaniment_asset=None; song.deleted_at=timezone.now(); song.save(update_fields=['accompaniment_asset','deleted_at'])
    client=APIClient(); client.force_authenticate(patient.user)
    response=client.post(f'/api/v1/patient/singing-sessions/{session.id}/song-playback/?track=accompaniment')
    assert response.status_code == 200, response.content
    assert response.data['data']['asset_id'] == str(original)
    session.refresh_from_db()
    assert session.playback_accompaniment_asset_id == original

@pytest.mark.django_db
def test_submit_metadata_is_idempotent():
    patient, session=uploaded_session()
    session.playback_source_asset=session.song.source_asset;session.playback_accompaniment_asset=session.song.source_asset;session.save()
    data=metadata(session)
    client=APIClient(); client.force_authenticate(patient.user)
    url=f'/api/v1/patient/singing-sessions/{session.id}/submit/'
    first=client.post(url, {'playback_metadata':data}, format='json', HTTP_IDEMPOTENCY_KEY='body')
    assert first.status_code == 202, first.content
    assert client.post(url, {'playback_metadata':data}, format='json', HTTP_IDEMPOTENCY_KEY='body').status_code == 200
    data['sample_rate']=44100
    assert client.post(url, {'playback_metadata':data}, format='json', HTTP_IDEMPOTENCY_KEY='body').status_code == 409

@pytest.mark.django_db
def test_old_submit_and_old_session():
    from apps.singing.serializers import SingingSessionReadSerializer
    patient, session=uploaded_session()
    assert SingingSessionReadSerializer(session).data['playback']['combined_available'] is False
    client=APIClient();client.force_authenticate(patient.user)
    assert client.post(f'/api/v1/patient/singing-sessions/{session.id}/submit/', {}, format='json', HTTP_IDEMPOTENCY_KEY='old').status_code == 202

@pytest.mark.django_db
def test_snapshot_grant_rechecks_permissions_and_receipt(patient, tmp_path, settings):
    song=ready_song(tmp_path,settings)
    session=create_session(patient_id=patient.id,song_id=song.id,idempotency_key='grant').session
    client=APIClient()
    assert client.post(f'/api/v1/patient/singing-sessions/{session.id}/song-playback/').status_code == 401
    client.force_authenticate(patient.user)
    song.accompaniment_asset.sha256='f'*64;song.accompaniment_asset.save(update_fields=['sha256'])
    assert client.post(f'/api/v1/patient/singing-sessions/{session.id}/song-playback/?track=accompaniment').status_code == 409

@pytest.mark.django_db
def test_metadata_rejects_invalid_boundaries():
    from apps.singing.playback import validate_playback_metadata
    _,session=uploaded_session()
    session.playback_source_asset=session.song.source_asset;session.playback_accompaniment_asset=session.song.source_asset
    base=metadata(session)
    invalid=[]
    d=deepcopy(base);d['anchors'][0]['song_ms']=float('nan');invalid.append(d)
    d=deepcopy(base);d['anchors'] *= 10001;invalid.append(d)
    d=deepcopy(base);d['mode_changes']=[{}]*1001;invalid.append(d)
    d=deepcopy(base);d['extra']='x'*(1024*1024);invalid.append(d)
    for data in invalid:
        with pytest.raises(ValidationError):
            validate_playback_metadata(data,session=session)

@pytest.mark.django_db
def test_unverified_tracks_never_enable_combined(patient,tmp_path,settings):
    from apps.singing.playback import playback_description
    song=ready_song(tmp_path,settings)
    session=create_session(patient_id=patient.id,song_id=song.id,idempotency_key='unaligned').session
    session.playback_metadata=metadata(session)
    assert playback_description(session)['combined_available'] is False

@pytest.mark.django_db
def test_verified_offset_is_fixed_and_receipt_change_invalidates(patient,doctor,tmp_path,settings):
    from apps.songs.alignment import verify_track_alignment,current_alignment
    from apps.songs.reference_pitch_services import asset_fingerprint
    from apps.singing.playback import playback_description
    song=ready_song(tmp_path,settings)
    doctor.user.must_change_password=False;doctor.user.save(update_fields=["must_change_password"])
    alignment=verify_track_alignment(actor=doctor.user,song_id=song.id,source_marker_ms=1000,accompaniment_marker_ms=3000,evidence='同一鼓点已试听核验',expected_source_fingerprint=asset_fingerprint(song.source_asset),expected_accompaniment_fingerprint=asset_fingerprint(song.accompaniment_asset))
    assert alignment['offset_ms']==2000
    session=create_session(patient_id=patient.id,song_id=song.id,idempotency_key='aligned').session
    session.playback_metadata=metadata(session)
    assert playback_description(session)['combined_available'] is True
    assert playback_description(session)['accompaniment_offset_ms']==2000
    song.accompaniment_asset.sha256='f'*64;song.accompaniment_asset.save(update_fields=['sha256'])
    song.refresh_from_db()
    assert current_alignment(song) is None
    session.refresh_from_db()
    assert session.playback_alignment['offset_ms']==2000

@pytest.mark.django_db
def test_alignment_api_checks_operator_receipt_and_missing_intro(patient,doctor,tmp_path,settings):
    from apps.songs.reference_pitch_services import asset_fingerprint
    song=ready_song(tmp_path,settings)
    payload={'source_marker_ms':1000,'accompaniment_marker_ms':3000,'evidence':'同一鼓点已试听核验','expected_source_fingerprint':asset_fingerprint(song.source_asset),'expected_accompaniment_fingerprint':asset_fingerprint(song.accompaniment_asset)}
    client=APIClient();url=f'/api/v1/admin/songs/{song.id}/track-alignment/'
    client.force_authenticate(patient.user)
    assert client.post(url,payload,format='json').status_code==403
    doctor.user.must_change_password=False;doctor.user.save(update_fields=['must_change_password'])
    client.force_authenticate(doctor.user)
    bad={**payload,'expected_source_fingerprint':'f'*64}
    assert client.post(url,bad,format='json').status_code==409
    bad={**payload,'source_marker_ms':3000,'accompaniment_marker_ms':1000}
    assert client.post(url,bad,format='json').status_code==400
    response=client.post(url,payload,format='json')
    assert response.status_code==200,response.content
    assert response.data['data']['offset_ms']==2000

@pytest.mark.django_db
def test_patient_can_preview_unaligned_accompaniment(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    client = APIClient(); client.force_authenticate(patient.user)
    response = client.post(f'/api/v1/patient/songs/{song.id}/preview/?track=accompaniment')
    assert response.status_code == 200, response.content
    assert response.data['data']['alignment_verified'] is False
    assert response.data['data']['accompaniment_offset_ms'] is None
    assert response.data['data']['url']

@pytest.mark.django_db
def test_history_accompaniment_preview_is_explicit_and_does_not_forge_snapshot(patient, doctor, tmp_path, settings):
    from apps.singing.models import SingingSession
    song = ready_song(tmp_path, settings)
    session = SingingSession.objects.create_from_snapshots(patient=patient, song=song)
    doctor.user.must_change_password = False; doctor.user.save(update_fields=['must_change_password'])
    client = APIClient(); client.force_authenticate(doctor.user)
    url = f'/api/v1/admin/singing-sessions/{session.id}/playback-accompaniment/'
    assert client.post(url).status_code == 409
    response = client.post(url + f'?preview=true&expected_asset_id={song.accompaniment_asset_id}')
    assert response.status_code == 200, response.content
    assert response.data['data']['asset_id'] == str(song.accompaniment_asset_id)
    session.refresh_from_db()
    assert session.playback_metadata is None
    assert session.playback_accompaniment_asset_id is None
    client.force_authenticate(patient.user)
    assert client.post(url + '?preview=true').status_code == 403

@pytest.mark.django_db
def test_recording_can_play_bound_unaligned_accompaniment(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    session = create_session(patient_id=patient.id, song_id=song.id, idempotency_key='unaligned').session
    client = APIClient(); client.force_authenticate(patient.user)
    response = client.post(f'/api/v1/patient/singing-sessions/{session.id}/song-playback/?track=accompaniment')
    assert response.status_code == 200, response.content
    assert response.data['data']['asset_id'] == str(song.accompaniment_asset_id)
    assert session.playback_alignment is None

@pytest.mark.django_db
def test_history_preview_rejects_changed_asset_and_deleted_song(patient, doctor, tmp_path, settings):
    from apps.singing.models import SingingSession
    song = ready_song(tmp_path, settings)
    session = SingingSession.objects.create_from_snapshots(patient=patient, song=song)
    doctor.user.must_change_password = False; doctor.user.save(update_fields=['must_change_password'])
    client = APIClient(); client.force_authenticate(doctor.user)
    url = f'/api/v1/admin/singing-sessions/{session.id}/playback-accompaniment/?preview=true'
    assert client.post(url + '&expected_asset_id=00000000-0000-4000-8000-000000000001').status_code == 409
    song.deleted_at = timezone.now(); song.save(update_fields=['deleted_at'])
    assert client.post(url + f'&expected_asset_id={song.accompaniment_asset_id}').status_code == 409

@pytest.mark.django_db
def test_longer_unaligned_accompaniment_does_not_reject_submission():
    patient, session = uploaded_session()
    session.playback_source_asset = session.song.source_asset
    session.playback_accompaniment_asset = session.song.source_asset
    session.save()
    data = metadata(session)
    end = session.song_snapshot['duration_seconds'] * 1000 + 2000
    data['anchors'].append({'recording_ms':end,'song_ms':end,'track':'accompaniment','playing':False,'segment':0})
    client = APIClient(); client.force_authenticate(patient.user)
    response = client.post(f'/api/v1/patient/singing-sessions/{session.id}/submit/', {'playback_metadata':data}, format='json', HTTP_IDEMPOTENCY_KEY='longer-backing')
    assert response.status_code == 202, response.content
    session.refresh_from_db()
    assert session.playback_metadata['anchors'][-1]['song_ms'] == end
    assert session.playback_alignment is None

@pytest.mark.django_db
def test_native_accompaniment_timeline_still_has_bounded_duration():
    from apps.singing.playback import validate_playback_metadata
    _, session = uploaded_session()
    session.playback_source_asset = session.song.source_asset
    session.playback_accompaniment_asset = session.song.source_asset
    data = metadata(session)
    data['anchors'][0]['song_ms'] = 24 * 60 * 60 * 1000 + 1
    with pytest.raises(ValidationError):
        validate_playback_metadata(data, session=session)
