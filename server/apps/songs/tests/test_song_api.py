import hashlib

import pytest
from django.db import IntegrityError, transaction
from django.test import override_settings
from rest_framework.test import APIClient

from apps.accounts.models import Role, User
from apps.analysis.models import AnalysisTask
from apps.media.models import MediaAsset
from apps.songs.models import Song


@pytest.fixture
def admin_user(db):
    return User.objects.create_user(login_id="song-admin", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_song_upload_intent_then_create_and_patient_catalog(tmp_path, settings, admin_user):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    client = APIClient()
    client.force_authenticate(admin_user)
    grant_response = client.post("/api/v1/admin/songs/upload-grants/", {"mime": "audio/mpeg", "size": 6}, format="json")
    assert grant_response.status_code == 201
    grant = grant_response.json()["data"]
    assert grant["song_id"] == grant["owner_id"]
    assert client.put(grant["upload_url"], b"source", content_type="audio/mpeg").status_code == 204
    assert client.post(f"/api/v1/admin/media/{grant['asset_id']}/complete/", {}, format="json").status_code == 200
    create = client.post("/api/v1/admin/songs/", {"id": grant["song_id"], "title": "新歌", "artist": "歌手", "genre": "流行", "language": "中文", "duration_seconds": 60, "source_asset": grant["asset_id"]}, format="json")
    assert create.status_code == 201
    assert create.json()["data"]["source_asset"] == grant["asset_id"]

    response = client.post(f"/api/v1/admin/songs/{grant['song_id']}/reanalyze/", {"task_type": "vocal_separation"}, format="json")
    assert response.status_code == 202
    assert AnalysisTask.objects.filter(song_id=grant["song_id"]).count() == 1


@pytest.mark.django_db
def test_deleted_song_cannot_be_published_and_patient_cannot_access_admin(admin_user):
    song = Song.objects.create(title="已删除", artist="歌手", genre="流行", language="中文", duration_seconds=60, deleted_at="2026-08-14T00:00:00Z")
    client = APIClient()
    client.force_authenticate(admin_user)
    assert client.post(f"/api/v1/admin/songs/{song.id}/publish/").status_code == 404
    patient = User.objects.create_user(login_id="song-patient", password="888888", role=Role.PATIENT, must_change_password=False)
    client.force_authenticate(patient)
    assert client.get("/api/v1/admin/songs/").status_code == 403


@pytest.mark.django_db
def test_invalid_song_list_query_is_validation_error(admin_user):
    client = APIClient()
    client.force_authenticate(admin_user)
    response = client.get("/api/v1/admin/songs/?page=bad&sort=not-a-field")
    assert response.status_code == 400
    assert response.json()["code"] == "validation_error"


@pytest.mark.django_db(transaction=True)
def test_song_database_constraints_reject_invalid_duration_and_enum():
    with pytest.raises(IntegrityError), transaction.atomic():
        Song.objects.create(title="坏数据", artist="歌手", genre="流行", language="中文", duration_seconds=0)
    with pytest.raises(IntegrityError), transaction.atomic():
        Song.objects.create(title="坏枚举", artist="歌手", genre="流行", language="中文", duration_seconds=1, analysis_status="unknown")
