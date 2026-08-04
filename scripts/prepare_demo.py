import base64
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


def synthetic_wav(seconds: int = 8) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(2)
        audio.setsampwidth(2)
        audio.setframerate(48_000)
        frames = bytearray()
        for index in range(48_000 * seconds):
            sample = round(6_000 * math.sin(2 * math.pi * 220 * index / 48_000))
            frames.extend(struct.pack("<hh", sample, sample))
        audio.writeframes(frames)
    return output.getvalue()


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

    print("内部演示数据已准备：管理员 admin，虚构参与者 13900000001 / 88888888。")


if __name__ == "__main__":
    main()
