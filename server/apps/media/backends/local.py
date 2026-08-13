from __future__ import annotations

import hashlib
import fcntl
import json
import os
import re
import stat
from contextlib import contextmanager
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
_MANIFEST_FIELDS = {"version", "object_key", "asset_id", "generation", "blob", "mime", "size", "sha256"}
_MARKER_FIELDS = {"version", "phase", "object_key", "asset_id", "new", "previous", "manifest_temp"}


@dataclass(frozen=True)
class PreparedLocalUpload:
    object_key: str
    asset_id: str
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
        # 保留调用方给出的真实路径形态；不能先 resolve，否则会掩盖 root 本身是 symlink。
        self.root = Path(root).absolute()
        self.signer = signing.Signer(key=signing_secret, salt=self.signing_salt)
        self.environment = environment

    @property
    def blobs_root(self) -> Path:
        return self.root / ".blobs"

    @property
    def pending_root(self) -> Path:
        return self.root / ".pending"

    @property
    def locks_root(self) -> Path:
        return self.root / ".locks"

    def _safe_directory(self, path: Path, *, create: bool = True) -> Path:
        try:
            if create:
                path.mkdir(parents=True, exist_ok=True)
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
                raise StorageValidationError("媒体存储目录不合法")
            resolved_root = self.root.resolve(strict=True)
            resolved = path.resolve(strict=True)
            if path != self.root and resolved.parent != resolved_root and resolved_root not in resolved.parents:
                raise StorageValidationError("媒体存储目录越界")
            return path
        except StorageValidationError:
            raise
        except OSError as exc:
            raise StorageValidationError("媒体存储目录不可访问") from exc

    def _ensure_internal_roots(self) -> None:
        self._safe_directory(self.root)
        for path in (self.root / ".manifests", self.blobs_root, self.locks_root, self.pending_root):
            self._safe_directory(path)

    def _safe_nested_parent(self, base: Path, segments: list[str]) -> Path:
        current = self._safe_directory(base)
        for segment in segments:
            current = self._safe_directory(current / segment)
        return current

    @staticmethod
    def _object_digest(object_key: str) -> str:
        return hashlib.sha256(object_key.encode("utf-8")).hexdigest()

    @staticmethod
    def _validate_object_key_only(object_key: str) -> list[str]:
        segments = object_key.split("/")
        if (
            not object_key or object_key.startswith("/") or "\\" in object_key or "%" in object_key
            or any(part in {"", ".", ".."} or any(char.isspace() or ord(char) < 32 for char in part) for part in segments)
        ):
            raise StorageValidationError("对象键不合法")
        return segments

    def _path(self, object_key: str) -> Path:
        segments = self._validate_object_key_only(object_key)
        self._safe_directory(self.root)
        parent = self._safe_nested_parent(self.root, segments[:-1])
        path = parent / segments[-1]
        if path.exists() or path.is_symlink():
            try:
                info = path.lstat()
            except OSError as exc:
                raise StorageValidationError("对象路径不可访问") from exc
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
                raise StorageValidationError("对象路径不合法")
        return path

    def _manifest_path(self, object_key: str) -> Path:
        self._path(object_key)
        manifests_root = self._safe_directory(self.root / ".manifests")
        segments = object_key.split("/")
        parent = self._safe_nested_parent(manifests_root, segments[:-1])
        path = parent / f"{segments[-1]}.json"
        if path.exists() or path.is_symlink():
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
                raise StorageValidationError("媒体清单路径不合法")
        return path

    def _legacy_metadata_path(self, object_key: str) -> Path:
        path = self._path(object_key)
        return path.with_name(f".{path.name}.metadata.json")

    def _blob_path(self, blob: str, object_key: str) -> Path:
        expected_prefix = self._object_digest(object_key)[:16]
        if not isinstance(blob, str) or not re.fullmatch(rf"{expected_prefix}-[0-9a-f]{{32}}", blob):
            raise StorageValidationError("媒体清单 blob 标识不合法")
        root = self._safe_directory(self.blobs_root)
        path = root / blob
        if path.exists() or path.is_symlink():
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
                raise StorageValidationError("媒体清单 blob 路径不合法")
        return path

    def _marker_path(self, object_key: str, generation: str) -> Path:
        if not _HEX32.fullmatch(generation):
            raise StorageValidationError("媒体清单版本不合法")
        return self.pending_root / f"{self._object_digest(object_key)}-{generation}.json"

    @contextmanager
    def object_lock(self, object_key: str):
        self._path(object_key)
        locks_root = self._safe_directory(self.locks_root)
        lock_path = locks_root / f"{self._object_digest(object_key)}.lock"
        flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
        try:
            descriptor = os.open(lock_path, flags, 0o600)
        except OSError as exc:
            raise StorageValidationError("无法取得对象文件锁") from exc
        with os.fdopen(descriptor, "a+b") as lock_file:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)

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
        if stored.get("object_key") != object_key:
            raise StorageValidationError("媒体清单对象不匹配")
        try:
            if str(UUID(str(stored.get("asset_id", "")))) != stored.get("asset_id"):
                raise ValueError
        except (TypeError, ValueError, AttributeError) as exc:
            raise StorageValidationError("媒体清单资产标识不合法") from exc
        self._blob_path(stored.get("blob", ""), object_key)
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
        self._ensure_internal_roots()
        manifest = self._load_manifest(object_key)
        assert manifest is not None
        blob_path = self._blob_path(manifest["blob"], object_key)
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

    def _write_json_sync(self, path: Path, payload: Mapping[str, Any]) -> None:
        if path.exists() or path.is_symlink():
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
                raise StorageValidationError("媒体状态文件路径不合法")
        flags = os.O_CREAT | os.O_WRONLY | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0)
        try:
            descriptor = os.open(path, flags, 0o600)
        except OSError as exc:
            raise StorageValidationError("媒体状态文件不可写") from exc
        with os.fdopen(descriptor, "w", encoding="utf-8") as target:
            json.dump(payload, target, separators=(",", ":"))
            target.flush(); os.fsync(target.fileno())

    def _validate_marker(self, marker: Any, marker_path: Path, *, object_key: str, asset_id: UUID | str) -> dict[str, Any]:
        normalized_asset_id = str(UUID(str(asset_id)))
        if not isinstance(marker, dict) or set(marker) != _MARKER_FIELDS or marker.get("version") != 1:
            raise StorageValidationError("待恢复标记结构不合法")
        if marker.get("phase") not in {"prepared", "published"} or marker.get("object_key") != object_key or marker.get("asset_id") != normalized_asset_id:
            raise StorageValidationError("待恢复标记所有权不合法")
        new = self._validate_manifest(marker.get("new"), object_key)
        if new["asset_id"] != normalized_asset_id or marker_path != self._marker_path(object_key, new["generation"]):
            raise StorageValidationError("待恢复标记路径不合法")
        previous = marker.get("previous")
        if previous is not None:
            previous = self._validate_manifest(previous, object_key)
            if previous["asset_id"] != normalized_asset_id:
                raise StorageValidationError("待恢复标记历史所有权不合法")
        if marker["phase"] == "prepared" and previous is not None:
            raise StorageValidationError("待恢复标记阶段不合法")
        temp_name = marker.get("manifest_temp")
        expected_prefix = f".manifest-{self._object_digest(object_key)}-{UUID(normalized_asset_id).hex}-{new['generation']}-"
        if not isinstance(temp_name, str) or not temp_name.startswith(expected_prefix) or Path(temp_name).name != temp_name:
            raise StorageValidationError("待恢复标记临时文件不合法")
        temp_path = self._manifest_path(object_key).parent / temp_name
        if temp_path.exists() or temp_path.is_symlink():
            info = temp_path.lstat()
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
                raise StorageValidationError("待恢复标记临时文件路径不合法")
        return {**marker, "new": new, "previous": previous}

    def _load_marker(self, marker_path: Path, *, object_key: str, asset_id: UUID | str) -> dict[str, Any]:
        try:
            marker = json.loads(marker_path.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError) as exc:
            raise StorageValidationError("待恢复标记损坏") from exc
        return self._validate_marker(marker, marker_path, object_key=object_key, asset_id=asset_id)

    def pending_marker_claims(self) -> tuple[list[tuple[UUID, str]], int]:
        self._ensure_internal_roots()
        claims: list[tuple[UUID, str]] = []
        invalid = 0
        for marker_path in self.pending_root.glob("*.json"):
            try:
                info = marker_path.lstat()
                if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
                    raise StorageValidationError("待恢复标记路径不合法")
                raw = json.loads(marker_path.read_text(encoding="utf-8"))
                object_key = raw.get("object_key") if isinstance(raw, dict) else ""
                asset_id = UUID(str(raw.get("asset_id"))) if isinstance(raw, dict) else None
                self._validate_marker(raw, marker_path, object_key=object_key, asset_id=asset_id)
                claims.append((asset_id, object_key))
            except (OSError, ValueError, TypeError, AttributeError, StorageValidationError):
                invalid += 1
        return claims, invalid

    def create_upload_grant(self, *, owner_id: UUID, media_type: str, mime: str, size: int) -> UploadGrant:
        validate_media_request(media_type=media_type, mime=mime, size=size)
        key = build_object_key(self.environment, media_type)
        token, expires_at = self._issue_token(
            {"object_key": key, "owner_id": str(owner_id), "media_type": media_type, "mime": mime, "size": size},
            settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS,
        )
        return UploadGrant(object_key=key, expires_at=expires_at, upload_token=token)

    def migrate_legacy_layout(self, *, object_key: str, asset_id: UUID | str, generation: str, expected_size: int, expected_mime: str, expected_sha256: str) -> bool:
        """在调用方持有 DB 行锁时，将旧 data+sidecar 复制为不可变 blob+manifest。"""
        self._ensure_internal_roots()
        normalized_asset_id = str(UUID(str(asset_id)))
        with self.object_lock(object_key):
            current = self._load_manifest(object_key, required=False)
            if current:
                if current["asset_id"] != normalized_asset_id or current["generation"] != generation:
                    raise StorageValidationError("旧媒体清单与数据库不一致")
                self._path(object_key).unlink(missing_ok=True)
                self._legacy_metadata_path(object_key).unlink(missing_ok=True)
                return False
            if not _HEX32.fullmatch(generation):
                raise StorageValidationError("旧媒体 generation 不合法")
            legacy_path = self._path(object_key)
            sidecar_path = self._legacy_metadata_path(object_key)
            if not legacy_path.is_file() or not sidecar_path.is_file():
                raise StorageValidationError("旧媒体文件不存在")
            try:
                with sidecar_path.open(encoding="utf-8") as source:
                    sidecar = json.load(source)
            except (OSError, ValueError, TypeError) as exc:
                raise StorageValidationError("旧媒体元数据损坏") from exc
            if set(sidecar) != {"mime", "size", "sha256"} or sidecar != {"mime": expected_mime, "size": expected_size, "sha256": expected_sha256}:
                raise StorageValidationError("旧媒体元数据与数据库不一致")
        # 复制/哈希在文件锁外完成；DB 行锁仍由服务层持有。发布与普通上传走同一 marker/CAS 协议。
        with legacy_path.open("rb") as source:
            prepared = self._prepare_stream(
                object_key=object_key, stream=source, mime=expected_mime, asset_id=normalized_asset_id,
                expected_size=expected_size, expected_sha256=expected_sha256, generation=generation,
            )
        try:
            published = self.publish_manifest(prepared, expected_generation="")
        except Exception:
            self.discard_prepared(prepared)
            raise
        self.finalize_publish(published)
        with self.object_lock(object_key):
            legacy_path.unlink(missing_ok=True)
            sidecar_path.unlink(missing_ok=True)
        return True

    def _prepare_stream(self, *, object_key: str, stream: BinaryIO, mime: str, asset_id: UUID | str,
                        expected_size: int, expected_sha256: str | None = None,
                        generation: str | None = None) -> PreparedLocalUpload:
        self._ensure_internal_roots()
        normalized_asset_id = str(UUID(str(asset_id)))
        generation = generation or uuid4().hex
        if not _HEX32.fullmatch(generation):
            raise StorageValidationError("媒体清单版本不合法")
        manifest_parent = self._manifest_path(object_key).parent
        digest_name = self._object_digest(object_key)
        blob = f"{digest_name[:16]}-{generation}"
        blob_path = self._blob_path(blob, object_key)
        marker_path = self._marker_path(object_key, generation)
        temporary = NamedTemporaryFile(dir=self.blobs_root, prefix=f".upload-{digest_name[:16]}-", delete=False)
        temporary_path = Path(temporary.name)
        manifest_temp_path: Path | None = None
        digest = hashlib.sha256(); size = 0
        try:
            with temporary:
                while chunk := stream.read(self.chunk_size):
                    size += len(chunk)
                    if size > expected_size:
                        raise StorageValidationError("上传文件大小超出凭证限制")
                    digest.update(chunk); temporary.write(chunk)
                temporary.flush(); os.fsync(temporary.fileno())
            if size != expected_size:
                raise StorageValidationError("上传文件大小与凭证不一致")
            sha256 = digest.hexdigest()
            if expected_sha256 is not None and sha256 != expected_sha256:
                raise StorageValidationError("媒体文件与可信回执不一致")
            os.replace(temporary_path, blob_path)
            manifest = {"version": 1, "object_key": object_key, "asset_id": normalized_asset_id, "generation": generation, "blob": blob, "mime": mime, "size": size, "sha256": sha256}
            prefix = f".manifest-{digest_name}-{UUID(normalized_asset_id).hex}-{generation}-"
            manifest_temp = NamedTemporaryFile(dir=manifest_parent, prefix=prefix, delete=False, mode="w", encoding="utf-8")
            manifest_temp_path = Path(manifest_temp.name)
            with manifest_temp:
                json.dump(manifest, manifest_temp, separators=(",", ":")); manifest_temp.flush(); os.fsync(manifest_temp.fileno())
            self._write_json_sync(marker_path, {"version": 1, "phase": "prepared", "object_key": object_key, "asset_id": normalized_asset_id, "new": manifest, "previous": None, "manifest_temp": manifest_temp_path.name})
            return PreparedLocalUpload(object_key, normalized_asset_id, generation, blob, mime, size, sha256, manifest_temp_path, marker_path)
        except Exception:
            temporary_path.unlink(missing_ok=True)
            if manifest_temp_path:
                manifest_temp_path.unlink(missing_ok=True)
            blob_path.unlink(missing_ok=True); marker_path.unlink(missing_ok=True)
            raise

    def prepare_authorized_stream(self, *, object_key: str, token: str, stream: BinaryIO, mime: str, asset_id: UUID | str | None = None) -> PreparedLocalUpload:
        claim = self._read_token(token)
        if claim.get("object_key") != object_key or claim.get("mime") != mime:
            raise StorageValidationError("上传内容与凭证不一致")
        normalized_asset_id = str(UUID(str(asset_id))) if asset_id is not None else str(UUID(int=0))
        return self._prepare_stream(
            object_key=object_key, stream=stream, mime=mime, asset_id=normalized_asset_id,
            expected_size=int(claim["size"]),
        )

    def publish_manifest(self, prepared: PreparedLocalUpload, *, expected_generation: str) -> PublishedLocalUpload:
        with self.object_lock(prepared.object_key):
            prepared_marker = self._load_marker(prepared.pending_marker, object_key=prepared.object_key, asset_id=prepared.asset_id)
            previous = self._load_manifest(prepared.object_key, required=False)
            actual = previous["generation"] if previous else ""
            if actual != expected_generation:
                raise StorageValidationError("媒体清单版本冲突")
            if previous and previous["asset_id"] != prepared.asset_id:
                raise StorageValidationError("媒体清单资产冲突")
            marker = {**prepared_marker, "phase": "published", "previous": previous}
            self._write_json_sync(prepared.pending_marker, marker)
            os.replace(prepared.manifest_temp, self._manifest_path(prepared.object_key))
            return PublishedLocalUpload(prepared=prepared, previous_manifest=previous)

    def discard_prepared(self, prepared: PreparedLocalUpload) -> None:
        with self.object_lock(prepared.object_key):
            current = self._load_manifest(prepared.object_key, required=False)
            if current and current["generation"] == prepared.generation:
                raise StorageValidationError("不可丢弃已发布清单")
            prepared.manifest_temp.unlink(missing_ok=True)
            prepared.pending_marker.unlink(missing_ok=True)
            self._blob_path(prepared.blob, prepared.object_key).unlink(missing_ok=True)

    def finalize_publish(self, published: PublishedLocalUpload) -> None:
        self.finalize_generation(published.prepared.object_key, published.prepared.generation, asset_id=published.prepared.asset_id)

    def _blob_is_referenced(self, blob: str, *, exclude_marker: Path | None = None) -> bool:
        manifests_root = self.root / ".manifests"
        if not manifests_root.is_dir() or manifests_root.is_symlink():
            return False
        for manifest_path in manifests_root.rglob("*.json"):
            try:
                with manifest_path.open(encoding="utf-8") as source:
                    if json.load(source).get("blob") == blob:
                        return True
            except (OSError, ValueError, TypeError, AttributeError):
                # 未知或损坏 manifest 一律保守保留 blob。
                return True
        if self.pending_root.is_dir() and not self.pending_root.is_symlink():
            for marker_path in self.pending_root.glob("*.json"):
                if exclude_marker is not None and marker_path == exclude_marker:
                    continue
                try:
                    raw = json.loads(marker_path.read_text(encoding="utf-8"))
                    referenced = [raw.get("new")]
                    if raw.get("previous") is not None:
                        referenced.append(raw.get("previous"))
                    if any(isinstance(item, dict) and item.get("blob") == blob for item in referenced):
                        return True
                except (OSError, ValueError, TypeError, AttributeError):
                    return True
        return False

    def recover_pending(self, object_key: str, *, asset_id: UUID | str, expected_generation: str) -> bool:
        """依据数据库 generation 恢复进程中断留下的 prepared/published marker。"""
        self._path(object_key)
        self._ensure_internal_roots()
        recovered = False
        with self.object_lock(object_key):
            marker_pattern = f"{self._object_digest(object_key)}-*.json"
            for marker_path in self.pending_root.glob(marker_pattern):
                marker = self._load_marker(marker_path, object_key=object_key, asset_id=asset_id)
                new = marker["new"]
                temp_name = marker["manifest_temp"]
                manifest_temp = self._manifest_path(object_key).parent / temp_name
                current = self._load_manifest(object_key, required=False)
                actual_generation = current["generation"] if current else ""
                if expected_generation == new["generation"] and actual_generation != new["generation"]:
                    # DB 已记录新 generation，但进程在原子 replace 前退出：只接受绑定的
                    # manifest temp/blob，并在同一对象锁内完成 prepared→published。
                    if actual_generation:
                        raise StorageValidationError("待恢复清单与数据库版本不一致")
                    try:
                        with manifest_temp.open(encoding="utf-8") as source:
                            temp_manifest = self._validate_manifest(json.load(source), object_key)
                    except (OSError, ValueError, TypeError) as exc:
                        raise StorageValidationError("待恢复清单临时文件损坏") from exc
                    if temp_manifest != new:
                        raise StorageValidationError("待恢复清单临时文件不匹配")
                    blob_path = self._blob_path(new["blob"], object_key)
                    if not blob_path.is_file() or blob_path.stat().st_size != new["size"]:
                        raise StorageValidationError("待恢复 blob 不一致")
                    self._write_json_sync(marker_path, {**marker, "phase": "published"})
                    os.replace(manifest_temp, self._manifest_path(object_key))
                    actual_generation = new["generation"]
                    recovered = True
                if actual_generation == new["generation"] and expected_generation != new["generation"]:
                    previous = marker["previous"]
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
                    if not self._blob_is_referenced(new["blob"], exclude_marker=marker_path):
                        self._blob_path(new["blob"], object_key).unlink(missing_ok=True)
                    manifest_temp.unlink(missing_ok=True)
                    marker_path.unlink(missing_ok=True)
                    recovered = True
        return recovered

    def finalize_generation(self, object_key: str, generation: str, *, asset_id: UUID | str) -> bool:
        self._ensure_internal_roots()
        finalized = False
        with self.object_lock(object_key):
            marker_pattern = f"{self._object_digest(object_key)}-*.json"
            for marker_path in self.pending_root.glob(marker_pattern):
                marker = self._load_marker(marker_path, object_key=object_key, asset_id=asset_id)
                if marker["new"]["generation"] != generation:
                    continue
                current = self._load_manifest(object_key)
                if current["generation"] != generation or current["asset_id"] != str(UUID(str(asset_id))):
                    raise StorageValidationError("媒体清单版本冲突")
                previous = marker["previous"]
                if previous:
                    if previous["blob"] != current["blob"] and not self._blob_is_referenced(previous["blob"], exclude_marker=marker_path):
                        self._blob_path(previous["blob"], object_key).unlink(missing_ok=True)
                (self._manifest_path(object_key).parent / marker["manifest_temp"]).unlink(missing_ok=True)
                marker_path.unlink(missing_ok=True)
                finalized = True
        return finalized

    def write_authorized_stream(self, *, object_key: str, token: str, stream: BinaryIO, mime: str, asset_id: UUID | str | None = None, before_publish=None) -> None:
        prepared = self.prepare_authorized_stream(object_key=object_key, token=token, stream=stream, mime=mime, asset_id=asset_id)
        try:
            previous = self._load_manifest(object_key, required=False)
            if before_publish:
                before_publish()
            published = self.publish_manifest(prepared, expected_generation=previous["generation"] if previous else "")
        except Exception:
            self.discard_prepared(prepared)
            raise
        self.finalize_publish(published)

    def write_upload(self, *, grant: UploadGrant, content: bytes, mime: str, asset_id: UUID | str | None = None) -> None:
        from io import BytesIO
        self.write_authorized_stream(object_key=grant.object_key, token=grant.upload_token, stream=BytesIO(content), mime=mime, asset_id=asset_id)

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
        normalized_asset_id = str(UUID(str(asset_id))) if asset_id is not None else str(UUID(int=0))
        if manifest["asset_id"] != normalized_asset_id:
            raise StorageValidationError("媒体清单资产不一致")
        if expected_generation is not None and manifest["generation"] != expected_generation:
            raise StorageValidationError("媒体清单版本不一致")
        token, expires_at = self._issue_token({
            "object_key": object_key, "kind": "private", "asset_id": normalized_asset_id,
            "generation": manifest["generation"], "blob": manifest["blob"],
        }, ttl_seconds)
        segments = "/".join(quote(segment, safe="") for segment in object_key.split("/"))
        return PrivateUrl(url=f"/api/v1/media/private/{segments}?{urlencode({'signature': token})}", expires_at=expires_at, token=token)

    def authorize_private(self, token: str, object_key: str, *, asset_id: UUID | str | None = None, expected_generation: str | None = None) -> Path:
        payload = self.verify_private_token(token, object_key)
        normalized_asset_id = str(UUID(str(asset_id))) if asset_id is not None else str(UUID(int=0))
        if payload.get("asset_id") != normalized_asset_id:
            raise StorageValidationError("下载签名资产不匹配")
        manifest, blob_path = self._snapshot(object_key, verify_hash=False)
        if manifest["asset_id"] != normalized_asset_id:
            raise StorageValidationError("媒体清单资产不一致")
        if expected_generation is not None and manifest["generation"] != expected_generation:
            raise StorageValidationError("媒体清单版本不一致")
        if payload.get("generation") != manifest["generation"] or payload.get("blob") != manifest["blob"]:
            raise StorageValidationError("下载清单版本已变化")
        return blob_path

    def verify_private_token(self, token: str, object_key: str) -> dict[str, Any]:
        """纯验签：在任何 DB 查询、文件锁、清单读取或历史布局写入前调用。"""
        payload = self._read_token(token)
        if payload.get("kind") != "private" or payload.get("object_key") != object_key:
            raise StorageValidationError("下载签名无效")
        try:
            UUID(str(payload.get("asset_id", "")))
        except (TypeError, ValueError, AttributeError) as exc:
            raise StorageValidationError("下载签名资产不合法") from exc
        if not _HEX32.fullmatch(str(payload.get("generation", ""))):
            raise StorageValidationError("下载签名版本不合法")
        self._validate_object_key_only(object_key)
        expected_prefix = self._object_digest(object_key)[:16]
        if not re.fullmatch(rf"{expected_prefix}-[0-9a-f]{{32}}", str(payload.get("blob", ""))):
            raise StorageValidationError("下载签名 blob 不合法")
        return payload

    def verify_and_open_private(self, token: str, object_key: str, *, asset_id: UUID | str | None = None, expected_generation: str | None = None) -> BinaryIO:
        # 文件锁覆盖 token/DB generation/manifest 快照验证到 open；返回后 fd 可安全跨 unlink 读取。
        with self.object_lock(object_key):
            path = self.authorize_private(token, object_key, asset_id=asset_id, expected_generation=expected_generation)
            try:
                return path.open("rb")
            except OSError as exc:
                raise StorageValidationError("媒体 blob 不可打开") from exc

    open_authorized_private = verify_and_open_private

    def read_private(self, token: str) -> bytes:
        payload = self._read_token(token)
        if payload.get("kind") != "private":
            raise StorageValidationError("下载签名无效")
        with self.verify_and_open_private(token, payload["object_key"], asset_id=payload.get("asset_id", "")) as source:
            return source.read()

    def mark_for_cleanup(self, object_key: str) -> None:
        self._path(object_key)
