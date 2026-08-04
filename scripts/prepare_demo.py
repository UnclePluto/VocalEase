import base64
import hashlib
import io
import math
import os
import struct
import wave

import httpx

API_BASE_URL = os.getenv("VOCAEASE_DEMO_API_URL", "http://127.0.0.1:8000")
ADMIN_USERNAME = os.getenv("VOCAEASE_BOOTSTRAP_ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.getenv("VOCAEASE_BOOTSTRAP_ADMIN_PASSWORD", "admin888888")
DEMO_TITLE = "一期内部测试示例曲"
LAB_RESEARCH_CODE = "DEMO-LAB-001"
LAB_PHONE = "13900000002"
LAB_PASSWORD = "VocaEase-Demo-2026"


def synthetic_wav(seconds: int = 8, channels: int = 2, frequency: int = 220) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(channels)
        audio.setsampwidth(2)
        audio.setframerate(48_000)
        frames = bytearray()
        for index in range(48_000 * seconds):
            sample = round(6_000 * math.sin(2 * math.pi * frequency * index / 48_000))
            frames.extend(struct.pack("<" + ("h" * channels), *([sample] * channels)))
        audio.writeframes(frames)
    return output.getvalue()


def participant_headers(
    client: httpx.Client,
    admin_headers: dict[str, str],
    participant_id: str,
) -> dict[str, str]:
    client.post(
        f"/api/v1/admin/participants/{participant_id}/reset-password",
        headers=admin_headers,
    ).raise_for_status()
    login = client.post(
        "/api/v1/auth/participant/login",
        json={"phone": LAB_PHONE, "password": "88888888"},
    )
    login.raise_for_status()
    initial_headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    changed = client.post(
        "/api/v1/auth/participant/change-password",
        headers=initial_headers,
        json={"current_password": "88888888", "new_password": LAB_PASSWORD},
    )
    changed.raise_for_status()
    return {"Authorization": f"Bearer {changed.json()['access_token']}"}


def prepare_lab_session(
    client: httpx.Client,
    admin_headers: dict[str, str],
    song: dict,
    track: dict,
    lyric: dict,
) -> None:
    summaries = client.get(
        "/api/v1/admin/singing-sessions/summary",
        headers=admin_headers,
    )
    summaries.raise_for_status()
    if any(
        item["participant_research_code"] == LAB_RESEARCH_CODE
        and item["status"] == "submitted"
        for item in summaries.json()
    ):
        return

    participants = client.get(
        "/api/v1/admin/participants",
        headers=admin_headers,
        params={"q": LAB_RESEARCH_CODE},
    )
    participants.raise_for_status()
    matches = participants.json()
    if matches:
        participant = matches[0]
    else:
        created = client.post(
            "/api/v1/admin/participants",
            headers=admin_headers,
            json={
                "name": "虚构声音实验室参与者",
                "phone": LAB_PHONE,
                "research_code": LAB_RESEARCH_CODE,
            },
        )
        created.raise_for_status()
        participant = created.json()

    headers = participant_headers(client, admin_headers, participant["id"])

    created_session = client.post(
        "/api/v1/singing-sessions",
        headers=headers,
        json={
            "song_id": song["id"],
            "backing_track_id": track["id"],
            "lyric_version_id": lyric["id"],
            "used_headphones": True,
            "headphone_risk_confirmed": False,
            "device_snapshot": {
                "manufacturer": "VocaEase",
                "model": "内部合成设备",
                "android_version": "14",
                "app_version": "0.1.0-demo",
                "input_type": "built_in_mic",
                "output_route": "wired_headphones",
                "bluetooth_mode": None,
                "sample_rate": 48_000,
                "channels": 1,
                "bit_depth": 16,
            },
        },
    )
    created_session.raise_for_status()
    singing_session = created_session.json()
    raw_voice = synthetic_wav(seconds=14, channels=1, frequency=330)
    client.post(
        f"/api/v1/singing-sessions/{singing_session['id']}/capture-completed",
        headers=headers,
        json={
            "accompaniment_start_frame": 144_000,
            "audio_start_monotonic_ns": 1_000_000_000,
            "accompaniment_start_monotonic_ns": 4_000_000_000,
            "recorded_frame_count": 672_000,
        },
    ).raise_for_status()
    client.post(
        f"/api/v1/singing-sessions/{singing_session['id']}/quality-reports/client",
        headers=headers,
        json={
            "algorithm_version": "demo-fixture-v1",
            "status": "ok",
            "metrics": {
                "readable": True,
                "sample_rate": 48_000,
                "channels": 1,
                "bit_depth": 16,
                "duration_ms": 14_000,
                "rms_dbfs": -11.7,
                "silent_sample_ratio": 0,
                "clipped_sample_ratio": 0,
                "stage_complete": True,
                "used_headphones": True,
                "route_risk": False,
                "file_warnings": [],
                "markers": [],
            },
        },
    ).raise_for_status()
    digest = hashlib.sha256(raw_voice).hexdigest()
    created_upload = client.post(
        f"/api/v1/singing-sessions/{singing_session['id']}/upload",
        headers=headers,
        json={
            "expected_chunks": 1,
            "total_bytes": len(raw_voice),
            "total_sha256": digest,
        },
    )
    created_upload.raise_for_status()
    upload_id = created_upload.json()["id"]
    client.put(
        f"/api/v1/voice-uploads/{upload_id}/chunks/0",
        headers=headers | {"X-Chunk-SHA256": digest},
        content=raw_voice,
    ).raise_for_status()
    client.post(
        f"/api/v1/voice-uploads/{upload_id}/complete",
        headers=headers,
    ).raise_for_status()


def main() -> None:
    with httpx.Client(base_url=API_BASE_URL, timeout=120) as client:
        login = client.post(
            "/api/v1/auth/admin/login",
            json={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD},
        )
        login.raise_for_status()
        headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

        participants = client.get(
            "/api/v1/admin/participants",
            headers=headers,
            params={"q": "DEMO-001"},
        )
        participants.raise_for_status()
        if not participants.json():
            created = client.post(
                "/api/v1/admin/participants",
                headers=headers,
                json={
                    "name": "虚构演示参与者",
                    "phone": "13900000001",
                    "research_code": "DEMO-001",
                },
            )
            created.raise_for_status()

        songs = client.get("/api/v1/admin/songs", headers=headers)
        songs.raise_for_status()
        song = next((item for item in songs.json() if item["title"] == DEMO_TITLE), None)
        if song is None:
            created_song = client.post(
                "/api/v1/admin/songs",
                headers=headers,
                json={"title": DEMO_TITLE, "artist": "无版权合成音频"},
            )
            created_song.raise_for_status()
            song = {
                **created_song.json(),
                "backing_tracks": [],
                "published": False,
            }
            cover = base64.b64decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0"
                "lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
            )
            client.post(
                f"/api/v1/admin/songs/{song['id']}/cover",
                headers=headers,
                files={"file": ("demo.png", cover, "image/png")},
            ).raise_for_status()

        if not song["backing_tracks"]:
            uploaded = client.post(
                f"/api/v1/admin/songs/{song['id']}/backing-tracks",
                headers=headers,
                files={"file": ("demo-backing.wav", synthetic_wav(), "audio/wav")},
            )
            uploaded.raise_for_status()
            track = uploaded.json()
        else:
            track = song["backing_tracks"][-1]

        if not track.get("lyrics"):
            lyrics = client.put(
                f"/api/v1/admin/backing-tracks/{track['id']}/lyrics",
                headers=headers,
                json={
                    "lrc": (
                        "[00:00.00]准备开始\n"
                        "[00:02.00]一期内部测试\n"
                        "[00:05.00]请勿录入真实参与者数据"
                    )
                },
            )
            lyrics.raise_for_status()
            lyric = lyrics.json()
        else:
            lyric = track["lyrics"][-1]

        if not song["published"]:
            client.post(
                f"/api/v1/admin/songs/{song['id']}/publish",
                headers=headers,
                json={
                    "backing_track_id": track["id"],
                    "lyric_version_id": lyric["id"],
                },
            ).raise_for_status()

        separations = client.get(
            "/api/v1/admin/separations",
            headers=headers,
            params={"song_id": song["id"]},
        )
        separations.raise_for_status()
        if not separations.json():
            client.post(
                f"/api/v1/admin/songs/{song['id']}/separations",
                headers=headers,
                files={"file": ("demo-original.wav", synthetic_wav(), "audio/wav")},
            ).raise_for_status()

        prepare_lab_session(client, headers, song, track, lyric)

    print(
        "内部演示数据已准备：管理员 admin；"
        "虚构参与者 13900000001 / 88888888；"
        "声音实验室合成会话已生成。"
    )


if __name__ == "__main__":
    main()
