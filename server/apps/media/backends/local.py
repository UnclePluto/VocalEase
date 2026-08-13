from __future__ import annotations

import hashlib
import json
import os
from datetime import timedelta
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import Any, BinaryIO, Mapping
from urllib.parse import quote, urlencode
from uuid import UUID
from uuid import uuid4

from django.conf import settings
from django.core import signing
from django.utils import timezone

from apps.media.contracts import ObjectMetadata, PrivateUrl, StorageValidationError, UploadGrant, UploadReceipt, build_object_key, validate_media_request


class LocalStorageBackend:
    """不暴露文件根目录、完全由签名 API 控制的本地对象存储。"""

    signing_salt = "vocaease.media.local"
    chunk_size = 64 * 1024

    def __init__(self, *, root: str | Path, signing_secret: str, environment: str):
        self.root = Path(root).resolve()
        self.signer = signing.Signer(key=signing_secret, salt=self.signing_salt)
        self.environment = environment

    def _path(self, object_key: str) -> Path:
        segments = object_key.split("/")
        if (
            not object_key or object_key.startswith("/") or "\\" in object_key or "%" in object_key
            or any(part in {"", ".", ".."} or any(char.isspace() or ord(char) < 32 for char in part) for part in segments)
        ):
            raise StorageValidationError("对象键不合法")
        path = (self.root / object_key).resolve()
        if self.root != path and self.root not in path.parents:
            raise StorageValidationError("对象键越界")
        return path

    def _manifest_path(self, object_key: str) -> Path:
        self._path(object_key)
        return self.root / ".manifests" / f"{object_key}.json"

    def _blob_path(self, blob_id: str) -> Path:
        return self.root / ".blobs" / blob_id

    def _issue_token(self, value: Mapping[str, Any], ttl_seconds: int):
        expires_at = timezone.now() + timedelta(seconds=ttl_seconds)
        return self.signer.sign_object({**value, "expires_at": expires_at.timestamp()}), expires_at

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

    def write_authorized_stream(self, *, object_key: str, token: str, stream: BinaryIO, mime: str) -> None:
        claim = self._read_token(token)
        if claim.get("object_key") != object_key or claim.get("mime") != mime:
            raise StorageValidationError("上传内容与凭证不一致")
        path = self._path(object_key)
        self.root.joinpath(".blobs").mkdir(parents=True, exist_ok=True)
        self._manifest_path(object_key).parent.mkdir(parents=True, exist_ok=True)
        temporary = NamedTemporaryFile(dir=self.root / ".blobs", prefix=".upload-", delete=False)
        temporary_path = Path(temporary.name)
        digest = hashlib.sha256()
        size = 0
        try:
            with temporary:
                while chunk := stream.read(self.chunk_size):
                    size += len(chunk)
                    if size > claim["size"]:
                        raise StorageValidationError("上传文件大小超出凭证限制")
                    digest.update(chunk)
                    temporary.write(chunk)
            if size != claim["size"]:
                raise StorageValidationError("上传文件大小与凭证不一致")
            blob_id = uuid4().hex
            blob_path = self._blob_path(blob_id)
            os.replace(temporary_path, blob_path)
            manifest_tmp = NamedTemporaryFile(dir=self._manifest_path(object_key).parent, prefix=".manifest-", delete=False, mode="w", encoding="utf-8")
            with manifest_tmp:
                json.dump({"blob": blob_id, "mime": mime, "size": size, "sha256": digest.hexdigest()}, manifest_tmp)
                manifest_tmp.flush(); os.fsync(manifest_tmp.fileno())
            os.replace(manifest_tmp.name, self._manifest_path(object_key))
        except Exception:
            temporary_path.unlink(missing_ok=True)
            raise

    def write_upload(self, *, grant: UploadGrant, content: bytes, mime: str) -> None:
        """仅供契约测试使用；HTTP 层一律传递流。"""
        from io import BytesIO
        self.write_authorized_stream(object_key=grant.object_key, token=grant.upload_token, stream=BytesIO(content), mime=mime)

    def stat(self, object_key: str) -> ObjectMetadata:
        manifest_path = self._manifest_path(object_key)
        if not manifest_path.is_file():
            raise StorageValidationError("媒体对象不存在")
        try:
            with manifest_path.open(encoding="utf-8") as metadata_file:
                stored = json.load(metadata_file)
        except (OSError, ValueError, TypeError) as exc:
            raise StorageValidationError("媒体对象元数据损坏") from exc
        path = self._blob_path(stored.get("blob", ""))
        size = path.stat().st_size if path.is_file() else -1
        if size != stored.get("size") or not stored.get("mime") or len(stored.get("sha256", "")) != 64:
            raise StorageValidationError("媒体对象元数据不一致")
        digest = hashlib.sha256()
        with path.open("rb") as source:
            while chunk := source.read(self.chunk_size):
                digest.update(chunk)
        if digest.hexdigest() != stored["sha256"]:
            raise StorageValidationError("媒体对象哈希不一致")
        return ObjectMetadata(object_key=object_key, size=size, mime=stored["mime"], sha256=stored["sha256"])

    def verify_completion(self, object_key: str, payload: Mapping[str, Any] | None = None) -> UploadReceipt:
        metadata = self.stat(object_key)
        return UploadReceipt(**metadata.__dict__)

    def create_private_url(self, object_key: str, *, ttl_seconds: int) -> PrivateUrl:
        self.stat(object_key)
        token, expires_at = self._issue_token({"object_key": object_key, "kind": "private"}, ttl_seconds)
        segments = "/".join(quote(segment, safe="") for segment in object_key.split("/"))
        return PrivateUrl(url=f"/api/v1/media/private/{segments}?{urlencode({'signature': token})}", expires_at=expires_at, token=token)

    def authorize_private(self, token: str, object_key: str) -> Path:
        payload = self._read_token(token)
        if payload.get("kind") != "private" or payload.get("object_key") != object_key:
            raise StorageValidationError("下载签名无效")
        self.stat(object_key)
        with self._manifest_path(object_key).open(encoding="utf-8") as source:
            return self._blob_path(json.load(source)["blob"])

    def read_private(self, token: str) -> bytes:
        """兼容旧契约测试；生产下载端点不会调用此方法。"""
        payload = self._read_token(token)
        if payload.get("kind") != "private":
            raise StorageValidationError("下载签名无效")
        with self.authorize_private(token, payload["object_key"]).open("rb") as source:
            return source.read()

    def mark_for_cleanup(self, object_key: str) -> None:
        # 仅由服务层持久化待清理标记；实际删除交给未来受限 worker。
        self._path(object_key)
