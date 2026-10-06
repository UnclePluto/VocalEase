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
def test_session_keeps_original_resources_after_song_edit(patient, tmp_path, settings):
    song=ready_song(tmp_path, settings)
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
