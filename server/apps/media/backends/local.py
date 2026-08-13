from __future__ import annotations

import hashlib
from datetime import timedelta
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import urlencode
from uuid import UUID

from django.core import signing
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


class LocalStorageBackend:
    """受控本地对象存储；MEDIA_ROOT 永不作为静态目录暴露。"""

    signing_salt = "vocaease.media.local"

    def __init__(self, *, root: str | Path, signing_secret: str, environment: str):
        self.root = Path(root).resolve()
        self.signer = signing.Signer(key=signing_secret, salt=self.signing_salt)
        self.environment = environment

    def _path(self, object_key: str) -> Path:
        if not object_key or object_key.startswith("/") or "\\" in object_key or any(part in {"", ".", ".."} for part in object_key.split("/")):
            raise StorageValidationError("对象键不合法")
        path = (self.root / object_key).resolve()
        if self.root != path and self.root not in path.parents:
            raise StorageValidationError("对象键越界")
        return path

    def _issue_token(self, value: Mapping[str, Any], ttl_seconds: int) -> tuple[str, object]:
        expires_at = timezone.now() + timedelta(seconds=ttl_seconds)
        payload = dict(value)
        payload["expires_at"] = expires_at.timestamp()
        return self.signer.sign_object(payload), expires_at

    def _read_token(self, token: str) -> dict[str, Any]:
        try:
            payload = self.signer.unsign_object(token)
        except signing.BadSignature as exc:
            raise StorageValidationError("签名无效") from exc
        if float(payload.get("expires_at", 0)) <= timezone.now().timestamp():
            raise StorageValidationError("签名已过期")
        return payload

    def create_upload_grant(self, *, owner_id: UUID, media_type: str, mime: str, size: int) -> UploadGrant:
        validate_media_request(media_type=media_type, mime=mime, size=size)
        key = build_object_key(self.environment, media_type)
        token, expires_at = self._issue_token(
            {"object_key": key, "owner_id": str(owner_id), "media_type": media_type, "mime": mime, "size": size},
            settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS,
        )
        return UploadGrant(object_key=key, expires_at=expires_at, upload_token=token)

    def write_upload(self, *, grant: UploadGrant, content: bytes, mime: str) -> None:
        payload = self._read_token(grant.upload_token)
        if payload["object_key"] != grant.object_key or payload["mime"] != mime or payload["size"] != len(content):
            raise StorageValidationError("上传内容与凭证不一致")
        path = self._path(grant.object_key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def write_authorized_upload(self, *, object_key: str, token: str, content: bytes, mime: str) -> None:
        grant_payload = self._read_token(token)
        if grant_payload["object_key"] != object_key:
            raise StorageValidationError("上传对象不匹配")
        grant = UploadGrant(object_key=object_key, expires_at=timezone.now(), upload_token=token)
        self.write_upload(grant=grant, content=content, mime=mime)

    def stat(self, object_key: str) -> ObjectMetadata:
        path = self._path(object_key)
        if not path.is_file():
            raise StorageValidationError("媒体对象不存在")
        content = path.read_bytes()
        return ObjectMetadata(object_key=object_key, size=len(content), mime="", sha256=hashlib.sha256(content).hexdigest())

    def verify_completion(self, object_key: str, payload: Mapping[str, Any]) -> UploadReceipt:
        metadata = self.stat(object_key)
        if int(payload.get("size", -1)) != metadata.size:
            raise StorageValidationError("对象大小不一致")
        sha256 = str(payload.get("sha256", ""))
        if sha256 != metadata.sha256:
            raise StorageValidationError("对象哈希不一致")
        mime = str(payload.get("mime", ""))
        if not mime:
            raise StorageValidationError("缺少对象 MIME 类型")
        return UploadReceipt(object_key=object_key, size=metadata.size, mime=mime, sha256=metadata.sha256)

    def create_private_url(self, object_key: str, *, ttl_seconds: int) -> PrivateUrl:
        self._path(object_key)
        token, expires_at = self._issue_token({"object_key": object_key, "kind": "private"}, ttl_seconds)
        return PrivateUrl(url=f"/api/v1/media/private/{object_key}?{urlencode({'signature': token})}", expires_at=expires_at, token=token)

    def read_private(self, token: str) -> bytes:
        payload = self._read_token(token)
        if payload.get("kind") != "private":
            raise StorageValidationError("下载签名无效")
        return self._path(str(payload["object_key"])).read_bytes()
