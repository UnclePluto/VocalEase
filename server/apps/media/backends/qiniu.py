from __future__ import annotations

import base64
import hashlib
import hmac
import json
from datetime import datetime, timedelta, timezone as datetime_timezone
from typing import Any, Callable, Mapping
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen
from uuid import UUID

from django.conf import settings
from django.utils import timezone

from apps.media.contracts import ObjectMetadata, PrivateUrl, StorageValidationError, UploadGrant, UploadReceipt, build_object_key, validate_media_request


def _urlsafe(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


class QiniuStorageBackend:
    """七牛 Kodo 官方 QBox/PutPolicy/RS stat 协议适配器。"""

    # 七牛官方回调鉴权仅对 application/x-www-form-urlencoded 将原始 body 纳入 QBox 签名。
    callback_content_type = "application/x-www-form-urlencoded"

    def __init__(self, *, access_key: str, secret_key: str, bucket: str, domain: str, callback_url: str, environment: str, stat_transport: Callable[[Request], bytes] | None = None):
        if not all((access_key, secret_key, bucket, domain, callback_url)):
            raise StorageValidationError("七牛私有空间配置不完整")
        self.access_key, self.secret_key, self.bucket = access_key, secret_key, bucket
        self.domain, self.callback_url, self.environment = domain.rstrip("/"), callback_url, environment
        self.stat_transport = stat_transport

    @classmethod
    def from_settings(cls, **kwargs):
        return cls(access_key=settings.QINIU_ACCESS_KEY, secret_key=settings.QINIU_SECRET_KEY, bucket=settings.QINIU_BUCKET, domain=settings.QINIU_DOMAIN, callback_url=settings.QINIU_CALLBACK_URL, environment=settings.MEDIA_ENVIRONMENT, **kwargs)

    def _sign(self, data: bytes) -> str:
        return _urlsafe(hmac.new(self.secret_key.encode(), data, hashlib.sha1).digest())

    def create_upload_grant(self, *, owner_id: UUID, media_type: str, mime: str, size: int) -> UploadGrant:
        validate_media_request(media_type=media_type, mime=mime, size=size)
        object_key = build_object_key(self.environment, media_type)
        deadline = int((timezone.now() + timedelta(seconds=settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS)).timestamp())
        policy = {"scope": f"{self.bucket}:{object_key}", "deadline": deadline, "insertOnly": 1, "fsizeLimit": size, "mimeLimit": mime, "detectMime": 1, "callbackUrl": self.callback_url, "callbackBodyType": self.callback_content_type, "callbackBody": "key=$(key)&hash=$(etag)&fsize=$(fsize)&mime=$(mimeType)"}
        encoded_policy = _urlsafe(json.dumps(policy, separators=(",", ":"), sort_keys=True).encode())
        token = f"{self.access_key}:{self._sign(encoded_policy.encode())}:{encoded_policy}"
        return UploadGrant(object_key=object_key, expires_at=datetime.fromtimestamp(deadline, tz=datetime_timezone.utc), upload_url=settings.QINIU_UPLOAD_URL, upload_token=token, fields={"key": object_key, "token": token})

    def _entry(self, object_key: str) -> str:
        return _urlsafe(f"{self.bucket}:{object_key}".encode())

    def _management_token(self, *, method: str, host: str, path: str, content_type: str) -> str:
        # Kodo 管理 API 的 QiniuToken v2 规范：method/path、Host、Content-Type 和空 body 均参与签名。
        data = f"{method} {path}\nHost: {host}\nContent-Type: {content_type}\n\n".encode()
        return f"Qiniu {self.access_key}:{self._sign(data)}"

    def stat(self, object_key: str) -> ObjectMetadata:
        parsed = urlsplit(settings.QINIU_RS_HOST.rstrip("/"))
        path = f"/stat/{self._entry(object_key)}"
        endpoint = f"{parsed.scheme}://{parsed.netloc}{path}"
        content_type = "application/x-www-form-urlencoded"
        request = Request(endpoint, method="GET", headers={"Content-Type": content_type, "Authorization": self._management_token(method="GET", host=parsed.netloc, path=path, content_type=content_type)})
        try:
            raw = self.stat_transport(request) if self.stat_transport else urlopen(request, timeout=settings.QINIU_STAT_TIMEOUT_SECONDS).read()
            if isinstance(raw, ObjectMetadata):
                return ObjectMetadata(object_key=object_key, size=raw.size, mime=raw.mime, sha256=raw.sha256, etag=raw.etag)
            data = json.loads(raw.decode())
        except Exception as exc:
            raise StorageValidationError("七牛对象状态查询失败") from exc
        try:
            metadata = ObjectMetadata(object_key=object_key, size=int(data["fsize"]), mime=str(data["mimeType"]), etag=str(data["hash"]))
        except (KeyError, TypeError, ValueError) as exc:
            raise StorageValidationError("七牛对象状态响应不完整") from exc
        if not metadata.mime or not metadata.etag or metadata.size < 0:
            raise StorageValidationError("七牛对象状态响应不合法")
        return metadata

    def verify_completion(self, object_key: str, payload: Mapping[str, Any] | None = None) -> UploadReceipt:
        remote = self.stat(object_key)
        if payload is not None and (payload.get("key") != object_key or int(payload.get("fsize", -1)) != remote.size or str(payload.get("mime", "")) != remote.mime or str(payload.get("hash", "")) != remote.etag):
            raise StorageValidationError("七牛回调元数据与可信对象不一致")
        return UploadReceipt(**remote.__dict__)

    def verify_callback_signature(self, *, authorization: str, content_type: str, raw_path_query: str | None = None, callback_url: str | None = None, body: bytes) -> bool:
        if content_type.split(";", 1)[0].strip().lower() != self.callback_content_type:
            return False
        raw_path_query = raw_path_query or (urlsplit(callback_url or "").path + (f"?{urlsplit(callback_url or '').query}" if urlsplit(callback_url or "").query else ""))
        configured = urlsplit(self.callback_url)
        configured_path_query = configured.path + (f"?{configured.query}" if configured.query else "")
        if raw_path_query != configured_path_query:
            return False
        prefix = f"QBox {self.access_key}:"
        # Content-Type 必须精确匹配 policy；该类型下官方 QBox 签名覆盖原始 path/query 与 raw body。
        signing_data = raw_path_query.encode() + b"\n" + body
        return authorization.startswith(prefix) and hmac.compare_digest(self._sign(signing_data), authorization[len(prefix):])

    def sign_callback_for_test(self, raw_path_query: str, body: bytes, content_type: str = "application/x-www-form-urlencoded") -> str:
        return f"QBox {self.access_key}:{self._sign(raw_path_query.encode() + b'\n' + body)}"

    def create_private_url(self, object_key: str, *, ttl_seconds: int) -> PrivateUrl:
        expires_at = timezone.now() + timedelta(seconds=ttl_seconds)
        encoded_key = "/".join(quote(part, safe="") for part in object_key.split("/"))
        unsigned = f"{self.domain}/{encoded_key}?e={int(expires_at.timestamp())}"
        token = f"{self.access_key}:{self._sign(unsigned.encode())}"
        return PrivateUrl(url=f"{unsigned}&token={token}", expires_at=expires_at, token=token)

    def mark_for_cleanup(self, object_key: str) -> None:
        # 删除交给未来受限的 Kodo 管理操作；该标记接口幂等且不在上传请求中删除历史对象。
        return None
