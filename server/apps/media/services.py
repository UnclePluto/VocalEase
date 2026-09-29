from __future__ import annotations

from io import BytesIO
import hashlib
from typing import Any, Mapping
from uuid import UUID
from uuid import uuid4
from datetime import timedelta

from django.conf import settings
from django.db import models, transaction
from django.utils import timezone
from rest_framework.exceptions import APIException, ValidationError

from apps.media.backends.local import LocalStorageBackend, PreparedLocalUpload
from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.contracts import BACKENDS, OWNER_MEDIA_TYPES, StorageBackend, StorageValidationError, UploadGrant, UploadReceipt, validate_media_request
from apps.media.models import MediaAsset
from apps.patients.models import PatientProfile


class MediaConflict(APIException):
    status_code = 409
    default_code = "media_state_conflict"
    default_detail = "媒体当前状态不允许此操作"


STORAGE_BACKEND_FACTORIES = {
    "local": lambda: LocalStorageBackend(root=settings.MEDIA_LOCAL_ROOT, signing_secret=settings.SECRET_KEY, environment=settings.MEDIA_ENVIRONMENT),
    "qiniu": lambda: QiniuStorageBackend.from_settings(),
}


def storage_backend_for(name: str) -> StorageBackend:
    try:
        return STORAGE_BACKEND_FACTORIES[name]()
    except KeyError as exc:
        raise StorageValidationError("未知媒体存储后端") from exc


def get_storage_backend() -> StorageBackend:
    return storage_backend_for(settings.MEDIA_BACKEND)


def backend_for_asset(asset: MediaAsset) -> StorageBackend:
    return storage_backend_for(asset.backend)


def _resolve_owner(*, owner_type: str, owner_id: UUID) -> tuple[PatientProfile | None, UUID]:
    if owner_type == MediaAsset.OwnerType.PATIENT:
        try:
            patient = PatientProfile.objects.get(pk=owner_id, deleted_at__isnull=True)
        except PatientProfile.DoesNotExist as exc:
            raise ValidationError({"owner_id": "患者资料不存在或不可用"}, code="invalid_media_owner") from exc
        return patient, patient.id
    if owner_type in {MediaAsset.OwnerType.SONG, MediaAsset.OwnerType.SYSTEM, MediaAsset.OwnerType.EXPORT}:
        return None, owner_id
    raise ValidationError({"owner_type": "不支持的媒体所有者类型"}, code="invalid_media_owner")


def create_upload_grant(*, owner: PatientProfile | None = None, owner_type: str = "patient", owner_id: UUID | None = None, media_type: str, mime: str, size: int, backend: StorageBackend | None = None) -> tuple[MediaAsset, UploadGrant]:
    if owner is not None:
        owner_type, owner_id = MediaAsset.OwnerType.PATIENT, owner.id
    if owner_id is None:
        raise ValidationError({"owner_id": "媒体所有者不能为空"}, code="invalid_media_owner")
    if media_type not in OWNER_MEDIA_TYPES.get(owner_type, frozenset()):
        raise ValidationError({"media_type": "媒体类型与所有者类型不匹配"}, code="invalid_media_owner")
    try:
        validate_media_request(media_type=media_type, mime=mime, size=size)
    except StorageValidationError as exc:
        raise ValidationError({"media": str(exc)}, code="invalid_media") from exc
    patient_owner, actual_owner_id = _resolve_owner(owner_type=owner_type, owner_id=owner_id)
    backend = backend or get_storage_backend()
    grant = backend.create_upload_grant(owner_id=actual_owner_id, media_type=media_type, mime=mime, size=size)
    return MediaAsset.objects.create(patient_owner=patient_owner, owner_type=owner_type, owner_id=actual_owner_id, media_type=media_type, backend=next(name for name in BACKENDS if backend.__class__.__name__.lower().startswith(name)), object_key=grant.object_key, mime=mime, size=size, status=MediaAsset.Status.UPLOADING, upload_expires_at=grant.expires_at), grant


def reissue_upload_grant(*, asset: MediaAsset) -> UploadGrant:
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
        if locked.deleted_at is not None:
            raise MediaConflict("上传凭证已过期或不可用", code="media_grant_expired")
        if locked.status != MediaAsset.Status.UPLOADING:
            raise MediaConflict("媒体当前状态不允许重签上传凭证", code="media_grant_not_reissuable")
        now = timezone.now()
        expires_at = locked.upload_expires_at
        if expires_at <= now:
            expires_at = now + timedelta(seconds=settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS)
        backend = backend_for_asset(locked)
        try:
            grant = backend.reissue_upload_grant(
                object_key=locked.object_key, owner_id=locked.owner_id, media_type=locked.media_type,
                mime=locked.mime, size=locked.size, expires_at=expires_at,
            )
        except StorageValidationError as exc:
            raise MediaConflict(str(exc), code="media_grant_expired") from exc
        if locked.upload_expires_at != grant.expires_at:
            locked.upload_expires_at = grant.expires_at
            locked.save(update_fields=["upload_expires_at", "updated_at"])
        return grant


def _check_receipt(asset: MediaAsset, receipt: UploadReceipt) -> None:
    if receipt.object_key != asset.object_key or receipt.size != asset.size or receipt.mime != asset.mime:
        raise MediaConflict("上传对象元数据与凭证不一致", code="media_metadata_mismatch")
    if asset.backend == "local" and (
        len(receipt.sha256) != 64 or receipt.sha256 != asset.sha256
        or receipt.generation != asset.manifest_generation
    ):
        raise MediaConflict("本地媒体可信清单与暂存回执不一致", code="media_hash_invalid")
    if asset.backend == "qiniu" and not receipt.etag:
        raise MediaConflict("七牛媒体缺少可信 ETag", code="media_etag_invalid")


def complete_local_asset(*, asset: MediaAsset) -> MediaAsset:
    if asset.backend != "local":
        raise MediaConflict("七牛媒体只能等待已验签回调", code="media_callback_required")
    backend = backend_for_asset(asset)
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
        if locked.status == MediaAsset.Status.FAILED:
            raise MediaConflict()
        if locked.status == MediaAsset.Status.READY:
            completed = locked
        else:
            if locked.status != MediaAsset.Status.STAGED or locked.upload_expires_at <= timezone.now():
                raise MediaConflict("上传凭证已过期或状态不可用", code="media_grant_expired")
            try:
                receipt = backend.verify_completion(locked.object_key, expected_generation=locked.manifest_generation)
            except StorageValidationError as exc:
                raise MediaConflict(str(exc), code="media_verification_failed") from exc
            _check_receipt(locked, receipt)
            locked.sha256, locked.status = receipt.sha256, MediaAsset.Status.READY
            locked.save(update_fields=["sha256", "status", "updated_at"])
            completed = locked
        backend.finalize_generation(
            completed.object_key, completed.manifest_generation, asset_id=completed.id,
            expected_size=completed.size, expected_mime=completed.mime, expected_sha256=completed.sha256,
        )
    return completed


def claim_local_upload(*, asset: MediaAsset) -> UUID:
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
        now = timezone.now()
        # 租约过期时允许新的请求接管；旧 writer 发布前会再次校验 nonce，
        # 因而不能覆盖后来者的 manifest。
        if locked.status == MediaAsset.Status.RECEIVING and locked.upload_lease_expires_at and locked.upload_lease_expires_at <= now:
            backend = backend_for_asset(locked)
            if not isinstance(backend, LocalStorageBackend):
                raise MediaConflict("上传租约后端不合法", code="media_upload_lease_invalid")
            backend.recover_pending(locked.object_key, asset_id=locked.id, expected_generation=locked.manifest_generation)
            locked.status = MediaAsset.Status.UPLOADING
            locked.upload_nonce = None
            locked.upload_lease_expires_at = None
        if locked.status != MediaAsset.Status.UPLOADING:
            raise MediaConflict("上传已在进行或不可用", code="media_upload_in_progress")
        nonce = uuid4()
        locked.status = MediaAsset.Status.RECEIVING
        locked.upload_nonce = nonce
        locked.upload_lease_expires_at = now + timedelta(minutes=5)
        locked.save(update_fields=["status", "upload_nonce", "upload_lease_expires_at", "updated_at"])
        return nonce


def release_local_upload(*, asset_id: UUID, nonce: UUID, success: bool) -> None:
    if success:
        raise MediaConflict("成功上传必须通过原子清单发布", code="media_upload_publish_required")
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset_id)
        if locked.upload_nonce != nonce:
            raise MediaConflict("上传租约不匹配", code="media_upload_lease_invalid")
        locked.status = MediaAsset.Status.UPLOADING
        locked.upload_nonce = None
        locked.upload_lease_expires_at = None
        locked.save(update_fields=["status", "upload_nonce", "upload_lease_expires_at", "updated_at"])


def publish_local_upload(*, asset_id: UUID, nonce: UUID, prepared: PreparedLocalUpload, backend: LocalStorageBackend) -> MediaAsset:
    published = None
    try:
        with transaction.atomic():
            locked = MediaAsset.objects.select_for_update().get(pk=asset_id)
            if (
                locked.backend != "local" or locked.status != MediaAsset.Status.RECEIVING
                or locked.upload_nonce != nonce or locked.upload_lease_expires_at <= timezone.now()
            ):
                raise MediaConflict("上传租约已失效", code="media_upload_lease_invalid")
            if prepared.object_key != locked.object_key or prepared.asset_id != str(locked.id) or prepared.size != locked.size or prepared.mime != locked.mime:
                raise MediaConflict("上传对象元数据与凭证不一致", code="media_metadata_mismatch")
            # 行锁覆盖版本核对、唯一一次 manifest replace 与 DB receipt 更新；流式 I/O 已在事务外完成。
            published = backend.publish_manifest(prepared, expected_generation=locked.manifest_generation)
            locked.status = MediaAsset.Status.STAGED
            locked.sha256 = prepared.sha256
            locked.manifest_generation = prepared.generation
            locked.upload_nonce = None
            locked.upload_lease_expires_at = None
            locked.save(update_fields=["status", "sha256", "manifest_generation", "upload_nonce", "upload_lease_expires_at", "updated_at"])
    except Exception as exc:
        if published is not None:
            # 不在 DB 锁释放后直接补偿；新事务重新取得同一行锁，再按统一文件锁协议恢复。
            try:
                recover_local_asset(asset_id=asset_id, backend=backend)
            except Exception:
                # 已 fsync 的 marker 保留，后续接管或 scanner 可继续恢复。
                pass
        else:
            backend.discard_prepared(prepared)
        if isinstance(exc, StorageValidationError):
            raise MediaConflict(str(exc), code="media_manifest_conflict") from exc
        raise
    finalize_local_publish(asset_id=asset_id, generation=prepared.generation, backend=backend)
    return locked


def recover_local_asset(*, asset_id: UUID, backend: LocalStorageBackend | None = None) -> MediaAsset:
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset_id)
        backend = backend or backend_for_asset(locked)
        if locked.backend != "local" or not isinstance(backend, LocalStorageBackend):
            raise MediaConflict("媒体恢复后端不合法", code="media_backend_invalid")
        backend.recover_pending(
            locked.object_key, asset_id=locked.id, expected_generation=locked.manifest_generation,
            expected_size=locked.size if locked.status in {MediaAsset.Status.STAGED, MediaAsset.Status.READY} else None,
            expected_mime=locked.mime if locked.status in {MediaAsset.Status.STAGED, MediaAsset.Status.READY} else None,
            expected_sha256=locked.sha256 if locked.status in {MediaAsset.Status.STAGED, MediaAsset.Status.READY} else None,
        )
        return locked


def finalize_local_publish(*, asset_id: UUID, generation: str, backend: LocalStorageBackend | None = None) -> MediaAsset:
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset_id)
        backend = backend or backend_for_asset(locked)
        if locked.backend != "local" or locked.manifest_generation != generation or not isinstance(backend, LocalStorageBackend):
            raise MediaConflict("媒体清单完成状态不一致", code="media_manifest_conflict")
        backend.finalize_generation(
            locked.object_key, generation, asset_id=locked.id,
            expected_size=locked.size, expected_mime=locked.mime, expected_sha256=locked.sha256,
        )
        return locked


def ensure_local_asset_layout(*, asset: MediaAsset) -> MediaAsset:
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
        backend = backend_for_asset(locked)
        if locked.backend != "local" or not isinstance(backend, LocalStorageBackend):
            return locked
        try:
            backend.migrate_legacy_layout(
                object_key=locked.object_key, asset_id=locked.id, generation=locked.manifest_generation,
                expected_size=locked.size, expected_mime=locked.mime, expected_sha256=locked.sha256,
            )
        except StorageValidationError:
            if (locked.metadata or {}).get("migration_pending"):
                metadata = dict(locked.metadata or {})
                metadata["migration_pending"] = False
                metadata["local_migration_reason"] = "legacy_runtime_evidence_missing_or_invalid"
                locked.status = MediaAsset.Status.FAILED
                locked.metadata = metadata
                locked.save(update_fields=["status", "metadata", "updated_at"])
                return locked
            raise
        metadata = dict(locked.metadata or {})
        if metadata.get("migration_pending"):
            metadata["migration_pending"] = False
            metadata["local_layout"] = "immutable_v2"
            locked.metadata = metadata
            locked.save(update_fields=["metadata", "updated_at"])
        return locked


def recover_stale_local_uploads(*, now=None) -> dict[str, int]:
    now = now or timezone.now()
    stats = {"receiving_recovered": 0, "staged_finalized": 0, "ready_finalized": 0, "legacy_converted": 0, "errors": 0, "unknown_markers": 0, "oversized_markers": 0, "truncated_markers": 0}
    scanner_backend = storage_backend_for("local")
    marker_index = scanner_backend.scan_pending_markers()
    marker_claims, scan_stats = list(marker_index.claims), marker_index.stats
    stats["unknown_markers"] = scan_stats["unknown"]
    stats["oversized_markers"] = scan_stats["oversized"]
    stats["truncated_markers"] = scan_stats["truncated"]
    claims_by_asset: dict[UUID, set[str]] = {}
    for asset_id, object_key in marker_claims:
        claims_by_asset.setdefault(asset_id, set()).add(object_key)
    candidate_ids = set(MediaAsset.objects.filter(backend="local").filter(
        models.Q(status__in=[MediaAsset.Status.RECEIVING, MediaAsset.Status.STAGED])
        | models.Q(status=MediaAsset.Status.READY, metadata__migration_pending=True)
    ).values_list("id", flat=True))
    candidate_ids.update(asset_id for asset_id, _ in marker_claims)
    for asset_id in candidate_ids:
        try:
            with transaction.atomic():
                locked = MediaAsset.objects.select_for_update().get(pk=asset_id)
                claimed_keys = claims_by_asset.get(asset_id, set())
                mismatches = {key for key in claimed_keys if key != locked.object_key}
                if mismatches:
                    stats["unknown_markers"] += len(mismatches)
                has_matching_marker = locked.object_key in claimed_keys
                if locked.backend != "local":
                    stats["unknown_markers"] += int(has_matching_marker)
                    continue
                backend = backend_for_asset(locked)
                if not isinstance(backend, LocalStorageBackend):
                    raise MediaConflict("媒体恢复后端不合法")
                if locked.status == MediaAsset.Status.READY and (locked.metadata or {}).get("migration_pending"):
                    try:
                        if has_matching_marker:
                            backend.recover_pending(
                                locked.object_key, asset_id=locked.id, expected_generation=locked.manifest_generation,
                                expected_size=locked.size, expected_mime=locked.mime, expected_sha256=locked.sha256,
                                marker_index=marker_index,
                            )
                            backend.finalize_generation(
                                locked.object_key, locked.manifest_generation, asset_id=locked.id,
                                expected_size=locked.size, expected_mime=locked.mime, expected_sha256=locked.sha256,
                                marker_index=marker_index,
                            )
                        else:
                            backend.migrate_legacy_layout(
                                object_key=locked.object_key, asset_id=locked.id, generation=locked.manifest_generation,
                                expected_size=locked.size, expected_mime=locked.mime, expected_sha256=locked.sha256,
                            )
                    except StorageValidationError:
                        metadata = dict(locked.metadata or {})
                        metadata["migration_pending"] = False
                        metadata["local_migration_reason"] = "legacy_runtime_evidence_missing_or_invalid"
                        locked.status = MediaAsset.Status.FAILED
                        locked.metadata = metadata
                        locked.save(update_fields=["status", "metadata", "updated_at"])
                        stats["errors"] += 1
                        continue
                    metadata = dict(locked.metadata or {})
                    metadata["migration_pending"] = False
                    metadata["local_layout"] = "immutable_v2"
                    locked.metadata = metadata
                    locked.save(update_fields=["metadata", "updated_at"])
                    stats["legacy_converted"] += 1
                elif locked.status == MediaAsset.Status.RECEIVING and locked.upload_lease_expires_at and locked.upload_lease_expires_at <= now:
                    backend.recover_pending(
                        locked.object_key, asset_id=locked.id, expected_generation=locked.manifest_generation,
                        marker_index=marker_index,
                    )
                    locked.status = MediaAsset.Status.UPLOADING
                    locked.upload_nonce = None
                    locked.upload_lease_expires_at = None
                    locked.save(update_fields=["status", "upload_nonce", "upload_lease_expires_at", "updated_at"])
                    stats["receiving_recovered"] += 1
                elif locked.status == MediaAsset.Status.STAGED and has_matching_marker:
                    recovered = backend.recover_pending(
                        locked.object_key, asset_id=locked.id, expected_generation=locked.manifest_generation,
                        expected_size=locked.size, expected_mime=locked.mime, expected_sha256=locked.sha256,
                        marker_index=marker_index,
                    )
                    finalized = backend.finalize_generation(
                        locked.object_key, locked.manifest_generation, asset_id=locked.id,
                        expected_size=locked.size, expected_mime=locked.mime, expected_sha256=locked.sha256,
                        marker_index=marker_index,
                    )
                    if recovered or finalized:
                        stats["staged_finalized"] += 1
                elif locked.status == MediaAsset.Status.READY and has_matching_marker:
                    recovered = backend.recover_pending(
                        locked.object_key, asset_id=locked.id, expected_generation=locked.manifest_generation,
                        expected_size=locked.size, expected_mime=locked.mime, expected_sha256=locked.sha256,
                        marker_index=marker_index,
                    )
                    finalized = backend.finalize_generation(
                        locked.object_key, locked.manifest_generation, asset_id=locked.id,
                        expected_size=locked.size, expected_mime=locked.mime, expected_sha256=locked.sha256,
                        marker_index=marker_index,
                    )
                    if recovered or finalized:
                        stats["ready_finalized"] += 1
                elif locked.status == MediaAsset.Status.UPLOADING:
                    backend.recover_pending(
                        locked.object_key, asset_id=locked.id, expected_generation=locked.manifest_generation,
                        marker_index=marker_index,
                    )
        except MediaAsset.DoesNotExist:
            stats["unknown_markers"] += max(1, len(claims_by_asset.get(asset_id, set())))
        except Exception:
            stats["errors"] += 1
    return stats


def complete_qiniu_callback(*, payload: Mapping[str, Any], backend: QiniuStorageBackend) -> MediaAsset:
    object_key = str(payload.get("key", ""))
    with transaction.atomic():
        try:
            locked = MediaAsset.objects.select_for_update().get(object_key=object_key, backend="qiniu", deleted_at__isnull=True)
        except MediaAsset.DoesNotExist as exc:
            raise MediaConflict("未找到对应上传凭证", code="media_grant_not_found") from exc
        if locked.status == MediaAsset.Status.FAILED:
            raise MediaConflict()
        if locked.status == MediaAsset.Status.READY:
            if locked.etag != str(payload.get("hash", "")):
                raise MediaConflict("重复回调 ETag 不一致", code="media_metadata_mismatch")
            return locked
        if locked.status != MediaAsset.Status.UPLOADING or locked.upload_expires_at <= timezone.now():
            raise MediaConflict("上传凭证已过期或状态不可用", code="media_grant_expired")
        try:
            receipt = backend.verify_completion(locked.object_key, payload)
        except StorageValidationError as exc:
            raise MediaConflict(str(exc), code="media_verification_failed") from exc
        _check_receipt(locked, receipt)
        locked.etag, locked.sha256, locked.status = receipt.etag, receipt.sha256, MediaAsset.Status.READY
        locked.save(update_fields=["etag", "sha256", "status", "updated_at"])
        return locked


def mark_asset_for_cleanup(*, asset: MediaAsset) -> MediaAsset:
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
        if locked.status == MediaAsset.Status.PENDING_CLEANUP:
            return locked
        locked.status = MediaAsset.Status.PENDING_CLEANUP
        locked.upload_nonce = None
        locked.upload_lease_expires_at = None
        locked.save(update_fields=["status", "upload_nonce", "upload_lease_expires_at", "updated_at"])
        return locked


def publish_generated_asset(
    *, owner_id: UUID, mime: str, content: bytes | None = None,
    stream=None, size: int | None = None, content_sha256: str | None = None,
    heartbeat=None,
) -> MediaAsset:
    """把可信服务端生成物发布到 Task 4 的私有媒体协议，不暴露临时路径。"""
    if content is not None:
        if stream is not None:
            raise MediaConflict("生成物只能提供一种内容来源", code="media_metadata_mismatch")
        stream = BytesIO(content)
        size = len(content)
        content_sha256 = hashlib.sha256(content).hexdigest()
    if stream is None or not isinstance(size, int) or size <= 0 or not content_sha256:
        raise MediaConflict("生成物元数据不完整", code="media_metadata_mismatch")
    stream.seek(0)
    upload_stream = _HeartbeatReadProxy(stream, heartbeat) if heartbeat else stream
    if heartbeat:
        heartbeat()
    existing = MediaAsset.objects.filter(
        owner_type=MediaAsset.OwnerType.EXPORT,
        owner_id=owner_id,
        media_type="export",
        mime=mime,
        size=size,
        status=MediaAsset.Status.READY,
        deleted_at__isnull=True,
        metadata__generated_sha256=content_sha256,
    ).order_by("created_at", "id").first()
    if existing is not None:
        if heartbeat:
            heartbeat()
        backend = backend_for_asset(existing)
        if isinstance(backend, LocalStorageBackend):
            existing = ensure_local_asset_layout(asset=existing)
            if existing.status == MediaAsset.Status.READY and existing.sha256 == content_sha256:
                if heartbeat:
                    heartbeat()
                return existing
        elif isinstance(backend, QiniuStorageBackend):
            receipt = backend.verify_completion(existing.object_key)
            if receipt.size == existing.size and receipt.mime == existing.mime and receipt.etag == existing.etag:
                if heartbeat:
                    heartbeat()
                return existing
    asset, grant = create_upload_grant(
        owner_type=MediaAsset.OwnerType.EXPORT,
        owner_id=owner_id,
        media_type="export",
        mime=mime,
        size=size,
    )
    asset.metadata = {**(asset.metadata or {}), "generated_sha256": content_sha256}
    asset.save(update_fields=["metadata", "updated_at"])
    backend = backend_for_asset(asset)
    try:
        if isinstance(backend, LocalStorageBackend):
            nonce = claim_local_upload(asset=asset)
            prepared = backend.prepare_authorized_stream(
                object_key=asset.object_key,
                token=grant.upload_token,
                stream=upload_stream,
                mime=mime,
                asset_id=asset.id,
            )
            publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
            completed = complete_local_asset(asset=asset)
            with transaction.atomic():
                completed = MediaAsset.objects.select_for_update().get(pk=completed.pk)
                completed.metadata = {**(completed.metadata or {}), "generated_sha256": content_sha256}
                completed.save(update_fields=["metadata", "updated_at"])
            return completed
        if isinstance(backend, QiniuStorageBackend):
            if content is not None:
                receipt = backend.upload_generated(grant=grant, content=content, mime=mime)
            else:
                stream.seek(0)
                receipt = backend.upload_generated_stream(grant=grant, stream=upload_stream, size=size, mime=mime)
            with transaction.atomic():
                locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
                if locked.status == MediaAsset.Status.READY:
                    if locked.etag != receipt.etag:
                        raise MediaConflict("生成物回执不一致", code="media_metadata_mismatch")
                    locked.metadata = {**(locked.metadata or {}), "generated_sha256": content_sha256}
                    locked.save(update_fields=["metadata", "updated_at"])
                    return locked
                if locked.status != MediaAsset.Status.UPLOADING:
                    raise MediaConflict()
                _check_receipt(locked, receipt)
                locked.etag = receipt.etag
                locked.sha256 = receipt.sha256
                locked.status = MediaAsset.Status.READY
                locked.metadata = {**(locked.metadata or {}), "generated_sha256": content_sha256}
                locked.save(update_fields=["etag", "sha256", "status", "metadata", "updated_at"])
                return locked
        raise MediaConflict("生成物存储后端不受支持", code="media_backend_invalid")
    except Exception:
        MediaAsset.objects.filter(pk=asset.pk).exclude(status=MediaAsset.Status.READY).update(
            status=MediaAsset.Status.FAILED,
            upload_nonce=None,
            upload_lease_expires_at=None,
        )
        raise


class _HeartbeatReadProxy:
    def __init__(self, stream, heartbeat):
        self._stream = stream
        self._heartbeat = heartbeat

    def read(self, *args, **kwargs):
        self._heartbeat()
        chunk = self._stream.read(*args, **kwargs)
        self._heartbeat()
        return chunk

    def __getattr__(self, name):
        return getattr(self._stream, name)
