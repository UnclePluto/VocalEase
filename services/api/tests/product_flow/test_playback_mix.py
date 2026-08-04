import hashlib
import io
import json
import os
import subprocess
import wave
from pathlib import Path
from uuid import uuid4

from fastapi.testclient import TestClient
from redis import Redis
from sqlalchemy import func, select
from vocaease_api.app import create_app
from vocaease_api.database import MediaFile
from vocaease_api.mixing_models import PlaybackMixJob
from vocaease_api.singing_models import AuditEvent

DATABASE_URL = os.getenv(
    "VOCAEASE_TEST_DATABASE_URL",
    "postgresql+psycopg://vocaease:vocaease_dev@127.0.0.1:54329/vocaease",
)
REDIS_URL = "redis://127.0.0.1:63799/13"
INTERNAL_TOKEN = "mix-test-worker-token"
QUEUE_NAME = "vocaease:playback-mix:pending"


def silent_wav(seconds: int) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(48_000)
        audio.writeframes(b"\0\0" * 48_000 * seconds)
    return output.getvalue()


def participant(
    client: TestClient,
    admin_headers: dict[str, str],
    label: str,
) -> tuple[dict[str, str], str]:
    suffix = uuid4().hex[:10]
    phone = f"137{int(suffix, 16) % 100_000_000:08d}"
    created = client.post(
        "/api/v1/admin/participants",
        headers=admin_headers,
        json={
            "name": f"混音测试-{label}",
            "phone": phone,
            "research_code": f"MIX-{label}-{suffix}",
        },
    )
    assert created.status_code == 201
    initial = client.post(
        "/api/v1/auth/participant/login",
        json={"phone": phone, "password": "88888888"},
    ).json()["access_token"]
    changed = client.post(
        "/api/v1/auth/participant/change-password",
        headers={"Authorization": f"Bearer {initial}"},
        json={"current_password": "88888888", "new_password": f"Mix-{label}-2026-pass"},
    )
    return (
        {"Authorization": f"Bearer {changed.json()['access_token']}"},
        created.json()["id"],
    )


def snapshot() -> dict[str, object]:
    return {
        "manufacturer": "Google",
        "model": "Pixel Test",
        "android_version": "14",
        "app_version": "0.1.0",
        "input_type": "built_in_mic",
        "output_route": "wired_headphones",
        "bluetooth_mode": None,
        "sample_rate": 48_000,
        "channels": 1,
        "bit_depth": 16,
    }


def take_task(redis: Redis) -> dict[str, object]:
    item = redis.blpop(QUEUE_NAME, timeout=1)
    assert item is not None
    return json.loads(item[1])


def write_m4a(source: bytes, target: Path) -> dict[str, object]:
    source_path = target.with_suffix(".source.wav")
    target.parent.mkdir(parents=True, exist_ok=True)
    source_path.write_bytes(source)
    process = subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-v",
            "error",
            "-i",
            str(source_path),
            "-ar",
            "48000",
            "-ac",
            "2",
            "-c:a",
            "aac",
            "-b:a",
            "192k",
            str(target),
        ],
        capture_output=True,
        check=False,
    )
    assert process.returncode == 0
    source_path.unlink()
    content = target.read_bytes()
    return {
        "content_type": "audio/mp4",
        "size_bytes": len(content),
        "sha256": hashlib.sha256(content).hexdigest(),
    }


def test_verified_voice_creates_idempotent_authorized_playback_mix(monkeypatch, tmp_path):
    media_directory = tmp_path / "media"
    monkeypatch.setenv("VOCAEASE_DATABASE_URL", DATABASE_URL)
    monkeypatch.setenv("VOCAEASE_MEDIA_DIRECTORY", str(media_directory))
    monkeypatch.setenv("VOCAEASE_SEPARATION_REDIS_URL", REDIS_URL)
    monkeypatch.setenv("VOCAEASE_WORKER_INTERNAL_TOKEN", INTERNAL_TOKEN)
    monkeypatch.setenv("VOCAEASE_PLAYBACK_MEDIA_SIGNING_SECRET", "mix-test-signing-secret")
    redis = Redis.from_url(REDIS_URL, decode_responses=True)
    redis.flushdb()
    app = create_app()

    with TestClient(app) as client:
        admin_token = client.post(
            "/api/v1/auth/admin/login",
            json={"username": "admin", "password": "admin888888"},
        ).json()["access_token"]
        admin_headers = {"Authorization": f"Bearer {admin_token}"}
        song = client.post(
            "/api/v1/admin/songs",
            headers=admin_headers,
            json={"title": f"混音测试-{uuid4().hex[:8]}", "artist": "合成音频"},
        ).json()
        track = client.post(
            f"/api/v1/admin/songs/{song['id']}/backing-tracks",
            headers=admin_headers,
            files={"file": ("backing.wav", silent_wav(1), "audio/wav")},
        ).json()
        lyrics = client.put(
            f"/api/v1/admin/backing-tracks/{track['id']}/lyrics",
            headers=admin_headers,
            json={"lrc": "[00:00.00]开始"},
        ).json()
        assert (
            client.post(
                f"/api/v1/admin/songs/{song['id']}/publish",
                headers=admin_headers,
                json={
                    "backing_track_id": track["id"],
                    "lyric_version_id": lyrics["id"],
                },
            ).status_code
            == 204
        )
        owner_headers, owner_id = participant(client, admin_headers, "OWNER")
        other_headers, _ = participant(client, admin_headers, "OTHER")
        created = client.post(
            "/api/v1/singing-sessions",
            headers=owner_headers,
            json={
                "song_id": song["id"],
                "backing_track_id": track["id"],
                "lyric_version_id": lyrics["id"],
                "used_headphones": True,
                "device_snapshot": snapshot(),
            },
        ).json()
        session_id = created["id"]
        client.post(
            f"/api/v1/singing-sessions/{session_id}/capture-completed",
            headers=owner_headers,
            json={
                "accompaniment_start_frame": 24_000,
                "audio_start_monotonic_ns": 1_000_000_000,
                "accompaniment_start_monotonic_ns": 1_500_000_000,
                "recorded_frame_count": 336_000,
            },
        )
        raw_voice = silent_wav(7)
        upload = client.post(
            f"/api/v1/singing-sessions/{session_id}/upload",
            headers=owner_headers,
            json={
                "expected_chunks": 1,
                "total_bytes": len(raw_voice),
                "total_sha256": hashlib.sha256(raw_voice).hexdigest(),
            },
        ).json()
        assert (
            client.put(
                f"/api/v1/voice-uploads/{upload['id']}/chunks/0",
                headers=owner_headers | {"X-Chunk-SHA256": hashlib.sha256(raw_voice).hexdigest()},
                content=raw_voice,
            ).status_code
            == 200
        )
        verified = client.post(
            f"/api/v1/voice-uploads/{upload['id']}/complete",
            headers=owner_headers,
        )
        assert verified.status_code == 200, verified.text
        task = take_task(redis)
        mix = client.get(
            f"/api/v1/singing-sessions/{session_id}/playback-mix",
            headers=owner_headers,
        ).json()
        assert mix["status"] == "queued"
        assert mix["experience_file"] is True
        assert mix["accompaniment_start_frame"] == 24_000
        assert task["job_id"] == mix["id"]
        assert task["backing_storage_key"].startswith("backing-tracks/")

        repeated_complete = client.post(
            f"/api/v1/voice-uploads/{upload['id']}/complete",
            headers=owner_headers,
        )
        assert repeated_complete.status_code == 200
        assert redis.llen(QUEUE_NAME) == 0

        internal_headers = {"X-VocaEase-Worker-Token": INTERNAL_TOKEN}
        client.post(
            f"/api/v1/internal/playback-mixes/{mix['id']}/started",
            headers=internal_headers,
            json={"attempt": 1},
        )
        client.post(
            f"/api/v1/internal/playback-mixes/{mix['id']}/failed",
            headers=internal_headers,
            json={"attempt": 1, "failure_code": "OUTPUT_INVALID"},
        )
        failed = client.get(
            f"/api/v1/singing-sessions/{session_id}/playback-mix",
            headers=owner_headers,
        ).json()
        assert failed["failure_message"] == "回放混音格式异常"
        retried = client.post(
            f"/api/v1/singing-sessions/{session_id}/playback-mix/retry",
            headers=owner_headers,
        )
        assert retried.status_code == 200
        assert retried.json()["attempts"] == 2
        retry_task = take_task(redis)
        assert retry_task["raw_voice_storage_key"] == task["raw_voice_storage_key"]
        assert retry_task["backing_storage_key"] == task["backing_storage_key"]
        assert retry_task["accompaniment_start_frame"] == 24_000

        started_retry = client.post(
            f"/api/v1/internal/playback-mixes/{mix['id']}/started",
            headers=internal_headers,
            json={"attempt": 2},
        )
        assert started_retry.status_code == 204
        assert (
            client.post(
                f"/api/v1/internal/playback-mixes/{mix['id']}/started",
                headers=internal_headers,
                json={"attempt": 2},
            ).status_code
            == 204
        )
        output_key = f"playback-mixes/{mix['id']}/mix.m4a"
        output = write_m4a(raw_voice, media_directory / output_key)
        output["storage_key"] = output_key
        completed = client.post(
            f"/api/v1/internal/playback-mixes/{mix['id']}/completed",
            headers=internal_headers,
            json={"attempt": 2, "output": output},
        )
        assert completed.status_code == 204, completed.text
        assert (
            client.post(
                f"/api/v1/internal/playback-mixes/{mix['id']}/completed",
                headers=internal_headers,
                json={"attempt": 2, "output": output},
            ).status_code
            == 204
        )

        assert (
            client.get(
                f"/api/v1/singing-sessions/{session_id}/playback-mix",
                headers=other_headers,
            ).status_code
            == 403
        )
        assert (
            client.post(
                f"/api/v1/singing-sessions/{session_id}/playback-mix/access",
                headers=other_headers,
            ).status_code
            == 403
        )
        access = client.post(
            f"/api/v1/singing-sessions/{session_id}/playback-mix/access",
            headers=owner_headers,
        )
        assert access.status_code == 200
        assert access.json()["expires_in_seconds"] == 300
        assert access.json()["experience_file"] is True
        assert client.get(access.json()["url"]).status_code == 200
        assert client.get(access.json()["url"] + "x").status_code == 401
        assert (
            client.post(
                f"/api/v1/singing-sessions/{session_id}/playback-mix/access",
                headers=admin_headers,
            ).status_code
            == 200
        )

        with app.state.session_factory() as database:
            job = database.scalar(
                select(PlaybackMixJob).where(PlaybackMixJob.singing_session_id == session_id)
            )
            output_media_id = job.output_media_id
            output_count = database.scalar(
                select(func.count(MediaFile.id)).where(
                    MediaFile.purpose == "playback_mix",
                    MediaFile.id == output_media_id,
                )
            )
            played_count = database.scalar(
                select(func.count(AuditEvent.id)).where(
                    AuditEvent.action == "playback_mix.played",
                    AuditEvent.object_id == session_id,
                )
            )
        assert output_count == 1
        assert played_count == 1
        assert (
            client.get(f"/api/v1/media/{output_media_id}", headers=owner_headers).status_code == 403
        )
        assert (
            client.get(f"/api/v1/media/{output_media_id}", headers=admin_headers).status_code == 403
        )
        denied_audits = client.get(
            "/api/v1/admin/audit-events",
            headers=admin_headers,
            params={"action": "media.access_denied"},
        )
        assert any(
            event["object_id"] == str(output_media_id)
            and event["detail"]["reason"] == "purpose_requires_audited_endpoint"
            for event in denied_audits.json()
        )
        assert (
            client.patch(
                f"/api/v1/admin/participants/{owner_id}",
                headers=admin_headers,
                json={"active": False},
            ).status_code
            == 200
        )
        assert client.get(access.json()["url"]).status_code == 401
        assert client.get(verified.json()["media_url"], headers=owner_headers).status_code == 401
        assert client.get(verified.json()["media_url"], headers=other_headers).status_code == 403
