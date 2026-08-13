from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import re
from typing import Any, Mapping, Protocol
from uuid import UUID, uuid4

from django.conf import settings
from django.utils import timezone


MEBIBYTE = 1024 * 1024
MEDIA_TYPES: dict[str, frozenset[str]] = {
    "song_source": frozenset({"audio/mpeg", "audio/wav", "audio/flac"}),
    "song_accompaniment": frozenset({"audio/mpeg", "audio/wav", "audio/flac"}),
    "song_vocal": frozenset({"audio/mpeg", "audio/wav", "audio/flac"}),
    "lyrics": frozenset({"text/plain", "application/json"}),
    "singing_audio": frozenset({"audio/mpeg", "audio/wav", "audio/flac", "audio/mp4", "audio/webm"}),
    "singing_video": frozenset({"video/mp4", "video/webm"}),
    "waveform": frozenset({"application/json"}),
    "export": frozenset({"text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}),
}
PATIENT_MEDIA_TYPES = frozenset({"singing_audio", "singing_video"})
OWNER_MEDIA_TYPES: dict[str, frozenset[str]] = {
    "patient": PATIENT_MEDIA_TYPES,
    "song": frozenset({"song_source", "song_accompaniment", "song_vocal", "lyrics"}),
    "system": frozenset({"waveform", "song_accompaniment", "song_vocal", "lyrics"}),
    "export": frozenset({"export"}),
}
BACKENDS = frozenset({"local", "qiniu"})
OWNER_TYPES = frozenset({"patient", "song", "system", "export"})
_ENVIRONMENT_RE = re.compile(r"^[A-Za-z0-9_-]+$")


class StorageValidationError(ValueError):
    """调用方给出的媒体参数或对象状态不可信。"""


def max_size_for(media_type: str) -> int:
    if media_type in {"song_source", "song_accompaniment", "song_vocal", "singing_audio"}:
        return min(int(getattr(settings, "MEDIA_AUDIO_MAX_BYTES", 50 * MEBIBYTE)), 50 * MEBIBYTE)
    if media_type == "singing_video":
        return int(getattr(settings, "MEDIA_VIDEO_MAX_BYTES", 500 * MEBIBYTE))
    return int(getattr(settings, "MEDIA_OTHER_MAX_BYTES", 10 * MEBIBYTE))


def validate_media_request(*, media_type: str, mime: str, size: int) -> None:
    if media_type not in MEDIA_TYPES:
        raise StorageValidationError("不支持的媒体类型")
    if mime not in MEDIA_TYPES[media_type]:
        raise StorageValidationError("媒体 MIME 类型不受支持")
    if not isinstance(size, int) or size <= 0 or size > max_size_for(media_type):
        raise StorageValidationError("媒体文件大小不合法")


def build_object_key(environment: str, media_type: str, now: datetime | None = None) -> str:
    if media_type not in MEDIA_TYPES:
        raise StorageValidationError("不支持的媒体类型")
    if not _ENVIRONMENT_RE.fullmatch(environment):
        raise StorageValidationError("存储环境标识不合法")
    now = now or timezone.now()
    return f"{environment}/{media_type}/{now:%Y/%m/%d}/{uuid4().hex}"


@dataclass(frozen=True)
class UploadGrant:
    object_key: str
    expires_at: datetime
    upload_url: str = ""
    upload_token: str = ""
    fields: Mapping[str, str] | None = None


@dataclass(frozen=True)
class ObjectMetadata:
    object_key: str
    size: int
    mime: str
    sha256: str = ""
    etag: str = ""
    generation: str = ""
    blob: str = ""


@dataclass(frozen=True)
class UploadReceipt(ObjectMetadata):
    pass


@dataclass(frozen=True)
class PrivateUrl:
    url: str
    expires_at: datetime
    token: str = ""


class StorageBackend(Protocol):
    def create_upload_grant(self, *, owner_id: UUID, media_type: str, mime: str, size: int) -> UploadGrant: ...

    def verify_completion(self, object_key: str, payload: Mapping[str, Any] | None = None) -> UploadReceipt: ...

    def create_private_url(self, object_key: str, *, ttl_seconds: int) -> PrivateUrl: ...

    def stat(self, object_key: str) -> ObjectMetadata: ...

    def mark_for_cleanup(self, object_key: str) -> None: ...
