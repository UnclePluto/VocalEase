import io
import os
import wave
from uuid import uuid4

from fastapi.testclient import TestClient
from vocaease_api.app import create_app

DATABASE_URL = os.getenv(
    "VOCAEASE_TEST_DATABASE_URL",
    "postgresql+psycopg://vocaease:vocaease_dev@127.0.0.1:54329/vocaease",
)


def wav_bytes(sample_rate: int = 44_100, seconds: int = 1) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(sample_rate)
        audio.writeframes(b"\0\0" * sample_rate * seconds)
    return output.getvalue()


def test_admin_publishes_versioned_backing_track_and_lrc(monkeypatch, tmp_path):
    suffix = uuid4().hex[:10]
    phone = f"138{int(suffix, 16) % 100_000_000:08d}"
    monkeypatch.setenv("VOCAEASE_DATABASE_URL", DATABASE_URL)
    monkeypatch.setenv("VOCAEASE_MEDIA_DIRECTORY", str(tmp_path / "media"))

    with TestClient(create_app()) as client:
        admin_login = client.post(
            "/api/v1/auth/admin/login",
            json={"username": "admin", "password": "admin888888"},
        )
        admin_headers = {"Authorization": f"Bearer {admin_login.json()['access_token']}"}
        created = client.post(
            "/api/v1/admin/songs",
            headers=admin_headers,
            json={"title": f"测试歌曲-{suffix}", "artist": "测试歌手"},
        )
        assert created.status_code == 201
        song_id = created.json()["id"]

        cover = client.post(
            f"/api/v1/admin/songs/{song_id}/cover",
            headers=admin_headers,
            files={"file": ("cover.png", b"\x89PNG\r\n\x1a\n", "image/png")},
        )
        assert cover.status_code == 200
        assert cover.json()["cover_url"].startswith("/api/v1/media/")

        uploaded = client.post(
            f"/api/v1/admin/songs/{song_id}/backing-tracks",
            headers=admin_headers,
            files={"file": ("backing.wav", wav_bytes(), "audio/wav")},
        )
        assert uploaded.status_code == 200, uploaded.text
        track = uploaded.json()
        assert track["sample_rate"] == 48_000
        assert track["channels"] == 2
        assert len(track["source_sha256"]) == 64
        admin_catalog = client.get("/api/v1/admin/songs", headers=admin_headers)
        assert admin_catalog.status_code == 200
        listed = next(song for song in admin_catalog.json() if song["id"] == song_id)
        assert listed["backing_tracks"][0]["id"] == track["id"]
        assert listed["published"] is False

        cannot_publish = client.post(
            f"/api/v1/admin/songs/{song_id}/publish",
            headers=admin_headers,
            json={"backing_track_id": track["id"], "lyric_version_id": uuid4().hex},
        )
        assert cannot_publish.status_code == 422

        lrc = "[00:00.00]准备开始\n[00:00.50]第一句\n[00:01.00]结束"
        lyrics = client.put(
            f"/api/v1/admin/backing-tracks/{track['id']}/lyrics",
            headers=admin_headers,
            json={"lrc": lrc},
        )
        assert lyrics.status_code == 200
        assert lyrics.json()["lines"][1] == {"time_ms": 500, "text": "第一句"}

        published = client.post(
            f"/api/v1/admin/songs/{song_id}/publish",
            headers=admin_headers,
            json={
                "backing_track_id": track["id"],
                "lyric_version_id": lyrics.json()["id"],
            },
        )
        assert published.status_code == 204

        participant = client.post(
            "/api/v1/admin/participants",
            headers=admin_headers,
            json={"name": "曲库测试参与者", "phone": phone, "research_code": f"CAT-{suffix}"},
        )
        assert participant.status_code == 201
        login = client.post(
            "/api/v1/auth/participant/login",
            json={"phone": phone, "password": "88888888"},
        )
        initial_headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
        changed = client.post(
            "/api/v1/auth/participant/change-password",
            headers=initial_headers,
            json={"current_password": "88888888", "new_password": "Catalog-2026-pass"},
        )
        participant_headers = {"Authorization": f"Bearer {changed.json()['access_token']}"}

        catalog = client.get("/api/v1/catalog/songs", headers=participant_headers)
        assert catalog.status_code == 200
        visible = next(song for song in catalog.json() if song["id"] == song_id)
        assert visible["title"] == f"测试歌曲-{suffix}"
        assert visible["duration_ms"] >= 1_000
        assert visible["backing_track_id"] == track["id"]
        assert visible["lyric_version_id"] == lyrics.json()["id"]
        assert visible["lines"][0]["text"] == "准备开始"
        assert client.get(visible["backing_track_url"]).status_code == 401
        assert (
            client.get(visible["backing_track_url"], headers=participant_headers).status_code == 200
        )

        unpublish = client.post(f"/api/v1/admin/songs/{song_id}/unpublish", headers=admin_headers)
        assert unpublish.status_code == 204
        remaining = client.get("/api/v1/catalog/songs", headers=participant_headers).json()
        assert all(song["id"] != song_id for song in remaining)
