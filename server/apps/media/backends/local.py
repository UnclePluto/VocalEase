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
_LEGACY_MANIFEST_FIELDS = {"version", "generation", "blob", "mime", "size", "sha256"}
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
    scanner_marker_hard_limit = 1000
    scanner_marker_bytes_hard_limit = 64 * 1024

    def __init__(self, *, root: str | Path, signing_secret: str, environment: str):
        required_constants = ("O_NOFOLLOW", "O_DIRECTORY")
        required_dirfd = (os.open, os.stat, os.unlink, os.rename)
        if any(not isinstance(getattr(os, name, None), int) or not getattr(os, name, 0) for name in required_constants) or any(call not in os.supports_dir_fd for call in required_dirfd):
            raise StorageValidationError("平台缺少本地媒体安全文件能力")
        # 保留调用方给出的真实路径形态；不能先 resolve，否则会掩盖 root 本身是 symlink。
        self.root = Path(root).absolute()
        self.signer = signing.Signer(key=signing_secret, salt=self.signing_salt)
        self.environment = environment
        self._safe_open_hook = lambda kind, path: None
        self._safe_directory(self.root)

    @contextmanager
    def _parent_fd(self, path: Path, *, create: bool = False):
        try:
            relative = path.parent.relative_to(self.root)
        except ValueError as exc:
            raise StorageValidationError("媒体文件路径越界") from exc
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
        descriptors: list[int] = []
        try:
            root_fd = os.open(self.root, flags)
            descriptors.append(root_fd)
            current = root_fd
            for part in relative.parts:
                if part in {"", ".", ".."}:
                    raise StorageValidationError("媒体目录路径不合法")
                if create:
                    try:
                        os.mkdir(part, 0o700, dir_fd=current)
                    except FileExistsError:
                        pass
                child = os.open(part, flags, dir_fd=current)
                if not stat.S_ISDIR(os.fstat(child).st_mode):
                    os.close(child)
                    raise StorageValidationError("媒体目录路径不合法")
                descriptors.append(child)
                current = child
        except StorageValidationError:
            raise
        except OSError as exc:
            raise StorageValidationError("媒体目录不可安全访问") from exc
        try:
            yield current
        finally:
            for descriptor in reversed(descriptors):
                try:
                    os.close(descriptor)
                except OSError:
                    pass

    def _safe_open_file(self, path: Path, *, kind: str, flags: int, mode: int = 0o600, create_parent: bool = False) -> int:
        with self._parent_fd(path, create=create_parent) as parent_fd:
            before = None
            if not (flags & os.O_EXCL):
                try:
                    before = os.stat(path.name, dir_fd=parent_fd, follow_symlinks=False)
                    if not stat.S_ISREG(before.st_mode):
                        raise StorageValidationError("媒体文件类型不合法")
                except FileNotFoundError:
                    if not (flags & os.O_CREAT):
                        raise StorageValidationError("媒体文件不存在")
            self._safe_open_hook(kind, path)
            try:
                descriptor = os.open(path.name, flags | os.O_NOFOLLOW, mode, dir_fd=parent_fd)
            except OSError as exc:
                raise StorageValidationError("媒体文件不可安全打开") from exc
            after = os.fstat(descriptor)
            if not stat.S_ISREG(after.st_mode) or (before is not None and (before.st_dev, before.st_ino) != (after.st_dev, after.st_ino)):
                os.close(descriptor)
                raise StorageValidationError("媒体文件在打开期间发生变化")
            return descriptor

    def _safe_read_json(self, path: Path, *, kind: str, max_bytes: int | None = None) -> Any:
        descriptor = self._safe_open_file(path, kind=kind, flags=os.O_RDONLY)
        try:
            size = os.fstat(descriptor).st_size
            if max_bytes is not None and size > max_bytes:
                raise StorageValidationError("媒体状态文件过大")
            with os.fdopen(descriptor, "r", encoding="utf-8") as source:
                descriptor = -1
                return json.load(source)
        except StorageValidationError:
            raise
        except (OSError, ValueError, TypeError) as exc:
            raise StorageValidationError("媒体状态文件损坏") from exc
        finally:
            if descriptor >= 0:
                os.close(descriptor)

    def _safe_stat_file(self, path: Path, *, kind: str) -> os.stat_result:
        descriptor = self._safe_open_file(path, kind=kind, flags=os.O_RDONLY)
        try:
            return os.fstat(descriptor)
        finally:
            os.close(descriptor)

    def _safe_unlink(self, path: Path, *, missing_ok: bool = True) -> None:
        try:
            with self._parent_fd(path) as parent_fd:
                os.unlink(path.name, dir_fd=parent_fd)
        except FileNotFoundError:
            if not missing_ok:
                raise StorageValidationError("媒体文件不存在")
        except OSError as exc:
            raise StorageValidationError("媒体文件不可安全删除") from exc

    def _safe_replace(self, source: Path, destination: Path) -> None:
        source_descriptor = self._safe_open_file(source, kind="replace_source", flags=os.O_RDONLY)
        source_identity = os.fstat(source_descriptor)
        try:
            with self._parent_fd(source) as source_fd, self._parent_fd(destination, create=True) as destination_fd:
                self._safe_open_hook("replace", destination)
                try:
                    os.rename(source.name, destination.name, src_dir_fd=source_fd, dst_dir_fd=destination_fd)
                except OSError as exc:
                    raise StorageValidationError("媒体文件不可原子发布") from exc
            destination_descriptor = self._safe_open_file(destination, kind="replace_destination", flags=os.O_RDONLY)
            try:
                destination_identity = os.fstat(destination_descriptor)
                if (source_identity.st_dev, source_identity.st_ino) != (destination_identity.st_dev, destination_identity.st_ino):
                    raise StorageValidationError("媒体原子发布源文件发生变化")
            finally:
                os.close(destination_descriptor)
        finally:
            os.close(source_descriptor)

    def _new_temp_file(self, parent: Path, *, prefix: str, binary: bool):
        for _ in range(16):
            name = f"{prefix}{uuid4().hex}"
            path = parent / name
            try:
                descriptor = self._safe_open_file(
                    path, kind="temp", flags=os.O_CREAT | os.O_EXCL | os.O_RDWR, create_parent=True,
                )
                return path, os.fdopen(descriptor, "w+b" if binary else "w+", encoding=None if binary else "utf-8")
            except StorageValidationError:
                continue
        raise StorageValidationError("无法创建安全媒体临时文件")

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
        flags = os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW
        try:
            with self._parent_fd(lock_path, create=True) as parent_fd:
                descriptor = os.open(lock_path.name, flags, 0o600, dir_fd=parent_fd)
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
        try:
            stored = self._safe_read_json(path, kind="manifest", max_bytes=self.scanner_marker_bytes_hard_limit)
        except StorageValidationError as exc:
            if "不存在" not in str(exc):
                raise
            if required:
                raise StorageValidationError("媒体对象不存在")
            return None
        try:
            return self._validate_manifest(stored, object_key)
        except StorageValidationError:
            raise
        except (OSError, ValueError, TypeError) as exc:
            raise StorageValidationError("媒体清单损坏") from exc

    def _load_manifest_state(self, object_key: str) -> tuple[str, dict[str, Any] | None]:
        path = self._manifest_path(object_key)
        try:
            stored = self._safe_read_json(path, kind="manifest", max_bytes=self.scanner_marker_bytes_hard_limit)
        except StorageValidationError as exc:
            if "不存在" in str(exc):
                return "missing", None
            raise
        try:
            return "current", self._validate_manifest(stored, object_key)
        except StorageValidationError:
            if (
                isinstance(stored, dict) and set(stored) == _LEGACY_MANIFEST_FIELDS
                and stored.get("version") == 1 and _HEX32.fullmatch(str(stored.get("generation", "")))
                and _HEX32.fullmatch(str(stored.get("blob", "")))
                and type(stored.get("size")) is int and stored["size"] > 0
                and isinstance(stored.get("mime"), str) and _HEX64.fullmatch(str(stored.get("sha256", "")))
            ):
                return "legacy_v1", stored
            raise

    def _verify_trusted_receipt(self, manifest: Mapping[str, Any], blob_path: Path, *, asset_id: UUID | str,
                                generation: str, size: int, mime: str, sha256: str) -> None:
        if (
            manifest.get("asset_id") != str(UUID(str(asset_id))) or manifest.get("generation") != generation
            or manifest.get("size") != size or manifest.get("mime") != mime or manifest.get("sha256") != sha256
        ):
            raise StorageValidationError("媒体清单与数据库可信回执不一致")
        digest = hashlib.sha256(); actual_size = 0
        descriptor = self._safe_open_file(blob_path, kind="blob", flags=os.O_RDONLY)
        with os.fdopen(descriptor, "rb") as source:
            while chunk := source.read(self.chunk_size):
                actual_size += len(chunk); digest.update(chunk)
        if actual_size != size or digest.hexdigest() != sha256:
            raise StorageValidationError("媒体 blob 与数据库可信回执不一致")

    def _snapshot(self, object_key: str, *, verify_hash: bool) -> tuple[dict[str, Any], Path]:
        self._ensure_internal_roots()
        manifest = self._load_manifest(object_key)
        assert manifest is not None
        blob_path = self._blob_path(manifest["blob"], object_key)
        try:
            if self._safe_stat_file(blob_path, kind="blob").st_size != manifest["size"]:
                raise StorageValidationError("媒体清单与 blob 不一致")
        except OSError as exc:
            raise StorageValidationError("媒体 blob 不可访问") from exc
        if verify_hash:
            digest = hashlib.sha256()
            descriptor = self._safe_open_file(blob_path, kind="blob", flags=os.O_RDONLY)
            with os.fdopen(descriptor, "rb") as source:
                while chunk := source.read(self.chunk_size):
                    digest.update(chunk)
            if digest.hexdigest() != manifest["sha256"]:
                raise StorageValidationError("媒体对象哈希不一致")
        return manifest, blob_path

    def _write_json_sync(self, path: Path, payload: Mapping[str, Any]) -> None:
        descriptor = self._safe_open_file(path, kind="state", flags=os.O_CREAT | os.O_WRONLY | os.O_TRUNC, create_parent=True)
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
            marker = self._safe_read_json(marker_path, kind="marker", max_bytes=self.scanner_marker_bytes_hard_limit)
        except StorageValidationError as exc:
            raise StorageValidationError("待恢复标记损坏") from exc
        return self._validate_marker(marker, marker_path, object_key=object_key, asset_id=asset_id)

    def pending_marker_claims(self) -> tuple[list[tuple[UUID, str]], dict[str, int]]:
        self._ensure_internal_roots()
        claims: list[tuple[UUID, str]] = []
        stats = {"unknown": 0, "oversized": 0, "truncated": 0}
        configured_count = max(1, min(int(getattr(settings, "MEDIA_SCANNER_MAX_MARKERS", self.scanner_marker_hard_limit)), self.scanner_marker_hard_limit))
        configured_bytes = max(1, min(int(getattr(settings, "MEDIA_SCANNER_MAX_MARKER_BYTES", self.scanner_marker_bytes_hard_limit)), self.scanner_marker_bytes_hard_limit))
        seen = 0
        with self._parent_fd(self.pending_root / ".scan") as pending_fd, os.scandir(pending_fd) as entries:
            for entry in entries:
                name = entry.name
                if not name.endswith(".json") or Path(name).name != name:
                    continue
                marker_path = self.pending_root / name
                try:
                    info = entry.stat(follow_symlinks=False)
                    if not stat.S_ISREG(info.st_mode):
                        raise StorageValidationError("待恢复标记路径不合法")
                    if info.st_size > configured_bytes:
                        stats["oversized"] += 1
                        stats["unknown"] += 1
                        continue
                    if seen >= configured_count:
                        stats["truncated"] += 1
                        continue
                    seen += 1
                    raw = self._safe_read_json(marker_path, kind="marker", max_bytes=configured_bytes)
                    object_key = raw.get("object_key") if isinstance(raw, dict) else ""
                    asset_id = UUID(str(raw.get("asset_id"))) if isinstance(raw, dict) else None
                    self._validate_marker(raw, marker_path, object_key=object_key, asset_id=asset_id)
                    claims.append((asset_id, object_key))
                except (OSError, ValueError, TypeError, AttributeError, StorageValidationError):
                    stats["unknown"] += 1
        return claims, stats

    def create_upload_grant(self, *, owner_id: UUID, media_type: str, mime: str, size: int) -> UploadGrant:
        validate_media_request(media_type=media_type, mime=mime, size=size)
        key = build_object_key(self.environment, media_type)
        token, expires_at = self._issue_token(
            {"object_key": key, "owner_id": str(owner_id), "media_type": media_type, "mime": mime, "size": size},
            settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS,
        )
        return UploadGrant(object_key=key, expires_at=expires_at, upload_token=token)

    def migrate_legacy_layout(self, *, object_key: str, asset_id: UUID | str, generation: str, expected_size: int, expected_mime: str, expected_sha256: str) -> bool:
        """在调用方持有 DB 行锁时，用普通 marker 协议转换 v0 sidecar 或 v1 manifest。"""
        self._ensure_internal_roots()
        normalized_asset_id = str(UUID(str(asset_id)))
        legacy_paths: list[Path] = []
        with self.object_lock(object_key):
            layout, stored = self._load_manifest_state(object_key)
            if layout == "current":
                current = stored
                assert current is not None
                if current["asset_id"] != normalized_asset_id or current["generation"] != generation:
                    raise StorageValidationError("旧媒体清单与数据库不一致")
                self._verify_trusted_receipt(
                    current, self._blob_path(current["blob"], object_key), asset_id=asset_id,
                    generation=generation, size=expected_size, mime=expected_mime, sha256=expected_sha256,
                )
                return False
            if not _HEX32.fullmatch(generation):
                raise StorageValidationError("旧媒体 generation 不合法")
            if layout == "legacy_v1":
                legacy = stored
                assert legacy is not None
                if legacy["size"] != expected_size or legacy["mime"] != expected_mime or legacy["sha256"] != expected_sha256:
                    raise StorageValidationError("旧媒体清单与数据库不一致")
                old_blob = self.blobs_root / legacy["blob"]
                source_fd = self._safe_open_file(old_blob, kind="legacy_blob", flags=os.O_RDONLY)
                legacy_paths.append(old_blob)
            else:
                legacy_path = self._path(object_key)
                sidecar_path = self._legacy_metadata_path(object_key)
                sidecar = self._safe_read_json(sidecar_path, kind="legacy_sidecar", max_bytes=self.scanner_marker_bytes_hard_limit)
                if set(sidecar) != {"mime", "size", "sha256"} or sidecar != {"mime": expected_mime, "size": expected_size, "sha256": expected_sha256}:
                    raise StorageValidationError("旧媒体元数据与数据库不一致")
                source_fd = self._safe_open_file(legacy_path, kind="legacy_blob", flags=os.O_RDONLY)
                legacy_paths.extend([legacy_path, sidecar_path])
        # 复制/哈希在文件锁外完成；DB 行锁仍由服务层持有。发布与普通上传走同一 marker/CAS 协议。
        with os.fdopen(source_fd, "rb") as source:
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
            for legacy_path in legacy_paths:
                self._safe_unlink(legacy_path)
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
        temporary_path, temporary = self._new_temp_file(self.blobs_root, prefix=f".upload-{digest_name[:16]}-", binary=True)
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
            self._safe_replace(temporary_path, blob_path)
            manifest = {"version": 1, "object_key": object_key, "asset_id": normalized_asset_id, "generation": generation, "blob": blob, "mime": mime, "size": size, "sha256": sha256}
            prefix = f".manifest-{digest_name}-{UUID(normalized_asset_id).hex}-{generation}-"
            manifest_temp_path, manifest_temp = self._new_temp_file(manifest_parent, prefix=prefix, binary=False)
            with manifest_temp:
                json.dump(manifest, manifest_temp, separators=(",", ":")); manifest_temp.flush(); os.fsync(manifest_temp.fileno())
            self._write_json_sync(marker_path, {"version": 1, "phase": "prepared", "object_key": object_key, "asset_id": normalized_asset_id, "new": manifest, "previous": None, "manifest_temp": manifest_temp_path.name})
            return PreparedLocalUpload(object_key, normalized_asset_id, generation, blob, mime, size, sha256, manifest_temp_path, marker_path)
        except Exception:
            self._safe_unlink(temporary_path)
            if manifest_temp_path:
                self._safe_unlink(manifest_temp_path)
            self._safe_unlink(blob_path); self._safe_unlink(marker_path)
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
            layout, stored = self._load_manifest_state(prepared.object_key)
            previous = stored if layout == "current" else None
            actual = previous["generation"] if previous else ""
            if actual != expected_generation:
                raise StorageValidationError("媒体清单版本冲突")
            if previous and previous["asset_id"] != prepared.asset_id:
                raise StorageValidationError("媒体清单资产冲突")
            marker = {**prepared_marker, "phase": "published", "previous": previous}
            self._write_json_sync(prepared.pending_marker, marker)
            self._safe_replace(prepared.manifest_temp, self._manifest_path(prepared.object_key))
            return PublishedLocalUpload(prepared=prepared, previous_manifest=previous)

    def discard_prepared(self, prepared: PreparedLocalUpload) -> None:
        with self.object_lock(prepared.object_key):
            layout, stored = self._load_manifest_state(prepared.object_key)
            current = stored if layout == "current" else None
            if current and current["generation"] == prepared.generation:
                raise StorageValidationError("不可丢弃已发布清单")
            self._safe_unlink(prepared.manifest_temp)
            self._safe_unlink(prepared.pending_marker)
            self._safe_unlink(self._blob_path(prepared.blob, prepared.object_key))

    def finalize_publish(self, published: PublishedLocalUpload) -> None:
        prepared = published.prepared
        self.finalize_generation(
            prepared.object_key, prepared.generation, asset_id=prepared.asset_id,
            expected_size=prepared.size, expected_mime=prepared.mime, expected_sha256=prepared.sha256,
        )

    def _blob_is_referenced(self, blob: str, *, exclude_marker: Path | None = None) -> bool:
        manifests_root = self.root / ".manifests"
        if not manifests_root.is_dir() or manifests_root.is_symlink():
            return False
        for manifest_path in manifests_root.rglob("*.json"):
            try:
                if self._safe_read_json(manifest_path, kind="manifest", max_bytes=self.scanner_marker_bytes_hard_limit).get("blob") == blob:
                    return True
            except (OSError, ValueError, TypeError, AttributeError, StorageValidationError):
                # 未知或损坏 manifest 一律保守保留 blob。
                return True
        if self.pending_root.is_dir() and not self.pending_root.is_symlink():
            for marker_path in self.pending_root.glob("*.json"):
                if exclude_marker is not None and marker_path == exclude_marker:
                    continue
                try:
                    raw = self._safe_read_json(marker_path, kind="marker", max_bytes=self.scanner_marker_bytes_hard_limit)
                    referenced = [raw.get("new")]
                    if raw.get("previous") is not None:
                        referenced.append(raw.get("previous"))
                    if any(isinstance(item, dict) and item.get("blob") == blob for item in referenced):
                        return True
                except (OSError, ValueError, TypeError, AttributeError, StorageValidationError):
                    return True
        return False

    def recover_pending(self, object_key: str, *, asset_id: UUID | str, expected_generation: str,
                        expected_size: int | None = None, expected_mime: str | None = None,
                        expected_sha256: str | None = None) -> bool:
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
                layout, stored = self._load_manifest_state(object_key)
                current = stored if layout == "current" else None
                actual_generation = current["generation"] if current else ""
                if expected_generation == new["generation"] and actual_generation != new["generation"]:
                    # DB 已记录新 generation，但进程在原子 replace 前退出：只接受绑定的
                    # manifest temp/blob，并在同一对象锁内完成 prepared→published。
                    if actual_generation:
                        raise StorageValidationError("待恢复清单与数据库版本不一致")
                    try:
                        temp_manifest = self._validate_manifest(self._safe_read_json(manifest_temp, kind="manifest_temp", max_bytes=self.scanner_marker_bytes_hard_limit), object_key)
                    except StorageValidationError as exc:
                        raise StorageValidationError("待恢复清单临时文件损坏") from exc
                    if temp_manifest != new:
                        raise StorageValidationError("待恢复清单临时文件不匹配")
                    blob_path = self._blob_path(new["blob"], object_key)
                    if expected_size is None or expected_mime is None or expected_sha256 is None:
                        raise StorageValidationError("待恢复操作缺少数据库可信回执")
                    self._verify_trusted_receipt(
                        new, blob_path, asset_id=asset_id, generation=expected_generation,
                        size=expected_size, mime=expected_mime, sha256=expected_sha256,
                    )
                    self._write_json_sync(marker_path, {**marker, "phase": "published"})
                    self._safe_replace(manifest_temp, self._manifest_path(object_key))
                    actual_generation = new["generation"]
                    recovered = True
                if actual_generation == new["generation"] and expected_generation != new["generation"]:
                    previous = marker["previous"]
                    previous_generation = previous["generation"] if previous else ""
                    if previous_generation != expected_generation:
                        raise StorageValidationError("待恢复清单与数据库版本不一致")
                    path = self._manifest_path(object_key)
                    if previous is None:
                        self._safe_unlink(path)
                    else:
                        restore_path, restore = self._new_temp_file(path.parent, prefix=".manifest-recover-", binary=False)
                        with restore:
                            json.dump(previous, restore, separators=(",", ":")); restore.flush(); os.fsync(restore.fileno())
                        self._safe_replace(restore_path, path)
                    actual_generation = expected_generation
                if actual_generation == expected_generation and expected_generation != new["generation"]:
                    if not self._blob_is_referenced(new["blob"], exclude_marker=marker_path):
                        self._safe_unlink(self._blob_path(new["blob"], object_key))
                    self._safe_unlink(manifest_temp)
                    self._safe_unlink(marker_path)
                    recovered = True
        return recovered

    def finalize_generation(self, object_key: str, generation: str, *, asset_id: UUID | str,
                            expected_size: int, expected_mime: str, expected_sha256: str) -> bool:
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
                if marker["new"] != current:
                    raise StorageValidationError("待完成 marker 与当前清单不一致")
                self._verify_trusted_receipt(
                    current, self._blob_path(current["blob"], object_key), asset_id=asset_id,
                    generation=generation, size=expected_size, mime=expected_mime, sha256=expected_sha256,
                )
                previous = marker["previous"]
                if previous:
                    if previous["blob"] != current["blob"] and not self._blob_is_referenced(previous["blob"], exclude_marker=marker_path):
                        self._safe_unlink(self._blob_path(previous["blob"], object_key))
                self._safe_unlink(self._manifest_path(object_key).parent / marker["manifest_temp"])
                self._safe_unlink(marker_path)
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
                return os.fdopen(self._safe_open_file(path, kind="blob", flags=os.O_RDONLY), "rb")
            except (OSError, StorageValidationError) as exc:
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
