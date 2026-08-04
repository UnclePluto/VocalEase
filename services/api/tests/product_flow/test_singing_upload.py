import hashlib
import io
import os
import wave
from uuid import UUID, uuid4

from fastapi.testclient import TestClient
from sqlalchemy import select
from vocaease_api.app import create_app
from vocaease_api.database import (
    BackingTrackVersion,
    LyricVersion,
    MediaFile,
    Song,
    SongPublication,
)

DATABASE_URL = os.getenv(
    "VOCAEASE_TEST_DATABASE_URL",
    "postgresql+psycopg://vocaease:vocaease_dev@127.0.0.1:54329/vocaease",
)


def silent_wav(seconds: int = 7) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(48_000)
        audio.writeframes(b"\0\0" * 48_000 * seconds)
    return output.getvalue()


def seed_published_song(app) -> dict[str, str]:
    suffix = uuid4().hex
    with app.state.session_factory() as session:
        source = MediaFile(
            storage_key=f"test/{suffix}-source.wav",
            content_type="audio/wav",
            size_bytes=1,
            sha256="0" * 64,
            purpose="song_source",
        )
        normalized = MediaFile(
            storage_key=f"test/{suffix}-backing.m4a",
            content_type="audio/mp4",
            size_bytes=1,
            sha256="1" * 64,
            purpose="backing_track",
        )
        song = Song(title=f"会话测试-{suffix[:8]}", artist="测试歌手")
        session.add_all([source, normalized, song])
        session.flush()
        track = BackingTrackVersion(
            song_id=song.id,
            version=1,
            source_media_id=source.id,
            normalized_media_id=normalized.id,
            duration_ms=1_000,
            sample_rate=48_000,
            channels=2,
            review_status="approved",
            source_kind="uploaded_backing",
        )
        session.add(track)
        session.flush()
        lyrics = LyricVersion(
            backing_track_id=track.id,
            version=1,
            lrc_text="[00:00.00]测试歌词",
        )
        session.add(lyrics)
        session.flush()
        session.add(
            SongPublication(
                song_id=song.id,
                backing_track_id=track.id,
                lyric_version_id=lyrics.id,
                active=True,
            )
        )
        session.commit()
        return {
            "song_id": str(song.id),
            "backing_track_id": str(track.id),
            "lyric_version_id": str(lyrics.id),
        }


def republish_song(app, song_version: dict[str, str]) -> dict[str, str]:
    with app.state.session_factory() as session:
        publication = session.scalar(
            select(SongPublication).where(
                SongPublication.song_id == UUID(song_version["song_id"]),
                SongPublication.active.is_(True),
            )
        )
        old_track = session.get(
            BackingTrackVersion, UUID(song_version["backing_track_id"])
        )
        assert publication is not None
        assert old_track is not None
        track = BackingTrackVersion(
            song_id=old_track.song_id,
            version=old_track.version + 1,
            source_media_id=old_track.source_media_id,
            normalized_media_id=old_track.normalized_media_id,
            duration_ms=old_track.duration_ms,
            sample_rate=old_track.sample_rate,
            channels=old_track.channels,
            review_status="approved",
            source_kind=old_track.source_kind,
        )
        session.add(track)
        session.flush()
        lyrics = LyricVersion(
            backing_track_id=track.id,
            version=1,
            lrc_text="[00:00.00]新版测试歌词",
        )
        session.add(lyrics)
        session.flush()
        publication.backing_track_id = track.id
        publication.lyric_version_id = lyrics.id
        session.commit()
        return {
            "song_id": song_version["song_id"],
            "backing_track_id": str(track.id),
            "lyric_version_id": str(lyrics.id),
        }


def authenticated_participant(
    client: TestClient,
) -> tuple[dict[str, str], dict[str, str], str, str]:
    suffix = uuid4().hex[:10]
    phone = f"139{int(suffix, 16) % 100_000_000:08d}"
    admin = client.post(
        "/api/v1/auth/admin/login",
        json={"username": "admin", "password": "admin888888"},
    ).json()["access_token"]
    participant = client.post(
        "/api/v1/admin/participants",
        headers={"Authorization": f"Bearer {admin}"},
        json={
            "name": "演唱上传测试",
            "phone": phone,
            "research_code": f"UPLOAD-{suffix}",
        },
    )
    initial = client.post(
        "/api/v1/auth/participant/login",
        json={"phone": phone, "password": "88888888"},
    ).json()["access_token"]
    token = client.post(
        "/api/v1/auth/participant/change-password",
        headers={"Authorization": f"Bearer {initial}"},
        json={"current_password": "88888888", "new_password": "Upload-test-2026"},
    ).json()["access_token"]
    return (
        {"Authorization": f"Bearer {admin}"},
        {"Authorization": f"Bearer {token}"},
        participant.json()["id"],
        phone,
    )


def snapshot() -> dict:
    return {
        "manufacturer": "Google",
        "model": "Android SDK built for arm64",
        "android_version": "14",
        "app_version": "0.1.0",
        "input_type": "built_in_mic",
        "output_route": "wired_headphones",
        "bluetooth_mode": None,
        "sample_rate": 48_000,
        "channels": 1,
        "bit_depth": 16,
    }


def test_continuous_session_and_resumable_voice_upload(monkeypatch, tmp_path):
    monkeypatch.setenv("VOCAEASE_DATABASE_URL", DATABASE_URL)
    monkeypatch.setenv("VOCAEASE_MEDIA_DIRECTORY", str(tmp_path / "media"))
    app = create_app()

    with TestClient(app) as client:
        song_version = seed_published_song(app)
        admin_headers, headers, participant_id, phone = authenticated_participant(client)

        no_second_confirmation = client.post(
            "/api/v1/singing-sessions",
            headers=headers,
            json={
                **song_version,
                "used_headphones": False,
                "headphone_risk_confirmed": False,
                "device_snapshot": snapshot(),
            },
        )
        assert no_second_confirmation.status_code == 422

        sensitive_snapshot = snapshot() | {"bluetooth_name": "不应保存的设备名"}
        rejected_sensitive_field = client.post(
            "/api/v1/singing-sessions",
            headers=headers,
            json={
                **song_version,
                "used_headphones": True,
                "device_snapshot": sensitive_snapshot,
            },
        )
        assert rejected_sensitive_field.status_code == 422

        current_song_version = republish_song(app, song_version)
        stale_version = client.post(
            "/api/v1/singing-sessions",
            headers=headers,
            json={
                **song_version,
                "used_headphones": True,
                "device_snapshot": snapshot(),
            },
        )
        assert stale_version.status_code == 409
        assert stale_version.json()["detail"] == "歌曲版本已更新，请刷新曲库后重试"

        created = client.post(
            "/api/v1/singing-sessions",
            headers=headers,
            json={
                **current_song_version,
                "used_headphones": False,
                "headphone_risk_confirmed": True,
                "device_snapshot": snapshot() | {"output_route": "speaker"},
            },
        )
        assert created.status_code == 201
        singing_session = created.json()
        assert singing_session["status"] == "recording"
        assert singing_session["headphone_risk_confirmed"] is True

        completed = client.post(
            f"/api/v1/singing-sessions/{singing_session['id']}/capture-completed",
            headers=headers,
            json={
                "accompaniment_start_frame": 144_000,
                "audio_start_monotonic_ns": 1_000_000_000,
                "accompaniment_start_monotonic_ns": 4_000_000_000,
                "recorded_frame_count": 336_000,
            },
        )
        assert completed.status_code == 200
        assert completed.json()["status"] == "pending_upload"
        client_quality = client.post(
            f"/api/v1/singing-sessions/{singing_session['id']}/quality-reports/client",
            headers=headers,
            json={
                "algorithm_version": "android-wav-qc-v1",
                "status": "warning",
                "metrics": {
                    "readable": True,
                    "sample_rate": 48_000,
                    "channels": 1,
                    "bit_depth": 16,
                    "duration_ms": 7_000,
                    "rms_dbfs": -120,
                    "silent_sample_ratio": 1,
                    "clipped_sample_ratio": 0,
                    "stage_complete": True,
                    "used_headphones": False,
                    "route_risk": True,
                    "file_warnings": ["未使用耳机"],
                    "markers": [],
                },
            },
        )
        assert client_quality.status_code == 204

        content = silent_wav()
        chunks = [content[:200_000], content[200_000:500_000], content[500_000:]]
        wrong_upload = client.post(
            f"/api/v1/singing-sessions/{singing_session['id']}/upload",
            headers=headers,
            json={
                "expected_chunks": len(chunks),
                "total_bytes": len(content),
                "total_sha256": "f" * 64,
            },
        )
        upload_id = wrong_upload.json()["id"]
        for index in [2, 0, 1]:
            chunk = chunks[index]
            response = client.put(
                f"/api/v1/voice-uploads/{upload_id}/chunks/{index}",
                headers=headers | {"X-Chunk-SHA256": hashlib.sha256(chunk).hexdigest()},
                content=chunk,
            )
            assert response.status_code == 200
        duplicate = client.put(
            f"/api/v1/voice-uploads/{upload_id}/chunks/1",
            headers=headers | {"X-Chunk-SHA256": hashlib.sha256(chunks[1]).hexdigest()},
            content=chunks[1],
        )
        assert duplicate.status_code == 200
        assert duplicate.json()["received_chunks"] == [0, 1, 2]

        mismatch = client.post(f"/api/v1/voice-uploads/{upload_id}/complete", headers=headers)
        assert mismatch.status_code == 422

        restarted = client.post(
            f"/api/v1/singing-sessions/{singing_session['id']}/upload",
            headers=headers,
            json={
                "expected_chunks": len(chunks),
                "total_bytes": len(content),
                "total_sha256": hashlib.sha256(content).hexdigest(),
            },
        )
        assert restarted.status_code == 201
        assert restarted.json()["id"] == upload_id
        assert restarted.json()["received_chunks"] == []
        for index, chunk in enumerate(chunks):
            assert (
                client.put(
                    f"/api/v1/voice-uploads/{upload_id}/chunks/{index}",
                    headers=headers | {"X-Chunk-SHA256": hashlib.sha256(chunk).hexdigest()},
                    content=chunk,
                ).status_code
                == 200
            )
        submitted = client.post(f"/api/v1/voice-uploads/{upload_id}/complete", headers=headers)
        assert submitted.status_code == 200, submitted.text
        assert submitted.json()["status"] == "verified"
        assert submitted.json()["quality_report"]["stage_complete"] is True
        assert submitted.json()["quality_report"]["status"] == "warning"

        session_detail = client.get(
            f"/api/v1/singing-sessions/{singing_session['id']}", headers=headers
        ).json()
        assert session_detail["status"] == "submitted"
        assert session_detail["raw_voice_url"].endswith("/raw-voice")
        raw_media_url = session_detail["raw_voice_url"]
        assert client.get(raw_media_url, headers=headers).status_code == 200

        summaries = client.get("/api/v1/admin/singing-sessions/summary", headers=admin_headers)
        assert summaries.status_code == 200
        assert any(item["id"] == singing_session["id"] for item in summaries.json())
        lab = client.get(
            f"/api/v1/admin/singing-sessions/{singing_session['id']}/lab",
            headers=admin_headers,
        )
        assert lab.status_code == 200, lab.text
        assert lab.json()["stages"] == {
            "pre_start_ms": 0,
            "singing_start_ms": 3_000,
            "singing_end_ms": 4_000,
            "post_end_ms": 7_000,
        }
        assert lab.json()["waveform"]
        assert client.get(lab.json()["spectrogram_url"], headers=admin_headers).status_code == 200
        assert client.get(lab.json()["spectrogram_url"], headers=headers).status_code == 403
        raw_play = client.get(lab.json()["raw_voice_url"], headers=admin_headers)
        assert raw_play.status_code == 200
        raw_download = client.get(
            lab.json()["raw_voice_url"],
            headers=admin_headers,
            params={"download": "true"},
        )
        assert "raw-voice-" in raw_download.headers["content-disposition"]
        raw_access_audits = client.get(
            "/api/v1/admin/audit-events",
            headers=admin_headers,
            params={"action": "raw_voice.played"},
        )
        assert any(
            event["object_id"] == singing_session["id"]
            for event in raw_access_audits.json()
        )
        spectrogram_audits = client.get(
            "/api/v1/admin/audit-events",
            headers=admin_headers,
            params={"action": "spectrogram.viewed"},
        )
        assert any(
            event["object_id"] == singing_session["id"]
            for event in spectrogram_audits.json()
        )

        preserve_recording = client.request(
            "DELETE",
            f"/api/v1/admin/participants/{participant_id}",
            headers=admin_headers,
            json={"delete_singing_data": False},
        )
        assert preserve_recording.status_code == 204
        assert (
            client.get(
                f"/api/v1/admin/singing-sessions/{singing_session['id']}/lab",
                headers=admin_headers,
            ).status_code
            == 200
        )
        assert (
            client.post(
                "/api/v1/auth/participant/login",
                json={"phone": phone, "password": "Upload-test-2026"},
            ).status_code
            == 401
        )

        deleted = client.delete(
            f"/api/v1/admin/singing-sessions/{singing_session['id']}",
            headers=admin_headers,
        )
        assert deleted.status_code == 204
        assert client.get(raw_media_url, headers=admin_headers).status_code == 404
        assert (
            client.get(
                f"/api/v1/admin/singing-sessions/{singing_session['id']}/lab",
                headers=admin_headers,
            ).status_code
            == 404
        )
        deletion_audit = client.get(
            "/api/v1/admin/audit-events",
            headers=admin_headers,
            params={"action": "singing_session.deleted"},
        )
        assert any(event["object_id"] == singing_session["id"] for event in deletion_audit.json())
