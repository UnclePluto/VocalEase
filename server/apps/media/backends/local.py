from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass
from datetime import timedelta
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import Any, BinaryIO, Mapping
from urllib.parse import quote, urlencode
from uuid import UUID, uuid4

from django.conf import settings
from django.core import signing
from django.utils import timezone

from apps.media.contracts import ObjectMetadata, PrivateUrl, StorageValidationError, UploadGrant, UploadReceipt, build_object_key, validate_media_request


_HEX32 = re.compile(r"^[0-9a-f]{32}$")
_HEX64 = re.compile(r"^[0-9a-f]{64}$")
_MANIFEST_FIELDS = {"version", "generation", "blob", "mime", "size", "sha256"}


@dataclass(frozen=True)
class PreparedLocalUpload:
    object_key: str
    generation: str
    blob: str
    mime: str
    size: int
    sha256: str
    manifest_temp: Path
    pending_marker: Path


@dataclass(frozen=True)
class PublishedLocalUpload:
    prepared: PreparedLocalUpload
    previous_manifest: dict[str, Any] | None


class LocalStorageBackend:
    """不暴露根目录，以不可变 blob + 单清单 CAS 提供本地私有对象。"""

    signing_salt = "vocaease.media.local"
    chunk_size = 64 * 1024

    def __init__(self, *, root: str | Path, signing_secret: str, environment: str):
        self.root = Path(root).resolve()
        self.signer = signing.Signer(key=signing_secret, salt=self.signing_salt)
        self.environment = environment

    @property
    def blobs_root(self) -> Path:
        return self.root / ".blobs"

    @property
    def pending_root(self) -> Path:
        return self.root / ".pending"

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
        manifests_root = (self.root / ".manifests").resolve()
        path = self.root / ".manifests" / f"{object_key}.json"
        if not path.resolve().is_relative_to(manifests_root):
            raise StorageValidationError("媒体清单路径越界")
        return path

    def _blob_path(self, blob: str) -> Path:
        if not isinstance(blob, str) or not _HEX32.fullmatch(blob):
            raise StorageValidationError("媒体清单 blob 标识不合法")
        path = (self.blobs_root / blob).resolve()
        if path.parent != self.blobs_root.resolve():
            raise StorageValidationError("媒体清单 blob 路径越界")
        return path

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

    def _validate_manifest(self, stored: Any, object_key: str) -> dict[str, Any]:
        if not isinstance(stored, dict) or set(stored) != _MANIFEST_FIELDS:
            raise StorageValidationError("媒体清单结构不合法")
        if type(stored["version"]) is not int or stored["version"] != 1 or not _HEX32.fullmatch(stored.get("generation", "")):
            raise StorageValidationError("媒体清单版本不合法")
        self._blob_path(stored.get("blob", ""))
        if type(stored.get("size")) is not int or stored["size"] <= 0:
            raise StorageValidationError("媒体清单大小不合法")
        if not isinstance(stored.get("mime"), str) or not _HEX64.fullmatch(stored.get("sha256", "")):
            raise StorageValidationError("媒体清单字段不合法")
        try:
            media_type = object_key.split("/")[1]
            validate_media_request(media_type=media_type, mime=stored["mime"], size=stored["size"])
        except (IndexError, StorageValidationError) as exc:
            raise StorageValidationError("媒体清单类型不合法") from exc
        return stored

    def _load_manifest(self, object_key: str, *, required: bool = True) -> dict[str, Any] | None:
        path = self._manifest_path(object_key)
        if not path.is_file():
            if required:
                raise StorageValidationError("媒体对象不存在")
            return None
        try:
            with path.open(encoding="utf-8") as source:
                return self._validate_manifest(json.load(source), object_key)
        except StorageValidationError:
            raise
        except (OSError, ValueError, TypeError) as exc:
            raise StorageValidationError("媒体清单损坏") from exc

    def _snapshot(self, object_key: str, *, verify_hash: bool) -> tuple[dict[str, Any], Path]:
        manifest = self._load_manifest(object_key)
        assert manifest is not None
        blob_path = self._blob_path(manifest["blob"])
        try:
            if not blob_path.is_file() or blob_path.stat().st_size != manifest["size"]:
                raise StorageValidationError("媒体清单与 blob 不一致")
        except OSError as exc:
            raise StorageValidationError("媒体 blob 不可访问") from exc
        if verify_hash:
            digest = hashlib.sha256()
            with blob_path.open("rb") as source:
                while chunk := source.read(self.chunk_size):
                    digest.update(chunk)
            if digest.hexdigest() != manifest["sha256"]:
                raise StorageValidationError("媒体对象哈希不一致")
        return manifest, blob_path

    @staticmethod
    def _write_json_sync(path: Path, payload: Mapping[str, Any]) -> None:
        with path.open("w", encoding="utf-8") as target:
            json.dump(payload, target, separators=(",", ":"))
            target.flush(); os.fsync(target.fileno())

    def create_upload_grant(self, *, owner_id: UUID, media_type: str, mime: str, size: int) -> UploadGrant:
        validate_media_request(media_type=media_type, mime=mime, size=size)
        key = build_object_key(self.environment, media_type)
        token, expires_at = self._issue_token(
            {"object_key": key, "owner_id": str(owner_id), "media_type": media_type, "mime": mime, "size": size},
            settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS,
        )
        return UploadGrant(object_key=key, expires_at=expires_at, upload_token=token)

    def prepare_authorized_stream(self, *, object_key: str, token: str, stream: BinaryIO, mime: str) -> PreparedLocalUpload:
        claim = self._read_token(token)
        if claim.get("object_key") != object_key or claim.get("mime") != mime:
            raise StorageValidationError("上传内容与凭证不一致")
        self._path(object_key)
        self.blobs_root.mkdir(parents=True, exist_ok=True)
        self.pending_root.mkdir(parents=True, exist_ok=True)
        manifest_parent = self._manifest_path(object_key).parent
        manifest_parent.mkdir(parents=True, exist_ok=True)
        temporary = NamedTemporaryFile(dir=self.blobs_root, prefix=".upload-", delete=False)
        temporary_path = Path(temporary.name)
        generation = uuid4().hex
        blob = uuid4().hex
        blob_path = self._blob_path(blob)
        marker_path = self.pending_root / f"{generation}.json"
        manifest_temp_path: Path | None = None
        digest = hashlib.sha256(); size = 0
        try:
            with temporary:
                while chunk := stream.read(self.chunk_size):
                    size += len(chunk)
                    if size > claim["size"]:
                        raise StorageValidationError("上传文件大小超出凭证限制")
                    digest.update(chunk); temporary.write(chunk)
                temporary.flush(); os.fsync(temporary.fileno())
            if size != claim["size"]:
                raise StorageValidationError("上传文件大小与凭证不一致")
            os.replace(temporary_path, blob_path)
            manifest = {"version": 1, "generation": generation, "blob": blob, "mime": mime, "size": size, "sha256": digest.hexdigest()}
            manifest_temp = NamedTemporaryFile(dir=manifest_parent, prefix=".manifest-", delete=False, mode="w", encoding="utf-8")
            manifest_temp_path = Path(manifest_temp.name)
            with manifest_temp:
                json.dump(manifest, manifest_temp, separators=(",", ":")); manifest_temp.flush(); os.fsync(manifest_temp.fileno())
            self._write_json_sync(marker_path, {"phase": "prepared", "object_key": object_key, "new": manifest, "manifest_temp": manifest_temp_path.name})
            return PreparedLocalUpload(object_key, generation, blob, mime, size, digest.hexdigest(), manifest_temp_path, marker_path)
        except Exception:
            temporary_path.unlink(missing_ok=True)
            if manifest_temp_path:
                manifest_temp_path.unlink(missing_ok=True)
            blob_path.unlink(missing_ok=True); marker_path.unlink(missing_ok=True)
            raise

    def publish_manifest(self, prepared: PreparedLocalUpload, *, expected_generation: str) -> PublishedLocalUpload:
        previous = self._load_manifest(prepared.object_key, required=False)
        actual = previous["generation"] if previous else ""
        if actual != expected_generation:
            raise StorageValidationError("媒体清单版本冲突")
        marker = {"phase": "published", "object_key": prepared.object_key, "manifest_temp": prepared.manifest_temp.name, "new": {
            "version": 1, "generation": prepared.generation, "blob": prepared.blob,
            "mime": prepared.mime, "size": prepared.size, "sha256": prepared.sha256,
        }, "previous": previous}
        self._write_json_sync(prepared.pending_marker, marker)
        os.replace(prepared.manifest_temp, self._manifest_path(prepared.object_key))
        return PublishedLocalUpload(prepared=prepared, previous_manifest=previous)

    def discard_prepared(self, prepared: PreparedLocalUpload) -> None:
        prepared.manifest_temp.unlink(missing_ok=True)
        prepared.pending_marker.unlink(missing_ok=True)
        self._blob_path(prepared.blob).unlink(missing_ok=True)

    def compensate_publish(self, published: PublishedLocalUpload) -> None:
        current = self._load_manifest(published.prepared.object_key, required=False)
        if current and current["generation"] == published.prepared.generation:
            path = self._manifest_path(published.prepared.object_key)
            if published.previous_manifest is None:
                path.unlink(missing_ok=True)
            else:
                restore = NamedTemporaryFile(dir=path.parent, prefix=".manifest-rollback-", delete=False, mode="w", encoding="utf-8")
                with restore:
                    json.dump(published.previous_manifest, restore, separators=(",", ":")); restore.flush(); os.fsync(restore.fileno())
                os.replace(restore.name, path)
        self._blob_path(published.prepared.blob).unlink(missing_ok=True)
        published.prepared.pending_marker.unlink(missing_ok=True)

    def finalize_publish(self, published: PublishedLocalUpload) -> None:
        current = self._load_manifest(published.prepared.object_key)
        if current["generation"] != published.prepared.generation:
            raise StorageValidationError("媒体清单版本冲突")
        if published.previous_manifest and published.previous_manifest["blob"] != published.prepared.blob:
            self._blob_path(published.previous_manifest["blob"]).unlink(missing_ok=True)
        published.prepared.pending_marker.unlink(missing_ok=True)

    def recover_pending(self, object_key: str, *, expected_generation: str) -> None:
        """依据数据库 generation 恢复进程中断留下的 prepared/published marker。"""
        self._path(object_key)
        if not self.pending_root.is_dir():
            return
        for marker_path in self.pending_root.glob("*.json"):
            try:
                marker = json.loads(marker_path.read_text(encoding="utf-8"))
                if marker.get("object_key") != object_key:
                    continue
                new = self._validate_manifest(marker.get("new"), object_key)
                temp_name = marker.get("manifest_temp", "")
                if not isinstance(temp_name, str) or not temp_name.startswith(".manifest-") or Path(temp_name).name != temp_name:
                    raise StorageValidationError("待恢复清理标记不合法")
                manifest_temp = self._manifest_path(object_key).parent / temp_name
                current = self._load_manifest(object_key, required=False)
                actual_generation = current["generation"] if current else ""
                if actual_generation == new["generation"] and expected_generation != new["generation"]:
                    previous = marker.get("previous")
                    if previous is not None:
                        previous = self._validate_manifest(previous, object_key)
                    previous_generation = previous["generation"] if previous else ""
                    if previous_generation != expected_generation:
                        raise StorageValidationError("待恢复清单与数据库版本不一致")
                    path = self._manifest_path(object_key)
                    if previous is None:
                        path.unlink(missing_ok=True)
                    else:
                        restore = NamedTemporaryFile(dir=path.parent, prefix=".manifest-recover-", delete=False, mode="w", encoding="utf-8")
                        with restore:
                            json.dump(previous, restore, separators=(",", ":")); restore.flush(); os.fsync(restore.fileno())
                        os.replace(restore.name, path)
                    actual_generation = expected_generation
                if actual_generation == expected_generation and expected_generation != new["generation"]:
                    self._blob_path(new["blob"]).unlink(missing_ok=True)
                    manifest_temp.unlink(missing_ok=True)
                    marker_path.unlink(missing_ok=True)
            except StorageValidationError:
                raise
            except (OSError, ValueError, TypeError) as exc:
                raise StorageValidationError("待恢复清理标记损坏") from exc

    def finalize_generation(self, object_key: str, generation: str) -> None:
        if not self.pending_root.is_dir():
            return
        for marker_path in self.pending_root.glob("*.json"):
            try:
                marker = json.loads(marker_path.read_text(encoding="utf-8"))
                if marker.get("object_key") != object_key or marker.get("new", {}).get("generation") != generation:
                    continue
                current = self._load_manifest(object_key)
                if current["generation"] != generation:
                    raise StorageValidationError("媒体清单版本冲突")
                previous = marker.get("previous")
                if previous:
                    previous = self._validate_manifest(previous, object_key)
                    if previous["blob"] != current["blob"]:
                        self._blob_path(previous["blob"]).unlink(missing_ok=True)
                marker_path.unlink(missing_ok=True)
            except StorageValidationError:
                raise
            except (OSError, ValueError, TypeError) as exc:
                raise StorageValidationError("待清理标记损坏") from exc

    def write_authorized_stream(self, *, object_key: str, token: str, stream: BinaryIO, mime: str, before_publish=None) -> None:
        prepared = self.prepare_authorized_stream(object_key=object_key, token=token, stream=stream, mime=mime)
        try:
            previous = self._load_manifest(object_key, required=False)
            if before_publish:
                before_publish()
            published = self.publish_manifest(prepared, expected_generation=previous["generation"] if previous else "")
        except Exception:
            self.discard_prepared(prepared)
            raise
        self.finalize_publish(published)

    def write_upload(self, *, grant: UploadGrant, content: bytes, mime: str) -> None:
        from io import BytesIO
        self.write_authorized_stream(object_key=grant.object_key, token=grant.upload_token, stream=BytesIO(content), mime=mime)

    def stat(self, object_key: str, *, expected_generation: str | None = None) -> ObjectMetadata:
        # 哈希在受控流式写入时计算并写入不可变 manifest；stat 只核对 schema、路径与文件大小。
        manifest, _ = self._snapshot(object_key, verify_hash=False)
        if expected_generation is not None and manifest["generation"] != expected_generation:
            raise StorageValidationError("媒体清单版本不一致")
        return ObjectMetadata(object_key=object_key, size=manifest["size"], mime=manifest["mime"], sha256=manifest["sha256"], generation=manifest["generation"], blob=manifest["blob"])

    def verify_completion(self, object_key: str, payload: Mapping[str, Any] | None = None, *, expected_generation: str | None = None) -> UploadReceipt:
        metadata = self.stat(object_key, expected_generation=expected_generation)
        return UploadReceipt(**metadata.__dict__)

    def create_private_url(self, object_key: str, *, ttl_seconds: int, asset_id: UUID | str | None = None, expected_generation: str | None = None) -> PrivateUrl:
        manifest, _ = self._snapshot(object_key, verify_hash=False)
        if expected_generation is not None and manifest["generation"] != expected_generation:
            raise StorageValidationError("媒体清单版本不一致")
        token, expires_at = self._issue_token({
            "object_key": object_key, "kind": "private", "asset_id": str(asset_id or ""),
            "generation": manifest["generation"], "blob": manifest["blob"],
        }, ttl_seconds)
        segments = "/".join(quote(segment, safe="") for segment in object_key.split("/"))
        return PrivateUrl(url=f"/api/v1/media/private/{segments}?{urlencode({'signature': token})}", expires_at=expires_at, token=token)

    def authorize_private(self, token: str, object_key: str, *, asset_id: UUID | str | None = None, expected_generation: str | None = None) -> Path:
        payload = self._read_token(token)
        if payload.get("kind") != "private" or payload.get("object_key") != object_key:
            raise StorageValidationError("下载签名无效")
        if asset_id is not None and payload.get("asset_id") != str(asset_id):
            raise StorageValidationError("下载签名资产不匹配")
        manifest, blob_path = self._snapshot(object_key, verify_hash=False)
        if expected_generation is not None and manifest["generation"] != expected_generation:
            raise StorageValidationError("媒体清单版本不一致")
        if payload.get("generation") != manifest["generation"] or payload.get("blob") != manifest["blob"]:
            raise StorageValidationError("下载清单版本已变化")
        return blob_path

    def open_authorized_private(self, token: str, object_key: str, *, asset_id: UUID | str | None = None, expected_generation: str | None = None) -> BinaryIO:
        # 在同一份已验证 manifest 快照上打开；即使随后回收旧路径，已打开 fd 仍只读该 blob。
        return self.authorize_private(token, object_key, asset_id=asset_id, expected_generation=expected_generation).open("rb")

    def read_private(self, token: str) -> bytes:
        payload = self._read_token(token)
        if payload.get("kind") != "private":
            raise StorageValidationError("下载签名无效")
        with self.authorize_private(token, payload["object_key"], asset_id=payload.get("asset_id", "")).open("rb") as source:
            return source.read()

    def mark_for_cleanup(self, object_key: str) -> None:
        self._path(object_key)
