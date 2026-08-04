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
from vocaease_api.app import create_app

DATABASE_URL = os.getenv(
    "VOCAEASE_TEST_DATABASE_URL",
    "postgresql+psycopg://vocaease:vocaease_dev@127.0.0.1:54329/vocaease",
)
REDIS_URL = "redis://127.0.0.1:63799/14"
INTERNAL_TOKEN = "test-worker-token"
QUEUE_NAME = "vocaease:separation:pending"


def synthetic_wav() -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(44_100)
        audio.writeframes(b"\0\0" * 44_100)
    return output.getvalue()


def write_output(source: bytes, target: Path) -> dict[str, str | int]:
    source_path = target.with_suffix(".source.wav")
    source_path.parent.mkdir(parents=True, exist_ok=True)
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


def take_task(redis: Redis) -> dict[str, object]:
    item = redis.blpop(QUEUE_NAME, timeout=1)
    assert item is not None
    return json.loads(item[1])


def test_admin_runs_reviews_retries_and_accepts_server_separation(monkeypatch, tmp_path):
    media_directory = tmp_path / "media"
    monkeypatch.setenv("VOCAEASE_DATABASE_URL", DATABASE_URL)
    monkeypatch.setenv("VOCAEASE_MEDIA_DIRECTORY", str(media_directory))
    monkeypatch.setenv("VOCAEASE_SEPARATION_REDIS_URL", REDIS_URL)
    monkeypatch.setenv("VOCAEASE_WORKER_INTERNAL_TOKEN", INTERNAL_TOKEN)
    redis = Redis.from_url(REDIS_URL, decode_responses=True)
    redis.flushdb()
    source = synthetic_wav()

    with TestClient(create_app()) as client:
        login = client.post(
            "/api/v1/auth/admin/login",
            json={"username": "admin", "password": "admin888888"},
        )
        admin_headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
        song = client.post(
            "/api/v1/admin/songs",
            headers=admin_headers,
            json={"title": f"AI 分离测试-{uuid4().hex[:8]}", "artist": "合成测试音频"},
        )
        song_id = song.json()["id"]

        created = client.post(
            f"/api/v1/admin/songs/{song_id}/separations",
            headers=admin_headers,
            files={"file": ("original.wav", source, "audio/wav")},
        )
        assert created.status_code == 201, created.text
        job = created.json()
        assert job["status"] == "queued"
        assert job["attempts"] == 1
        preserved_source = client.get(job["source_url"], headers=admin_headers)
        assert preserved_source.status_code == 200
        assert preserved_source.content == source
        task = take_task(redis)
        assert task["job_id"] == job["id"]

        internal_headers = {"X-VocaEase-Worker-Token": INTERNAL_TOKEN}
        assert (
            client.post(
                f"/api/v1/internal/separations/{job['id']}/started",
                json={"attempt": 1},
            ).status_code
            == 401
        )
        started = client.post(
            f"/api/v1/internal/separations/{job['id']}/started",
            headers=internal_headers,
            json={"attempt": 1},
        )
        assert started.status_code == 204
        assert (
            client.post(
                f"/api/v1/internal/separations/{job['id']}/started",
                headers=internal_headers,
                json={"attempt": 1},
            ).status_code
            == 204
        )

        output_prefix = f"separations/{job['id']}/attempt-1"
        vocals_key = f"{output_prefix}/vocals.m4a"
        no_vocals_key = f"{output_prefix}/no-vocals.m4a"
        vocals = write_output(source, media_directory / vocals_key)
        no_vocals = write_output(source, media_directory / no_vocals_key)
        vocals["storage_key"] = vocals_key
        no_vocals["storage_key"] = no_vocals_key
        invalid_vocals = {**vocals, "sha256": "0" * 64}

        invalid = client.post(
            f"/api/v1/internal/separations/{job['id']}/completed",
            headers=internal_headers,
            json={"attempt": 1, "vocals": invalid_vocals, "no_vocals": no_vocals},
        )
        assert invalid.status_code == 422
        completed = client.post(
            f"/api/v1/internal/separations/{job['id']}/completed",
            headers=internal_headers,
            json={"attempt": 1, "vocals": vocals, "no_vocals": no_vocals},
        )
        assert completed.status_code == 204, completed.text
        assert (
            client.post(
                f"/api/v1/internal/separations/{job['id']}/completed",
                headers=internal_headers,
                json={"attempt": 1, "vocals": vocals, "no_vocals": no_vocals},
            ).status_code
            == 204
        )

        succeeded = client.get(
            f"/api/v1/admin/separations/{job['id']}", headers=admin_headers
        ).json()
        assert succeeded["status"] == "succeeded"
        assert succeeded["vocals_url"].startswith("/api/v1/media/")
        assert succeeded["no_vocals_url"].startswith("/api/v1/media/")
        assert client.get(succeeded["no_vocals_url"], headers=admin_headers).status_code == 200

        accepted = client.post(
            f"/api/v1/admin/separations/{job['id']}/accept",
            headers=admin_headers,
        )
        assert accepted.status_code == 200
        accepted_job = accepted.json()
        assert accepted_job["status"] == "accepted"
        assert accepted_job["approved_backing_track_id"] is not None
        lyrics = client.put(
            f"/api/v1/admin/backing-tracks/{accepted_job['approved_backing_track_id']}/lyrics",
            headers=admin_headers,
            json={"lrc": "[00:00.00]AI 分离候选伴奏\n[00:00.50]测试歌词"},
        )
        assert lyrics.status_code == 200
        published = client.post(
            f"/api/v1/admin/songs/{song_id}/publish",
            headers=admin_headers,
            json={
                "backing_track_id": accepted_job["approved_backing_track_id"],
                "lyric_version_id": lyrics.json()["id"],
            },
        )
        assert published.status_code == 204
        assert (
            client.post(
                f"/api/v1/admin/separations/{job['id']}/accept",
                headers=admin_headers,
            ).status_code
            == 409
        )

        failed_job = client.post(
            f"/api/v1/admin/songs/{song_id}/separations",
            headers=admin_headers,
            files={"file": ("original.wav", source, "audio/wav")},
        ).json()
        first_failed_task = take_task(redis)
        client.post(
            f"/api/v1/internal/separations/{failed_job['id']}/started",
            headers=internal_headers,
            json={"attempt": 1},
        )
        failed = client.post(
            f"/api/v1/internal/separations/{failed_job['id']}/failed",
            headers=internal_headers,
            json={"attempt": 1, "failure_code": "OUTPUT_MISSING"},
        )
        assert failed.status_code == 204
        assert (
            client.post(
                f"/api/v1/internal/separations/{failed_job['id']}/failed",
                headers=internal_headers,
                json={"attempt": 1, "failure_code": "OUTPUT_MISSING"},
            ).status_code
            == 204
        )
        observed_failure = client.get(
            f"/api/v1/admin/separations/{failed_job['id']}",
            headers=admin_headers,
        ).json()
        assert observed_failure["failure_code"] == "OUTPUT_MISSING"
        assert observed_failure["failure_message"] == "分离结果不完整"

        retried = client.post(
            f"/api/v1/admin/separations/{failed_job['id']}/retry",
            headers=admin_headers,
        )
        assert retried.status_code == 200
        assert retried.json()["status"] == "queued"
        assert retried.json()["attempts"] == 2
        retry_task = take_task(redis)
        assert retry_task["job_id"] == failed_job["id"]
        assert retry_task["attempt"] == 2
        assert retry_task["source_storage_key"] == first_failed_task["source_storage_key"]

        client.post(
            f"/api/v1/internal/separations/{failed_job['id']}/started",
            headers=internal_headers,
            json={"attempt": 2},
        )
        retry_prefix = f"separations/{failed_job['id']}/attempt-2"
        retry_vocals_key = f"{retry_prefix}/vocals.m4a"
        retry_no_vocals_key = f"{retry_prefix}/no-vocals.m4a"
        retry_vocals = write_output(source, media_directory / retry_vocals_key)
        retry_no_vocals = write_output(source, media_directory / retry_no_vocals_key)
        retry_vocals["storage_key"] = retry_vocals_key
        retry_no_vocals["storage_key"] = retry_no_vocals_key
        assert (
            client.post(
                f"/api/v1/internal/separations/{failed_job['id']}/completed",
                headers=internal_headers,
                json={
                    "attempt": 2,
                    "vocals": retry_vocals,
                    "no_vocals": retry_no_vocals,
                },
            ).status_code
            == 204
        )
        rejected = client.post(
            f"/api/v1/admin/separations/{failed_job['id']}/reject",
            headers=admin_headers,
        )
        assert rejected.status_code == 200
        assert rejected.json()["status"] == "rejected"
