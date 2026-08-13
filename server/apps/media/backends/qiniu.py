from __future__ import annotations

import base64
import hashlib
import hmac
import json
from datetime import datetime, timedelta, timezone as datetime_timezone
from typing import Any, Callable, Mapping
from urllib.parse import urlsplit
from uuid import UUID

from django.conf import settings
from django.utils import timezone

from apps.media.contracts import (
    ObjectMetadata,
    PrivateUrl,
    StorageValidationError,
    UploadGrant,
    UploadReceipt,
    build_object_key,
    validate_media_request,
)


def _urlsafe(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


class QiniuStorageBackend:
    """按七牛 Kodo QBox/PutPolicy 协议实现，避免测试依赖云端凭据。"""

    callback_content_type = "application/json"

    def __init__(
        self, *, access_key: str, secret_key: str, bucket: str, domain: str, callback_url: str,
        environment: str, stat_resolver: Callable[[str], ObjectMetadata] | None = None,
    ):
        if not all((access_key, secret_key, bucket, domain, callback_url)):
            raise StorageValidationError("七牛私有空间配置不完整")
        self.access_key = access_key
        self.secret_key = secret_key
        self.bucket = bucket
        self.domain = domain.rstrip("/")
        self.callback_url = callback_url
        self.environment = environment
        self.stat_resolver = stat_resolver

    @classmethod
    def from_settings(cls):
        return cls(
            access_key=settings.QINIU_ACCESS_KEY, secret_key=settings.QINIU_SECRET_KEY,
            bucket=settings.QINIU_BUCKET, domain=settings.QINIU_DOMAIN,
            callback_url=settings.QINIU_CALLBACK_URL, environment=settings.MEDIA_ENVIRONMENT,
        )

    def _sign(self, data: bytes) -> str:
        return _urlsafe(hmac.new(self.secret_key.encode(), data, hashlib.sha1).digest())

    def create_upload_grant(self, *, owner_id: UUID, media_type: str, mime: str, size: int) -> UploadGrant:
        validate_media_request(media_type=media_type, mime=mime, size=size)
        object_key = build_object_key(self.environment, media_type)
        deadline = int((timezone.now() + timedelta(seconds=settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS)).timestamp())
        policy = {
            "scope": f"{self.bucket}:{object_key}", "deadline": deadline, "insertOnly": 1,
            "fsizeLimit": size, "mimeLimit": mime, "detectMime": 1,
            "callbackUrl": self.callback_url, "callbackBodyType": self.callback_content_type,
            "callbackBody": '{"key":"$(key)","hash":"$(etag)","sha256":"$(x:sha256)","fsize":$(fsize),"mime":"$(mimeType)"}',
        }
        encoded_policy = _urlsafe(json.dumps(policy, separators=(",", ":"), sort_keys=True).encode())
        upload_token = f"{self.access_key}:{self._sign(encoded_policy.encode())}:{encoded_policy}"
        return UploadGrant(
            object_key=object_key, expires_at=datetime.fromtimestamp(deadline, tz=datetime_timezone.utc),
            upload_url=settings.QINIU_UPLOAD_URL, upload_token=upload_token, fields={"key": object_key, "token": upload_token},
        )

    def verify_callback_signature(self, *, authorization: str, content_type: str, callback_url: str, body: bytes) -> bool:
        if content_type.split(";", 1)[0].strip().lower() != self.callback_content_type:
            return False
        prefix = f"QBox {self.access_key}:"
        if not authorization or not authorization.startswith(prefix):
            return False
        parsed = urlsplit(callback_url)
        if not parsed.path:
            return False
        canonical = parsed.path + (f"?{parsed.query}" if parsed.query else "")
        expected = self._sign(canonical.encode() + b"\n" + body)
        supplied = authorization[len(prefix):]
        return hmac.compare_digest(expected, supplied)

    def verify_completion(self, object_key: str, payload: Mapping[str, Any]) -> UploadReceipt:
        if payload.get("key") not in {None, object_key}:
            raise StorageValidationError("回调对象键不匹配")
        size = payload.get("size", payload.get("fsize"))
        mime = str(payload.get("mime", ""))
        sha256 = str(payload.get("sha256", ""))
        etag = str(payload.get("hash", ""))
        if not isinstance(size, int) or not mime or len(sha256) != 64 or not etag:
            raise StorageValidationError("七牛回调元数据不完整")
        if self.stat_resolver:
            remote = self.stat(object_key)
            if remote.size != size or remote.mime != mime or remote.sha256 != sha256:
                raise StorageValidationError("七牛对象元数据不一致")
        return UploadReceipt(object_key=object_key, size=size, mime=mime, sha256=sha256)

    def stat(self, object_key: str) -> ObjectMetadata:
        if not self.stat_resolver:
            raise StorageValidationError("七牛对象状态查询未配置")
        return self.stat_resolver(object_key)

    def create_private_url(self, object_key: str, *, ttl_seconds: int) -> PrivateUrl:
        expires_at = timezone.now() + timedelta(seconds=ttl_seconds)
        separator = "&" if "?" in object_key else "?"
        unsigned = f"{self.domain}/{object_key}{separator}e={int(expires_at.timestamp())}"
        token = f"{self.access_key}:{self._sign(unsigned.encode())}"
        return PrivateUrl(url=f"{unsigned}&token={token}", expires_at=expires_at, token=token)
