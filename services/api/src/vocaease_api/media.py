import hashlib
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from uuid import uuid4

from fastapi import HTTPException, UploadFile, status

from vocaease_api.settings import Settings


@dataclass(frozen=True)
class AudioMetadata:
    duration_ms: int
    sample_rate: int
    channels: int


class LocalMediaStorage:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)

    def write(self, purpose: str, suffix: str, content: bytes) -> tuple[str, Path]:
        key = f"{purpose}/{uuid4().hex}{suffix}"
        path = self.path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return key, path

    def allocate(self, purpose: str, suffix: str) -> tuple[str, Path]:
        key = f"{purpose}/{uuid4().hex}{suffix}"
        path = self.path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        return key, path

    def path(self, key: str) -> Path:
        path = (self.root / key).resolve()
        if self.root.resolve() not in path.parents:
            raise ValueError("非法媒体存储键")
        return path


def read_upload(upload: UploadFile, settings: Settings) -> bytes:
    content = upload.file.read(settings.max_audio_upload_bytes + 1)
    if len(content) > settings.max_audio_upload_bytes:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "上传文件过大")
    if not content:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "上传文件为空")
    return content


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def validate_image(content: bytes, content_type: str) -> None:
    signatures = {
        "image/jpeg": (b"\xff\xd8\xff",),
        "image/png": (b"\x89PNG\r\n\x1a\n",),
        "image/webp": (b"RIFF",),
    }
    if not any(content.startswith(signature) for signature in signatures.get(content_type, ())):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "封面文件内容与格式不符")
    if content_type == "image/webp" and (len(content) < 12 or content[8:12] != b"WEBP"):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "封面文件内容与格式不符")


def probe_audio(path: Path) -> AudioMetadata:
    process = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "stream=sample_rate,channels:format=duration",
            "-of",
            "json",
            str(path),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if process.returncode != 0:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "无法读取音频媒体参数")
    try:
        payload = json.loads(process.stdout)
        stream = payload["streams"][0]
        return AudioMetadata(
            duration_ms=round(float(payload["format"]["duration"]) * 1000),
            sample_rate=int(stream["sample_rate"]),
            channels=int(stream["channels"]),
        )
    except (KeyError, IndexError, TypeError, ValueError) as error:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "音频媒体参数不完整") from error


def normalize_backing_track(source: Path, target: Path) -> AudioMetadata:
    process = subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-v",
            "error",
            "-i",
            str(source),
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
        text=True,
        check=False,
    )
    if process.returncode != 0:
        target.unlink(missing_ok=True)
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "伴奏转码失败")
    return probe_audio(target)


LRC_LINE = re.compile(r"^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?](.*)$")


def parse_lrc(content: str) -> list[dict[str, int | str]]:
    lines: list[dict[str, int | str]] = []
    for raw_line in content.splitlines():
        match = LRC_LINE.match(raw_line.strip())
        if not match:
            continue
        minute, second, fraction, text = match.groups()
        fraction_ms = int((fraction or "0").ljust(3, "0")[:3])
        lines.append(
            {
                "time_ms": (int(minute) * 60 + int(second)) * 1000 + fraction_ms,
                "text": text.strip(),
            }
        )
    if not lines:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "LRC 不包含有效时间行")
    return sorted(lines, key=lambda line: int(line["time_ms"]))
