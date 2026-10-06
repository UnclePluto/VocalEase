import pytest
from rest_framework.test import APIClient
from django.utils import timezone
from apps.patients.models import TreatmentPlan
from apps.singing.models import SingingSession
from apps.songs.models import Song
from .test_patient_api import doctor, patient, other_patient, ready_song  # noqa: F401


@pytest.mark.django_db
@pytest.mark.parametrize('plan_status', ['pending', 'completed', 'cancelled', None])
def test_free_singing_with_complete_tracks_does_not_require_plan_or_publication(patient, doctor, tmp_path, settings, plan_status):
    if plan_status is None:
        patient.treatment_plans.all().delete()
    else:
        patient.treatment_plans.update(status=plan_status)
    song = ready_song(tmp_path, settings)
    from apps.songs.alignment import verify_track_alignment
    from apps.songs.reference_pitch_services import asset_fingerprint
    doctor.user.must_change_password=False;doctor.user.save(update_fields=['must_change_password'])
    verify_track_alignment(actor=doctor.user,song_id=song.id,source_marker_ms=0,accompaniment_marker_ms=0,evidence='合同夹具同起点标记',expected_source_fingerprint=asset_fingerprint(song.source_asset),expected_accompaniment_fingerprint=asset_fingerprint(song.accompaniment_asset))
    Song.objects.filter(pk=song.pk).update(publication_status='draft')
    client = APIClient()
    client.force_authenticate(patient.user)
    assert client.get('/api/v1/patient/songs/').json()['data']['count'] == 1
    assert client.get(f'/api/v1/patient/songs/{song.id}/').status_code == 200
    for track in ['source', 'accompaniment']:
        preview = client.post(f'/api/v1/patient/songs/{song.id}/preview/?track={track}')
        assert preview.status_code == 200, preview.content
    response = client.post('/api/v1/patient/singing-sessions/', {'song_id': str(song.id)}, format='json', HTTP_IDEMPOTENCY_KEY='free-singing')
    assert response.status_code == 201, response.content
    session = SingingSession.objects.get(pk=response.json()['data']['id'])
    assert session.treatment_plan_id is None
    assert response.json()['data']['treatment_plan'] is None
    assert client.get(f'/api/v1/patient/singing-sessions/{session.id}/').json()['data']['treatment_plan'] is None
    assert client.get('/api/v1/patient/singing-sessions/').json()['data']['results'][0]['treatment_plan'] is None
    retry = client.post('/api/v1/patient/singing-sessions/', {'song_id': str(song.id)}, format='json', HTTP_IDEMPOTENCY_KEY='free-singing')
    assert retry.json()['data']['id'] == str(session.id)


@pytest.mark.django_db
@pytest.mark.parametrize('invalid', ['missing', 'deleted', 'pending', 'wrong_owner', 'wrong_type'])
def test_incomplete_accompaniment_is_unavailable_in_catalog_detail_and_creation(patient, tmp_path, settings, invalid):
    song = ready_song(tmp_path, settings)
    asset = song.accompaniment_asset
    if invalid == 'missing':
        song.accompaniment_asset = None
        song.save(update_fields=['accompaniment_asset'])
    else:
        if invalid == 'deleted': asset.deleted_at = timezone.now()
        if invalid == 'pending': asset.status = 'uploading'
        if invalid == 'wrong_owner': asset.owner_id = patient.pk
        if invalid == 'wrong_type': asset.media_type = 'song_vocal'
        asset.save()
    client = APIClient(); client.force_authenticate(patient.user)
    assert client.get('/api/v1/patient/songs/').json()['data']['count'] == 0
    assert client.get(f'/api/v1/patient/songs/{song.id}/').status_code == 404
    response = client.post('/api/v1/patient/singing-sessions/', {'song_id': str(song.id)}, format='json', HTTP_IDEMPOTENCY_KEY='invalid-track')
    assert response.status_code == 400


@pytest.mark.django_db
def test_phone_login_uses_existing_patient_password_and_rejects_ambiguity(patient, other_patient):
    client = APIClient()
    def login(phone, password='888888'):
        return client.post('/api/v1/auth/login/', {'login_id': phone, 'password': password, 'client_kind': 'android'}, format='json')
    assert login(patient.phone).status_code == 200
    assert login(patient.phone, 'wrong').status_code == 401
    assert login(' '+patient.phone[:3]+'-'+patient.phone[3:]+' ').status_code == 200
    other_patient.phone = patient.phone
    other_patient.save(update_fields=['phone'])
    assert login(patient.phone).status_code == 401
    other_patient.phone = '13500000999'; other_patient.save(update_fields=['phone'])
    patient.user.is_active = False; patient.user.save(update_fields=['is_active'])
    assert login(patient.phone).status_code == 401
