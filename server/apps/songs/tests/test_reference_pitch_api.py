import pytest
from rest_framework.test import APIClient
from rest_framework.exceptions import ValidationError
from apps.accounts.models import Role, User
from apps.songs.models import Song

DOCUMENT = {'schema_version': 1, 'origin': {'type': 'annotation', 'citation': '人工校准乐谱 2026-10-06'}, 'notes': [{'start_ms': 0, 'end_ms': 1000, 'midi_note': 57, 'confidence': 1}]}

@pytest.mark.django_db
def test_reference_pitch_import_and_read():
    admin = User.objects.create_user(login_id='pitch-admin', password='888888', role=Role.SYSTEM_ADMIN, must_change_password=False)
    patient = User.objects.create_user(login_id='pitch-patient', password='888888', role=Role.PATIENT, must_change_password=False)
    song = Song.objects.create(title='校准', artist='歌手', genre='流行', language='中文', duration_seconds=10)
    client = APIClient(); client.force_authenticate(admin)
    response = client.post(f'/api/v1/admin/songs/{song.id}/reference-pitch/', {'document': DOCUMENT, 'expected_fingerprint': ''}, format='json')
    assert response.status_code == 200, response.content
    version = response.data['data']['version']
    client.force_authenticate(patient)
    response = client.get(f'/api/v1/patient/songs/{song.id}/reference-pitch/')
    assert response.status_code == 200
    assert response.data['data']['version'] == version
    assert response.data['data']['notes'][0]['midi_note'] == 57
    assert client.post(f'/api/v1/admin/songs/{song.id}/reference-pitch/', {}, format='json').status_code == 403
    client.force_authenticate(None)
    assert client.get(f'/api/v1/patient/songs/{song.id}/reference-pitch/').status_code == 401

@pytest.mark.parametrize('patch', [{'start_ms': -1}, {'start_ms': True}, {'end_ms': 11000}, {'midi_note': 128}, {'confidence': float('nan')}, {'confidence': -1}])
def test_reference_pitch_rejects_invalid_document(patch):
    from apps.songs.reference_pitch import validate_pitch_document
    with pytest.raises(ValidationError):
        validate_pitch_document({**DOCUMENT, 'notes': [{**DOCUMENT['notes'][0], **patch}]}, duration_ms=10000)

def test_reference_pitch_rejects_overlap_and_limits():
    from apps.songs.reference_pitch import validate_pitch_document
    for notes in [[DOCUMENT['notes'][0]] * 2, [DOCUMENT['notes'][0]] * 100001]:
        with pytest.raises(ValidationError):
            validate_pitch_document({**DOCUMENT, 'notes': notes}, duration_ms=10000)
    with pytest.raises(ValidationError):
        validate_pitch_document({**DOCUMENT, 'origin': {'type':'annotation', 'citation':'x'*(10*1024*1024)}}, duration_ms=10000)

@pytest.mark.django_db
def test_admin_can_read_reference_generation_status_but_patient_cannot():
    admin = User.objects.create_user(login_id='pitch-read-admin', password='888888', role=Role.SYSTEM_ADMIN, must_change_password=False)
    patient = User.objects.create_user(login_id='pitch-read-patient', password='888888', role=Role.PATIENT, must_change_password=False)
    song = Song.objects.create(title='校准状态', artist='歌手', genre='流行', language='中文', duration_seconds=10)
    client = APIClient(); client.force_authenticate(admin)
    url=f'/api/v1/admin/songs/{song.id}/reference-pitch/'
    response=client.get(url)
    assert response.status_code == 200
    assert response.data['data']['status'] == 'missing'
    client.force_authenticate(patient)
    assert client.get(url).status_code == 403

@pytest.mark.django_db
def test_admin_song_exposes_reference_status():
    from apps.songs.serializers import SongReadSerializer, PatientSongReadSerializer
    from apps.songs.models import SongReferencePitch
    song=Song.objects.create(title='状态',artist='a',genre='a',language='中文',duration_seconds=10)
    SongReferencePitch.objects.create(song=song,status='pending',input_fingerprint='')
    assert SongReadSerializer(song).data['reference_pitch']['status'] == 'pending'
    assert 'reference_pitch' not in PatientSongReadSerializer(song).data
