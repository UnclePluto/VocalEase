import pytest
from django.test import override_settings
from rest_framework.test import APIClient

from apps.accounts.models import Role, User
from apps.analysis.models import AnalysisTask


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_manual_source_only_creates_draft_without_tasks(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    admin = User.objects.create_user(login_id="manual-admin", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)
    client = APIClient()
    client.force_authenticate(admin)
    grant = client.post("/api/v1/admin/songs/upload-grants/", {"mime": "audio/mpeg", "size": 6}, format="json").json()["data"]
    assert client.put(grant["upload_url"], b"source", content_type="audio/mpeg").status_code == 204
    assert client.post(f"/api/v1/admin/media/{grant['asset_id']}/complete/", {}, format="json").status_code == 200
    response = client.post("/api/v1/admin/songs/", {
        "id": grant["song_id"], "title": "人工歌曲", "artist": "歌手", "genre": "流行", "language": "中文",
        "duration_seconds": 60, "source_asset": grant["asset_id"], "ingestion_mode": "manual",
    }, format="json")
    assert response.status_code == 201
    assert response.json()["data"]["ingestion_mode"] == "manual"
    assert response.json()["data"]["publication_status"] == "draft"
    assert AnalysisTask.objects.filter(song_id=grant["song_id"]).count() == 0


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_manual_optional_vocal_binds_to_same_song(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    admin = User.objects.create_user(login_id="manual-vocal-admin", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)
    client = APIClient()
    client.force_authenticate(admin)
    source = client.post("/api/v1/admin/songs/upload-grants/", {"mime": "audio/mpeg", "size": 6}, format="json").json()["data"]
    vocal_response = client.post("/api/v1/admin/songs/upload-grants/", {"mime": "audio/mpeg", "size": 5, "song_id": source["song_id"], "media_type": "song_vocal"}, format="json")
    assert vocal_response.status_code == 201
    vocal = vocal_response.json()["data"]
    for grant, content in ((source, b"source"), (vocal, b"vocal")):
        assert client.put(grant["upload_url"], content, content_type="audio/mpeg").status_code == 204
        assert client.post(f"/api/v1/admin/media/{grant['asset_id']}/complete/", {}, format="json").status_code == 200
    values = {"id": source["song_id"], "title": "人工歌曲", "artist": "歌手", "genre": "流行", "language": "中文", "duration_seconds": 60, "source_asset": source["asset_id"], "vocal_asset": vocal["asset_id"], "ingestion_mode": "manual"}
    created = client.post("/api/v1/admin/songs/", values, format="json")
    assert created.status_code == 201
    assert created.json()["data"]["vocal_asset"] == vocal["asset_id"]
    assert created.json()["data"]["artifacts"]["vocal"] is True
    assert client.patch(f"/api/v1/admin/songs/{source['song_id']}/", {"ingestion_mode": "existing"}, format="json").status_code == 400


@pytest.mark.django_db
def test_optional_grant_requires_existing_song_or_intent():
    admin = User.objects.create_user(login_id="manual-no-intent", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)
    client = APIClient()
    client.force_authenticate(admin)
    missing = client.post("/api/v1/admin/songs/upload-grants/", {"mime": "audio/mpeg", "size": 5, "media_type": "song_vocal"}, format="json")
    assert missing.status_code == 400
    response = client.post("/api/v1/admin/songs/upload-grants/", {"mime": "audio/mpeg", "size": 5, "song_id": "c4e3d599-ad85-4b88-b0f9-d9414eaab09b", "media_type": "song_vocal"}, format="json")
    assert response.status_code == 400


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_lyrics_grant_rejects_non_lrc_mime_and_over_one_megabyte(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    admin = User.objects.create_user(login_id="manual-lyrics-rules", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)
    client = APIClient()
    client.force_authenticate(admin)
    source = client.post("/api/v1/admin/songs/upload-grants/", {"mime": "audio/mpeg", "size": 6}, format="json").json()["data"]
    for mime, size in (("application/json", 20), ("text/plain", 1024 * 1024 + 1)):
        response = client.post("/api/v1/admin/songs/upload-grants/", {"media_type": "lyrics", "song_id": source["song_id"], "mime": mime, "size": size}, format="json")
        assert response.status_code == 400
