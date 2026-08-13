from __future__ import annotations

from typing import Any, Mapping
from uuid import UUID
from uuid import uuid4
from datetime import timedelta

from django.conf import settings
from django.db import transaction
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
        backend.finalize_generation(completed.object_key, completed.manifest_generation, asset_id=completed.id)
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
        backend.recover_pending(locked.object_key, asset_id=locked.id, expected_generation=locked.manifest_generation)
        return locked


def finalize_local_publish(*, asset_id: UUID, generation: str, backend: LocalStorageBackend | None = None) -> MediaAsset:
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset_id)
        backend = backend or backend_for_asset(locked)
        if locked.backend != "local" or locked.manifest_generation != generation or not isinstance(backend, LocalStorageBackend):
            raise MediaConflict("媒体清单完成状态不一致", code="media_manifest_conflict")
        backend.finalize_generation(locked.object_key, generation, asset_id=locked.id)
        return locked


def ensure_local_asset_layout(*, asset: MediaAsset) -> MediaAsset:
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
        backend = backend_for_asset(locked)
        if locked.backend != "local" or not isinstance(backend, LocalStorageBackend):
            return locked
        backend.migrate_legacy_layout(
            object_key=locked.object_key, asset_id=locked.id, generation=locked.manifest_generation,
            expected_size=locked.size, expected_mime=locked.mime, expected_sha256=locked.sha256,
        )
        return locked


def recover_stale_local_uploads(*, now=None) -> dict[str, int]:
    now = now or timezone.now()
    stats = {"receiving_recovered": 0, "staged_finalized": 0, "ready_finalized": 0, "errors": 0, "unknown_markers": 0}
    scanner_backend = storage_backend_for("local")
    marker_claims, invalid_markers = scanner_backend.pending_marker_claims()
    stats["unknown_markers"] = invalid_markers
    candidate_ids = set(MediaAsset.objects.filter(backend="local", status__in=[MediaAsset.Status.RECEIVING, MediaAsset.Status.STAGED]).values_list("id", flat=True))
    candidate_ids.update(asset_id for asset_id, _ in marker_claims)
    for asset_id in candidate_ids:
        try:
            with transaction.atomic():
                locked = MediaAsset.objects.select_for_update().get(pk=asset_id)
                backend = backend_for_asset(locked)
                if not isinstance(backend, LocalStorageBackend):
                    raise MediaConflict("媒体恢复后端不合法")
                if locked.status == MediaAsset.Status.RECEIVING and locked.upload_lease_expires_at and locked.upload_lease_expires_at <= now:
                    backend.recover_pending(locked.object_key, asset_id=locked.id, expected_generation=locked.manifest_generation)
                    locked.status = MediaAsset.Status.UPLOADING
                    locked.upload_nonce = None
                    locked.upload_lease_expires_at = None
                    locked.save(update_fields=["status", "upload_nonce", "upload_lease_expires_at", "updated_at"])
                    stats["receiving_recovered"] += 1
                elif locked.status == MediaAsset.Status.STAGED:
                    backend.finalize_generation(locked.object_key, locked.manifest_generation, asset_id=locked.id)
                    stats["staged_finalized"] += 1
                elif locked.status == MediaAsset.Status.READY:
                    backend.finalize_generation(locked.object_key, locked.manifest_generation, asset_id=locked.id)
                    stats["ready_finalized"] += 1
                elif locked.status == MediaAsset.Status.UPLOADING:
                    backend.recover_pending(locked.object_key, asset_id=locked.id, expected_generation=locked.manifest_generation)
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
