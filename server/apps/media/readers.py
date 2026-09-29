"""只读取经数据库绑定的可信小型媒体内容。"""

import hashlib
from base64 import urlsafe_b64encode
from urllib.parse import urlsplit

import requests
from django.conf import settings
from rest_framework.exceptions import ValidationError

from apps.media.backends.local import LocalStorageBackend
from apps.media.contracts import StorageValidationError
from apps.media.models import MediaAsset
from apps.media.services import backend_for_asset


def _read_limited(stream, *, max_bytes: int) -> bytes:
    data = stream.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise ValidationError({"lyrics": "歌词超过 1MB"})
    return data


def _qiniu_etag(content: bytes) -> str:
    # 歌词上限 1MB，七牛此时使用单块 ETag 格式。
    return urlsafe_b64encode(b"\x16" + hashlib.sha1(content).digest()).decode().rstrip("=")


def read_verified_asset_bytes(*, asset: MediaAsset, max_bytes: int) -> bytes:
    if asset.status != "ready" or asset.deleted_at is not None or asset.size > max_bytes:
        raise ValidationError({"lyrics": "歌词不可用或超过大小限制"})
    backend = backend_for_asset(asset)
    if isinstance(backend, LocalStorageBackend):
        private = backend.create_private_url(asset.object_key, ttl_seconds=30, asset_id=asset.id, expected_generation=asset.manifest_generation)
        with backend.verify_and_open_private(private.token, asset.object_key, asset_id=asset.id, expected_generation=asset.manifest_generation) as source:
            content = _read_limited(source, max_bytes=max_bytes)
        if len(content) != asset.size or hashlib.sha256(content).hexdigest() != asset.sha256:
            raise StorageValidationError("歌词内容与可信回执不一致")
        return content
    private = backend.create_private_url(asset.object_key, ttl_seconds=30)
    expected = urlsplit(backend.domain)
    actual = urlsplit(private.url)
    if expected.scheme != "https" or actual.scheme != "https" or actual.netloc != expected.netloc:
        raise StorageValidationError("私有歌词地址无效")
    try:
        with requests.get(private.url, stream=True, timeout=(3, 10), allow_redirects=False) as response:
            if response.status_code != 200:
                raise StorageValidationError("歌词下载失败")
            content = _read_limited(response.raw, max_bytes=max_bytes)
    except requests.RequestException as exc:
        raise StorageValidationError("歌词下载失败") from exc
    if len(content) != asset.size or _qiniu_etag(content) != asset.etag:
        raise StorageValidationError("歌词内容与可信回执不一致")
    return content
