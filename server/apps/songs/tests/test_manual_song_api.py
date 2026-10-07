import pytest
from django.test import override_settings
from rest_framework.test import APIClient

from apps.accounts.models import Role, User


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_manual_song_publishes_and_resource_update_uses_expected_asset(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    admin = User.objects.create_user(login_id="manual-api-admin", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)
    client = APIClient()
    client.force_authenticate(admin)

    def upload(media_type, content, song_id=None):
        payload = {"media_type": media_type, "mime": "text/plain" if media_type == "lyrics" else "audio/mpeg", "size": len(content)}
        if song_id:
            payload["song_id"] = song_id
        grant_response = client.post("/api/v1/admin/songs/upload-grants/", payload, format="json")
        assert grant_response.status_code == 201
        grant = grant_response.json()["data"]
        assert client.put(grant["upload_url"], content, content_type=payload["mime"]).status_code == 204
        assert client.post(f"/api/v1/admin/media/{grant['asset_id']}/complete/", {}, format="json").status_code == 200
        return grant

    source = upload("song_source", b"source")
    song_id = source["song_id"]
    create = client.post("/api/v1/admin/songs/", {"id": song_id, "title": "手动", "artist": "歌手", "genre": "流行", "language": "中文", "duration_seconds": 60, "source_asset": source["asset_id"], "ingestion_mode": "manual"}, format="json")
    assert create.status_code == 201
    published = client.post(f"/api/v1/admin/songs/{song_id}/publish/")
    assert published.status_code == 200
    assert published.json()["data"]["publication_status"] == "published"
    vocal = upload("song_vocal", b"vocal", song_id)
    first = client.patch(f"/api/v1/admin/songs/{song_id}/resources/", {"updates": {"vocal_asset": vocal["asset_id"]}, "expected": {"vocal_asset": None}}, format="json")
    assert first.status_code == 200, first.content
    from apps.songs.models import SongReferencePitch
    assert SongReferencePitch.objects.filter(song_id=song_id, input_asset_id=vocal['asset_id'], status='pending').exists()
    assert first.json()["data"]["publication_status"] == "published"
    assert client.patch(f"/api/v1/admin/songs/{song_id}/resources/", {"updates": {"vocal_asset": vocal["asset_id"]}, "expected": {"vocal_asset": None}}, format="json").status_code == 409
    preview = client.post(f"/api/v1/admin/songs/{song_id}/preview/", {"track": "vocal"}, format="json")
    assert preview.status_code == 200
    assert preview.json()["data"]["url"]
    lyrics = upload("lyrics", b"[00:01.00]hello", song_id)
    linked = client.patch(f"/api/v1/admin/songs/{song_id}/resources/", {"updates": {"lyrics_asset": lyrics["asset_id"]}, "expected": {"lyrics_asset": None}}, format="json")
    assert linked.status_code == 200
    from apps.media import readers
    original_read = readers.read_verified_asset_bytes
    reads = 0

    def read_once(**kwargs):
        nonlocal reads
        reads += 1
        if reads > 1:
            raise RuntimeError("歌词重复读取")
        return original_read(**kwargs)

    monkeypatch.setattr(readers, "read_verified_asset_bytes", read_once)
    lines = client.get(f"/api/v1/admin/songs/{song_id}/lyrics/")
    assert lines.status_code == 200
    assert lines.json()["data"]["lines"] == [{"time_ms": 1000, "text": "hello"}]
    assert reads == 1
    monkeypatch.undo()
    # 通用媒体凭证允许 application/json 歌词资产；人工歌曲绑定仍只认 LRC 的 text/plain。
    from django.core.cache import cache
    cache.clear()
    other = client.post("/api/v1/admin/media/upload-grants/", {"owner_type": "song", "owner_id": song_id, "media_type": "lyrics", "mime": "application/json", "size": len(b"[00:01.00]hello")}, format="json")
    assert other.status_code == 201
    generic = other.json()["data"]
    assert client.put(generic["upload_url"], b"[00:01.00]hello", content_type="application/json").status_code == 204
    assert client.post(f"/api/v1/admin/media/{generic['asset_id']}/complete/", {}, format="json").status_code == 200
    invalid = client.patch(f"/api/v1/admin/songs/{song_id}/resources/", {"updates": {"lyrics_asset": generic["asset_id"]}, "expected": {"lyrics_asset": lyrics["asset_id"]}}, format="json")
    assert invalid.status_code == 409
