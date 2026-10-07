import pytest
from rest_framework.test import APIClient

from apps.singing.tests.test_patient_api import doctor, patient, ready_song  # noqa: F401
from apps.accounts.models import User, Role


@pytest.mark.django_db
def test_patient_reads_uploaded_lrc_without_storage_url(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    song.ingestion_mode = "manual"
    song.save(update_fields=["ingestion_mode"])
    admin = User.objects.create_user(login_id="lyrics-admin", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)
    client = APIClient()
    client.force_authenticate(admin)
    content = "[offset:100]\n[00:01.00]第一句\n[00:03.50]第二句".encode()
    grant = client.post("/api/v1/admin/songs/upload-grants/", {
        "song_id": str(song.id), "media_type": "lyrics", "mime": "text/plain", "size": len(content),
    }, format="json").json()["data"]
    assert client.put(grant["upload_url"], content, content_type="text/plain").status_code == 204
    assert client.post(f"/api/v1/admin/media/{grant['asset_id']}/complete/", {}, format="json").status_code == 200
    assert client.patch(f"/api/v1/admin/songs/{song.id}/resources/", {
        "updates": {"lyrics_asset": grant["asset_id"]}, "expected": {"lyrics_asset": None},
    }, format="json").status_code == 200
    client.force_authenticate(patient.user)
    response = client.get(f"/api/v1/patient/songs/{song.id}/lyrics/")
    assert response.status_code == 200, response.content
    assert response.json()["data"] == {"lines": [{"time_ms": 1100, "text": "第一句"}, {"time_ms": 3600, "text": "第二句"}]}
    client.force_authenticate(None)
    assert client.get(f"/api/v1/patient/songs/{song.id}/lyrics/").status_code == 401


@pytest.mark.django_db
def test_missing_lyrics_is_empty_but_unplayable_song_is_hidden(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    client = APIClient()
    client.force_authenticate(patient.user)
    response = client.get(f"/api/v1/patient/songs/{song.id}/lyrics/")
    assert response.status_code == 200, response.content
    assert response.json()["data"] == {"lines": []}
    song.accompaniment_asset = None
    song.save(update_fields=["accompaniment_asset"])
    assert client.get(f"/api/v1/patient/songs/{song.id}/lyrics/").status_code == 404


@pytest.mark.django_db
@pytest.mark.parametrize("invalid", ["deleted", "password_change", "wrong_role"])
def test_lyrics_respects_patient_access(patient, tmp_path, settings, invalid):
    song = ready_song(tmp_path, settings)
    if invalid == "deleted":
        from django.utils import timezone
        song.deleted_at = timezone.now()
        song.save(update_fields=["deleted_at"])
    elif invalid == "password_change":
        patient.user.must_change_password = True
        patient.user.save(update_fields=["must_change_password"])
    else:
        patient.user.role = Role.DOCTOR
        patient.user.save(update_fields=["role"])
    client = APIClient()
    client.force_authenticate(patient.user)
    assert client.get(f"/api/v1/patient/songs/{song.id}/lyrics/").status_code == (404 if invalid == "deleted" else 403)
